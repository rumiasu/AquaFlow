/**
 * 三个小程序入口的关键文字可读性静态回归。
 *
 * 跑法：node tests/js/key-text-readability.test.js
 *
 * 读取实际 WXML / WXSS / token，按生效选择器和真实容器底色计算 WCAG sRGB 对比度，
 * 并在 320/390 CSS px 宽度核对 rpx 字号地板。该检查不代表原生渲染。
 */

const assert = require('assert')
const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '..', '..')
function read(rel, stack = []) {
  if (stack.includes(rel)) throw new Error('cyclic WXSS import: ' + rel)
  const source = fs.readFileSync(path.join(ROOT, rel), 'utf8')
  if (!rel.endsWith('.wxss')) return source
  return source.replace(/^\s*@import\s+['"]([^'"]+)['"]\s*;?/gm, (_, imported) =>
    read(path.posix.normalize(path.posix.join(path.posix.dirname(rel), imported)), [...stack, rel]))
}
const cleanWxml = (text) => text.replace(/<!--[\s\S]*?-->/g, '')

function parseRules(text) {
  const source = text.replace(/\/\*[\s\S]*?\*\//g, '')
  const rules = []
  const expression = /([^{}]+)\{([^{}]*)\}/g
  let match
  while ((match = expression.exec(source))) {
    const selectorText = match[1].trim()
    if (!selectorText || selectorText.startsWith('@')) continue
    const declarations = {}
    match[2].split(';').forEach((part) => {
      const colon = part.indexOf(':')
      if (colon < 0) return
      const property = part.slice(0, colon).trim()
      const value = part.slice(colon + 1).trim()
      if (property && value) declarations[property] = value
    })
    selectorText.split(',').forEach((selector) => {
      rules.push({ selector: selector.trim(), declarations })
    })
  }
  return rules
}

function readSheet(rel) {
  return { rel, rules: parseRules(read(rel)) }
}

function getProperty(sheets, selectors, property) {
  const allowed = new Set(Array.isArray(selectors) ? selectors : [selectors])
  let value = ''
  for (const sheet of sheets) {
    for (const rule of sheet.rules) {
      if (!allowed.has(rule.selector)) continue
      if (Object.prototype.hasOwnProperty.call(rule.declarations, property)) {
        value = rule.declarations[property]
      }
    }
  }
  assert.ok(value, '缺少生效声明 ' + Array.from(allowed).join(' / ') + ' 的 ' + property)
  return value
}

function pageTokens(sheet) {
  const pages = sheet.rules.filter((rule) => rule.selector === 'page')
  assert.ok(pages.length, sheet.rel + ' 缺少 page token')
  return Object.assign({}, ...pages.map(page => page.declarations))
}

function resolveColor(value, tokens) {
  let current = value.trim()
  const visited = new Set()
  while (/^var\(/.test(current)) {
    const match = /^var\((--[\w-]+)\)$/.exec(current)
    assert.ok(match, '无法解析颜色变量：' + current)
    assert.ok(!visited.has(match[1]), '颜色变量循环：' + match[1])
    visited.add(match[1])
    assert.ok(tokens[match[1]], '找不到颜色 token：' + match[1])
    current = tokens[match[1]].trim()
  }
  const hex = /^#([\da-f]{3}|[\da-f]{6})$/i.exec(current)
  if (hex) {
    const digits = hex[1].length === 3 ? hex[1].split('').map((x) => x + x).join('') : hex[1]
    return {
      rgb: [0, 2, 4].map((i) => parseInt(digits.slice(i, i + 2), 16)),
      alpha: 1
    }
  }
  const rgba = /^rgba?\(\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*(?:,\s*(\d*\.?\d+)\s*)?\)$/i.exec(current)
  assert.ok(rgba, '颜色格式未纳入静态计算：' + current)
  return {
    rgb: [Number(rgba[1]), Number(rgba[2]), Number(rgba[3])],
    alpha: rgba[4] === undefined ? 1 : Number(rgba[4])
  }
}

function composite(foreground, background) {
  return foreground.rgb.map((channel, index) =>
    channel * foreground.alpha + background[index] * (1 - foreground.alpha))
}

function luminance(rgb) {
  const linear = rgb.map((channel) => channel / 255)
    .map((channel) => channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4)
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]
}

function contrast(foreground, background) {
  const a = luminance(foreground)
  const b = luminance(background)
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05)
}

