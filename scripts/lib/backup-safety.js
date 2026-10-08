'use strict'

const { createHash } = require('crypto')
const { TextDecoder } = require('util')
const { assertIdentifier } = require('./scratch-database')

function onlyKeys(value, keys, label) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).some(k => !keys.includes(k))) {
    throw new Error(label + ': missing object or unsupported fields')
  }
}
function endpoint(target) {
  return `TCP|${target.host}|${target.port}|${target.database}|${target.user}|${target.identity.hostname}|${target.identity.port}|${target.identity.uuid}`
}
function target(value, kind) {
  onlyKeys(value, ['host', 'port', 'database', 'user', 'identity', 'confirmation'], kind)
  if (typeof value.host !== 'string' || !/^(?:0|[1-9]\d{0,2})(?:\.(?:0|[1-9]\d{0,2})){3}$/.test(value.host) ||
      value.host.split('.').some(x => Number(x) > 255) || value.host !== '127.0.0.1') {
    throw new Error(kind + ': this local-only executor requires explicit 127.0.0.1')
  }
  if (!Number.isInteger(value.port) || value.port < 1 || value.port > 65535) throw new Error(kind + ': invalid port')
  assertIdentifier(value.database)
  const prefix = kind === 'source' ? 'aquaflow_test_' : 'aquaflow_restoredrill_'
  if (!value.database.startsWith(prefix) || value.database.length <= prefix.length || /(?:^|_)(?:prod|production|live|backup|archive|bak)(?:_|$)/.test(value.database)) {
    throw new Error(kind + ': named dedicated isolated schema required')
  }
  if (typeof value.user !== 'string' || !/^[A-Za-z0-9_]{1,32}$/.test(value.user)) throw new Error(kind + ': explicit user required')
  onlyKeys(value.identity, ['hostname', 'port', 'uuid'], kind + ' identity')
  if (typeof value.identity.hostname !== 'string' || !/^[A-Za-z0-9_.-]{1,255}$/.test(value.identity.hostname) ||
      value.identity.port !== value.port || !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value.identity.uuid || '')) {
    throw new Error(kind + ': complete expected server identity required')
  }
  if (value.confirmation !== endpoint(value)) throw new Error(kind + ': exact full-target authorization required')
  return value
}
function config(value, mode) {
  onlyKeys(value, ['source', 'target'], 'config')
  const source = target(value.source, 'source')
  const destination = mode === 'backup' ? null : target(value.target, 'target')
  // A server can have several ports/aliases; a different spelling does not authorize writing the source schema.
  if (destination && (source.database === destination.database ||
      endpoint(source) === endpoint(destination))) throw new Error('source and restore target must differ')
  return { source, target: destination }
}
function parseArgs(argv) {
  const mode = argv[0] || 'help'
  if (mode === 'help' && argv.length <= 1) return { mode }
  if (!['backup', 'restore', 'verify', 'drill'].includes(mode)) throw new Error('unsupported command; use help')
  const result = { mode }
  for (let i = 1; i < argv.length; i += 2) {
    const key = { '--config': 'config', '--backup': 'backup' }[argv[i]]
    if (!key || result[key] || !argv[i + 1] || argv[i + 1].startsWith('--')) throw new Error('unsupported, duplicate or incomplete option')
    result[key] = argv[i + 1]
  }
  if (!result.config || (['restore', 'verify'].includes(mode) && !result.backup) ||
      (['backup', 'drill'].includes(mode) && result.backup)) throw new Error('explicit config and command-specific backup path required')
  return result
}
function clientArgs(t) {
  return ['--no-defaults', '--no-login-paths', '--protocol=TCP', `--host=${t.host}`, `--port=${t.port}`,
    `--user=${t.user}`, '--default-character-set=utf8mb4', `--database=${t.database}`]
}
function childEnv(env, password) {
  // No MYSQL_HOST/PORT/HOME/TEST_LOGIN_FILE or unrelated application credentials are inherited.
  const result = {}
  for (const key of Object.keys(env)) if (/^(?:PATH|SYSTEMROOT|WINDIR|TEMP|TMP|COMSPEC)$/i.test(key)) result[key] = env[key]
  result.MYSQL_PWD = password
  return result
}
function assertIdentity(t, actual, empty = false) {
  if (!actual || actual.database !== t.database || actual.hostname !== t.identity.hostname ||
      Number(actual.port) !== t.identity.port || actual.uuid !== t.identity.uuid ||
      typeof actual.version !== 'string' || !/^\d+\.\d+\./.test(actual.version)) throw new Error('server/database identity mismatch or incomplete version')
  if (empty && (actual.scheduler !== 'OFF' || actual.objects !== 0)) throw new Error('restore requires event_scheduler OFF and an existing empty target')
}

