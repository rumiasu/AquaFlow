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

/**
 * 红点当前该不该亮的**标记位**（[2026-09-26] 自绘底栏加）。
 *
 * 为什么需要它：`wx.showTabBarRedDot` 只对**原生** tabBar 有效，而本端已改成自绘
 * （`tabBar.custom = true`，为了让「首页」只对站长显示 —— 微信没有隐藏单项的 API）。
 * 自绘底栏是**按页各一份**的组件实例，它得有个地方读"现在要不要亮"：
 * 写 storage，组件 onShow 时读一次；同时本文件直接把状态推给当前页那一份。
 */
const DOT_FLAG_KEY = 'homeTabDotOn'

/** 组件读标记位用（别在别处写这个键；要改就调 syncTabBarDot） */
function readDotFlag() {
  try {
    return wx.getStorageSync(DOT_FLAG_KEY) === true
  } catch (e) {
    return false
  }
}

/** 当前页那一份自绘底栏组件；拿不到（非 tab 页 / 还没登录）返回 null */
function currentTabBar() {
  try {
    if (typeof getCurrentPages !== 'function') return null
    const pages = getCurrentPages()
    const page = pages && pages[pages.length - 1]
    if (!page || typeof page.getTabBar !== 'function') return null
    return page.getTabBar() || null
  } catch (e) {
    // getTabBar 在非 tab 页上可能直接抛；取不到就当没有，绝不因此让待办刷新失败
    return null
  }
}

/**
 * 红点状态**唯一**的写入口：storage 标记 + 当前页的自绘底栏 + 原生 API（回退用）。
 *
 * ⚠️ 原生那条路不要删：`app.json` 的 `tabBar.list` 仍留着当低版本基础库的回退，
 * 那种环境下渲染的是原生三项，红点只有 `wx.showTabBarRedDot` 能画。
 */
function syncTabBarDot(on) {
  const flag = !!on
  try {
    wx.setStorageSync(DOT_FLAG_KEY, flag)
  } catch (e) { /* storage 写失败不影响下面两条路 */ }

  // 原生 API：自绘模式下会失败（没有原生条目），回退模式下才有用 —— 一律吞掉，绝不抛。
  // ⚠️ 本函数会从 app.onShow 里被调用，**抛异常等于拖垮启动路径**，所以连同步异常都兜住。
  try {
    if (flag) {
      wx.showTabBarRedDot({ index: HOME_TAB_INDEX, fail: () => {} })
    } else {
      wx.hideTabBarRedDot({ index: HOME_TAB_INDEX, fail: () => {} })
    }
  } catch (e) { /* 自绘底栏下这条 API 无效，红点由下面的组件路径画 */ }

  const bar = currentTabBar()
  if (!bar) return
  if (typeof bar.setDot === 'function') {
    bar.setDot(flag)
  } else {
    bar.setData({ showDot: flag })
  }
}

/** 用户是否开着待办提醒（默认开） */
function isReminderEnabled() {
  return wx.getStorageSync(REMINDER_KEY) !== false
}

/** 仅 ta 决定要不要亮红点，不做其它副作用 */
function setReminderEnabled(enabled) {
  wx.setStorageSync(REMINDER_KEY, !!enabled)
  if (!enabled) {
    // 立刻清掉，不然关掉开关后红点还在，看起来像没生效（下次 onShow 也不会再点亮）
    syncTabBarDot(false)
    return
  }
  // 重新打开时立刻拉一次：不拉的话要等下一次 onShow 才亮，用户会以为开关坏了
  //（失败只是不亮，不抛错 —— 同 fetchPendingSummary 的降级口径）
  syncPendingReminder()
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
  // 一个写入口管三处（原生 API / storage 标记 / 当前页的自绘底栏），见 syncTabBarDot
  syncTabBarDot(hasP0)
}

module.exports = {
  PENDING_SUMMARY,
  REMINDER_KEY,
  DOT_FLAG_KEY,
  readDotFlag,
  syncTabBarDot,
  isReminderEnabled,
  setReminderEnabled,
  fetchPendingSummary,
  syncPendingReminder,
  applyRedDot
}
