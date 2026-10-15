'use strict'
const assert = require('assert'), fs = require('fs'), path = require('path'), os = require('os')
const { spawnSync } = require('child_process')
const ROOT = path.resolve(__dirname, '../..')
const dir = path.join(ROOT, 'deployment/limited-trial')
const shell = process.env.AQUAFLOW_TEST_SH || 'sh'
const tests = [], test = (name, fn) => tests.push({ name, fn })
const requiredKeys = ['DB_URL','DB_USERNAME','DB_PASSWORD','JWT_SECRET','WX_APP_ID','WX_APP_SECRET','WX_STAFF_APP_ID','WX_STAFF_APP_SECRET','CORS_ALLOWED_ORIGINS']
function fixture() {
  // Synthetic strings only. No inherited credentials and no network/Java invocation.
  return { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot || '',
    DB_URL: 'jdbc:mysql://127.0.0.1:1/synthetic_no_connection', DB_USERNAME: 'synthetic-user',
    DB_PASSWORD: 'synthetic-password-not-live', JWT_SECRET: 'synthetic-only-not-a-live-key-1234567890',
    WX_APP_ID: 'synthetic-customer', WX_APP_SECRET: 'synthetic-customer-secret',
    WX_STAFF_APP_ID: 'synthetic-staff', WX_STAFF_APP_SECRET: 'synthetic-staff-secret',
    CORS_ALLOWED_ORIGINS: 'https://synthetic.test', SPRING_PROFILES_ACTIVE: 'prod',
    DEV_LOGIN_ENABLED: 'false', MOCK_WECHAT_PAY: 'false', SERVER_FORWARD_HEADERS_STRATEGY: 'none', SPRING_SQL_INIT_MODE: 'never' }
}
function run(env, args = ['--check-only']) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'aquaflow-trial-offline-'))
  const output = path.join(tmp, 'output.log'), fd = fs.openSync(output, 'w')
  let result
  try { result = spawnSync(shell, [path.join(dir, 'entrypoint.sh'), ...args], { env, timeout: 5000, stdio: ['ignore', fd, fd] }) }
  finally { fs.closeSync(fd) }
  const text = fs.readFileSync(output, 'utf8')
  fs.unlinkSync(output); fs.rmdirSync(tmp)
  assert.ifError(result.error)
  assert.ok(!text.includes('synthetic-password-not-live') && !text.includes('synthetic-only-not-a-live-key-1234567890'), 'must not output credential values')
  return { status: result.status, text }
}
test('synthetic configuration checks without starting Java or connecting', () => {
  const r = run(fixture()); assert.strictEqual(r.status, 0); assert.match(r.text, /AQUAFLOW_TRIAL_CONFIG_SHAPE_OK/)
})
for (const key of requiredKeys) test('missing '+key+' refuses before launch', () => {
  const e = fixture(); delete e[key]; const r = run(e, []); assert.strictEqual(r.status, 2); assert.ok(r.text.includes(key))
})
for (const value of ['__REQUIRED__','__REPLACE__','${UNRESOLVED}','   ']) test('placeholder/blank refuses: '+JSON.stringify(value), () => {
  const r = run({ ...fixture(), DB_PASSWORD: value }, []); assert.strictEqual(r.status, 2)
})
for (const [key,value] of [['SPRING_PROFILES_ACTIVE','local'],['DEV_LOGIN_ENABLED','true'],['MOCK_WECHAT_PAY','true'],['APP_DEV_LOGIN_ENABLED','true'],['APP_PAYMENT_MOCK_WECHAT_PAY','true'],['SERVER_FORWARD_HEADERS_STRATEGY','framework'],['SPRING_SQL_INIT_MODE','always'],['SPRING_APPLICATION_JSON','{}'],['JAVA_TOOL_OPTIONS','-version']]) test('unsafe/unsupported '+key+' refuses before launch', () => {
  assert.strictEqual(run({ ...fixture(), [key]: value }, []).status, 2)
})
const modelKeys = ['INDEPENDENT_BARREL_RIGHTS_ENABLED',
  'AQUAFLOW_BARREL_INDEPENDENT_RIGHTS_ENABLED', 'AQUAFLOW_BARREL_INDEPENDENTRIGHTSENABLED']
