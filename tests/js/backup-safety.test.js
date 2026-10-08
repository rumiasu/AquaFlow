'use strict'
const assert = require('assert')
const path = require('path')
const fs = require('fs')
const vm = require('vm')
const safety = require('../../scripts/lib/backup-safety')
const { main, IDENTITY_SQL, SOURCE_SHAPE_SQL } = require('../../scripts/lib/backup-runner')
let count = 0
function check(action) { action(); count++ }
function named(kind) {
  const t = { host: '127.0.0.1', port: 3306, database: kind === 'source' ? 'aquaflow_test_synthetic_offline' : 'aquaflow_restoredrill_synthetic_offline',
    user: 'synthetic_user', identity: { hostname: 'synthetic-host', port: 3306, uuid: '00000000-0000-0000-0000-000000000001' } }
  t.confirmation = safety.endpoint(t)
  return t
}
function configuration() { return { source: named('source'), target: named('target') } }
const BASE = Buffer.from("/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;\n/*!40101 SET NAMES utf8mb4 */;\nCREATE TABLE `sample` (`id` int NOT NULL, `note` varchar(200), PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='中文';\nINSERT INTO `sample` (`id`,`note`) VALUES (1,'中文; DROP DATABASE safe_as_data; \\'quote');\n/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;\n", 'utf8')

