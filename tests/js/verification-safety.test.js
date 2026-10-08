'use strict'

// F-68/F-74: execute the real guards and real M-09 gate with in-memory substitutes.
// No MySQL client, Java process, credential file or database is touched by this suite.
const assert = require('assert')
const fs = require('fs')
const path = require('path')
const vm = require('vm')
const os = require('os')
const { spawnSync } = require('child_process')
const { assertScratchDatabase } = require('../../scripts/lib/scratch-database')
const { getTestDatabaseTarget, parseJdbcTarget } = require('../../scripts/lib/test-database-target')
const { scanTransactionalCatches } = require('../../scripts/lib/transaction-catches')
const ROOT = path.resolve(__dirname, '../..')
let assertions = 0
function check(fn) { fn(); assertions++ }
const base = { name: 'aquaflow_test_session20261002', kind: 'test', source: 'aquaflow', confirmation: 'aquaflow_test_session20261002' }

// CI mixes Java LocalDateTime with SQL NOW(): runner, MySQL and JDBC must agree.
check(() => {
  const ci = fs.readFileSync(path.join(ROOT, '.github/workflows/ci.yml'), 'utf8')
  const jobEnv = ci.split(/\r?\n    env:\r?\n/)[1].split(/\r?\n    steps:/)[0]
  const mysqlEnv = ci.split(/\r?\n        env:\r?\n/)[1].split(/\r?\n        ports:/)[0]
  assert.match(jobEnv, /^      TZ: Asia\/Shanghai$/m, 'Java runner must share the database timezone')
  assert.match(mysqlEnv, /^          TZ: Asia\/Shanghai$/m)
  for (const key of ['TEST_DB_URL', 'DB_URL']) {
    const url = jobEnv.match(new RegExp('^      ' + key + ': "([^"\\r\\n]+)"$', 'm'))
    assert(url, key)
    assert.equal(new URL(url[1].replace(/^jdbc:/, '')).searchParams.get('serverTimezone'), 'Asia/Shanghai')
  }
})

for (const name of ['aquaflow', 'production', 'latest', 'contest', 'customer_test', 'aquaflow_test_backup',
  'aquaflow_test_bak', 'aquaflow_test_live', 'aquaflow_test_production', 'aquaflow_test_archive',
  'aquaflow_test_;DROP DATABASE aquaflow', 'aquaflow_test_`', 'aquaflow_test_$(whoami)',
  'aquaflow_test_-x', ' aquaflow_test', 'AquaFlow_test', 'aquaflow_test_', 'a'.repeat(65)]) {
  check(() => assert.throws(() => assertScratchDatabase({ ...base, name, confirmation: name })))
}
check(() => assert.throws(() => assertScratchDatabase({ ...base, source: base.name })))
check(() => assert.throws(() => assertScratchDatabase({ ...base, confirmation: undefined })))
check(() => assert.throws(() => assertScratchDatabase({ ...base, confirmation: 'aquaflow_test_other' })))
for (const [kind, name] of [['test', 'aquaflow_test'], ['test', base.name],
  ['prodcheck', 'aquaflow_prodstartup_check_s1'], ['restoredrill', 'aquaflow_restoredrill_s1']]) {
  check(() => assert.strictEqual(assertScratchDatabase({ kind, name, source: 'aquaflow', confirmation: name }), name))
}
check(() => assert.throws(() => assertScratchDatabase({ ...base, kind: 'prodcheck' })))

const bindingEnv = {
  TEST_DB_NAME: base.name, MYSQL_HOST: '127.0.0.1', MYSQL_PORT: '3306',
  AQUAFLOW_ALLOW_DB_RESET: base.name, AQUAFLOW_ALLOW_TEST_DB_TARGET: '127.0.0.1:3306/' + base.name
}
check(() => assert.equal(getTestDatabaseTarget(bindingEnv).endpoint, bindingEnv.AQUAFLOW_ALLOW_TEST_DB_TARGET))
check(() => assert.equal(getTestDatabaseTarget({ ...bindingEnv, TEST_DB_URL: 'jdbc:mysql://127.0.0.1/' + base.name,
  DB_URL: 'jdbc:mysql://127.0.0.1:3306/' + base.name + '?useSSL=false' }).port, '3306'))
