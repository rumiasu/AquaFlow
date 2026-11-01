'use strict'
const assert = require('node:assert/strict')
const { spawnSync } = require('node:child_process')
const path = require('node:path')
const bash = process.platform === 'win32' ? 'D:/backend/Git/bin/bash.exe' : 'bash'
const command = 'git() { case "$SCAN_CASE" in hits) printf "src/synthetic.java:2\\n"; return 0;; clean) return 1;; error2) return 2;; error128) return 128;; missing) return 127;; empty0) return 0;; esac; }; export -f git; bash "$SCAN_SCRIPT"'
let count = 0
for (const [scenario, expected] of [['hits',1],['clean',0],['error2',2],['error128',2],['missing',2],['empty0',2]]) {
  const result = spawnSync(bash, ['-c', command], { encoding: 'utf8', env: { ...process.env,
    SCAN_CASE: scenario, SCAN_SCRIPT: path.resolve(__dirname, '../../scripts/scan-secrets.sh').replace(/\\/g,'/') } })
  if (result.error) throw result.error
  assert.equal(result.status, expected, scenario + ': ' + result.stdout + result.stderr)
  if (expected === 2) {
    assert(!result.stdout.includes('未发现硬编码密钥'), 'an incomplete scan cannot be reported as clean')
    assert(/scan-secrets/.test(result.stderr))
  }
  if (scenario === 'hits') assert(result.stdout.includes('src/synthetic.java'))
  count++
}
console.log('AQUAFLOW_SUITE_OK ' + count)