// Everything below uses synthetic argv, credentials, bytes and child stubs; no DB or private config read.
function fixture(options = {}) {
  const files = new Map([['config.json', Buffer.from(JSON.stringify(options.config || configuration()))]])
  const fds = new Map(); const calls = []; const reads = []; let nextFd = 10; let restored = false
  const io = {
    existsSync() { return true }, mkdirSync() {}, closeSync(fd) { fds.delete(fd) },
    openSync(file, flags) {
      if (flags === 'wx') { assert(!files.has(file)); files.set(file, Buffer.alloc(0)) }
      else assert(files.has(file))
      const fd = nextFd++; fds.set(fd, file); return fd
    },
    writeFileSync(file, data, flags) { if (flags && flags.flag === 'wx') assert(!files.has(file)); files.set(file, Buffer.from(data)) },
    readFileSync(file, encoding) { reads.push(file); assert(files.has(file)); const data = files.get(file); return encoding ? data.toString(encoding) : data },
    // No unlink/rm primitive: either happy or failing orchestration must keep all evidence.
  }
  const env = { AQUAFLOW_MYSQL_BIN: path.resolve('/synthetic/mysql'), AQUAFLOW_BACKUP_SOURCE_PASSWORD: 'synthetic-source-only',
    AQUAFLOW_BACKUP_TARGET_PASSWORD: 'synthetic-target-only', MYSQL_HOST: 'unapproved', MYSQL_HOME: '/unapproved',
    MYSQL_TEST_LOGIN_FILE: '/unapproved/login', MYSQL_TCP_PORT: '9999', PATH: 'synthetic-path', UNRELATED_SECRET: 'synthetic-hidden' }
  function spawn(command, args, opts) {
    calls.push({ command, args, opts, stdin: typeof opts.stdio[0] === 'number' ? Buffer.from(files.get(fds.get(opts.stdio[0]))) : null })
    const out = fds.get(opts.stdio[1]); const isDump = path.basename(command).startsWith('mysqldump')
    const key = args.some(a => a.includes('aquaflow_restoredrill_')) ? 'target' : 'source'
    const sql = (args.find(a => a.startsWith('--execute=')) || '').slice('--execute='.length)
    let data = '0\n'
    if (isDump) data = options.dumpBytes || (key === 'target' && options.corruptContent ? Buffer.from(BASE.toString('utf8').replace('中文; DROP', '改变; DROP')) : BASE)
    else if (sql === IDENTITY_SQL) {
      const t = configuration()[key]
      data = JSON.stringify({ hostname: t.identity.hostname, port: t.port, uuid: t.identity.uuid, database: t.database,
        scheduler: options.scheduler || 'OFF', sqlMode: options.sqlMode === undefined ? 'STRICT_TRANS_TABLES' : options.sqlMode,
        version: options.versionMismatch && key === 'target' ? '8.0.synthetic-other' : '8.0.synthetic',
        objects: key === 'target' ? options.occupied ? 1 : restored ? 1 : 0 : 1,
        ...(options.identityMismatch === key ? { uuid: '00000000-0000-0000-0000-000000000099' } : {}) })
    } else if (sql === SOURCE_SHAPE_SQL) data = options.unsupportedShape ? '1\n' : '0\n'
    else if (calls[calls.length - 1].stdin) { restored = true; data = '' }
    else if (sql.includes('ticket_account') && options.ticketLotOnly || sql.includes('customer_barrel_asset') && options.barrelLotOnly) {
      // Simulate real group-key semantics: the legacy account-left join loses a lot-only group.
      data = sql.includes('UNION SELECT') ? '1\n' : '0\n'
    } else if (sql.includes('orders o') && options.orphan) data = '1\n'
    files.set(out, Buffer.isBuffer(data) ? data : Buffer.from(data))
    files.set(fds.get(opts.stdio[2]), Buffer.from('synthetic stderr; not dump data'))
    return { status: options.importFailure && calls[calls.length - 1].stdin ? 1 : 0 }
  }
  let tick = 0n
  return { io, files, calls, reads, env, run(mode = 'drill', extra = []) {
    const old = console.log; console.log = () => {}
    try { return main([mode, '--config', 'config.json', ...extra], { fs: io, spawnSync: spawn, env, clock: () => (tick += 1000000n) }) }
    finally { console.log = old }
  } }
}
check(() => assert.deepEqual(safety.parseArgs([]), { mode: 'help' }))
for (const argv of [['backup'], ['cleanup'], ['backup', '--config', 'x', '--keep', '1'], ['drill', '--config', 'a', '--config', 'b'],
  ['drill', '--config', 'a', '--host', 'bad'], ['verify', 'a.sql', 'dbname'], ['backup', '--config'], ['drill', '--config', 'a', '--backup', 'x']]) {
  check(() => assert.throws(() => safety.parseArgs(argv)))
}
for (const alter of [c => { c.source.host = '127.0.0.1 --execute=BAD' }, c => { c.source.host = 'localhost' },
  c => { c.source.port = '3306' }, c => { c.source.database = 'aquaflow' }, c => { c.source.extraArgs = ['--force'] },
  c => { c.source.confirmation = c.source.database }, c => { delete c.target.confirmation }, c => { delete c.source.identity.uuid },
  c => { c.target.database = c.source.database; c.target.confirmation = safety.endpoint(c.target) }]) {
  check(() => { const c = configuration(); alter(c); const f = fixture({ config: c }); assert.throws(() => f.run()); assert.equal(f.calls.length, 0); assert.deepEqual(f.reads, ['config.json']) })
}
check(() => { const f = fixture(); delete f.env.AQUAFLOW_BACKUP_TARGET_PASSWORD; assert.throws(() => f.run(), /credential/); assert.equal(f.calls.length, 0) })
for (const options of [{ identityMismatch: 'source' }, { identityMismatch: 'target' }, { scheduler: 'ON' }, { scheduler: 'DISABLED' }, { occupied: true }, { unsupportedShape: true }]) {
  check(() => { const f = fixture(options); assert.throws(() => f.run()); assert.equal(f.calls.filter(x => x.stdin).length, 0) })
}
for (const sqlMode of ['NO_BACKSLASH_ESCAPES', 'STRICT_TRANS_TABLES,NO_BACKSLASH_ESCAPES', 'ANSI_QUOTES', null]) {
  check(() => { const f = fixture({ sqlMode }); assert.throws(() => f.run(), /SQL mode/); assert.equal(f.calls.filter(c => c.stdin).length, 0) })
}
for (const switchSql of ["SET SQL_MODE='NO_BACKSLASH_ESCAPES';", "/*!40101 SET SQL_MODE='ANSI_QUOTES' */;",
  'SET SQL_MODE=@OLD_CHARACTER_SET_CLIENT;', "SET @OLD_SQL_MODE='NO_BACKSLASH_ESCAPES'; SET SQL_MODE=@OLD_SQL_MODE;",
  'SET SQL_MODE=@OLD_SQL_MODE;']) {
  check(() => { const f = fixture({ dumpBytes: Buffer.concat([BASE, Buffer.from(switchSql)]) }); assert.throws(() => f.run()); assert.equal(f.calls.filter(c => c.stdin).length, 0) })
}
check(() => {
  const safe = Buffer.concat([Buffer.from("/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;\n"), BASE, Buffer.from('/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;')])
  const f = fixture({ dumpBytes: safe }); assert.equal(f.run().status, 'passed')
})
for (const options of [{ corruptContent: true }, { ticketLotOnly: true }, { barrelLotOnly: true }, { orphan: true }, { importFailure: true }, { versionMismatch: true }]) {
  check(() => { const f = fixture(options); assert.throws(() => f.run()); const results = [...f.files].filter(([p]) => p.endsWith('result.json')); assert.equal(results.length, 1); assert.equal(JSON.parse(results[0][1]).status, 'failed'); assert.equal(JSON.parse(results[0][1]).targetRetained, true); assert(f.calls.every(x => !x.args.some(a => /DROP DATABASE|CREATE DATABASE|SET GLOBAL/i.test(a)))) })
}
check(() => {
  const f = fixture(); const result = f.run()
  assert.equal(result.status, 'passed'); assert(result.targetRetained)
  assert.deepEqual(result.timings.map(x => x.name), ['export', 'import', 'content-verification', 'E8-E5-orders'])
  assert(result.totalSeconds > 0)
  for (const c of f.calls) {
    assert.strictEqual(c.opts.shell, false); assert.strictEqual(c.opts.windowsHide, true)
    assert.deepEqual(c.args.slice(0, 3), ['--no-defaults', '--no-login-paths', '--protocol=TCP'])
    assert(c.args.includes('--host=127.0.0.1')); assert(c.args.includes('--port=3306'))
    assert(!c.args.some(a => /synthetic-(?:source|target)-only|--force|--all-databases|--password/.test(a)))
    assert.deepEqual(Object.keys(c.opts.env).sort(), ['MYSQL_PWD', 'PATH'])
    assert.notEqual(c.opts.stdio[1], c.opts.stdio[2])
    if (!path.basename(c.command).startsWith('mysqldump')) { assert(c.args.includes('--skip-reconnect')); assert(c.args.includes('--local-infile=0')) }
  }
  const imported = f.calls.find(c => c.stdin).stdin
  assert(imported.subarray(imported.length - BASE.length).equals(BASE))
  assert(imported.toString('utf8').startsWith('SET @aquaflow_guard=IF('))
  assert(imported.toString('utf8').includes("@@global.event_scheduler='OFF'"))
  assert(imported.toString('utf8').includes("FIND_IN_SET('NO_BACKSLASH_ESCAPES',@@SESSION.sql_mode)=0"))
  assert(imported.toString('utf8').includes("FIND_IN_SET('ANSI_QUOTES',@@SESSION.sql_mode)=0"))
  const dump = f.calls.find(c => path.basename(c.command).startsWith('mysqldump'))
  assert(!dump.args.some(a => a.startsWith('--database=')))
  assert.equal(dump.args[dump.args.length - 1], configuration().source.database)
  const mf = [...f.files].find(([p]) => p.endsWith('.manifest.json'))
  assert.equal(JSON.parse(mf[1]).fingerprint.sample.count, 1)
  assert.equal(JSON.parse(mf[1]).sha256, safety.hash(BASE))
})
check(() => { const f = fixture(); f.run('backup'); assert(f.calls.every(c => !c.args.some(a => a.includes('aquaflow_restoredrill_')))) })
check(() => { const f = fixture(); const snapshot = f.run('backup'); const result = f.run('restore', ['--backup', snapshot.backup]); assert.equal(result.status, 'passed'); assert.equal(f.calls.filter(c => c.stdin).length, 1) })
check(() => { const f = fixture(); const snapshot = f.run('backup'); f.run('restore', ['--backup', snapshot.backup]); const result = f.run('verify', ['--backup', snapshot.backup]); assert.equal(result.status, 'passed'); assert.equal(f.calls.filter(c => c.stdin).length, 1) })
for (const rehash of [false, true]) check(() => {
  const f = fixture(); const snapshot = f.run('backup'); const bad = Buffer.concat([BASE, Buffer.from('USE aquaflow;')]); f.files.set(snapshot.backup, bad)
  if (rehash) { const mf = JSON.parse(f.files.get(snapshot.backup + '.manifest.json')); mf.sha256 = safety.hash(bad); mf.bytes = bad.length; f.files.set(snapshot.backup + '.manifest.json', Buffer.from(JSON.stringify(mf))) }
  assert.throws(() => f.run('restore', ['--backup', snapshot.backup])); assert.equal(f.calls.filter(c => c.stdin).length, 0)
})
check(() => {
  const t = named('target'); const actual = { database: t.database, hostname: t.identity.hostname, port: t.port, uuid: t.identity.uuid, version: '8.0.synthetic', scheduler: 'OFF', objects: null }
  assert.throws(() => safety.assertIdentity(t, actual, true)); actual.objects = 0; delete actual.version; assert.throws(() => safety.assertIdentity(t, actual, true))
})
check(() => { const fp = safety.inspectDump(BASE); assert.equal(fp.sample.count, 1); assert.throws(() => safety.sameContent(fp, safety.inspectDump(Buffer.from(BASE.toString().replace('中文; DROP', '不同; DROP')))), /fingerprint/) })
check(() => {
  const ddl = 'CREATE TABLE `s` (`id` int, `active` bigint GENERATED ALWAYS AS ((case when (`id` in (1,2)) then `id` else NULL end)) STORED, `label` varchar(100) GENERATED ALWAYS AS (IF(`id` IS NULL,CONCAT(\'中文\',IFNULL(`id`,\'\')),NULL)) STORED, UNIQUE KEY `idx` (`id`)) ENGINE=InnoDB;'
  assert.equal(safety.inspectDump(Buffer.from(ddl)).s.count, 0)
})
for (const sql of ['USE aquaflow;', 'CREATE DATABASE x;', 'DROP TABLE sample;', '/*!50000 DROP DATABASE x */;',
  'INSERT INTO other.sample (`id`) VALUES (1);', 'LOAD DATA LOCAL INFILE \'x\' INTO TABLE sample;',
  'SET GLOBAL event_scheduler=OFF;', 'SET SQL_MODE=\'NO_BACKSLASH_ESCAPES\';', '\\! external',
  'DELIMITER $$', 'CREATE EVENT e ON SCHEDULE EVERY 1 DAY DO SELECT 1;', 'CREATE VIEW v AS SELECT 1;',
  'INSERT INTO `sample` (`id`,`note`) VALUES (2,LOAD_FILE(\'/x\'));',
  'CREATE TABLE `evil` (`id` int DEFAULT (sys_eval(\'bad\'))) ENGINE=InnoDB;',
  'CREATE TABLE `evil` (`id` int) ENGINE=InnoDB DATA DIRECTORY=\'/x\';', '/*!50000 USE */ aquaflow;']) {
  check(() => assert.throws(() => safety.inspectDump(Buffer.concat([BASE, Buffer.from(sql)]))))
}
check(() => assert.throws(() => safety.inspectDump(Buffer.from([0xff, 0xfe]))))
check(() => assert.throws(() => safety.inspectDump(Buffer.from(BASE.toString() + "INSERT INTO `sample` (`id`,`note`) VALUES (2,'unclosed);"))))
check(() => {
  for (const sql of safety.BALANCE_QUERIES.slice(0, 2)) { assert(sql.includes('UNION SELECT')); assert(sql.includes('a.station_id<=>k.station_id')); assert(sql.includes('l.station_id<=>k.station_id')) }
})
check(() => {
  let calls = 0
  const entry = fs.readFileSync(path.resolve(__dirname, '../../scripts/backup-restore-drill.js'), 'utf8')
  const context = { module: { exports: {} }, process: { argv: ['node', 'script', 'drill'] },
    require(name) {
      assert.equal(name, './lib/backup-runner')
      return { main(argv) { calls++; return main(argv, { fs: { readFileSync() { throw new Error('private read forbidden') } }, spawnSync() { throw new Error('client forbidden') } }) } }
    }
  }
  assert.throws(() => vm.runInNewContext(entry, context))
  assert.equal(calls, 1)
})
console.log('全部通过：' + count + ' 项备份安全无库验证')
console.log('AQUAFLOW_SUITE_OK ' + count)
