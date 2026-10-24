'use strict'
// Static projection of actual WXML/WXSS with synthetic data. No native WeChat, login or upload runs.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict')
const { execFileSync } = require('node:child_process'), { chromium } = require('playwright')
const { parseWxml, validateConditionalSiblings } = require('../js/wxml-tree')
const { pageFixture } = require('../js/page-recovery.test')
const { fixture: homeFixture } = require('../js/customer-home-recovery-view.test')
const { WATER_PRODUCT_TEMPLATES, TEMPLATE_IMAGE } = require('../../miniapp-delivery/utils/water-product-templates')
const { stationName } = require('../../miniapp-user/utils/station-view')
const ROOT = path.resolve(__dirname, '../..'), OUT = process.argv[2] || '/tmp/af-phone-round3-layout'
const baseline = process.argv[3] || '88aedb0'
const read = (file, before) => before ? execFileSync('git', ['show', baseline + ':' + file], { cwd: ROOT, encoding: 'utf8' }) : fs.readFileSync(path.join(ROOT, file), 'utf8')
const readCss = (file, before) => read(file, before).replace(/@import\s+["']([^"']+)["'];/g, (_, imported) => readCss(path.posix.join(path.posix.dirname(file), imported), before))
const esc = v => String(v == null ? '' : v).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
function evaluate(expr, data) {
  return vm.runInNewContext(expr, { ...data }, { timeout: 100 })
}
const value = (text, data) => text.replace(/{{([\s\S]*?)}}/g, (_, expr) => evaluate(expr, data) ?? '')
function image(src, side) {
  if (src.startsWith('data:')) return src
  const file = path.join(ROOT, side, src)
  if (!src.startsWith('/') || !fs.existsSync(file)) return ''
  const ext = path.extname(file).slice(1)
  return 'data:image/' + (ext === 'jpg' ? 'jpeg' : ext) + ';base64,' + fs.readFileSync(file).toString('base64')
}
function render(children, data, side) {
  let html = '', taken = false
  for (const node of children) {
    if (node.tag === '#text') { html += esc(value(node.value, data)); continue }
    if (['template', 'page-meta', 'privacy-popup'].includes(node.tag)) continue
    const attrs = node.attrs
    if ('wx:for' in attrs) {
      const rows = evaluate(attrs['wx:for'].slice(2, -2), data), copy = { ...attrs }; delete copy['wx:for']
      for (let index = 0; index < rows.length; index++) html += render([{ ...node, attrs: copy }], { ...data, item: rows[index], index }, side)
      taken = false; continue
    }
    let visible = true
    if ('wx:if' in attrs) { visible = !!evaluate(attrs['wx:if'].slice(2, -2), data); taken = visible }
    else if ('wx:elif' in attrs) { visible = !taken && !!evaluate(attrs['wx:elif'].slice(2, -2), data); taken ||= visible }
    else if ('wx:else' in attrs) { visible = !taken; taken = true }
    else taken = false
    if (!visible) continue
    if (node.tag === 'block') { html += render(node.children, data, side); continue }
    const tag = ({ text: 'span', image: 'img', input: 'input', button: 'button' })[node.tag] || 'div'
    let a = node.tag === 'text' ? ' data-wx-text="true"' : ''
    for (const key of ['class', 'style', 'placeholder', 'value']) if (key in attrs && !(node.tag === 'image' && key === 'style')) a += ' ' + key + '="' + esc(value(attrs[key], data)) + '"'
    if (node.tag === 'image') a += ' src="' + image(value(attrs.src, data), side) + '" style="object-fit:' + (attrs.mode === 'aspectFill' ? 'cover' : 'contain') + ';' + esc(attrs.style ? value(attrs.style, data) : '') + '"'
    if (node.tag === 'scroll-view') a += ' data-scroll="' + ('scroll-x' in attrs ? 'x' : 'y') + '"'
    if (attrs.bindtap || attrs.catchtap) a += ' data-handler="' + (attrs.bindtap || attrs.catchtap) + '"'
    html += '<' + tag + a + '>' + (['img', 'input'].includes(tag) ? '' : render(node.children, data, side) + '</' + tag + '>')
  }
  return html
}
function css(raw, width, scale) {
  return raw.replace(/(?<![.\w-])page\s*\{/g, 'body {').replace(/(?<![.\w-])text(?=\s*\{)/g, '[data-wx-text]').replace(/([\d.]+)rpx/g, (_, n) => Number(n) * width / 750 + 'px')
    .replace(/font-size:\s*([\d.]+)px/g, (_, n) => 'font-size:' + Number(n) * scale + 'px').replace(/env\(safe-area-inset-bottom\)/g, '16px')
}
function catalogData() {
  const f = pageFixture('product'), rows = [
    { id: 1, displayName: '合成自定义商品', ownerStationId: 1, brand: '合成资料', spec: '测试18.9L', imageUrl: null, selected: true, stockText: '0', enabled: 0, effectivePriceText: '10', deposit: 40, refDepositText: '40' },
    { id: 2, displayName: '普利思饮用纯净水', imageUrl: '/assets/product/pulisi-mineral.webp', selected: false },
    { id: 3, displayName: '合成长中文商品名称无空格编号ABC12345678901234567890', imageUrl: '/assets/product/pulisi-spring.webp', selected: false }
  ].map(r => ({ brand: '合成资料', spec: '测试规格', category: 1, categoryLabel: '桶装水', refPriceText: '10', ...r }))
  return { ...f.p.data, loading: false, list: rows, viewList: rows, tab: 'all', showWaterTemplates: false, waterTemplates: WATER_PRODUCT_TEMPLATES, templateImage: TEMPLATE_IMAGE }
}
;(async () => {
  fs.mkdirSync(OUT, { recursive: true })
  const browser = await chromium.launch({ executablePath: '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] })
  const page = await browser.newPage(), results = []
  await page.route('**/*', route => route.abort())
  try {
    for (const item of [{ name: 'phone', width: 375, height: 720, scale: 1 }, { name: 'small-large-font', width: 320, height: 568, scale: 1.3 }]) {
      for (const scenario of ['catalog', 'templates', 'home', 'home-selected', 'station-error']) for (const before of [true, false]) {
        const side = ['catalog', 'templates'].includes(scenario) ? 'miniapp-delivery' : 'miniapp-user'
        const base = side + (side === 'miniapp-user' ? '/pages/home/index' : '/pages/station-mgmt/products/index')
        let data
        if (side === 'miniapp-delivery') { data = catalogData(); data.showWaterTemplates = scenario === 'templates' }
        else {
          const f = homeFixture(); if (scenario === 'home-selected') { const station = { name: '合成长水站名称与编号ABC12345678901234567890' }; f.page.setData({ state: 'ready', catalogState: 'empty', currentStation: station, currentStationName: stationName(station) }) } else f.page.showNoStation()
          data = { ...f.page.data, homeWaterBackdropFailed: true,
            statusBarHeight: 24, navBarHeight: 44, navContentWidth: item.width - 140, showStationList: scenario === 'station-error', stationListError: scenario === 'station-error' ? '水站列表暂时无法加载，请稍后重试' : '' }
        }
        const tree = parseWxml(read(base + '.wxml', before)); validateConditionalSiblings(tree)
        await page.setViewportSize({ width: item.width, height: item.height })
        const styles = css(readCss(side + '/app.wxss', before) + readCss(base + '.wxss', before), item.width, item.scale)
        const reset = 'html,body{margin:0;width:100%;min-height:100%;font-family:"Noto Sans CJK SC",sans-serif}div,span,button,input{box-sizing:border-box}button,input{font:inherit}button{border:0}input{display:block;border:0;outline:0}img{display:block} [data-scroll="x"]{overflow-x:auto;overflow-y:hidden} [data-scroll="y"]{overflow-y:auto}'
        await page.setContent('<meta charset="utf-8"><style>' + reset + styles + '</style>' + render(tree.children, data, side))
        if (scenario === 'templates') await page.locator('.water-templates').scrollIntoViewIfNeeded()
        const geometry = await page.evaluate(() => {
          const rect = s => { const el = document.querySelector(s); if (!el) return null; const r = el.getBoundingClientRect(); return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, height: r.height, width: r.width } }
          return { viewport: innerWidth, scrollWidth: document.documentElement.scrollWidth, top: rect('.top-bar'), card: rect('.card'), info: rect('.info'), action: rect('.action'),
            footer: rect('.footer'), create: rect('.btn-define'), templateEntry: rect('.btn-template'), templates: document.querySelectorAll('.water-templates').length,
            placeholderFont: document.querySelector('.thumb.placeholder') ? parseFloat(getComputedStyle(document.querySelector('.thumb.placeholder')).fontSize) : null,
            nav: rect('.home-nav-row'), heading: rect('.hero'), stationPicker: rect('.station-picker'), text: document.body.innerText,
            overflowing: [...document.querySelectorAll('body *')].filter(e => e.getBoundingClientRect().right > innerWidth + 1 && !e.closest('[data-scroll="x"]')).slice(0, 8).map(e => ({ tag: e.tagName, cls: e.className, right: e.getBoundingClientRect().right })) }
        })
        const name = (before ? 'before-' : 'after-') + scenario + '-' + item.name
        fs.writeFileSync(path.join(OUT, 'current-geometry.json'), JSON.stringify({ name, ...geometry }, null, 2))
        await page.screenshot({ path: path.join(OUT, name + '.png') })
        if (!before) {
          assert(geometry.scrollWidth <= item.width + 1, scenario + ' horizontal overflow')
          if (scenario === 'catalog') {
            assert.equal(geometry.templates, 0); assert(geometry.action.left >= geometry.info.left - 1)
            assert(geometry.action.right <= geometry.card.right); assert(geometry.placeholderFont <= 20)
            assert(geometry.create.height >= 48 && geometry.templateEntry.height >= 48)
          }
          if (scenario === 'station-error') { assert(geometry.text.includes('水站列表暂未加载，请重试')); assert(!geometry.text.includes('暂无可用水站')) }
        }
        results.push({ ...item, name, before, scenario, ...geometry, text: undefined })
      }
    }
    fs.writeFileSync(path.join(OUT, 'layout.json'), JSON.stringify({ baseline, scope: 'Actual WXML/WXSS; synthetic data; static Chromium only; no native SDK, keyboard, back or live upload proof', results }, null, 2))
    console.log('AQUAFLOW_LAYOUT_OK ' + results.length)
  } finally { await browser.close() }
})().catch(error => { console.error(error.stack); process.exitCode = 1 })
