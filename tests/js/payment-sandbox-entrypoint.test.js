const assert = require('assert'), fs = require('fs'), path = require('path'), os = require('os')
const { spawnSync } = require('child_process')
const dir = path.resolve(__dirname, '../../deployment/payment-sandbox')
const required = ['DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'JWT_SECRET', 'WX_APP_ID', 'WX_APP_SECRET', 'WX_STAFF_APP_ID', 'WX_STAFF_APP_SECRET',
  'CORS_ALLOWED_ORIGINS', 'PAYMENT_SANDBOX_ENV_ID', 'PAYMENT_SANDBOX_SERVICE_ID', 'PAYMENT_SANDBOX_BINDING_ID',
  'PAYMENT_SANDBOX_RUNTIME_ENV_ID', 'PAYMENT_SANDBOX_RUNTIME_SERVICE_ID', 'PAYMENT_SANDBOX_DB_HOST', 'PAYMENT_SANDBOX_DB_PORT', 'PAYMENT_SANDBOX_DB_NAME', 'PAYMENT_SANDBOX_DB_SERVER_UUID']
function environment() {
  const e = { PATH: process.env.PATH, SystemRoot: process.env.SystemRoot || '' }
  required.forEach(k => { e[k] = 'synthetic-binding' })
  return Object.assign(e, { JWT_SECRET: 'synthetic-only-not-live-minimum-32-characters', DB_PASSWORD: 'synthetic-secret-not-live',
    SPRING_PROFILES_ACTIVE: 'payment-sandbox', DEV_LOGIN_ENABLED: 'false', MOCK_WECHAT_PAY: 'true', MOCK_PAYMENT_ISOLATED: 'true',
    MOCK_PAYMENT_CUSTOMER_IDS: '7', SERVER_FORWARD_HEADERS_STRATEGY: 'none', SPRING_SQL_INIT_MODE: 'never' })
}
function run(env, args = ['--check-only']) {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'aquaflow-sandbox-entry-')), file = path.join(temporary, 'output'), fd = fs.openSync(file, 'w')
  let result
  try { result = spawnSync(process.env.AQUAFLOW_TEST_SH || 'sh', [path.join(dir, 'entrypoint.sh'), ...args], { env, timeout: 5000, stdio: ['ignore', fd, fd] }) }
  finally { fs.closeSync(fd) }
  const output = fs.readFileSync(file, 'utf8'); fs.unlinkSync(file); fs.rmdirSync(temporary); assert.ifError(result.error)
  assert(!output.includes(env.DB_PASSWORD)); assert(!output.includes(env.JWT_SECRET)); return { result, output }
}
let passed = 0
function check(name, fn) { fn(); passed++; console.log('PASS ' + name) }
check('offline check is explicitly not database or cloud verification', () => {
  const r = run(environment()); assert.equal(r.result.status, 0); assert(r.output.includes('AQUAFLOW_PAYMENT_SANDBOX_SHAPE_OK'))
})
for (const key of required) check('missing ' + key + ' refuses before Java', () => {
  const e = environment(); delete e[key]; assert.equal(run(e, []).result.status, 2)
})
for (const [key, value] of [['SPRING_PROFILES_ACTIVE', 'prod'], ['SPRING_PROFILES_ACTIVE', 'test,payment-sandbox'],
  ['DEV_LOGIN_ENABLED', 'true'], ['APP_DEV_LOGIN_ENABLED', 'true'], ['MOCK_PAYMENT_ISOLATED', 'false'], ['MOCK_PAYMENT_CUSTOMER_IDS', ''],
  ['PAYMENT_SANDBOX_RUNTIME_ENV_ID', 'another-environment'], ['PAYMENT_SANDBOX_RUNTIME_SERVICE_ID', 'another-service'],
  ['APP_PAYMENT_MOCK_WECHAT_PAY', 'false'], ['SPRING_SQL_INIT_MODE', 'always'], ['JAVA_TOOL_OPTIONS', '-Dsynthetic=1'],
  ['SPRING_CONFIG_LOCATION', 'synthetic.yml'], ['INDEPENDENT_BARREL_RIGHTS_ENABLED', 'false'],
  ['AQUAFLOW_BARREL_INDEPENDENT_RIGHTS_ENABLED', 'false'], ['AQUAFLOW_BARREL_INDEPENDENTRIGHTSENABLED', 'false']]) check(key + ' override refuses', () => {
  const e = environment(); e[key] = value; assert.equal(run(e).result.status, 2)
})
check('extra launch arguments cannot switch the independent image into prod', () => assert.equal(run(environment(), ['--spring.profiles.active=prod']).result.status, 2))
console.log('AQUAFLOW_SUITE_OK ' + passed)
