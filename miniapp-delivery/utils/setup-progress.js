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
  // 2026-10-26：总览按模块导航，不能让同一页面的每个字段各占一张卡、重复「去填写」。
  // 仍保留原条目和路由；文案只组合已下发的 label，不在这里重判完成条件。
  return Array.from(groups.values()).flatMap(rows => {
    const pending = rows.filter(it => !it.done)
    const labels = Array.from(new Set((pending.length ? pending : rows).map(it => it.label).filter(Boolean)))
    // 2026-10-26：总览的‘怎么做’只给一行；长说明曾挤满弹层，完整操作留在模块页。
    const first = (pending.length ? pending : rows)[0]
    const instruction = String(first.action || first.suggestion || ('进入' + (first.where || '模块') + '填写' + first.label)).replace(/\s+/g, ' ').trim()
    const chars = Array.from(instruction.replace(/[。；;]+$/, ''))
    const moduleHowTo = chars.slice(0, 16).join('') + (chars.length > 16 || rows.length > 1 ? '等' : '')
    return rows.map((it, index) => ({ ...it, moduleStart: index === 0, moduleHowTo,
      moduleDone: pending.length === 0, moduleLabels: labels.join('、'), moduleRows: index === 0 ? rows : [] }))
  })
}
function progressPatch(guide) {
  const known = guide && Number.isSafeInteger(guide.p0PendingCount) && guide.p0PendingCount >= 0
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
