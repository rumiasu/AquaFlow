const assert = require('assert')
const fs = require('fs')
const path = require('path')
const { loadPage, createWx, createApp, ROOT } = require('./harness')
const { parseWxml, renderElements } = require('./wxml-tree')

function verify(route, flag, errorHandler, prepare, data) {
  const app = createApp()
  app.globalData.isLogin = false
  const page = loadPage(`miniapp-user/pages/${route}/index.js`, { wx: createWx(), app })
  prepare(page)
  const template = fs.readFileSync(path.join(ROOT, `miniapp-user/pages/${route}/index.wxml`), 'utf8')
  const tree = parseWxml(template)
  const images = () => renderElements(tree, { ...page.data, ...data(page) }, { includeText: true })
    .filter(e => e.tag === 'image' && e.attrs.src === '{{sceneImageUrl}}' && page.data.sceneImageUrl === '/assets/ui/scene-home-water.webp')
  assert.strictEqual(page.data[flag], false, `${route}: initial state`)
  page[errorHandler]()
  assert.strictEqual(page.data[flag], true, `${route}: failure shown`)
  if (route !== 'home') assert.strictEqual(images().length, 0, `${route}: fallback replaces image`)
  page.onShow()
  assert.strictEqual(page.data[flag], false, `${route}: return retries`)
  if (route !== 'home') assert.strictEqual(images().length, 1, `${route}: image restored`)
  page[errorHandler]()
  assert.strictEqual(page.data[flag], true, `${route}: repeated failure still falls back`)
  console.log(`PASS ${route}: failure → return retry → repeated failure`)
}

verify('mine', 'entryGraphicFailed', 'onEntryGraphicError', page => {
  page.loadAssets = () => {}
}, () => ({ isLogin: true, userInfo: {} }))
verify('template', 'emptyTemplateImageFailed', 'onEmptyTemplateImageError', page => {
  page.loadData = () => Promise.resolve()
}, () => ({ loading: false, loaded: true, loadError: '', templates: [], needLogin: false }))
verify('home', 'posterArtFailed', 'onPosterArtError', page => {
  page.invalidateHomeQuote = () => {}
  page.refreshDerived = () => {}
  page._syncHomeIssueSession = () => {}
  page._updateHomeFeedback = () => {}
}, () => ({}))