check(() => assert.equal(getTestDatabaseTarget({ ...bindingEnv, MYSQL_HOST: '192.0.2.10', MYSQL_PORT: '3307',
  AQUAFLOW_ALLOW_TEST_DB_TARGET: '192.0.2.10:3307/' + base.name }).host, '192.0.2.10'))
for (const change of [
  { AQUAFLOW_ALLOW_TEST_DB_TARGET: undefined }, { AQUAFLOW_ALLOW_TEST_DB_TARGET: '127.0.0.1:3307/' + base.name },
  { TEST_DB_URL: 'jdbc:mysql://127.0.0.1:3306/customer_business_test' },
  { TEST_DB_URL: 'jdbc:mysql://192.0.2.11:3306/' + base.name }, { MYSQL_PORT: '3307' },
  { DB_URL: 'jdbc:mysql://127.0.0.1:3306/aquaflow' }, { MYSQL_USER: 'root --host=other.invalid' },
  { MYSQL_HOST: '127.1' }, { MYSQL_HOST: '[::1]' }, { MYSQL_PORT: '03306' }
]) check(() => assert.throws(() => getTestDatabaseTarget({ ...bindingEnv, ...change })))
for (const name of ['aquaflow_test_old', 'aquaflow_test_restore', 'aquaflow_test_pre', 'aquaflow_test_bak_s1']) {
  check(() => assert.throws(() => getTestDatabaseTarget({ ...bindingEnv, TEST_DB_NAME: name,
    AQUAFLOW_ALLOW_DB_RESET: name, AQUAFLOW_ALLOW_TEST_DB_TARGET: '127.0.0.1:3306/' + name })))
}
for (const suffix of ['?host=other.invalid', '?port=3307', '?databaseName=aquaflow', '?socketFactory=Factory',
  '?socksProxyHost=other.invalid', '?propertiesTransform=Transform', '?useConfigs=profile', '?sessionVariables=sql_mode=x',
  '?connectionLifecycleInterceptors=Interceptor', '?initSql=USE_aquaflow', '?user=test&password=never-echo',
  '?useSSL=false&useSSL=true', '?useSSL=false&USEssl=true', '?serverTimezone=Asia%2FShanghai', '?useSSL=false&', '#fragment']) {
  check(() => assert.throws(() => parseJdbcTarget('jdbc:mysql://127.0.0.1:3306/' + base.name + suffix)))
}
for (const url of ['jdbc:mysql:loadbalance://127.0.0.1/' + base.name, 'jdbc:mysql://127.0.0.1,other.invalid/' + base.name,
  'jdbc:mysql://address=(host=other.invalid)(port=3306)/' + base.name, 'jdbc:mysql://root:never-echo@127.0.0.1/' + base.name,
  'jdbc:mysql://127.0.0.1/' + base.name + '%2fextra', 'jdbc:mysql://127.0.0.1:0/' + base.name]) {
  check(() => assert.throws(() => parseJdbcTarget(url)))
}
for (const key of ['SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_HIKARI_JDBC_URL', 'SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_HOST',
  'SPRING_APPLICATION_JSON', 'SPRING_CONFIG_IMPORT', 'SPRING_PROFILES_ACTIVE', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS',
  'JDK_JAVA_OPTIONS', 'JAVA_OPTS', 'GRADLE_OPTS', 'MYSQL_PROTOCOL', 'MYSQL_SOCKET', 'MYSQL_TCP_PORT', 'java_tool_options', 'mysql_protocol', 'test_db_url']) {
  check(() => assert.throws(() => getTestDatabaseTarget({ ...bindingEnv, [key]: 'untrusted-configuration' })))
}
const startupSqlOverrides = [
  ['SPRING_SQL_INIT_MODE', 'always'], ['SPRING_SQL_INIT_SCHEMA_LOCATIONS', 'file:/fake-startup.sql'],
  ['SPRING_SQL_INIT_DATA_LOCATIONS_0', 'file:/fake-startup.sql'], ['spring.sql.init.schema-locations[0]', 'file:/fake-startup.sql'],
  ['spring_sql_init_mode', 'embedded'], ['Spring.Sql.Init.Mode', 'NEVER'], ['SPRING_SQL_INIT_PLATFORM', 'mysql'],
  ['SPRING_SQL_INIT_USERNAME', 'fake-init-user'], ['SPRING_SQL_INIT_PASSWORD', 'never-echo-init-password'],
  ['SPRING_SQL_INIT_CONTINUE_ON_ERROR', 'true'], ['SPRING_SQL_INIT_ENABLED', 'true'],
  ['SPRING_FLYWAY_URL', 'jdbc:mysql://192.0.2.11/unconfirmed'], ['SPRING_LIQUIBASE_CHANGE_LOG', 'file:/fake-change.sql'],
  ['SPRING_BATCH_JDBC_INITIALIZE_SCHEMA', 'always'], ['SPRING_QUARTZ_JDBC_SCHEMA', 'file:/fake-startup.sql'],
  ['SPRING_SESSION_JDBC_INITIALIZE_SCHEMA', 'always'], ['SPRING_JPA_HIBERNATE_DDL_AUTO', 'create'],
  ['SPRING_HIBERNATE_HBM2DDL_AUTO', 'create'], ['SPRING_R2DBC_URL', 'r2dbc:mysql://192.0.2.11/unconfirmed'],
  ['SPRING_DATASOURCE_SCHEMA', 'file:/fake-startup.sql'], ['SPRING_DATASOURCE_DATA', 'file:/fake-startup.sql']
]
for (const [key, value] of startupSqlOverrides) check(() => {
  assert.throws(() => getTestDatabaseTarget({ ...bindingEnv, [key]: value }),
    error => error instanceof Error && !error.message.includes('never-echo-init-password'))
})
check(() => assert.equal(getTestDatabaseTarget({ ...bindingEnv, SPRING_SQL_INIT_MODE: 'never' }).endpoint, bindingEnv.AQUAFLOW_ALLOW_TEST_DB_TARGET))
check(() => assert.throws(() => getTestDatabaseTarget({ ...bindingEnv, SPRING_SQL_INIT_MODE: 'never', SPRING_SQL_INIT_SCHEMA_LOCATIONS: 'file:/fake-startup.sql' })))