const DUMP_ARGS = ['--single-transaction', '--quick', '--hex-blob', '--complete-insert', '--skip-extended-insert',
  '--order-by-primary', '--skip-comments', '--skip-add-drop-table', '--skip-add-locks', '--skip-disable-keys',
  '--no-tablespaces', '--set-gtid-purged=OFF', '--routines', '--triggers', '--events']
const hash = bytes => createHash('sha256').update(bytes).digest('hex')

// Restricted mysql-dump dialect, not a general SQL rewriter. Unsupported syntax fails closed.
// Versioned comments execute in MySQL; unwrap them before checking, while retaining quoted data literally.
function tokens(bytes) {
  let text = new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  if (text.includes('\0')) throw new Error('NUL in SQL input')
  const result = []
  function scan(input) {
    for (let i = 0; i < input.length;) {
      const c = input[i]
      if (/\s/.test(c)) { i++; continue }
      if (input.startsWith('--', i) && /\s/.test(input[i + 2] || ' ' ) || c === '#') {
        const end = input.indexOf('\n', i); i = end < 0 ? input.length : end + 1; continue
      }
      if (input.startsWith('/*', i)) {
        const end = input.indexOf('*/', i + 2)
        if (end < 0) throw new Error('unterminated SQL comment')
        if (input[i + 2] === '!') scan(input.slice(i + 3, end).replace(/^\d+\s*/, ''))
        else if (input[i + 2] === '+') throw new Error('unsupported optimizer comment')
        i = end + 2; continue
      }
      if (c === "'" || c === '`') {
        const start = i++
        let closed = false
        while (i < input.length) {
          if (c === "'" && input[i] === '\\') { i += 2; continue }
          if (input[i++] === c) {
            if (input[i] === c) { i++; continue }
            closed = true; break
          }
        }
        if (!closed) throw new Error('unterminated SQL literal')
        result.push(input.slice(start, i)); continue
      }
      const number = /^(?:0x[0-9a-f]+|\d+(?:\.\d+)?(?:e[+-]?\d+)?)/i.exec(input.slice(i))
      if (number) { result.push(number[0]); i += number[0].length; continue }
      const word = /^(?:@@?|)[A-Za-z_][A-Za-z0-9_$]*/.exec(input.slice(i))
      if (word) { result.push(word[0]); i += word[0].length; continue }
      if ('(),;=+-'.includes(c)) { result.push(c); i++; continue }
      throw new Error('unsupported SQL character or qualified/external object at offset ' + i)
    }
  }
  scan(text)
  return result
}
function name(token) {
  const value = token && token.startsWith('`') ? token.slice(1, -1) : token
  assertIdentifier(value)
  return value
}
const SET_WORDS = new Set(['NAMES', 'UTF8MB4', 'UTF8', 'BINARY', 'CHARACTER_SET_CLIENT', 'CHARACTER_SET_RESULTS',
  'COLLATION_CONNECTION', 'TIME_ZONE', 'UNIQUE_CHECKS', 'FOREIGN_KEY_CHECKS', 'SQL_MODE', 'SQL_NOTES',
  'COLLATE', 'UTF8MB4_0900_AI_CI', 'UTF8MB4_GENERAL_CI'])
const DDL_PARENS = new Set(['BIGINT', 'INT', 'INTEGER', 'TINYINT', 'SMALLINT', 'MEDIUMINT', 'DECIMAL', 'NUMERIC',
  'VARCHAR', 'CHAR', 'VARBINARY', 'BINARY', 'ENUM', 'SET', 'DATETIME', 'TIMESTAMP', 'TIME', 'BIT', 'DOUBLE', 'FLOAT',
  'IF', 'IFNULL', 'CONCAT', 'COALESCE', 'CURRENT_TIMESTAMP', 'AS', 'IN', 'WHEN', 'CHECK', 'KEY', 'PRIMARY', 'UNIQUE', 'FOREIGN'])