function colorPair(sheets, selectors, property, tokens, parentRgb) {
  return composite(resolveColor(getProperty(sheets, selectors, property), tokens), parentRgb)
}

function hasClass(wxml, className) {
  const classes = Array.from(cleanWxml(wxml).matchAll(/\bclass="([^"]*)"/g))
    .map((match) => match[1].replace(/\{\{[\s\S]*?\}\}/g, ''))
  return classes.some((value) => value.split(/\s+/).includes(className))
}

function assertRenderedClass(wxml, className, description) {
  assert.ok(hasClass(wxml, className), description + ' 的 WXML 没有对应 .' + className)
}

function viewClassStackAt(wxml, marker) {
  const target = wxml.indexOf(marker)
  assert.ok(target >= 0, 'WXML 缺少结构标记：' + marker)
  const expression = /<\/?view\b[^>]*>/g
  const stack = []
  let match
  while ((match = expression.exec(wxml)) && match.index < target) {
    if (match[0].startsWith('</')) {
      stack.pop()
      continue
    }
    const classes = /\bclass="([^"]*)"/.exec(match[0])
    stack.push(classes ? classes[1].split(/\s+/).filter(Boolean) : [])
    if (match[0].endsWith('/>')) stack.pop()
  }
  return stack.flat()
}

function fontRpx(sheets, selectors) {
  const value = getProperty(sheets, selectors, 'font-size')
  const match = /^(\d+(?:\.\d+)?)(rpx|px)$/.exec(value)
  assert.ok(match, '字号须是可计算的 rpx 或 CSS px：' + value)
  return { value: Number(match[1]), unit: match[2] }
}

function assertFontFloor(name, rpx, minimumPx) {
  for (const width of [320, 390]) {
    const cssPx = rpx.unit === 'px' ? rpx.value : rpx.value * width / 750
    assert.ok(cssPx >= minimumPx,
      name + ' 在 ' + width + 'px 仅 ' + cssPx.toFixed(2) + 'px，低于 ' + minimumPx + 'px')
  }
}

const userTokens = pageTokens(readSheet('miniapp-user/app.wxss'))
const deliveryTokens = pageTokens(readSheet('miniapp-delivery/app.wxss'))
const userCommon = readSheet('miniapp-user/styles/common.wxss')
const orderSheet = readSheet('miniapp-user/pages/order/create.wxss')
const userActions = readSheet('miniapp-user/styles/actions.wxss')
const assetSheet = readSheet('miniapp-user/components/AssetStationView/index.wxss')
const managerHomeSheet = readSheet('miniapp-delivery/pages/coordination/index.wxss')
const managerIndexSheet = readSheet('miniapp-delivery/pages/station-mgmt/index.wxss')
const orderWxml = cleanWxml(read('miniapp-user/pages/order/create.wxml'))
const assetWxml = cleanWxml(read('miniapp-user/components/AssetStationView/index.wxml'))
const managerHomeWxml = cleanWxml(read('miniapp-delivery/pages/coordination/index.wxml'))
const managerIndexWxml = cleanWxml(read('miniapp-delivery/pages/station-mgmt/index.wxml'))
const managerJs = read('miniapp-delivery/pages/coordination/index.js')

let passed = 0
let failed = 0
function test(name, run) {
  try {
    run()
    passed++
    console.log('  ✓ ' + name)
  } catch (error) {
    failed++
    console.log('  ✗ ' + name + '\n      ' + error.message)
  }
}

console.log('三入口关键文字：实际 WXSS/token、WXML 与 WCAG sRGB 静态回归')

test('订单站点横幅必要文字在实际暖纸底上达到 4.5:1', () => {
  assertRenderedClass(orderWxml, 'station-status-banner', '订单站点横幅')
  assertRenderedClass(orderWxml, 'banner-text', '订单站点横幅')
  const page = resolveColor(userTokens['--bg-color'], userTokens).rgb
  const background = composite(
    resolveColor(getProperty([orderSheet], '.station-status-banner', 'background'), userTokens),
    page
  )
  const foreground = colorPair(
    [orderSheet], '.station-status-banner .banner-text', 'color', userTokens, [255, 255, 255])
  const ratio = contrast(foreground, background)
  console.log('      横幅前景/合成底色：' + ratio.toFixed(4) + ':1')
  assert.ok(ratio >= 4.5, '横幅文字对实际透明底合成色仅 ' + ratio.toFixed(4) + ':1')
})