function scriptContext(file, env, argv) {
  const calls = []
  const localRead = []
  const writes = new Map()
  const writeHistory = []
  const dir = path.join(ROOT, 'scripts')
  const context = {
    __dirname: dir, module: { exports: {} },
    process: { env: { ...env }, argv: ['node', file, ...argv], platform: 'win32',
      exit(code) { const error = new Error('EXIT ' + code); error.exitCode = code; throw error } },
    console: { log() {}, error() {} },
    require(name) {
      if (name === 'fs') return {
        mkdtempSync(prefix) { return prefix + 'in-memory-session' },
        existsSync() { return true }, writeFileSync(file, data) { writes.set(file, data); writeHistory.push(data) },
        readFileSync(file) {
          if (file.endsWith('application-local.yml')) { localRead.push(file); return 'datasource:\n password: disposable-test-placeholder\n' }
          return writes.get(file) || ''
        }
      }
      if (name === 'child_process') return {
        spawnSync(command) { calls.push(command); return { status: context.clientStatus || 0 } },
        spawn() { throw new Error('Java launch forbidden in this suite') }
      }
      if (name === './lib/scratch-database') return require('../../scripts/lib/scratch-database')
      if (name === './lib/backup-runner') return require('../../scripts/lib/backup-runner')
      return require(name)
    }
  }
  vm.createContext(context)
  return { context, calls, localRead, writes, writeHistory }
}

// Actual entry points must refuse before even reading local credentials.
for (const [file, variable, name, argv] of [
  ['prod-startup-check.js', 'AQUAFLOW_PRODCHECK_DB', 'aquaflow', []],
  ['prod-startup-check.js', 'AQUAFLOW_PRODCHECK_DB', 'aquaflow_prodstartup_check_backup', []],
  ['backup-restore-drill.js', 'AQUAFLOW_DRILL_DB', 'aquaflow', ['drill']],
  ['backup-restore-drill.js', 'AQUAFLOW_DRILL_DB', 'aquaflow_restoredrill_backup', ['cleanup']],
  ['backup-restore-drill.js', 'AQUAFLOW_DRILL_DB', 'aquaflow_restoredrill_s1', ['drill']]
]) {
  const input = scriptContext(file, { [variable]: name, AQUAFLOW_ALLOW_DB_RESET: name,
    AQUAFLOW_DB: name === 'aquaflow_restoredrill_s1' ? name : 'aquaflow' }, argv)
  check(() => {
    assert.throws(() => vm.runInContext(fs.readFileSync(path.join(ROOT, 'scripts', file), 'utf8'), input.context))
    assert.strictEqual(input.calls.length, 0)
    assert.strictEqual(input.localRead.length, 0)
  })
}
for (const [file, argv] of [['prod-startup-check.js', []], ['backup-restore-drill.js', ['drill']]]) {
  const input = scriptContext(file, {}, argv)
  check(() => { assert.throws(() => vm.runInContext(fs.readFileSync(path.join(ROOT, 'scripts', file), 'utf8'), input.context)); assert.strictEqual(input.calls.length, 0); assert.strictEqual(input.localRead.length, 0) })
}

