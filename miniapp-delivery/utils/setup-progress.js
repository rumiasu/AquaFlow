// Presentation only. Required/done/route facts come from the existing setup-guide response.
const { fetchSetupGuide } = require('./setup-reminder')
const initialModuleData = () => ({ moduleSetupLoading: true, moduleSetupReady: false, moduleSetupTasks: [], moduleSetupError: '' })
const pathOf = route => typeof route === 'string' ? route.split('?')[0] : ''
function moduleTaskPatch(guide, route) {
  if (!guide || !Array.isArray(guide.items)) return { moduleSetupLoading: false, moduleSetupReady: false, moduleSetupError: '本模块准备任务尚未核实，请重试' }
  return { moduleSetupLoading: false, moduleSetupReady: true, moduleSetupError: '',
    moduleSetupTasks: guide.items.filter(it => it && !it.done && it.level === 'P0' && pathOf(it.route) === route) }
}
function groupSetupItems(items) {
  const groups = new Map()
  for (const it of items || []) { if (!it) continue; const key = pathOf(it.route) || it.where || ''
    if (!groups.has(key)) groups.set(key, []); groups.get(key).push(it) }
  return Array.from(groups.values()).flatMap(rows => rows.map((it, index) => ({ ...it, moduleStart: index === 0 })))
}
function progressPatch(guide) {
  const known = guide && Number.isSafeInteger(guide.doneCount) && Number.isSafeInteger(guide.totalCount)
    && guide.totalCount > 0 && guide.doneCount >= 0 && guide.doneCount <= guide.totalCount
    && Number.isSafeInteger(guide.p0PendingCount) && guide.p0PendingCount >= 0
  return { setupProgressKnown: !!known, setupDoneCount: known ? guide.doneCount : null,
    setupTotalCount: known ? guide.totalCount : null, setupRequiredPending: known ? guide.p0PendingCount : null }
}
function owner() { const app = getApp(), g = app.globalData || {}, u = g.userInfo || {}
  return { app, key: JSON.stringify([app._loginGeneration || 0, !!g.isLogin, u.staffId, u.role, u.stationId, u.bindStatus]) } }
async function refreshModuleTasks(page, route) {
  const proof = owner(), seq = page._moduleSetupSeq = (page._moduleSetupSeq || 0) + 1
  if (!proof.app.canAccessStationBusiness()) return
  page.setData({ moduleSetupLoading: true, moduleSetupReady: false, moduleSetupError: '' })
  const guide = await fetchSetupGuide(), now = owner()
  if (seq !== page._moduleSetupSeq || proof.app !== now.app || proof.key !== now.key
      || page._unloaded || page._productDestroyed || page._moduleSetupClosed) return
  page.setData(moduleTaskPatch(guide, route))
  return guide
}
module.exports = { initialModuleData, moduleTaskPatch, groupSetupItems, progressPatch, refreshModuleTasks }
