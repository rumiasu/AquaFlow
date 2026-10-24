'use strict'
// Static Chromium projection of actual WXML/WXSS and real layout handlers. No native/online proof.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict')
const { chromium } = require('playwright')
const { parseWxml, validateConditionalSiblings } = require('../js/wxml-tree')
const ROOT = path.resolve(__dirname, '../..'), OUT = process.argv[2] || '/tmp/af-product-unified-layout'
const BASELINE = process.argv[3] || '/tmp/af-ui-phone-20261024/baseline', base = 'miniapp-delivery/pages/station-mgmt/products/index'
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
const evaluate = (text, data) => vm.runInNewContext(text.slice(2, -2), { ...data }, { timeout: 100 })
const value = (text, data) => text.replace(/{{([\s\S]*?)}}/g, (_, e) => vm.runInNewContext(e, { ...data }, { timeout: 100 }) ?? '')
function render(children, data) {
  let html = '', taken = false
  for (const node of children) {
    if (node.tag === '#text') { html += esc(value(node.value, data)); continue }
    const a = node.attrs
    if ('wx:for' in a) { const rows = evaluate(a['wx:for'], data) || [], attrs = { ...a }; delete attrs['wx:for']; rows.forEach((row, index) => { html += render([{ ...node, attrs }], { ...data, item: row, index }) }); taken = false; continue }
    let visible = true
    if ('wx:if' in a) { visible = !!evaluate(a['wx:if'], data); taken = visible }
    else if ('wx:elif' in a) { visible = !taken && !!evaluate(a['wx:elif'], data); taken ||= visible }
    else if ('wx:else' in a) { visible = !taken; taken = true }
    else taken = false
    if (!visible || node.tag === 'page-meta') continue
    if (node.tag === 'page-container' && !evaluate(a.show, data)) continue
    if (node.tag === 'block') { html += render(node.children, data); continue }
    if (node.tag === 'switch') { html += '<div class="projection-switch ' + (evaluate(a.checked, data) ? 'checked' : '') + '"></div>'; continue }
    const tag = node.tag === 'input' ? 'input' : node.tag === 'button' ? 'button' : node.tag === 'text' ? 'span' : 'div'
    let attrs = ''
    for (const key of ['class', 'style', 'placeholder', 'value']) if (key in a) attrs += ' ' + key + '="' + esc(value(a[key], data)) + '"'
    if (node.tag === 'scroll-view') attrs += ' data-scroll="' + ('scroll-x' in a ? 'x' : 'y') + '"'
    if (a.bindtap || a.catchtap) attrs += ' data-handler="' + (a.bindtap || a.catchtap) + '"'
    html += '<' + tag + attrs + '>' + (tag === 'input' ? '' : render(node.children, data) + '</' + tag + '>')
  }
  return html
}
function css(raw, width, scale) {
  return raw.replace(/(?<![.\w-])page\s*\{/g, 'body {').replace(/([\d.]+)rpx/g, (_, n) => Number(n) * width / 750 + 'px')
    .replace(/font-size:\s*([\d.]+)px/g, (_, n) => 'font-size:' + Number(n) * scale + 'px').replace(/env\(safe-area-inset-bottom\)/g, '16px')
}
function fixtureAt(root) {
  const target = require.resolve('../js/extreme-product-save.test')
  delete require.cache[target]; process.env.AQUAFLOW_REPO = root
  return require(target).fixture()
}
const sample = (extra = {}) => ({ id: 17, selected: true, ownerStationId: 11, name: '合成长商品名称与编号ABC12345678901234567890', displayName: '合成长商品名称与编号ABC12345678901234567890',
  category: 1, categoryLabel: '桶装水', brand: '合成品牌', spec: '18.9L', price: 12, salePrice: 15, deposit: 40, depositPrice: 50,
  quantity: 10, stockText: '10', enabled: 1, priorityDisplay: 1, ticketEnabled: 0, effectivePriceText: '15.00', refPriceText: '12.00', refDepositText: '40.00', ...extra })
;(async () => {
  fs.mkdirSync(OUT, { recursive: true })
  const browser = await chromium.launch({ executablePath: '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] }), results = []
  try {
    for (const scene of [
      { name: 'catalog-phone', kind: 'catalog', width: 460, height: 1024, scale: 1, dpr: 2 },
      { name: 'catalog-small-font', kind: 'catalog', width: 320, height: 568, scale: 1.3 },
      { name: 'platform-reopen', kind: 'platform', width: 460, height: 1024, scale: 1, dpr: 2, stale: true },
      { name: 'platform-small-font', kind: 'platform', width: 320, height: 568, scale: 1.3 },
      { name: 'custom-keyboard-font', kind: 'owned', width: 320, height: 308, scale: 1.3 }
    ]) for (const before of [true, false]) {
      const root = before ? BASELINE : ROOT, t = fixtureAt(root), own = sample(), platform = sample({ id: 18, ownerStationId: null, displayName: '合成平台商品', name: '合成平台商品' })
      let windowHeight = scene.height; t.wx.getWindowInfo = () => ({ windowHeight })
      t.page.setData({ loading: false, loadError: '', list: [own, platform], viewList: [own, platform], templateImage: '/synthetic.png' })
      if (scene.stale) { t.page.openAdd(); t.page.onProductKeyboardHeightChange({ detail: { height: 300 } }); t.page.closeEdit() }
      if (scene.kind !== 'catalog') {
        t.page.onOpenSetting({ currentTarget: { dataset: { item: scene.kind === 'owned' ? own : platform } } })
        t.page.setData({ pkgReady: true })
        if (scene.kind === 'owned') { t.page.setData({ stockMode: 'loss', stockInput: '8' }); t.page.refreshStockPreview() }
      }
      const tree = parseWxml(fs.readFileSync(path.join(root, base + '.wxml'), 'utf8')); validateConditionalSiblings(tree)
      const page = await browser.newPage({ viewport: { width: scene.width, height: scene.height }, deviceScaleFactor: scene.dpr || 1 })
      await page.route('**/*', route => route.abort())
      const raw = fs.readFileSync(path.join(root, 'miniapp-delivery/app.wxss'), 'utf8') + fs.readFileSync(path.join(root, base + '.wxss'), 'utf8')
      const reset = 'html,body{margin:0;width:100%;min-height:100%;font-family:"Noto Sans CJK SC",sans-serif}div,span,button,input{box-sizing:border-box}button,input{font:inherit}button{border:0}input{display:block;border:0;outline:0}[data-scroll="x"]{overflow-x:auto;overflow-y:hidden}[data-scroll="y"]{overflow-y:auto}.projection-switch{flex:none;width:40px;height:24px;border-radius:16px;background:#b4bbc6}.projection-switch.checked{background:#1264d6}.projection-switch::before{content:"";display:block;width:20px;height:20px;margin:2px;border-radius:50%;background:white}.projection-switch.checked::before{margin-left:18px}'
      await page.setContent('<meta charset="utf-8"><style>' + reset + css(raw, scene.width, scene.scale) + '</style>' + render(tree.children, t.page.data))
      const geometry = await page.evaluate(() => {
        const rect = el => { if (!el) return null; const r = el.getBoundingClientRect(); return { top: r.top, bottom: r.bottom, left: r.left, right: r.right, height: r.height, width: r.width } }
        const sheet = document.querySelector('.product-edit-modal'), body = document.querySelector('.product-edit-body'), footer = document.querySelector('.product-edit-footer')
        const before = body ? body.scrollTop : null; if (body) body.scrollTop = body.scrollHeight
        return { scrollWidth: document.documentElement.scrollWidth, sheet: rect(sheet), footer: rect(footer), body: rect(body),
          scroller: body ? { before, after: body.scrollTop, scrollHeight: body.scrollHeight, clientHeight: body.clientHeight } : null,
          actions: [...document.querySelectorAll('.card .action')].map(el => ({ rect: rect(el), buttons: [...el.querySelectorAll('button')].map(b => ({ text: b.innerText, rect: rect(b) })) })),
          overflowing: [...document.querySelectorAll('body *')].filter(e => e.getBoundingClientRect().right > innerWidth + 1 && !e.closest('[data-scroll="x"]')).slice(0, 8).map(e => ({ cls: e.className, rect: rect(e) })) }
      })
      const name = (before ? 'before-' : 'after-') + scene.name
      if (!before) {
        assert(geometry.scrollWidth <= scene.width + 1, name + ' horizontal overflow')
        if (geometry.footer) { assert(Math.abs(geometry.footer.bottom - scene.height) < 1, name + ' footer floats'); assert(geometry.body.height > 0); if (geometry.scroller.scrollHeight > geometry.scroller.clientHeight) assert(geometry.scroller.after > 0) }
        for (const action of geometry.actions) for (const button of action.buttons) { assert(button.rect.height >= 44); assert(button.rect.left >= action.rect.left - 1 && button.rect.right <= action.rect.right + 1) }
      }
      if (scene.kind !== 'catalog') { const body = page.locator('.product-edit-body'); await body.evaluate(el => { el.scrollTop = 0 }) }
      await page.screenshot({ path: path.join(OUT, name + '.png') })
      if (!before && scene.kind === 'catalog') {
        const clearance = await page.evaluate(() => { window.scrollTo(0, document.documentElement.scrollHeight); return {
          lastActionBottom: [...document.querySelectorAll('.card .action')].at(-1).getBoundingClientRect().bottom,
          footerTop: document.querySelector('.footer').getBoundingClientRect().top } })
        assert(clearance.lastActionBottom <= clearance.footerTop, name + ' last action hidden under footer')
        geometry.catalogBottomClearance = clearance
        if (scene.width === 320) { await page.locator('.card .action').first().evaluate(el => el.scrollIntoView({ block: 'center' })); await page.screenshot({ path: path.join(OUT, name + '-actions.png') }) }
      }
      if (!before && scene.kind === 'owned') { await page.locator('.product-edit-body').evaluate(el => { el.scrollTop = el.scrollHeight }); await page.screenshot({ path: path.join(OUT, name + '-stock.png') }) }
      results.push({ ...scene, before, ...geometry }); await page.close()
    }
    fs.writeFileSync(path.join(OUT, 'layout.json'), JSON.stringify({ baseline: 'bc3dedd', scope: 'Static HTML projection of real source; synthetic product data; native switches substituted; no real keyboard/back/WeChat/COS proof', results }, null, 2))
    console.log('AQUAFLOW_LAYOUT_OK ' + results.length)
  } finally { await browser.close() }
})().catch(e => { console.error(e.stack); process.exitCode = 1 })