test('订单顶层错误和白卡报价错误按各自实际祖先底色核算', () => {
  assert.ok(orderWxml.includes('<view class="container"'), '订单页根容器缺失')
  assert.ok(orderWxml.includes('<view class="load-error" wx:if="{{loadError}}"'), '顶层 loadError 未渲染')
  const paymentStart = orderWxml.indexOf('<view class="payment-method-section">')
  const quoteAt = orderWxml.indexOf('<view class="load-error" wx:if="{{quoteError}}"')
  assert.ok(paymentStart >= 0 && quoteAt > paymentStart, 'quoteError 不在支付白卡内')
  const backgroundRule = getProperty([userCommon], '.load-error', 'background')
  const pageBackground = resolveColor(userTokens['--bg-color'], userTokens).rgb
  const topBackground = composite(resolveColor(backgroundRule, userTokens), pageBackground)
  const topForeground = colorPair(
    [userCommon, orderSheet], ['.load-error', '.container > .load-error'], 'color',
    userTokens, [255, 255, 255])
  const topRatio = contrast(topForeground, topBackground)
  const cardBackground = resolveColor(userTokens['--bg-card'], userTokens).rgb
  const quoteBackground = composite(resolveColor(backgroundRule, userTokens), cardBackground)
  const quoteForeground = colorPair(
    [userCommon, orderSheet], '.load-error', 'color', userTokens, [255, 255, 255])
  const quoteRatio = contrast(quoteForeground, quoteBackground)
  console.log('      顶层错误/暖纸合成底：' + topRatio.toFixed(4) + ':1；报价错误/白卡合成底：' + quoteRatio.toFixed(4) + ':1')
  assert.ok(topRatio >= 4.5, '顶层 load-error 对暖纸合成底仅 ' + topRatio.toFixed(4) + ':1')
  assert.ok(quoteRatio >= 4.5, '白卡 quoteError 对白卡合成底仅 ' + quoteRatio.toFixed(4) + ':1')
})

test('提交禁用态可读，站长未核对徽标文字在白卡上达到 4.5:1', () => {
  assertRenderedClass(orderWxml, 'checkout-submit', '下单主动作')
  assert.ok(orderWxml.includes('暂不可下单') && orderWxml.includes('提交中...'), '禁用/提交中文案状态缺失')
  const submitBackground = resolveColor(
    getProperty([userActions], '.af-action.is-disabled', 'background'), userTokens)
  const submitForeground = resolveColor(
    getProperty([userActions], '.af-action.is-disabled', 'color'), userTokens)
  const submitRatio = contrast(composite(submitForeground, [255, 255, 255]),
    composite(submitBackground, [255, 255, 255]))
  assert.ok(submitRatio >= 4.5, '提交禁用态白卡上文字仅 ' + submitRatio.toFixed(4) + ':1')

  assertRenderedClass(managerIndexWxml, 'entry-unknown', '站长待办未核对状态')
  const entryForeground = resolveColor(
    getProperty([managerIndexSheet], '.entry-unknown', 'color'), deliveryTokens)
  const entryBackground = resolveColor(
    getProperty([managerIndexSheet], '.pending-card', 'background'), deliveryTokens)
  const entryRatio = contrast(entryForeground.rgb, entryBackground.rgb)
  assertRenderedClass(managerIndexWxml, 'pending-value', 'pending count badge')
  const badgeForeground = resolveColor(
    getProperty([managerIndexSheet], '.pending-value', 'color'), deliveryTokens)
  const badgeBackground = resolveColor(
    getProperty([managerIndexSheet], '.pending-card', 'background'), deliveryTokens)
  const badgeRatio = contrast(badgeForeground.rgb, badgeBackground.rgb)
  assert.ok(badgeRatio >= 4.5, 'entry-badge contrast is ' + badgeRatio.toFixed(4) + ':1')

  assert.ok(orderWxml.includes('<view class="popup-content asset-warning">'), 'asset warning card is absent')
  const popupForeground = resolveColor(
    getProperty([orderSheet], '.asset-warning .popup-desc', 'color'), userTokens)
  const popupBackground = resolveColor(
    getProperty([orderSheet], '.popup-content', 'background-color'), userTokens)
  const popupRatio = contrast(popupForeground.rgb, popupBackground.rgb)
  assert.ok(popupRatio >= 4.5, 'asset warning popup-desc contrast is ' + popupRatio.toFixed(4) + ':1')
  console.log('      pending badge: ' + badgeRatio.toFixed(4) + ':1; asset-warning popup-desc: ' + popupRatio.toFixed(4) + ':1')
  console.log('      禁用提交文字：' + submitRatio.toFixed(4) + ':1；未核对状态/白卡：' + entryRatio.toFixed(4) + ':1')
  assert.ok(entryRatio >= 4.5, 'entry-unknown 对白卡仅 ' + entryRatio.toFixed(4) + ':1')
})

