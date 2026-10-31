const { showBusinessModal } = require('./business-dialog')
const { fetchSetupGuide, SEEN_KEY } = require('./setup-reminder')

/** 成功新增/绑定后定向核验，不使用首页首次静默基线，也不把失败当作已配好。 */
function owner() {
  const app = getApp(), g = app.globalData || {}, u = g.userInfo || {}
  return { app, key: JSON.stringify([app._loginGeneration || 0, !!g.isLogin, u.staffId || u.id, u.stationId, u.role]) }
}
function current(value) { const now = owner(); return now.app === value.app && now.key === value.key }

async function check(page, showModal = true) {
  page._staffFollowupNeedsCheck = true
  const context = owner(), seq = page._staffFollowupSeq = (page._staffFollowupSeq || 0) + 1
  page.setData({ payrollFollowupLoading: true, payrollFollowupError: '' })
  let guide
  try { guide = await fetchSetupGuide() } catch (_) { guide = null }
  if (seq !== page._staffFollowupSeq || !current(context) || page._staffGone) return
  const target = guide && Array.isArray(guide.items) && guide.items.find(item => item.key === 'staffPayroll')
  const pending = target && !target.done
  if (!guide || !target || typeof target.done !== 'boolean') {
    page.setData({ payrollFollowupLoading: false, payrollFollowupError: '员工已保存，薪资结构尚未核实，请重试核对。' })
    return
  }
  page._staffFollowupNeedsCheck = false
  page.setData({ payrollFollowupLoading: false, payrollFollowupError: '', payrollFollowup: pending ? target : null })
  // 2026-10-27：本页已提示后，返回首页不能再把同一项当作新增；只追加本项，不改首次基线及其他提醒。
  if (pending && SEEN_KEY) {
    try {
      const seen = wx.getStorageSync(SEEN_KEY), keys = Array.isArray(seen) ? seen : []
      if (!keys.includes(target.key)) wx.setStorageSync(SEEN_KEY, keys.concat(target.key))
    } catch (_) { /* 本地提示标记失败不影响员工已保存及当前配置入口。 */ }
  }
  if (!pending || !showModal) return
  showBusinessModal({ title: '员工已加入 · 核对薪资', content: (target.levelText || '建议') + '：' + target.label + '\n' + (target.why || '')
      + '\n可稍后配置，员工页保留填写入口。', confirmText: '去配置', cancelText: '稍后填写',
    confirmColor: '#1264D6', cancelColor: '#637083',
    success: r => { if (r.confirm && current(context) && !page._staffGone) open(page) }
  })
}
function open(page) {
  const item = page.data.payrollFollowup
  if (item && item.route) wx.navigateTo({ url: item.route + (item.route.includes('?') ? '&' : '?') + 'tab=rate' })
}
module.exports = { check, open, owner, current }
