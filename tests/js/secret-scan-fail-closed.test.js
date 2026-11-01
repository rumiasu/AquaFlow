'use strict'
const assert = require('node:assert/strict')
const { spawnSync } = require('node:child_process')
const path = require('node:path')
// Follow the existing AQUAFLOW_TEST_SH override; no developer-specific installation path.
function resolveShell(env, platform) {
  return env.AQUAFLOW_TEST_SH || (platform === 'win32' ? 'bash.exe' : 'bash')
}
const command = 'git() { case "$SCAN_CASE" in hits) printf "src/synthetic.java:2\\n"; return 0;; clean) return 1;; error2) return 2;; error128) return 128;; missing) return 127;; empty0) return 0;; esac; }; source "$SCAN_SCRIPT"'
function runScan(scenario, env, platform) {
  const result = spawnSync(resolveShell(env, platform), ['-c', command], { encoding: 'utf8', env: { ...env,
    SCAN_CASE: scenario, SCAN_SCRIPT: path.resolve(__dirname, '../../scripts/scan-secrets.sh').replace(/\\/g,'/') } })
  if (result.error) throw result.error
  return result
}
let count = 0
assert.equal(resolveShell({ AQUAFLOW_TEST_SH: 'C:/custom tools/bash.exe' }, 'win32'), 'C:/custom tools/bash.exe'); count++
assert.equal(resolveShell({ AQUAFLOW_TEST_SH: '/custom/tools/bash' }, 'linux'), '/custom/tools/bash'); count++
assert.equal(resolveShell({}, 'win32'), 'bash.exe'); count++
assert.equal(resolveShell({}, 'linux'), 'bash'); count++
const missingShell = path.join(__dirname, '__no_such_shell_for_negative_control__')
assert.throws(() => runScan('clean', { ...process.env, AQUAFLOW_TEST_SH: missingShell }, process.platform), /ENOENT/); count++
for (const [scenario, expected] of [['hits',1],['clean',0],['error2',2],['error128',2],['missing',2],['empty0',2]]) {
  const result = runScan(scenario, process.env, process.platform)
  assert.equal(result.status, expected, scenario + ': ' + result.stdout + result.stderr)
  if (expected === 2) {
    assert(!result.stdout.includes('未发现硬编码密钥'), 'an incomplete scan cannot be reported as clean')
    assert(/scan-secrets/.test(result.stderr))
  }
  if (scenario === 'hits') assert(result.stdout.includes('src/synthetic.java'))
  count++
}
console.log('AQUAFLOW_SUITE_OK ' + count)