test('关键正文/金额/站点名与辅助标签/状态在 320 和 390 宽度达到字号地板', () => {
  const required = [
    [orderWxml, [userCommon, orderSheet], '.station-status-banner .banner-text', 14, '下单站点/状态文字'],
    [orderWxml, [userCommon, orderSheet], '.container > .load-error', 14, '订单顶层错误'],
    [orderWxml, [userCommon, orderSheet], '.payment-methods .load-error', 14, '报价错误'],
    [orderWxml, [orderSheet], '.address-name', 14, '收件人'],
    [orderWxml, [orderSheet], '.address-detail', 14, '配送地址'],
    [orderWxml, [orderSheet], '.address-empty', 14, '地址空/错提示'],
    [orderWxml, [orderSheet], '.product-name', 14, 'product name'],
    [orderWxml, [orderSheet], '.subtotal-value', 14, 'product subtotal'],
    [orderWxml, [orderSheet], '.fee-label', 14, 'fee label'],
    [orderWxml, [orderSheet], '.fee-value', 14, 'fee amount'],
    [orderWxml, [orderSheet], '.detail-total-label', 14, 'ticket cover text'],
    [orderWxml, [orderSheet], '.submit-unknown-text', 14, 'unknown result text'],
    [orderWxml, [orderSheet], '.detail-item-meta', 14, 'detail quantity'],
    [orderWxml, [orderSheet], '.detail-item-price', 14, 'detail amount'],
    [orderWxml, [orderSheet], '.detail-total-value', 14, 'detail total'],
    [orderWxml, [orderSheet], '.pending-order-text', 14, 'pending order message'],
    [orderWxml, [orderSheet], '.method-name', 14, '支付方式'],
    [orderWxml, [orderSheet], '.fee-warn-text', 14, '报价限制说明'],
    [orderWxml, [orderSheet], '.asset-warning .popup-desc', 14, '统一确认弹窗付款/押金说明'],
    [orderWxml, [orderSheet], '.warn-action', 14, '报价下一步动作'],
    [orderWxml, [userActions, orderSheet], '.af-action--primary', 14, '下单主动作'],
    [assetWxml, [assetSheet], '.scope-name', 14, '资产查看水站名'],
    [assetWxml, [assetSheet], '.scope-tip', 14, '资产适用限制'],
    [assetWxml, [assetSheet], '.scope-order', 14, '本次下单水站说明'],
    [assetWxml, [assetSheet], '.scope-switch', 14, '切换水站动作'],
    [assetWxml, [assetSheet], '.picker-name', 14, '选择器水站名'],
    [assetWxml, [assetSheet], '.picker-message', 14, '水站加载/错误/空态'],
    [assetWxml, [assetSheet], '.picker-retry', 14, '水站列表操作'],
    [managerHomeWxml, [managerHomeSheet], '.todo-amount', 14, '待办金额'],
    [managerIndexWxml, [managerIndexSheet], '.pending-value', 14, '待办徽标计数'],
    [managerIndexWxml, [managerIndexSheet], '.badge-error', 14, '站长徽标加载错误'],
    [managerIndexWxml, [managerIndexSheet], '.pending-entry .entry-title', 14, 'pending entry title'],
  ]
  const paymentCopy = '<text class="popup-desc">配送员送达后现金/扫码支付，确认下单后将锁定订单。</text>'
  const depositCopy = '<text class="popup-desc">您有正在配送中的水桶，本次下单将产生额外桶押金。</text>'
  for (const copy of [paymentCopy, depositCopy]) {
    assert.ok(orderWxml.includes(copy), '统一确认弹窗中的付款责任/新增押金文案缺失')
    assert.ok(viewClassStackAt(orderWxml, copy).includes('asset-warning'),
      '关键付款/押金文案必须由统一确认弹窗的 .asset-warning 正文样式覆盖')
  }
  required.forEach(([wxml, sheets, selector, floor, label]) => {
    const className = selector.slice(selector.lastIndexOf('.') + 1).replace(/[^a-zA-Z0-9_-].*$/, '')
    assertRenderedClass(wxml, className, label)
    assertFontFloor(label, fontRpx(sheets, selector), floor)
  })
  const auxiliary = [
    [orderWxml, [orderSheet], '.address-phone', '收件电话'],
    [orderWxml, [orderSheet], '.product-spec', 'product spec'],
    [orderWxml, [orderSheet], '.detail-item-meta', 'deposit quantity label'],
    [orderWxml, [orderSheet], '.popup-desc', 'confirmation dialog copy'],
    [orderWxml, [orderSheet], '.detail-item-name', 'detail label'],
    [orderWxml, [orderSheet], '.detail-total-label', 'detail total label'],
    [orderWxml, [orderSheet], '.pending-order-sub', 'pending order status'],
    [orderWxml, [orderSheet], '.method-desc', '支付方式说明'],
    [orderWxml, [orderSheet], '.total-label', '应付金额标签'],
    [assetWxml, [assetSheet], '.scope-caption', '资产范围标签'],
    [assetWxml, [assetSheet], '.scope-status', '资产站状态'],
    [assetWxml, [assetSheet], '.scope-tip', '水站筛选说明'],
    [assetWxml, [assetSheet], '.picker-status', '水站状态'],
    [assetWxml, [assetSheet], '.picker-selected', '当前查看状态'],
    [managerHomeWxml, [managerHomeSheet], '.todo-label', '待办标签'],
    [managerIndexWxml, [managerIndexSheet], '.entry-unknown', '徽标核对状态'],
    [managerIndexWxml, [managerIndexSheet], '.pending-entry .entry-desc', 'pending entry description'],
  ]
  auxiliary.forEach(([wxml, sheets, selector, label]) => {
    const className = selector.slice(selector.lastIndexOf('.') + 1)
    assertRenderedClass(wxml, className, label)
    assertFontFloor(label, fontRpx(sheets, selector), 12)
  })
})