// Real prod preparation/cleanup with fake clients; recheck the confirmation at cleanup.
{
  const name = 'aquaflow_prodstartup_check_s1'
  const input = scriptContext('prod-startup-check.js', { AQUAFLOW_PRODCHECK_DB: name, AQUAFLOW_ALLOW_DB_RESET: name }, [])
  const source = fs.readFileSync(path.join(ROOT, 'scripts/prod-startup-check.js'), 'utf8').split('const JAR = jarPath()')[0]
  vm.runInContext(source + '\nmodule.exports = {prepareDb,dropDb};', input.context)
  check(() => { input.context.module.exports.prepareDb(); assert.strictEqual(input.calls.length, 3); assert(input.writeHistory.some(sql => String(sql).includes('DROP DATABASE IF EXISTS ' + name))) })
  check(() => { input.context.module.exports.dropDb(); assert.strictEqual(input.calls.length, 4) })
  check(() => { delete input.context.process.env.AQUAFLOW_ALLOW_DB_RESET; assert.throws(() => input.context.module.exports.dropDb()); assert.strictEqual(input.calls.length, 4) })
  check(() => { input.context.process.env.AQUAFLOW_ALLOW_DB_RESET = name; input.context.clientStatus = 1; assert.throws(() => input.context.module.exports.dropDb(), /EXIT 1/); assert.strictEqual(input.calls.length, 5) })
}
{
  const text = fs.readFileSync(path.join(ROOT, 'scripts/prod-startup-check.js'), 'utf8')
  const tail = text.slice(text.lastIndexOf("console.log('-'.repeat(88))"))
  let drops = 0
  check(() => {
    assert.throws(() => vm.runInNewContext(tail, { bad: 1, SCRATCH_DB: 'aquaflow_prodstartup_check_s1',
      console: { log() {}, error() {} }, dropDb() { drops++ }, process: { exit() { throw new Error('stopped') } } }), /stopped/)
    assert.strictEqual(drops, 0)
  })
}
// Backup no longer has a deletion command or credential-reading help path.
// Full success/failure restore orchestration is exercised with binary FD stubs in backup-safety.test.js.
for (const argv of [['cleanup'], ['backup', '--keep', '1'], ['drill']]) {
  const input = scriptContext('backup-restore-drill.js', {}, argv)
  check(() => { assert.throws(() => vm.runInContext(fs.readFileSync(path.join(ROOT, 'scripts/backup-restore-drill.js'), 'utf8'), input.context)); assert.equal(input.calls.length, 0); assert.equal(input.localRead.length, 0) })
}
{
  const input = scriptContext('backup-restore-drill.js', {}, ['help'])
  check(() => { vm.runInContext(fs.readFileSync(path.join(ROOT, 'scripts/backup-restore-drill.js'), 'utf8'), input.context); assert.equal(input.calls.length, 0); assert.equal(input.localRead.length, 0) })
}
// Bash entry uses the SAME guard before mysql discovery and again immediately before DROP.
{
  const sh = fs.readFileSync(path.join(ROOT, 'scripts/provision-test-db.sh'), 'utf8')
  check(() => { assert(sh.indexOf('node "$HERE/scripts/lib/scratch-database.js"') < sh.indexOf('# 探测 mysql')); assert.strictEqual(sh.match(/^node "\$HERE\/scripts\/lib\/scratch-database.js"/gm).length, 2) })
}

