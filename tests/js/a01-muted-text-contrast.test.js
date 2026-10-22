/**
 * 六对象已有 A01 根因的限定文字色回归。node tests/js/a01-muted-text-contrast.test.js
 * 只读实际 token / WXSS / WXML；4.5:1 使用未舍入 WCAG sRGB 计算。
 * 静态检查不代替同版本原生像素、字级、状态覆盖或 A01 完整验收。
 */
const assert = require('assert')
const fs = require('fs')
const path = require('path')
const ROOT = process.argv[2] ? path.resolve(process.argv[2]) : path.resolve(__dirname, '..', '..')
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const PAGES = [
  {
    "path": "miniapp-user/pages/order/create.wxss",
    "selectors": [
      ".fee-hint",
      ".popup-desc",
      ".detail-subtotal-label"
    ]
  },
  {
    "path": "miniapp-user/pages/order/detail.wxss",
    "selectors": [
      ".status-sub",
      ".pay-badge.default",
      ".label",
      ".pay-hint",
      ".item-spec",
      ".item-qty"
    ]
  },
  {
    "path": "miniapp-delivery/pages/order/complete.wxss",
    "selectors": [
      ".head-subtitle",
      ".success-subtitle",
      ".cust-tag",
      ".goods-empty",
      ".money-meta-item",
      ".item-spec",
      ".reason-qty-label",
      ".reason-hint",
      ".collect-sub-text",
      ".section-tip",
      ".photo-add-text",
      ".note-count",
      ".modal-desc",
      ".floor-unit"
    ]
  },
  {
    "path": "miniapp-delivery/pages/station-mgmt/customers/index.wxss",
    "selectors": [
      ".chip",
      ".customer-phone",
      ".customer-phone.muted",
      ".stat-label",
      ".empty",
      ".loading",
      ".modal-stat",
      ".ent-entry-desc",
      ".ent-empty",
      ".ent-item-time",
      ".ent-item-line"
    ]
  },
  {
    "path": "miniapp-user/pages/barrel/index.wxss",
    "selectors": [
      ".stat-label",
      ".stat-unit",
      ".bucket-time",
      ".record-summary",
      ".record-action-secondary",
      ".info-tip",
      ".eco-desc",
      ".preview-placeholder",
      ".preview-lots-title",
      ".preview-storage text",
      ".water-type-qty"
    ]
  },
  {
    "path": "miniapp-delivery/pages/station-mgmt/barrel-return/index.wxss",
    "selectors": [
      ".eyebrow",
      ".page-caption",
      ".record-number",
      ".status.neutral",
      ".muted",
      ".card-foot",
      ".read-state",
      ".alert-tip",
      ".empty",
      ".notice",
      ".timeline-copy",
      ".time-label",
      ".step-hint"
    ]
  },
  {
    "path": "miniapp-delivery/pages/role-select/index.wxss",
    "selectors": [
      ".role-desc",
      ".footer-tip"
    ]
  }
]

function rules(raw) {
  const source = raw.replace(/\/\*[\s\S]*?\*\//g, '')
  const result = []
  for (const match of source.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const declarations = {}
    match[2].split(';').forEach(part => {
      const colon = part.indexOf(':')
      if (colon >= 0) declarations[part.slice(0, colon).trim()] = part.slice(colon + 1).trim()
    })
    match[1].trim().split(',').forEach(selector => result.push({ selector: selector.trim(), declarations }))
  }
  return result
}
function property(sheet, selector, name) {
  let value
  for (const rule of sheet) if (rule.selector === selector && name in rule.declarations) value = rule.declarations[name]
  assert(value, selector + ' missing ' + name)
  return value
}
function tokens(rel) { return rules(read(rel)).find(rule => rule.selector === 'page').declarations }
function color(value, palette) {
  const seen = new Set()
  while (value.startsWith('var(')) {
    const match = /^var\((--[\w-]+)\)$/.exec(value)
    assert(match && !seen.has(match[1]), 'unsupported/cyclic color ' + value)
    seen.add(match[1]); value = palette[match[1]]
    assert(value, 'missing token ' + match[1])
  }
  const hex = /^#([\da-f]{3}|[\da-f]{6})$/i.exec(value)
  assert(hex, 'unsupported opaque color ' + value)
  const digits = hex[1].length === 3 ? hex[1].split('').map(x => x + x).join('') : hex[1]
  return [0, 2, 4].map(i => parseInt(digits.slice(i, i + 2), 16))
}
function luminance(rgb) {
  const v = rgb.map(x => x / 255).map(x => x <= 0.04045 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4)
  return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]
}
function contrast(a, b) {
  const x = luminance(a), y = luminance(b)
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05)
}
const palettes = {
  'miniapp-user': tokens('miniapp-user/styles/variable.wxss'),
  'miniapp-delivery': tokens('miniapp-delivery/app.wxss')
}
let passed = 0, failed = 0
function test(name, action) {
  try { action(); passed++; console.log('  PASS ' + name) }
  catch (error) { failed++; console.log('  FAIL ' + name + ': ' + error.message) }
}