test('提高字号后动态站名、待办标签、金额和计数仍可换行自适应', () => {
  for (const [wxml, className, label] of [
    [assetWxml, 'scope-name', '资产站名'],
    [assetWxml, 'picker-name', '选择器站名'],
    [assetWxml, 'picker-message', '水站提示'],
    [managerHomeWxml, 'todo-label', '四列待办标签'],
    [managerHomeWxml, 'todo-amount', '四列待办金额'],
    [managerIndexWxml, 'entry-unknown', '未核对状态']
  ]) assertRenderedClass(wxml, className, label)

  assert.strictEqual(getProperty([assetSheet], '.scope-name', 'overflow-wrap'), 'break-word')
  assert.strictEqual(getProperty([assetSheet], '.picker-name', 'overflow-wrap'), 'break-word')
  assert.strictEqual(getProperty([assetSheet], '.picker-message', 'white-space'), 'normal')
  assert.ok(['break-word', 'anywhere'].includes(getProperty([assetSheet], '.picker-message', 'overflow-wrap')))
  assert.strictEqual(getProperty([managerHomeSheet], '.todo-row', 'flex-wrap'), 'wrap')
  assert.strictEqual(getProperty([managerHomeSheet], '.todo-item', 'min-width'), '0')
  assert.strictEqual(getProperty([managerHomeSheet], '.todo-label', 'white-space'), 'normal')
  assert.ok(['break-word', 'anywhere'].includes(getProperty([managerHomeSheet], '.todo-label', 'overflow-wrap')))
  assert.strictEqual(getProperty([managerHomeSheet], '.todo-amount', 'white-space'), 'normal')
  assert.ok(['break-word', 'anywhere'].includes(getProperty([managerHomeSheet], '.todo-amount', 'overflow-wrap')))
  assert.strictEqual(getProperty([managerIndexSheet], '.pending-value', 'height'), 'auto')
  assert.strictEqual(getProperty([managerIndexSheet], '.entry-unknown', 'white-space'), 'normal')
  assert.ok(['break-word', 'anywhere'].includes(getProperty([managerIndexSheet], '.entry-unknown', 'overflow-wrap')))

  // 站长待办现在只把非零项塞进四列卡；别把遗留的零值灰字当可见内容重染。
  assert.ok(/if\s*\(count\s*<=\s*0\)\s*return/.test(managerJs), 'decorateTodo 必须继续过滤零计数')
  assert.ok(managerHomeWxml.includes('todo && todo.items.length'), '待办卡应继续随可见条目显隐')
})