for (const key of modelKeys) {
  test('independent model '+key+' false refuses even in check-only', () => {
    const r=run({ ...fixture(), [key]: 'false' }); assert.strictEqual(r.status,2); assert.ok(r.text.includes(key))
  })
  test('independent model '+key+' explicit blank refuses', () => {
    assert.strictEqual(run({ ...fixture(), [key]: '' }).status,2)
  })
  test('independent model '+key+' true preserves the model', () => {
    assert.strictEqual(run({ ...fixture(), [key]: 'true' }).status,0)
  })
  test('independent model conflict cannot hide false in '+key, () => {
    const e={ ...fixture(), ...Object.fromEntries(modelKeys.map(k=>[k,'true'])), [key]:'false' }
    assert.strictEqual(run(e).status,2)
  })
}
test('independent model defaults are true when omitted; final launch pins the actual c19 property', () => {
  assert.strictEqual(run(fixture()).status,0)
  const source=fs.readFileSync(path.join(dir,'entrypoint.sh'),'utf8')
  for(const key of modelKeys) {
    assert.ok(source.includes('${'+key+'-true}'))
    assert.ok(source.includes(key+'=true'))
  }
  assert.match(source,/exec java[^\n]* --aquaflow\.barrel\.independent-rights-enabled=true/)
})
test('short JWT and non-MySQL input refuse', () => {
  assert.strictEqual(run({ ...fixture(), JWT_SECRET: 'short' }, []).status, 2)
  assert.strictEqual(run({ ...fixture(), DB_URL: 'not-a-jdbc-url' }, []).status, 2)
})
test('caller cannot override launch args', () => assert.strictEqual(run(fixture(), ['--spring.profiles.active=local']).status, 2))
test('empty template refuses; secrets stay blank and flags are safe', () => {
  const text = fs.readFileSync(path.join(dir, 'trial.env.example'), 'utf8')
  const env = Object.fromEntries(text.split(/\r?\n/).filter(l => /^[A-Z_]+=/.test(l)).map(l => { const i=l.indexOf('=');return [l.slice(0,i),l.slice(i+1)] }))
  for(const key of requiredKeys) assert.strictEqual(env[key], '')
  assert.strictEqual(run({ PATH: process.env.PATH, SystemRoot: process.env.SystemRoot || '', ...env }, []).status, 2)
})
test('Docker context contains no local config or database initialization', () => {
  const docker = fs.readFileSync(path.join(dir,'Dockerfile'),'utf8'), ignore = fs.readFileSync(path.join(dir,'.dockerignore'),'utf8').replace(/\r\n/g, '\n')
  assert.match(docker,/USER 10001:10001/); assert.match(docker,/EXPOSE 8080/)
  assert.ok(!/COPY\s+\.\s/.test(docker)); assert.ok(!/gradle|mysql|curl|wget/.test(docker))
  assert.strictEqual(ignore.trim(),'**\n!Dockerfile\n!entrypoint.sh\n!app.jar')
})
function dockerInstructions() {
  return fs.readFileSync(path.join(dir, 'Dockerfile'), 'utf8').replace(/\\\r?\n/g, ' ')
    .split(/\r?\n/).map(line => line.trim()).filter(line => line && !line.startsWith('#'))
}
test('Docker launch permissions do not depend on archive file modes or implicit parent creation', () => {
  const steps = dockerInstructions()
  // 006 had readable files but a 0644 parent: check directory creation separately from COPY.
  const parent = steps.indexOf('RUN install -d -m 0755 /opt/aquaflow')
  assert.ok(parent >= 0, 'create a traversable application directory explicitly')
  for (const name of ['app.jar', 'entrypoint.sh']) {
    const copy = steps.indexOf('COPY --chmod=0644 '+name+' /opt/aquaflow/'+name)
    assert.ok(copy > parent, name+' must be copied with a readable mode after directory creation')
  }
})
test('Docker checks the actual runtime user can traverse and read the launch chain', () => {
  const steps = dockerInstructions(), user = steps.indexOf('USER 10001:10001')
  assert.ok(user >= 0)
  const check = steps.findIndex((step, index) => index > user && step.startsWith('RUN '))
  assert.ok(check > user, 'permission assertions must execute after switching to the runtime user')
  for (const command of ['test -x /opt', 'test -x /opt/aquaflow',
    'test -r /opt/aquaflow/entrypoint.sh', 'test -r /opt/aquaflow/app.jar',
    '/bin/sh -n /opt/aquaflow/entrypoint.sh']) {
    assert.ok(steps[check].split(/\s*&&\s*/).some(part => part.replace(/^RUN\s+/, '').trim() === command), command)
  }
  assert.ok(check > steps.findIndex(step => step.startsWith('COPY --chmod=0644 entrypoint.sh ')))
  assert.ok(check > steps.findIndex(step => step.startsWith('COPY --chmod=0644 app.jar ')))
})
test('cloud identifiers remain empty and environment activation is not authorized', () => {
  const t=JSON.parse(fs.readFileSync(path.join(dir,'transport-plan.example.json'),'utf8'))
  assert.strictEqual(t.designOnly,false); assert.strictEqual(t.runtimeImplementationApproved,true); assert.strictEqual(t.environmentActivationApproved,false)
  assert.strictEqual(t.mode,'UNSELECTED'); assert.strictEqual(t.trialHttpsBaseUrl,'')
  for(const value of Object.values(t.cloud)) assert.strictEqual(value,'')
})
let failed=0
for(const t of tests) { try { t.fn(); console.log('PASS '+t.name) } catch(e) { failed++; console.error('FAIL '+t.name,e.message) } }
if(failed) process.exitCode=1
else console.log('AQUAFLOW_SUITE_OK '+tests.length)