// These are the actual opaque light surfaces in the bounded pages; active/tinted
// warning or primary controls retain their more-specific foreground declarations.
for (const page of PAGES) {
  const palette = palettes[page.path.split('/')[0]]
  const sheet = rules(read(page.path))
  const wxml = read(page.path.replace('.wxss', '.wxml')).replace(/<!--[\s\S]*?-->/g, '')
  const classValues = Array.from(wxml.matchAll(/\bclass="([^"]*)"/g)).map(m => m[1])
  for (const selector of page.selectors) test(page.path + ' ' + selector, () => {
    const firstClass = /\.([\w-]+)/.exec(selector)[1]
    assert(classValues.some(value => value.split(/\s+/).includes(firstClass)), 'WXML lacks target ' + firstClass)
    const foreground = color(property(sheet, selector, 'color'), palette)
    for (const token of ['--bg-card', '--bg-color', '--bg-gray', '--warning-bg']) {
      const background = color(palette[token], palette)
      const ratio = contrast(foreground, background)
      assert(ratio >= 4.5, token + ' contrast ' + ratio.toFixed(9) + ':1 < 4.5:1')
    }
  })
}

const knownFacts = [
  ['customer-order', 'miniapp-user/pages/order/detail.wxml', '订单编号', 'miniapp-user/pages/order/detail.wxss', '.label'],
  ['manager-customers', 'miniapp-delivery/pages/station-mgmt/customers/index.wxml', '未填写电话', 'miniapp-delivery/pages/station-mgmt/customers/index.wxss', '.customer-phone.muted'],
  ['order-delivery same-order customer readback', 'miniapp-user/pages/order/detail.wxml', '订单编号', 'miniapp-user/pages/order/detail.wxss', '.label'],
  ['return-refund manager amount', 'miniapp-delivery/pages/station-mgmt/barrel-return/index.wxml', '应退押金', 'miniapp-delivery/pages/station-mgmt/barrel-return/index.wxss', '.muted'],
  ['barrel-rights', 'miniapp-user/pages/barrel/index.wxml', '用桶权益', 'miniapp-user/pages/barrel/index.wxss', '.stat-label'],
  ['roles-stations', 'miniapp-delivery/pages/role-select/index.wxml', '选择身份只是登记意向', 'miniapp-delivery/pages/role-select/index.wxss', '.footer-tip']
]
for (const [object, wxml, fact, wxss, selector] of knownFacts) test('known A01 binding ' + object, () => {
  assert(read(wxml).replace(/<!--[\s\S]*?-->/g, '').includes(fact), 'necessary fact absent')
  assert.strictEqual(property(rules(read(wxss)), selector, 'color'), 'var(--text-regular)')
})

test('active customer chips and retry surfaces retain readable white text', () => {
  const sheet = rules(read('miniapp-delivery/pages/station-mgmt/customers/index.wxss'))
  const palette = palettes['miniapp-delivery']
  for (const selector of ['.chip.active', '.ent-empty.ent-state-action']) {
    const ratio = contrast(color(property(sheet, selector, 'color'), palette), color(property(sheet, selector, 'background'), palette))
    assert(ratio >= 4.5)
  }
})

test('approved shared palette preserves disabled and decorative semantic roles', () => {
  for (const palette of Object.values(palettes)) {
    assert.strictEqual(palette['--text-secondary'], '#637083')
    assert.strictEqual(palette['--text-placeholder'], '#9CA7B5')
    assert.strictEqual(palette['--text-regular'], '#4B596A')
  }
  for (const [rel, selector, name, expected] of [
    ['miniapp-delivery/pages/station-mgmt/customers/index.wxss', '.badge.act-lost', 'background', 'var(--text-secondary)'],
    ['miniapp-user/pages/order/create.wxss', '.submit-btn.disabled', 'background', 'var(--text-placeholder)'],
    ['miniapp-delivery/pages/order/complete.wxss', '.btn-confirm-busy', 'background', 'var(--text-secondary)'],
    ['miniapp-user/pages/barrel/index.wxss', '.modal-btn.confirm.disabled', 'background', 'var(--bg-gray)'],
    ['miniapp-delivery/pages/station-mgmt/barrel-return/index.wxss', '.primary-button[disabled]', 'color', 'var(--text-secondary)'],
    ['miniapp-delivery/pages/role-select/index.wxss', '.role-arrow', 'color', 'var(--text-placeholder)'],
    ['miniapp-user/pages/order/detail.wxss', '.status-card.status-5', 'border-left', '8rpx solid var(--text-secondary)']
  ]) assert.strictEqual(property(rules(read(rel)), selector, name), expected)
  const sheet = rules(read('miniapp-user/pages/barrel/index.wxss')), palette = palettes['miniapp-user']
  const selector = '.modal-btn.confirm.disabled'
  assert(contrast(color(property(sheet, selector, 'color'), palette), color(property(sheet, selector, 'background'), palette)) >= 4.5)
  assert.strictEqual(property(sheet, selector + ' text', 'color'), 'var(--text-regular)')
})

if (failed) { console.log('A01_MUTED_TEXT_FAILED ' + failed); process.exitCode = 1 }
else { console.log('AQUAFLOW_SUITE_OK ' + passed) }
