'use strict'
const assert = require('assert'), fs = require('fs'), path = require('path')
const { registeredPages } = require('../../scripts/miniapp-pages')
const { analyzeProject } = require('../../scripts/check-miniapp-requires')
const repo = path.resolve(__dirname, '../..')
function walk(dir) { return fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]) }
for (const [name, expected] of [['miniapp-user', 27], ['miniapp-delivery', 48]]) {
  const root = path.join(repo, name), cfg = require(path.join(root, 'app.json'))
  const routes = registeredPages(cfg)
  assert.equal(routes.length, expected, name + ' must retain all registered routes')
  assert.equal(new Set(routes).size, routes.length, name + ' duplicate page route')
  assert.ok(routes.includes('pages/case-handling/index'), 'new handling route must be packaged')
  if (name === 'miniapp-delivery') assert.ok(cfg.pages.includes('pages/my-problem-receipts/index'), 'personal feedback route must stay in the delivery main package')
  for (const route of routes) for (const ext of ['.js', '.json', '.wxml', '.wxss']) assert.ok(fs.existsSync(path.join(root, route + ext)), name + '/' + route + ext)
  for (const tab of cfg.tabBar.list) assert.ok(cfg.pages.includes(tab.pagePath), name + ' tab must stay in main package')
  const files = walk(root), tracked = new Set(files.map(file => path.relative(repo, file).split(path.sep).join('/')))
  const report = analyzeProject(root, repo, tracked)
  assert.deepStrictEqual(report.errors, [], name + ' require closure and package boundary')
  const ignored = require(path.join(root, 'project.config.json')).packOptions.ignore || []
  const roots = (cfg.subPackages || []).map(pkg => pkg.root + '/')
  const owner = file => roots.find(prefix => file.startsWith(prefix)) || ''
  for (const file of files.filter(file => /\.(?:wxml|wxss)$/.test(file))) {
    const src = fs.readFileSync(file, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').replace(/<!--[\s\S]*?-->/g, ''), from = path.relative(root, file).split(path.sep).join('/')
    const patterns = [/\b(?:src|href)=["']([^"']+)["']/g, /@import\s+["']([^"']+)["']/g]
    for (const pattern of patterns) for (const match of src.matchAll(pattern)) {
      const ref = match[1]
      if (ref.includes('{{') || /^(?:https?:|data:|plugin:)/.test(ref)) continue
      const resolved = path.resolve(ref.startsWith('/') ? root : path.dirname(file), ref.replace(/^\//, ''))
      const target = path.relative(root, resolved).split(path.sep).join('/')
      assert.ok(!target.startsWith('../'), from + ': reference escapes package')
      assert.ok(fs.existsSync(resolved), from + ': missing ' + target)
      assert.ok(!ignored.some(rule => rule.type === 'file' ? target === rule.value : rule.type === 'folder' && target.startsWith(rule.value + '/')), from + ': ignored resource ' + target)
      assert.ok(!owner(target) || owner(from) === owner(target), from + ': cross-subpackage reference ' + target)
    }
  }
}
const customer = fs.readFileSync(path.join(repo, 'miniapp-user/pages/login/index.wxml'), 'utf8')
const staff = fs.readFileSync(path.join(repo, 'miniapp-delivery/pages/login/index.wxml'), 'utf8')
for (const page of [customer, staff]) {
  assert.ok(page.includes('class="af-brand-name">艾泉</text>'))
  assert.ok(page.includes('class="af-brand-english">AquaFlow</text>'))
}
const customerIgnore = require(path.join(repo, 'miniapp-user/project.config.json')).packOptions.ignore
assert.ok(!customerIgnore.some(rule => rule.value === 'assets/demo'), 'legacy dynamic demo image references remain packaged')
for (const file of ['pulisi-barrel.jpeg', 'pulisi-bottle.png']) assert.ok(fs.existsSync(path.join(repo, 'miniapp-user/assets/demo', file)))
const customerMark = fs.readFileSync(path.join(repo, 'miniapp-user/assets/logo/aiquan-mark.png'))
const customerMaster = fs.readFileSync(path.join(repo, 'docs/design/brand/aiquan-customer-master.png'))
assert.equal(customerMark.readUInt32BE(16), 320); assert.equal(customerMark.readUInt32BE(20), 272)
assert.equal(customerMaster.readUInt32BE(16), 592); assert.equal(customerMaster.readUInt32BE(20), 592)
const crypto = require('crypto')
for (const [file, sha] of [['aiquan-customer-crop-20261101.png', 'ea129b8de1d135cd6144106189c3a4b58d80b523ad896fb54ea3a55a505f892f'], ['aiquan-delivery-crop-20261101.png', '4e284c77ace0ce9597db2ae5fa9468abc1ff3a22385a8527c4c05aaa96c1eeb5']]) assert.equal(crypto.createHash('sha256').update(fs.readFileSync(path.join(repo, 'docs/design/brand', file))).digest('hex'), sha)
assert.ok(customer.includes('/assets/logo/aiquan-mark.png'))
assert.ok(staff.includes('/assets/logo/aiquan-delivery-mark.png'))
for (const page of [customer, staff, fs.readFileSync(path.join(repo, 'miniapp-user/pages/mine/about.wxml'), 'utf8')]) assert.match(page, /<image[^>]+mode="aspectFit"/)
assert.ok(customerMark.length < 100000, 'the runtime A mark must fit the package budget')
const image = fs.readFileSync(path.join(repo, 'miniapp-delivery/assets/logo/aiquan-delivery-mark.png'))
assert.equal(image.readUInt32BE(16), 336); assert.equal(image.readUInt32BE(20), 228)
assert.ok(image.length < 100000, 'the runtime mark must not be the multi-image source poster')
console.log('AQUAFLOW_SUITE_OK 3')
