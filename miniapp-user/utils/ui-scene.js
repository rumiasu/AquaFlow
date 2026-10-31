// Only this public decorative image is resolved here. No caller-supplied object keys.
const { send } = require('./transport')
const LOCAL = '/assets/ui/scene-home-water.webp'
const PATH = '/api/ui-assets/scene-home-water'
let cached = null, flight = null

function resolve(force = false) {
  if (flight) return flight
  if (!force && cached && cached.expiresAt > Date.now() + 60000) return Promise.resolve(cached)
  flight = new Promise((resolve, reject) => send({ path: PATH, method: 'GET',
    success(response) {
      const value = response && response.statusCode === 200 && response.data && response.data.code === 0 && response.data.data
      if (!value || typeof value.url !== 'string' || !/^https:\/\//i.test(value.url)
          || !Number.isFinite(value.expiresAt) || value.expiresAt <= Date.now() + 60000) return reject(new Error('场景图片地址暂不可用'))
      cached = { url: value.url, expiresAt: value.expiresAt }; resolve(cached)
    }, fail() { reject(new Error('场景图片地址读取失败')) }
  })).finally(() => { flight = null })
  return flight
}
function hide(page) {
  page._sceneActive = false
  page._sceneGeneration = (page._sceneGeneration || 0) + 1
  if (page._sceneTimer) clearTimeout(page._sceneTimer)
  page._sceneTimer = null
}
function show(page, flag) {
  hide(page); page._sceneActive = true; page._sceneFlag = flag; page._sceneRetry = false
  refresh(page, false)
}
function refresh(page, force) {
  const generation = page._sceneGeneration
  resolve(force).then(value => {
    if (!page._sceneActive || generation !== page._sceneGeneration) return
    // Keep the old displayed source while the signed URL request is in flight.
    page.setData({ sceneImageUrl: value.url, [page._sceneFlag]: false })
    if (page._sceneTimer) clearTimeout(page._sceneTimer)
    page._sceneTimer = setTimeout(() => refresh(page, false), Math.max(1000, value.expiresAt - Date.now() - 60000))
  }).catch(() => { /* Keep the existing image; a future page visit can retry. */ })
}
function imageError(page, event, flag) {
  if (!page._sceneFlag) page._sceneFlag = flag
  const source = event && event.currentTarget && event.currentTarget.dataset && event.currentTarget.dataset.src
  if (source && source !== page.data.sceneImageUrl) return
  const message = event && event.detail && event.detail.errMsg
  // Keep the platform error for diagnostics but never print a signed URL.
  console.warn('[ui-scene] image error:', typeof message === 'string' ? message.replace(/https?:\/\/[^\s]+/g, '[图片地址]').slice(0, 300) : '未提供错误详情')
  if (page.data.sceneImageUrl && page.data.sceneImageUrl !== LOCAL && !page._sceneRetry) {
    page._sceneRetry = true
    const generation = page._sceneGeneration
    resolve(true).then(value => {
      if (page._sceneActive && generation === page._sceneGeneration && value.url !== page.data.sceneImageUrl)
        page.setData({ sceneImageUrl: value.url, [page._sceneFlag]: false })
      else if (page._sceneActive && generation === page._sceneGeneration) page.setData({ sceneImageUrl: LOCAL, [page._sceneFlag]: false })
    }).catch(() => { if (page._sceneActive && generation === page._sceneGeneration) page.setData({ sceneImageUrl: LOCAL, [page._sceneFlag]: false }) })
    return
  }
  if (page.data.sceneImageUrl !== LOCAL) page.setData({ sceneImageUrl: LOCAL, [page._sceneFlag]: false })
  else page.setData({ [page._sceneFlag]: true })
}
module.exports = { LOCAL, resolve, show, hide, imageError }