test('报价限制说明在页面暖纸底与半透明提示底合成后达到 4.5:1', () => {
  const warning = '<view class="fee-warn" wx:if="{{checkoutWarnings.length > 0}}">'
  assert.ok(orderWxml.includes(warning), '后端下发的 checkoutWarnings 未渲染')
  assert.ok(orderWxml.includes('<text class="fee-warn-text" wx:for="{{checkoutWarnings}}" wx:key="*this">{{item}}</text>'),
    'fee-warn-text 未承载真实报价限制文案')
  assert.deepStrictEqual(viewClassStackAt(orderWxml, warning), ['container'],
    'fee-warn 的实际祖先底色应为订单页暖纸底')
  const pageBackground = resolveColor(userTokens['--bg-color'], userTokens).rgb
  const background = composite(
    resolveColor(getProperty([orderSheet], '.fee-warn', 'background'), userTokens), pageBackground)
  const foreground = colorPair([orderSheet], '.fee-warn-text', 'color', userTokens, background)
  const ratio = contrast(foreground, background)
  console.log('      fee-warn-text/暖纸合成底：' + ratio.toFixed(4) + ':1')
  assert.ok(ratio >= 4.5, 'fee-warn-text 对半透明暖纸提示底仅 ' + ratio.toFixed(4) + ':1')
})

test('站长 badgeError 是可重试错误状态，警示底上对比度达标', () => {
  const error = '<view class="badge-error" wx:if="{{badgeError}}" bindtap="loadBadgeCounts">{{badgeError}}</view>'
  assert.ok(managerIndexWxml.includes(error), 'badge-error 未显示真实错误文案并保留重试动作')
  const errorAncestors = viewClassStackAt(managerIndexWxml, error)
  assert.ok(errorAncestors.includes('container') && errorAncestors.includes('v2-management'),
    'badge-error 的实际祖先应为当前站长页容器')
  const pageBackground = resolveColor(getProperty([managerIndexSheet], '.v2-management', 'background'), deliveryTokens).rgb
  const background = composite(
    resolveColor(getProperty([managerIndexSheet], '.badge-error', 'background'), deliveryTokens),
    pageBackground)
  const foreground = colorPair(
    [managerIndexSheet], '.badge-error', 'color', deliveryTokens, background)
  const ratio = contrast(foreground, background)
  console.log('      badge-error/警示底：' + ratio.toFixed(4) + ':1')
  assert.ok(ratio >= 4.5, 'badge-error 对实际警示底仅 ' + ratio.toFixed(4) + ':1')
})

test('费用明细中的新增押金份数在浅蓝底上达到 4.5:1', () => {
  const quantity = '<text class="detail-item-meta">本次新增 {{extraDepositBuckets}} 份</text>'
  assert.ok(orderWxml.includes(quantity), '未显示本次新增份数')
  const ancestors = viewClassStackAt(orderWxml, quantity)
  assert.ok(ancestors.includes('popup-content') && ancestors.includes('detail-sheet') && ancestors.includes('detail-sheet-content'),
    '新增份数不在费用明细内容中')
  const background = resolveColor(getProperty([orderSheet], '.detail-sheet-content', 'background'), userTokens).rgb
  const foreground = colorPair([orderSheet], '.detail-item-meta', 'color', userTokens, background)
  const ratio = contrast(foreground, background)
  console.log('      新增份数/浅蓝明细卡：' + ratio.toFixed(4) + ':1')
  assert.ok(ratio >= 4.5, '新增份数对浅蓝明细卡仅 ' + ratio.toFixed(4) + ':1')
})

if (failed) {
  console.log('静态回归失败：' + failed + ' 项未通过')
  process.exitCode = 1
} else {
  console.log('静态回归通过：' + passed + ' 项')
  console.log('AQUAFLOW_SUITE_OK ' + passed)
}