// Run the actual Bash entry against a fake mysql executable. FD redirection avoids
// sandbox pipe restrictions. Even allowed targets only reach this recorder.
{
  const bash = process.env.AQUAFLOW_TEST_BASH || (process.platform === 'win32' ? 'D:/backend/Git/bin/bash.exe' : 'bash')
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'aquaflow-db-guard-'))
  const mysql = path.join(temp, 'fake-mysql.sh')
  const calls = path.join(temp, 'calls.txt')
  const output = path.join(temp, 'out.txt')
  const errors = path.join(temp, 'err.txt')
  const shellPath = value => process.platform === 'win32'
    ? value.replace(/\\/g, '/').replace(/^([A-Za-z]):/, (_, drive) => '/' + drive.toLowerCase()) : value
  fs.writeFileSync(mysql, `#!/usr/bin/env bash
# Check both the capability probe and every SQL invocation before recording anything.
if [[ -z "$MYSQL_TEST_LOGIN_FILE" || -e "$MYSQL_TEST_LOGIN_FILE" || "$MYSQL_TEST_LOGIN_FILE" == "$FAKE_UNTRUSTED_LOGIN" ]]; then exit 92; fi
if [[ "$1" != "--no-defaults" ]]; then exit 93; fi
if [[ " $* " == *" --version "* ]]; then
  if [[ "$FAKE_MYSQL_CAPABILITY" == "broken" ]]; then exit 94; fi
  if [[ "$FAKE_MYSQL_CAPABILITY" == "legacy" && " $* " == *" --no-login-paths "* ]]; then exit 2; fi
  exit 0
fi
if [[ "$FAKE_MYSQL_CAPABILITY" == "legacy" && " $* " == *" --no-login-paths "* ]]; then exit 2; fi
printf "%s\\n" "$*" >> "$FAKE_DB_CALLS"
if [[ " $* " == *" -N "* ]]; then printf "0\\n"; fi
`, { mode: 0o700 })
  try {
    const run = (name, confirmation, source = 'aquaflow', entry = 'scripts/provision-test-db.sh', changes = {}) => {
      fs.writeFileSync(calls, '')
      const out = fs.openSync(output, 'w'); const err = fs.openSync(errors, 'w')
      let result
      try {
        const cleanEnv = { ...process.env }
        for (const key of Object.keys(cleanEnv)) if (/^(SPRING|JAVA|_JAVA|JDK_JAVA|GRADLE_OPTS|MYSQL|TEST_DB|DB_URL|AQUAFLOW_ALLOW)/i.test(key)) delete cleanEnv[key]
        result = spawnSync(bash, [shellPath(path.isAbsolute(entry) ? entry : path.join(ROOT, entry))], {
          env: { ...cleanEnv, MYSQL_BIN: shellPath(mysql), MYSQL_USER: 'root', MYSQL_PWD: 'disposable-test-placeholder',
            MYSQL_HOST: '127.0.0.1', MYSQL_PORT: '3306', AQUAFLOW_ALLOW_TEST_DB_TARGET: '127.0.0.1:3306/' + name,
            TEST_DB_NAME: name, AQUAFLOW_ALLOW_DB_RESET: confirmation || '', AQUAFLOW_ALLOW_PRODCHECK_RESET: '',
            AQUAFLOW_DB: source, FAKE_DB_CALLS: shellPath(calls), ...changes },
          stdio: ['ignore', out, err], timeout: 15000
        })
      } finally { fs.closeSync(out); fs.closeSync(err) }
      if (result.error) throw result.error
      return { status: result.status, calls: fs.readFileSync(calls, 'utf8'), errors: fs.readFileSync(errors, 'utf8') }
    }
    for (const name of ['aquaflow', 'aquaflow_test_backup', 'aquaflow_test_bak', 'aquaflow_test_live',
      'aquaflow_test_;DROP DATABASE aquaflow', 'aquaflow_test_`', 'aquaflow_test_$(whoami)']) {
      check(() => { const r = run(name, name); assert.notStrictEqual(r.status, 0); assert.strictEqual(r.calls, '') })
    }
    check(() => { const r = run(base.name, base.name, base.name); assert.notStrictEqual(r.status, 0); assert.strictEqual(r.calls, '') })
    check(() => { const r = run(base.name, ''); assert.notStrictEqual(r.status, 0); assert.strictEqual(r.calls, '') })
    check(() => { const r = run(base.name, base.name); assert.strictEqual(r.status, 0, r.errors); assert.strictEqual(r.calls.trim().split('\n').length, 3); assert(r.calls.includes('DROP DATABASE IF EXISTS `' + base.name + '`')) })
    for (const capability of ['modern', 'legacy']) check(() => {
      const untrustedLogin = path.join(temp, 'untrusted-login.cnf')
      fs.writeFileSync(untrustedLogin, 'synthetic login file must never be read')
      try {
        const r = run(base.name, base.name, 'aquaflow', 'scripts/provision-test-db.sh', {
          FAKE_MYSQL_CAPABILITY: capability, FAKE_UNTRUSTED_LOGIN: shellPath(untrustedLogin),
          MYSQL_TEST_LOGIN_FILE: shellPath(untrustedLogin)
        })
        assert.equal(r.status, 0, r.errors)
        assert.equal(r.calls.trim().split('\n').length, 3)
        assert(r.calls.trim().split('\n').every(line => line.startsWith('--no-defaults ')))
        assert.equal(r.calls.includes('--no-login-paths'), capability === 'modern')
      } finally { fs.unlinkSync(untrustedLogin) }
    })
    check(() => {
      const r = run(base.name, base.name, 'aquaflow', 'scripts/provision-test-db.sh', { FAKE_MYSQL_CAPABILITY: 'broken' })
      assert.notEqual(r.status, 0); assert.equal(r.calls, '')
    })
    check(() => { const r = run(base.name, base.name, 'aquaflow', 'scripts/verify.sh'); assert.notStrictEqual(r.status, 0); assert.strictEqual(r.calls, '') })
    for (const changes of [
      { TEST_DB_URL: 'jdbc:mysql://review.invalid:3306/customer_business_test?useSSL=false' },
      { TEST_DB_URL: 'jdbc:mysql://127.0.0.1:3307/' + base.name },
      { TEST_DB_URL: 'jdbc:mysql://192.0.2.11:3306/' + base.name },
      { TEST_DB_URL: 'jdbc:mysql://127.0.0.1:3306/' + base.name + '?propertiesTransform=Transform' },
      { SPRING_APPLICATION_JSON: '{"spring":{"datasource":{"url":"untrusted"}}}' },
      { SPRING_DATASOURCE_HIKARI_JDBC_URL: 'untrusted' }, { JAVA_TOOL_OPTIONS: '-Dspring.datasource.url=untrusted' },
      { SPRING_SQL_INIT_MODE: 'always', SPRING_SQL_INIT_SCHEMA_LOCATIONS: 'file:/fake-startup.sql' },
      { SPRING_SQL_INIT_DATA_LOCATIONS_0: 'file:/fake-startup.sql' },
      { SPRING_SQL_INIT_MODE: 'never', SPRING_SQL_INIT_SCHEMA_LOCATIONS: 'file:/fake-startup.sql' },
      { SPRING_FLYWAY_URL: 'jdbc:mysql://192.0.2.11/unconfirmed' },
      { SPRING_DATASOURCE_SCHEMA: 'file:/fake-startup.sql' },
      { DB_URL: 'jdbc:mysql://127.0.0.1:3306/aquaflow' }, { AQUAFLOW_ALLOW_TEST_DB_TARGET: '' }
    ]) check(() => { const r = run(base.name, base.name, 'aquaflow', 'scripts/provision-test-db.sh', changes); assert.notEqual(r.status, 0); assert.equal(r.calls, '') })
    check(() => {
      const r = run(base.name, base.name, 'aquaflow', 'scripts/provision-test-db.sh', { MYSQL_HOST: '192.0.2.10', MYSQL_PORT: '3307',
        TEST_DB_URL: 'jdbc:mysql://192.0.2.10:3307/' + base.name, AQUAFLOW_ALLOW_TEST_DB_TARGET: '192.0.2.10:3307/' + base.name })
      assert.equal(r.status, 0, r.errors)
      for (const line of r.calls.trim().split('\n')) for (const arg of ['--no-defaults', '--no-login-paths', '--protocol=TCP', '--host=192.0.2.10', '--port=3307']) assert(line.includes(arg))
    })
    // Execute the real verify preflight/provision/backend entry, stopping at fake Gradle.
    // The original script directory is preserved explicitly; no real gradlew is invoked.
    const wrapper = path.join(temp, 'fake-verify-prefix.sh'), capture = path.join(temp, 'gradle-target.txt')
    let prefix = fs.readFileSync(path.join(ROOT, 'scripts/verify.sh'), 'utf8').split('echo "==================== [3/7]')[0]
    prefix = prefix.replace('cd "$(cd "$(dirname "$0")/.." && pwd)"', 'cd "' + shellPath(ROOT) + '"')
    fs.writeFileSync(wrapper, '#!/usr/bin/env bash\nfunction ./gradlew() { printf "%s\\n" "$TEST_DB_URL" > "$FAKE_GRADLE_CAPTURE"; return 93; }\n' + prefix)
    try {
      const changes = { AQUAFLOW_ALLOW_PRODCHECK_RESET: 'aquaflow_prodstartup_check', FAKE_GRADLE_CAPTURE: shellPath(capture) }
      for (const delta of [{ TEST_DB_URL: 'jdbc:mysql://review.invalid:3306/customer_business_test?useSSL=false' },
        { SPRING_DATASOURCE_URL: 'untrusted' }, { JDK_JAVA_OPTIONS: '-Dspring.datasource.url=untrusted' },
        { SPRING_SQL_INIT_MODE: 'always', SPRING_SQL_INIT_SCHEMA_LOCATIONS: 'file:/fake-startup.sql' },
        { SPRING_SQL_INIT_DATA_LOCATIONS: 'file:/fake-startup.sql' }]) check(() => {
        fs.writeFileSync(capture, '')
        const r = run(base.name, base.name, 'aquaflow', wrapper, { ...changes, ...delta })
        assert.notEqual(r.status, 0); assert.equal(r.calls, ''); assert.equal(fs.readFileSync(capture, 'utf8'), '')
      })
      check(() => {
        fs.writeFileSync(capture, '')
        const r = run(base.name, base.name, 'aquaflow', wrapper, changes)
        assert.equal(r.status, 93, r.errors); assert.equal(r.calls.trim().split('\n').length, 3)
        const forwarded = parseJdbcTarget(fs.readFileSync(capture, 'utf8').trim())
        assert.equal(forwarded.endpoint, bindingEnv.AQUAFLOW_ALLOW_TEST_DB_TARGET)
        assert(r.calls.split('\n').filter(Boolean).every(x => x.includes('--host=' + forwarded.host) && x.includes('--port=' + forwarded.port)))
      })
    } finally { for (const file of [wrapper, capture]) if (fs.existsSync(file)) fs.unlinkSync(file) }
    // CI's real preparation run block also calls the protected entry. Substitute every
    // external DB executable; no package installation or remote CI is performed.
    const ci = fs.readFileSync(path.join(ROOT, '.github/workflows/ci.yml'), 'utf8')
    const envBlock = ci.split(/\r?\n    env:\r?\n/)[1].split(/\r?\n    steps:/)[0]
    const ciEnv = {}
    for (const key of ['MYSQL_HOST', 'MYSQL_PORT', 'TEST_DB_NAME', 'AQUAFLOW_ALLOW_DB_RESET', 'AQUAFLOW_ALLOW_TEST_DB_TARGET', 'TEST_DB_URL', 'DB_URL']) {
      const m = envBlock.match(new RegExp('^      ' + key + ': (?:"([^"]*)"|([^\\r\\n]+))$', 'm'))
      assert(m, key); ciEnv[key] = m[1] || m[2].trim()
    }
    check(() => assert.equal(getTestDatabaseTarget(ciEnv).endpoint, '127.0.0.1:3306/aquaflow_test'))
    const bin = path.join(temp, 'bin'), ciWrapper = path.join(temp, 'fake-ci-prepare.sh')
    fs.mkdirSync(bin)
    fs.copyFileSync(mysql, path.join(bin, 'mysql')); fs.chmodSync(path.join(bin, 'mysql'), 0o700)
    fs.writeFileSync(path.join(bin, 'mysqladmin'), '#!/usr/bin/env bash\nexit 0\n', { mode: 0o700 })
    fs.writeFileSync(path.join(bin, 'sudo'), '#!/usr/bin/env bash\nexit 91\n', { mode: 0o700 })
    const prepBlock = ci.split('      - name: 初始化测试库（schema.sql 为权威基线）')[1].split('      - name: 后端集成测试')[0]
    const prepRun = prepBlock.split('        run: |')[1].split(/\r?\n/).map(line => line.replace(/^          /, '')).join('\n')
    fs.writeFileSync(ciWrapper, '#!/usr/bin/env bash\nset -euo pipefail\ncd "' + shellPath(ROOT) + '"\n' + prepRun)
    try {
      const changes = { ...ciEnv, DB_PASSWORD: 'disposable-test-placeholder', PATH: bin + path.delimiter + process.env.PATH }
      check(() => { const r = run('aquaflow_test', 'aquaflow_test', 'aquaflow', ciWrapper, changes); assert.equal(r.status, 0, r.errors); assert.equal(r.calls.trim().split('\n').length, 3) })
      check(() => { const r = run('aquaflow_test', 'aquaflow_test', 'aquaflow', ciWrapper, { ...changes, MYSQL_PORT: '3307' }); assert.notEqual(r.status, 0); assert.equal(r.calls, '') })
      check(() => { const r = run('aquaflow_test', 'aquaflow_test', 'aquaflow', ciWrapper, { ...changes, SPRING_SQL_INIT_MODE: 'always', SPRING_SQL_INIT_SCHEMA_LOCATIONS: 'file:/fake-startup.sql' }); assert.notEqual(r.status, 0); assert.equal(r.calls, '') })
    } finally {
      for (const file of [ciWrapper, ...['mysql', 'mysqladmin', 'sudo'].map(x => path.join(bin, x))]) fs.unlinkSync(file)
      fs.rmdirSync(bin)
    }
  } finally {
    for (const file of [mysql, calls, output, errors]) if (fs.existsSync(file)) fs.unlinkSync(file)
    fs.rmdirSync(temp)
  }
}