function checkSet(statement, session) {
  for (const t of statement.slice(1)) {
    if (/^[(),=]$/.test(t) || /^[01]$/.test(t)) continue
    if (t.startsWith("'")) {
      if (!["'+00:00'", "'NO_AUTO_VALUE_ON_ZERO'", "'utf8mb4'", "'utf8'", "'binary'"].includes(t)) throw new Error('unsupported session setting literal')
      continue
    }
    const variable = t.toUpperCase().replace(/^@@?/, '').replace(/^OLD_/, '')
    if (variable === 'SAVED_CS_CLIENT' && t.startsWith('@') || SET_WORDS.has(variable)) continue
    throw new Error('unsupported or global session setting')
  }
  // Only the standard dump save/reset/restore sequence may assign SQL_MODE.
  // A generic allowed user variable must not become an indirect mode-switch channel.
  const assignments = []
  let start = 1
  for (let i = 1; i <= statement.length; i++) {
    if (i === statement.length || statement[i] === ',') { assignments.push(statement.slice(start, i)); start = i + 1 }
  }
  for (const part of assignments) {
    const left = (part[0] || '').toUpperCase()
    if (left === '@OLD_SQL_MODE') {
      if (part.length !== 3 || part[1] !== '=' || part[2].toUpperCase() !== '@@SQL_MODE') throw new Error('unsupported SQL_MODE save')
      session.modeSaved = true
    } else if (left.replace(/^@@?/, '') === 'SQL_MODE') {
      if (left !== 'SQL_MODE' || part.length !== 3 || part[1] !== '=' ||
          !(part[2] === "'NO_AUTO_VALUE_ON_ZERO'" || part[2].toUpperCase() === '@OLD_SQL_MODE' && session.modeSaved)) {
        throw new Error('unsupported SQL_MODE switch or restore')
      }
    }
  }
}
function checkInsert(statement) {
  let i = 3
  if (statement[i++] !== '(') throw new Error('complete INSERT columns required')
  while (i < statement.length && statement[i] !== ')') {
    name(statement[i++]); if (statement[i] === ',') i++; else if (statement[i] !== ')') throw new Error('invalid INSERT columns')
  }
  if (statement[i++] !== ')' || statement[i++].toUpperCase() !== 'VALUES' || statement[i++] !== '(') throw new Error('literal VALUES required')
  let count = 0
  while (i < statement.length && statement[i] !== ')') {
    if (['+', '-'].includes(statement[i])) i++
    if (/^_(?:binary|utf8mb4|utf8)$/i.test(statement[i] || '')) i++
    const value = statement[i++] || ''
    if (!(value.startsWith("'") || /^(?:NULL|0x[0-9a-f]+|\d+(?:\.\d+)?(?:e[+-]?\d+)?)$/i.test(value))) throw new Error('nonliteral INSERT value')
    count++
    if (statement[i] === ',') i++; else if (statement[i] !== ')') throw new Error('invalid INSERT value separator')
  }
  if (!count || statement[i++] !== ')' || i !== statement.length) throw new Error('one complete row per INSERT required')
}
function inspectDump(bytes) {
  const all = tokens(bytes)
  const tables = new Map()
  const session = { modeSaved: false }
  let start = 0
  for (let i = 0; i < all.length; i++) {
    if (all[i] !== ';') continue
    const s = all.slice(start, i); start = i + 1
    if (!s.length) continue
    const kind = s[0].toUpperCase()
    if (kind === 'SET') { checkSet(s, session); continue }
    if (kind === 'CREATE' && s[1].toUpperCase() === 'TABLE') {
      const table = name(s[2])
      if (tables.has(table) || s[3] !== '(') throw new Error('duplicate or unsupported table definition')
      const words = s.filter(t => /^[A-Za-z_]/.test(t)).map(t => t.toUpperCase())
      if (words.some(t => ['SELECT', 'DATA', 'DIRECTORY', 'TABLESPACE', 'CONNECTION', 'PASSWORD', 'UNION', 'GLOBAL', 'DEFINER', 'OUTFILE', 'INFILE'].includes(t)) ||
          !s.some((t, n) => t.toUpperCase() === 'ENGINE' && s[n + 1] === '=' && (s[n + 2] || '').toUpperCase() === 'INNODB')) {
        throw new Error('only plain InnoDB table definitions supported')
      }
      for (let n = 3; n < s.length - 1; n++) {
        if (s[n + 1] !== '(' || !/^[A-Za-z_`]/.test(s[n])) continue
        if (['KEY', 'INDEX', 'REFERENCES'].includes((s[n - 1] || '').toUpperCase())) { name(s[n]); continue }
        if (!DDL_PARENS.has(s[n].toUpperCase())) throw new Error('unsupported function or table expression')
      }
      tables.set(table, { schema: hash(JSON.stringify(s)), rows: [] })
    } else if (kind === 'INSERT' && s[1].toUpperCase() === 'INTO') {
      const table = name(s[2]); const entry = tables.get(table)
      if (!entry) throw new Error('INSERT target not defined in this dump')
      checkInsert(s); entry.rows.push(hash(JSON.stringify(s)))
    } else throw new Error('dangerous or unsupported dump statement; manual review required')
  }
  if (start !== all.length || !tables.size) throw new Error('incomplete or empty dump')
  const result = {}
  for (const [table, entry] of [...tables].sort(([a], [b]) => a.localeCompare(b))) {
    result[table] = { schema: entry.schema, count: entry.rows.length, content: hash(JSON.stringify(entry.rows.sort())) }
  }
  return result
}
function sameContent(left, right) {
  if (JSON.stringify(left) !== JSON.stringify(right)) throw new Error('restored schema/data fingerprint mismatch')
}
function hex(value) { return `CONVERT(0x${Buffer.from(String(value), 'utf8').toString('hex')} USING utf8mb4)` }
function restoreGuard(t) {
  // The identity/empty-schema/scheduler check executes in the SAME connection before the first imported statement.
  const ok = `DATABASE()=${hex(t.database)} AND @@hostname=${hex(t.identity.hostname)} AND @@port=${t.port} AND @@server_uuid=${hex(t.identity.uuid)} AND @@global.event_scheduler='OFF' AND ` +
    `(SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE())=0 AND ` +
    `(SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE())=0 AND ` +
    `(SELECT COUNT(*) FROM information_schema.events WHERE event_schema=DATABASE())=0 AND ` +
    `FIND_IN_SET('NO_BACKSLASH_ESCAPES',@@SESSION.sql_mode)=0 AND FIND_IN_SET('ANSI_QUOTES',@@SESSION.sql_mode)=0`
  return `SET @aquaflow_guard=IF((${ok}),'SELECT 1','AQUAFLOW_ABORT_UNCONFIRMED_RESTORE');\nPREPARE aquaflow_guard FROM @aquaflow_guard;\nEXECUTE aquaflow_guard;\nDEALLOCATE PREPARE aquaflow_guard;\n`
}

// Union the group keys: account-only and lot-only groups must both be checked.
function balanceQuery(account, lot, quantity, money = false) {
  return `SELECT COUNT(*) FROM (SELECT k.customer_id,k.station_id,k.product_id,IFNULL(a.q,0) aq,IFNULL(l.q,0) lq${money ? ',IFNULL(a.m,0) am,IFNULL(l.m,0) lm' : ''}
    FROM (SELECT customer_id,station_id,product_id FROM ${account} UNION SELECT customer_id,station_id,product_id FROM ${lot}) k
    LEFT JOIN (SELECT customer_id,station_id,product_id,SUM(${quantity}) q${money ? ',SUM(right_amount) m' : ''} FROM ${account} GROUP BY 1,2,3) a ON a.customer_id<=>k.customer_id AND a.station_id<=>k.station_id AND a.product_id<=>k.product_id
    LEFT JOIN (SELECT customer_id,station_id,product_id,SUM(remain_qty) q${money ? ',SUM(remain_qty*unit_price) m' : ''} FROM ${lot} GROUP BY 1,2,3) l ON l.customer_id<=>k.customer_id AND l.station_id<=>k.station_id AND l.product_id<=>k.product_id) x
    WHERE aq<>lq${money ? ' OR ABS(am-lm)>0.005' : ''}`
}
const BALANCE_QUERIES = [balanceQuery('ticket_account', 'ticket_lot', 'remain_quantity', true),
  balanceQuery('customer_barrel_asset', 'customer_barrel_lot', 'quantity'),
  'SELECT COUNT(*) FROM orders o LEFT JOIN customer c ON c.id=o.customer_id LEFT JOIN station s ON s.id=o.station_id WHERE c.id IS NULL OR s.id IS NULL']

module.exports = { endpoint, config, parseArgs, clientArgs, childEnv, assertIdentity, DUMP_ARGS, hash,
  inspectDump, sameContent, restoreGuard, BALANCE_QUERIES }
