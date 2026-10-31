const assert = require('assert')
const fs = require('fs')
const path = require('path')
const os = require('os')
const { spawnSync } = require('child_process')
const ROOT = path.resolve(__dirname, '../..')
const python = process.env.AQUAFLOW_TEST_PYTHON || (process.platform === 'win32' ? 'python' : 'python3')
const valid = `module.exports = Behavior({
  data: {},
  methods: {
    openStationPanel() {},
    closeStationPanel: function () {}
  }
})`
let passed = 0
function check(name, source, behavior, expected) {
  const temporary = path.resolve(os.tmpdir())
  const directory = fs.mkdtempSync(path.join(temporary, 'aquaflow-behavior-audit-'))
  try {
    const page = path.join(directory, 'miniapp-delivery/pages/check/index')
    const helper = path.join(directory, 'miniapp-delivery/behaviors/stationNavbar.js')
    fs.mkdirSync(path.dirname(page), { recursive: true })
    fs.mkdirSync(path.dirname(helper), { recursive: true })
    fs.writeFileSync(page + '.js', source)
    fs.writeFileSync(page + '.wxml', '<button bindtap="openStationPanel"/><button bindtap="closeStationPanel"/>')
    fs.writeFileSync(helper, behavior)
    const result = spawnSync(python, [path.join(ROOT, 'audit_wxml_handlers.py'), '--root', directory], { encoding: 'utf8' })
    assert.ifError(result.error)
    assert.equal(result.status, expected, name + '\n' + result.stdout + result.stderr)
    passed++; console.log('PASS ' + name)
  } finally {
    assert(path.resolve(directory).startsWith(temporary + path.sep))
    assert(path.basename(directory).startsWith('aquaflow-behavior-audit-'))
    fs.rmSync(directory, { recursive: true, force: true })
  }
}
const imported = "const navigation = require('../../behaviors/stationNavbar')\n"
const registered = imported + 'Page({ data: {}, behaviors: [navigation] })'
check('real registered behavior exports both shorthand and function handlers', registered, valid, 0)
check('an unused import cannot satisfy a binding', imported + 'Page({ data: {} })', valid, 1)
check('a different registration cannot satisfy a binding', imported + 'Page({ behaviors: [other] })', valid, 1)
check('a comment does not register a behavior', imported + 'Page({ data: {} /* behaviors: [navigation] */ })', valid, 1)
check('an unrelated module path cannot borrow station handlers', registered.replace('behaviors/stationNavbar', 'utils/other'), valid, 1)
check('the helper must actually export a Behavior', registered, valid.replace('module.exports = Behavior(', 'const ignored = Behavior('), 1)
check('a missing real method remains an error', registered, valid.replace('openStationPanel()', 'missingMethod()'), 1)
check('a handler in data or outside methods is not installed', registered,
  "module.exports = Behavior({ data: { openStationPanel() {} }, methods: { closeStationPanel() {} } })\nfunction openStationPanel() {}", 1)
check('commented or quoted fake implementations remain missing', registered,
  'module.exports = Behavior({ methods: {\n    closeStationPanel() {}\n    // openStationPanel() {}\n  }, data: { text: `\n    openStationPanel() {}\n` } })', 1)
console.log('AQUAFLOW_SUITE_OK ' + passed)