const gate = fs.readFileSync(path.join(ROOT, 'scripts/check-ledger-claims.js'), 'utf8')
const actualM09 = gate.slice(gate.indexOf('// M-09'), gate.indexOf('// M-10'))
function gateResult(source) {
  let result
  vm.runInNewContext(actualM09, { mainSrc: [{ f: '/fixtures/Fixture.java' }],
    fs: { readFileSync() { return source } }, path, ROOT: '/fixtures', scanTransactionalCatches,
    must(id, claim, ok) { assert.strictEqual(id, 'M-09'); result = ok } })
  return result
}
const bad = 'try { work(); } catch (BusinessException e) { ignored(); }'
for (const method of [
  `@Transactional public void go() { ${bad} }`,
  `@Transactional\n public void go() { ${bad} }`,
  `@Transactional(rollbackFor = {Exception.class, RuntimeException.class})\n@Deprecated\n public void\n go(\n Long id\n ) throws Exception { ${bad} }`,
  `@org.springframework.transaction.annotation.Transactional\n public void go() { ${bad} }`,
  `@Transactional /* annotation comment { } */\n public void go() { String url="http://test/{fake}"; ${bad} }`,
  `@Transactional public void go() { try {} catch(final com.example.BusinessException | OtherException e) {} }`
]) check(() => assert.strictEqual(gateResult('class Fixture {' + method + '}'), false))
check(() => assert.strictEqual(gateResult('@Transactional class Fixture { public void go() {' + bad + '} }'), false))
check(() => assert.strictEqual(gateResult('@Transactional class Fixture { @Transactional(readOnly=true) public void go() {' + bad + '} }'), false))
for (const source of [
  `class Fixture { public void sweep(){ try { proxy.expire(); } catch(BusinessException e) {} } @Transactional public void expire() { work(); } }`,
  `class Fixture { @Transactional public void go(){ String text="catch (BusinessException e) {}"; char bracket='}'; /* catch (BusinessException e) {} */ work(); } }`,
  `class Fixture { @Transactional public void go(){ String text="""\n catch (BusinessException e) { }\n """; work(); } }`,
  `@Transactional class Fixture { @Transactional(propagation = Propagation.NOT_SUPPORTED) public void noTx(){ ${bad} } public void go(){ work(); } }`,
  `@Transactional class Fixture { public void go(){ work(); } class Inner { public void noTx(){ ${bad} } } }`,
  `interface Fixture { @Transactional void noBody(); }`
]) check(() => assert.strictEqual(gateResult(source), true))
check(() => assert.strictEqual(gateResult('class Fixture { @Transactional public void broken( {'), false))
check(() => assert.strictEqual(gateResult('class Fixture { @Transactional int badField; }'), false))
console.log('AQUAFLOW_SUITE_OK ' + assertions)
