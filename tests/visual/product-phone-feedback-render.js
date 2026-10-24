'use strict'
// WXML/WXSS static browser projection only. Native page-container, keyboard and Back are not emulated.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict')
const { chromium } = require('playwright')
const { pageFixture } = require('../js/page-recovery.test')
const { parseWxml } = require('../js/wxml-tree')
const ROOT = path.resolve(__dirname, '../..'), OUT = process.argv[2] || '/tmp/af-product-phone-layout'
const read = file => fs.readFileSync(path.join(ROOT, file), 'utf8')
const base = 'miniapp-delivery/pages/station-mgmt/products/index'
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
const value = (text, data) => text.replace(/{{([\s\S]*?)}}/g, (_, expression) => vm.runInNewContext(expression, { ...data }, { timeout: 100 }) ?? '')
const evaluate = (text, data) => vm.runInNewContext(text.slice(2, -2), { ...data }, { timeout: 100 })
function render(children, data) {
  let html = '', taken = false
  for (const node of children) {
    if (node.tag === '#text') { html += esc(value(node.value, data)); continue }
    const attrs = node.attrs
    if ('wx:for' in attrs) {
      const rows = evaluate(attrs['wx:for'], data), copy = { ...attrs }; delete copy['wx:for']
      for (let index = 0; index < rows.length; index++) html += render([{ ...node, attrs: copy }], { ...data, item: rows[index], index })
      taken = false; continue
    }
    let visible = true
    if ('wx:if' in attrs) { visible = !!evaluate(attrs['wx:if'], data); taken = visible }
    else if ('wx:elif' in attrs) { visible = !taken && !!evaluate(attrs['wx:elif'], data); taken ||= visible }
    else if ('wx:else' in attrs) { visible = !taken; taken = true }
    else taken = false
    if (!visible) continue
    if (node.tag === 'block') { html += render(node.children, data); continue }
    const tag = node.tag === 'input' ? 'input' : node.tag === 'button' ? 'button' : node.tag === 'text' ? 'span' : 'div'
    let a = ''
    for (const key of ['class', 'style', 'placeholder', 'value']) if (key in attrs) a += ' ' + key + '="' + esc(value(attrs[key], data)) + '"'
    if (node.tag === 'scroll-view') a += ' data-scroll="true"'
    if (attrs.bindtap) a += ' data-handler="' + esc(attrs.bindtap) + '"'
    html += '<' + tag + a + '>' + render(node.children, data) + (tag === 'input' ? '' : '</' + tag + '>')
  }
  return html
}
function locate(node, predicate) {
  if (predicate(node)) return node
  for (const child of node.children || []) { const found = locate(child, predicate); if (found) return found }
}
function css(raw, width, scale) {
  return raw.replace(/\bpage\s*\{/g, 'body {').replace(/([\d.]+)rpx/g, (_, n) => (Number(n) * width / 750) + 'px')
    .replace(/font-size:([\d.]+)px/g, (_, n) => 'font-size:' + Number(n) * scale + 'px').replace(/env\(safe-area-inset-bottom\)/g, '16px')
}
;(async () => {
  fs.mkdirSync(OUT, { recursive: true })
  const browser = await chromium.launch({ executablePath: '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] })
  const page = await browser.newPage()
  await page.route('**/*', route => route.abort())
  const root = parseWxml(read(base + '.wxml')), container = locate(root, n => n.tag === 'page-container'), results = []
  try {
    for (const item of [{ name: 'small', width: 320, height: 568, scale: 1 },
      { name: 'keyboard-large-font', width: 320, height: 308, scale: 1.3 },
      { name: 'phone', width: 375, height: 720, scale: 1 }]) {
      const f = pageFixture('product'); f.wx.getWindowInfo = () => ({ windowHeight: item.height }); f.p.openAdd()
      f.p.setData({ templateImage: '/assets/synthetic.png', 'editForm.name': '合成长中文商品名称与无空格编号ABC12345678901234567890', 'editForm.description': '合成长说明'.repeat(10),
        'editForm.ticketEnabled': true, pkgRows: Array.from({ length: 6 }, () => ({ qty: '10', price: '100' })) })
      await page.setViewportSize({ width: item.width, height: item.height })
      const styles = css(read('miniapp-delivery/app.wxss') + read(base + '.wxss'), item.width, item.scale)
      const reset = 'html,body{margin:0;width:100%;height:100%;font-family:"Noto Sans CJK SC",sans-serif}div{box-sizing:border-box}button,input{font:inherit;box-sizing:border-box}button{border:0}input{display:block}span{box-sizing:border-box}[data-scroll]{overflow-y:auto}body{background:#87909B}'
      await page.setContent('<meta charset="utf-8"><style>' + reset + styles + '</style>' + render(container.children, f.p.data))
      const geometry = await page.evaluate(() => {
        const rect = s => { const r = document.querySelector(s).getBoundingClientRect(); return { top: r.top, bottom: r.bottom, left: r.left, right: r.right, height: r.height } }
        const body = document.querySelector('.product-edit-body'), before = body.scrollTop
        const dismissHit = document.elementFromPoint(innerWidth / 2, 28)
        const insideHit = document.elementFromPoint(innerWidth / 2, rect('.product-edit-header').bottom + 10)
        body.scrollTop = body.scrollHeight
        return { sheet: rect('.product-edit-modal'), close: rect('.product-edit-close'), footer: rect('.product-edit-footer'),
          body: rect('.product-edit-body'), before, after: body.scrollTop, overflow: body.scrollHeight > body.clientHeight,
          closeStripHit: dismissHit && dismissHit.getAttribute('data-handler'),
          insideCloses: !!(insideHit && insideHit.closest('[data-handler="closeEdit"]')) }
      })
      fs.writeFileSync(path.join(OUT, 'current-geometry.json'), JSON.stringify({ ...item, ...geometry }, null, 2))
      assert(geometry.sheet.top >= 56); assert(geometry.close.height >= 44)
      assert(geometry.footer.bottom <= item.height); assert(geometry.body.height > 0)
      assert(geometry.overflow && geometry.after > geometry.before)
      assert.equal(geometry.closeStripHit, 'closeEdit'); assert.equal(geometry.insideCloses, false)
      await page.screenshot({ path: path.join(OUT, item.name + '.png') })
      results.push({ ...item, ...geometry })
    }
    fs.writeFileSync(path.join(OUT, 'layout.json'), JSON.stringify({ scope: 'Static Chromium mapping of real WXML/WXSS; synthetic form; no native WeChat/keyboard/Back proof', results }, null, 2))
    console.log('AQUAFLOW_LAYOUT_OK ' + results.length)
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
