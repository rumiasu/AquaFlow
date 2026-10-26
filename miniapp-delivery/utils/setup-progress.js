// The setup guide is the source of truth for task labels, levels and routes.
const { fetchSetupGuide } = require('./setup-reminder')
const initialModuleData = () => ({ moduleSetupLoading: true, moduleSetupReady: false, moduleSetupTasks: [], moduleSetupError: '' })
const pathOf = route => typeof route === 'string' ? route.split('?')[0] : ''
const PRODUCT_ROUTE = '/pages/station-mgmt/products/index'
const TICKET_ROUTE = '/pages/station-mgmt/ticket-packages/index'
function moduleOf(item) {
  if (item.key === 'catalogOnShelf' || item.key === 'ticketPackage') return '商品库存'
  return item.where || pathOf(item.route) || ''
}
function routeOf(item) {
  return item.key === 'ticketPackage' || pathOf(item.route) === TICKET_ROUTE ? PRODUCT_ROUTE : pathOf(item.route)
}
function completionEvidence(item) {
  const evidence = {
    stationPhone: '联系电话已保存；刷新准备状态后显示完成。',
    stationPosition: '站点地址和地图坐标均已保存；缺少任一项都不会通过核对。',
    catalogOnShelf: '至少一款商品已启用并上架。',
    deliveryFee: '站点配送计费配置已保存。',
    staffPayroll: '有配送员时，站点默认计件单价已保存；没有配送员可跳过。',
    ticketPackage: '水票是选填：不启用可跳过；启用后需有在售档位，或有效的站点统一折扣。'
  }
  return evidence[item.key] || '保存后重新核对本项状态。'
}
function taskForDetail(item) {
  return { ...item,
    route: routeOf(item),
    levelText: item.key === 'ticketPackage' ? '选填' : item.levelText,
    suggestion: item.key === 'ticketPackage' && !item.done
      ? '在商品列表筛选“已开水票”，打开对应商品编辑档位；也可在商品编辑中启用水票并设置在售档位。'
      : item.suggestion,
    completionEvidence: completionEvidence(item)
  }
}
function moduleTaskPatch(guide, route) {
  if (!guide || !Array.isArray(guide.items)) return { moduleSetupLoading: false, moduleSetupReady: false, moduleSetupError: '本页准备状态尚未核实，请重试' }
  return { moduleSetupLoading: false, moduleSetupReady: true, moduleSetupError: '',
    moduleSetupTasks: guide.items.filter(it => it && routeOf(it) === route).map(taskForDetail) }
}
function groupSetupItems(items) {
  const groups = new Map()
  for (const it of items || []) { if (!it) continue; const key = moduleOf(it)
    if (!groups.has(key)) groups.set(key, []); groups.get(key).push(it) }
  return Array.from(groups.values()).flatMap(rows => {
    const pending = rows.filter(it => !it.done)
    const required = Array.from(new Set(pending.filter(it => it.level === 'P0').map(it => it.label).filter(Boolean)))
    const optionalTickets = pending.some(it => it.key === 'ticketPackage')
    const otherPending = Array.from(new Set(pending.filter(it => it.level !== 'P0' && it.key !== 'ticketPackage').map(it => it.label).filter(Boolean)))
    const summary = []
    if (required.length) summary.push('必填待完善：' + required.join('、'))
    else summary.push('必填项无待补')
    if (optionalTickets) summary.push('选填待完善：水票档位')
    if (otherPending.length) summary.push('建议核对：' + otherPending.join('、'))
    if (!pending.length) summary.splice(0, summary.length, '本模块准备事项已完成')
    return rows.map((it, index) => ({ ...it, route: routeOf(it), moduleStart: index === 0,
      moduleTitle: moduleOf(it), moduleDone: required.length === 0, moduleSummary: summary.join('；'),
      moduleRows: index === 0 ? rows : [] }))
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
