'use strict'
const fs = require('fs')
const path = require('path')
const { spawnSync } = require('child_process')
const { randomUUID } = require('crypto')
const safety = require('./backup-safety')

const IDENTITY_SQL = `SELECT JSON_OBJECT('hostname',@@hostname,'port',@@port,'uuid',@@server_uuid,
  'database',DATABASE(),'version',VERSION(),'scheduler',@@global.event_scheduler,'sqlMode',@@SESSION.sql_mode,
  'objects',(SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE())+
    (SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE())+
    (SELECT COUNT(*) FROM information_schema.events WHERE event_schema=DATABASE()))`
const SOURCE_SHAPE_SQL = `SELECT
  (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND (table_type<>'BASE TABLE' OR engine<>'InnoDB'))+
  (SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE())+
  (SELECT COUNT(*) FROM information_schema.events WHERE event_schema=DATABASE())+
  (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE())`

function main(argv, dependencies = {}) {
  const input = safety.parseArgs(argv)
  if (input.mode === 'help') {
    console.log('Manual isolated-schema tool: backup|drill --config <nonsecret.json>; restore|verify --config <nonsecret.json> --backup <dump.sql>')
    console.log('Explicit TCP/server identity authorizations; existing empty restore target; event_scheduler OFF; artifacts and target retained. See docs/operations/05.')
    return { mode: 'help' }
  }
  const io = dependencies.fs || fs
  const spawn = dependencies.spawnSync || spawnSync
  const env = dependencies.env || process.env
  const clock = dependencies.clock || (() => process.hrtime.bigint())
  const started = clock()
  // Validate both complete authorizations before any credential access or client process.
  const targets = safety.config(JSON.parse(io.readFileSync(input.config, 'utf8')), input.mode)
  const bin = env.AQUAFLOW_MYSQL_BIN
  if (!bin || !path.isAbsolute(bin)) throw new Error('explicit absolute AQUAFLOW_MYSQL_BIN required')
  const clients = { mysql: path.join(bin, process.platform === 'win32' ? 'mysql.exe' : 'mysql'),
    dump: path.join(bin, process.platform === 'win32' ? 'mysqldump.exe' : 'mysqldump') }
  for (const file of Object.values(clients)) if (!io.existsSync(file)) throw new Error('approved MySQL client not found')
  const passwords = {}
  for (const key of ['source', ...(targets.target ? ['target'] : [])]) {
    const password = env[`AQUAFLOW_BACKUP_${key.toUpperCase()}_PASSWORD`]
    if (typeof password !== 'string' || !password.length || password.includes('\0')) throw new Error('existing authorized temporary ' + key + ' credential required')
    passwords[key] = password
  }
  const repo = path.resolve(__dirname, '../..')
  const runDir = path.join(repo, 'backup', 'safety', new Date().toISOString().replace(/[:.]/g, '-') + '-' + randomUUID())
  io.mkdirSync(runDir, { recursive: true })
  let seq = 0
  const timings = []
  function stage(name, action) {
    const start = clock()
    try { return action() } finally { timings.push({ name, seconds: Number(clock() - start) / 1e9 }) }
  }
  function client(kind, key, args, output, stdin) {
    const out = output || path.join(runDir, `${++seq}-stdout.bin`)
    const err = path.join(runDir, `${++seq}-stderr.bin`)
    const descriptors = []
    let result
    try {
      const inputFd = stdin ? io.openSync(stdin, 'r') : 'ignore'
      if (stdin) descriptors.push(inputFd)
      const stdout = io.openSync(out, 'wx'); descriptors.push(stdout)
      const stderr = io.openSync(err, 'wx'); descriptors.push(stderr)
      result = spawn(clients[kind], args, { cwd: repo, shell: false, windowsHide: true,
        timeout: 30 * 60 * 1000, stdio: [inputFd, stdout, stderr], env: safety.childEnv(env, passwords[key]) })
    } finally { for (const fd of descriptors) io.closeSync(fd) }
    if (!result || result.status !== 0 || result.error) throw new Error('MySQL client failed; retained diagnostics require private local review')
    return out
  }
  function query(key, sql) {
    const output = client('mysql', key, [...safety.clientArgs(targets[key]), '--skip-reconnect', '--local-infile=0', '--batch', '--raw', '--skip-column-names', '--execute=' + sql])
    return io.readFileSync(output, 'utf8').trim()
  }
  function identity(key, empty = false) {
    let actual
    try { actual = JSON.parse(query(key, IDENTITY_SQL)) } catch (_) { throw new Error('server identity query failed or incomplete') }
    safety.assertIdentity(targets[key], actual, empty)
    if (key === 'target' && actual.scheduler !== 'OFF') throw new Error('target event_scheduler must be OFF; executor never changes it')
    if (key === 'target' && (typeof actual.sqlMode !== 'string' || actual.sqlMode.split(',').some(mode => ['NO_BACKSLASH_ESCAPES', 'ANSI_QUOTES'].includes(mode.trim().toUpperCase())))) {
      throw new Error('target SQL mode unsupported or incomplete; import refused without changing server/session mode')
    }
    return actual
  }
  function dump(key, file) {
    if (query(key, SOURCE_SHAPE_SQL) !== '0') throw new Error('only plain InnoDB tables supported; views/programs/events require separate review')
    // mysqldump takes a positional schema; mysql's --database option is not a dump option.
    client('dump', key, [...safety.clientArgs(targets[key]).slice(0, -1), ...safety.DUMP_ARGS, targets[key].database], file)
    return safety.inspectDump(io.readFileSync(file))
  }
  function backup() {
    const before = identity('source')
    const file = path.join(runDir, 'source.sql')
    const snapshotStartedAt = new Date().toISOString()
    const fingerprint = stage('export', () => dump('source', file))
    identity('source')
    const bytes = io.readFileSync(file)
    const manifest = { format: 2, source: targets.source, serverVersion: before.version,
      snapshotStartedAt, completedAt: new Date().toISOString(), sha256: safety.hash(bytes), bytes: bytes.length, fingerprint }
    io.writeFileSync(file + '.manifest.json', JSON.stringify(manifest, null, 2), { flag: 'wx' })
    return { file, manifest, bytes }
  }
  function load() {
    const bytes = io.readFileSync(input.backup)
    const manifest = JSON.parse(io.readFileSync(input.backup + '.manifest.json', 'utf8'))
    if (manifest.format !== 2 || manifest.bytes !== bytes.length || manifest.sha256 !== safety.hash(bytes) ||
        !manifest.source || safety.endpoint(manifest.source) !== safety.endpoint(targets.source)) throw new Error('backup manifest/source/hash mismatch')
    safety.sameContent(manifest.fingerprint, safety.inspectDump(bytes))
    return { file: input.backup, manifest, bytes }
  }
  function restore(snapshot) {
    const actual = identity('target', true)
    if (actual.version !== snapshot.manifest.serverVersion) throw new Error('source/target server versions differ; separate compatibility review required')
    safety.inspectDump(snapshot.bytes)
    const guarded = path.join(runDir, 'guarded-restore.sql')
    io.writeFileSync(guarded, Buffer.concat([Buffer.from(safety.restoreGuard(targets.target), 'utf8'), snapshot.bytes]), { flag: 'wx' })
    stage('import', () => client('mysql', 'target', [...safety.clientArgs(targets.target), '--skip-reconnect', '--local-infile=0', '--binary-mode'], null, guarded))
    identity('target')
  }
  function verify(snapshot) {
    const actual = identity('target')
    if (actual.version !== snapshot.manifest.serverVersion) throw new Error('source/target server versions differ')
    stage('content-verification', () => safety.sameContent(snapshot.manifest.fingerprint, dump('target', path.join(runDir, 'restored.sql'))))
    stage('E8-E5-orders', () => {
      for (const sql of safety.BALANCE_QUERIES) if (query('target', sql) !== '0') throw new Error('restored business integrity check failed')
    })
    identity('target')
  }
  try {
    if (targets.target) identity('target', ['restore', 'drill'].includes(input.mode))
    const snapshot = ['backup', 'drill'].includes(input.mode) ? backup() : load()
    if (['restore', 'drill'].includes(input.mode)) restore(snapshot)
    if (input.mode !== 'backup') verify(snapshot)
    const result = { mode: input.mode, status: 'passed', backup: snapshot.file, targetRetained: !!targets.target,
      timings, totalSeconds: Number(clock() - started) / 1e9,
      limits: 'Manual local isolated schemas; RPO/RTO remain goals; full business recovery/PITR/offsite/startup not tested here.' }
    io.writeFileSync(path.join(runDir, 'result.json'), JSON.stringify(result, null, 2), { flag: 'wx' })
    console.log('Completed ' + input.mode + '; artifacts retained at ' + path.relative(repo, runDir))
    return result
  } catch (error) {
    io.writeFileSync(path.join(runDir, 'result.json'), JSON.stringify({ mode: input.mode, status: 'failed',
      targetRetained: !!targets.target, timings, totalSeconds: Number(clock() - started) / 1e9 }, null, 2), { flag: 'wx' })
    throw error
  }
}
module.exports = { main, IDENTITY_SQL, SOURCE_SHAPE_SQL }
