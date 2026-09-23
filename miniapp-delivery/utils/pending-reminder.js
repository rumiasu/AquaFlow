// 站长端「待办提醒（应用内）」。
//
// [2026-09-19 新建] 起因：别人递过来的申请（待分配单 / 转单 / 取消申请 / 退桶 / 绑定申请…）
// 此前**没有任何提醒**，站长只能自己一页页翻才知道。
//
// ⚠️ 三条边界，改之前先读：
//   1. **只有应用内提醒**。小程序在前台无法真推送；要让站长"没打开也能收到"只有微信订阅消息
//      一条路（需在微信后台申请模板 + 员工端真实 appid），产品裁定**暂不做**。
//      所以这里的红点只在"站长打开小程序时"刷新 —— 文案不要写成"已推送"。
//   2. **红点只报 P0**（后端 level='P0' 且 count>0 的条目数，即 `items[].level` 里那几个）。
//      P1/P2 一起算的话红点会天天亮着，站长就把它当背景噪音（"告警疲劳"的同形问题）。
//      ⚠️ 但**待办卡照常显示 P1/P2** —— "红点报不报"与"要不要让站长看见"是两件事。
//   3. 计数**全部由后端算**（`/api/manager/pending-summary`），前端不自己 filter、不自己加总：
//      本仓已经有"角标说 3、点进去 0 条"的土壤（多处口径分叉），别在这里再开一条。
const { get } = require('./request')

const PENDING_SUMMARY = '/api/manager/pending-summary'
// 用户在「设置」里关掉提醒后写这个键（默认开）。关了 = 不亮红点，**但待办卡照常显示** ——
// 静默吞掉待办比不提醒更糟。
const REMINDER_KEY = 'todoReminderEnabled'
const HOME_TAB_INDEX = 0 // 首页 tab 的位置（见 app.json 的 tabBar.list 顺序）

/** 用户是否开着待办提醒（默认开） */
function isReminderEnabled() {
  return wx.getStorageSync(REMINDER_KEY) !== false
}

/** 仅 ta 决定要不要亮红点，不做其它副作用 */
function setReminderEnabled(enabled) {
  wx.setStorageSync(REMINDER_KEY, !!enabled)
  if (!enabled) {
    // 立刻清掉，不然关掉开关后红点还在，看起来像没生效（下次 onShow 也不会再点亮）
    wx.hideTabBarRedDot({ index: HOME_TAB_INDEX, fail: () => {} })
  }
}

/**
 * 取待办汇总。失败**不抛**：它挂在 app.onShow 与各页面 onShow 上，
 * 一次网络抖动不该让页面弹红字（取不到就不亮红点，属于"安静地降级"）。
 * 但会 console.warn 留痕 —— 静默失败会让"红点不亮"与"真没待办"无法区分。
 */
async function fetchPendingSummary() {
  if (!isReminderEnabled()) return null
  try {
    const res = await get(PENDING_SUMMARY)
    if (!res || res.code !== 0) {
      console.warn('[pending-summary] 非成功响应:', res && res.message)
      return null
    }
    return res.data || null
  } catch (err) {
    console.warn('[pending-summary] 取待办汇总失败（不亮红点）:', err && err.message)
    return null
  }
}

/**
 * 拉一次并刷新首页 tab 的红点。给 tabBar 页的 onShow 与 app.onShow 调。
 * @returns {Promise<Object|null>} 汇总数据（调用方要渲染就复用这一份，别为了红点再请求一次）
 */
async function syncPendingReminder() {
  const data = await fetchPendingSummary()
  if (!data) return null
  applyRedDot(data)
  return data
}

/** 按已拿到的汇总数据亮/灭红点（不请求）。纯红点，不带数字 —— 见文件头边界 2 */
function applyRedDot(data) {
  if (!isReminderEnabled()) return
  const hasP0 = Number(data && data.p0Total) > 0
  if (hasP0) {
    wx.showTabBarRedDot({ index: HOME_TAB_INDEX, fail: () => {} })
  } else {
    wx.hideTabBarRedDot({ index: HOME_TAB_INDEX, fail: () => {} })
  }
}

module.exports = {
  PENDING_SUMMARY,
  REMINDER_KEY,
  isReminderEnabled,
  setReminderEnabled,
  fetchPendingSummary,
  syncPendingReminder,
  applyRedDot
}
