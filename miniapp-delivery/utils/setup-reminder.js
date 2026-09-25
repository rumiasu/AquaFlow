// 站长端「待填项提醒」：**条件刚成立的项**（例：刚招了配送员 → 「员工工资结构」从"不用填"
// 变成"必须填"）立刻弹窗提醒，不用等站长自己去点营业状态胶囊。
//
// [2026-09-24 新建] 起因（产品原话）："如果是正常营业突然加了配送员，类似这种加了判定条件的，
// 立刻弹出弹窗来提醒填写，营业状态栏也跟着变"。此前站长端已有完善度引导
// （`GET /api/manager/setup-guide`，规则目录见 `StationSetupGuideService`），但**只有他主动
// 点开营业状态小框才看得到** —— 条件悄悄成立时没有任何人告诉他。
//
// ⚠️ 五条边界，改之前先读：
//   1. **只有应用内提醒**。小程序在前台无法真推送；要"没打开也能收到"只有微信订阅消息一条路
//      （一次性授权，产品裁定不做，见 AGENTS §9.6）。所以"立刻"= ① **在本机做出那个动作之后
//      当场检查**（真正的立刻，见 pages/station-mgmt/staff/index.js 的两处调用）；
//      ② 每次进首页/配送页 onShow 再检查一次（覆盖不是在本机改出来的变化）。
//      文案不要写成"已推送"。
//   2. **只弹「新出现」的项**，不重复弹老账 —— 否则每次进首页都弹，站长会当背景噪音
//      （与 utils/pending-reminder.js 边界 2 的"告警疲劳"同一个道理）。
//   3. **已提醒过的 key 记在手机本地**（2026-09-24 产品选的本地口径，零迁移）。代价：
//      换手机或清缓存会重新弹一次 —— 只弹一次弹窗，不丢数据，可接受。
//   4. **首次运行静默建基线**：升级前就存在的未完成项一次性弹出来就是骚扰（老站能一次 4 条），
//      所以第一次只记、不弹；之后才只弹"新冒出来的"。这让"新出现"有可靠判据 ——
//      判据是**与上一次看到的集合做差**，不是猜。
//   5. **计数由后端算**（同 pending-reminder 边界 3）：本文件只 `filter(!done)` 决定"弹哪些"，
//      胶囊上的数字直接用响应里的 `pendingCount`，前端不自己加总。
const { getSetupGuide } = require('../api/station-mgmt')

// 已提醒过的 item key 数组（本地）。**与"已完成"无关** —— 完成与否由后端判，这里只记"弹过没"。
const SEEN_KEY = 'setupGuideSeenKeys'
// 基线是否已建立。没有它就无法区分"首次运行"与"所有 key 都提醒过了"（空数组两者同形）。
const BASELINE_KEY = 'setupGuideBaselineDone'

// 在飞的那次请求（见 checkSetupReminder）。模块级变量：所有并发调用共享它。
let inflight = null

function getSeenKeys() {
  const raw = wx.getStorageSync(SEEN_KEY)
  return Array.isArray(raw) ? raw : []
}

function setSeenKeys(keys) {
  wx.setStorageSync(SEEN_KEY, Array.isArray(keys) ? keys : [])
}

function hasBaseline() {
  return wx.getStorageSync(BASELINE_KEY) === true
}

/**
 * 拉完善度清单。失败**不抛**：它挂在页面 onShow 与"加完配送员"之后，
 * 一次网络抖动不该让页面弹红字（取不到就不提醒、胶囊不显示徽标，属于"安静地降级"）。
 * 但会 console.warn 留痕 —— 静默失败会让"没有待填项"与"没拉到"无法区分。
 */
async function fetchSetupGuide() {
  try {
    const res = await getSetupGuide()
    if (!res || res.code !== 0) {
      console.warn('[setup-reminder] 非成功响应:', res && res.message)
      return null
    }
    return res.data || null
  } catch (err) {
    console.warn('[setup-reminder] 取完善度清单失败（本次不提醒）:', err && err.message)
    return null
  }
}

/** 未完成项（条件项条件不成立时后端直接给 done=true，所以这里不用再判条件）。 */
function pendingOf(guide) {
  return ((guide && guide.items) || []).filter(it => it && !it.done)
}

/**
 * 检查一次并（必要时）弹窗。
 *
 * @returns {Promise<{pendingCount:number, pending:Array, summaryText:string, onlineHint:string, freshItems:Array}|null>}
 *   **只有"真的没拉到"才返回 null**（调用方据此保留原值，不要清零 —— 一次抖动不该让
 *   "待填 2 项"消失，也不该把旧列表当成新的）。
 */
function checkSetupReminder() {
  // 并发闸门：页面 onShow 与"打开小框"/"加完配送员"可能几乎同时触发。
  // ⚠️ **共享同一个在飞的请求**，不要"后来者返回 null" —— 那会让调用方把
  //    "有人正在拉"误判成"拉取失败"，从而弹出一句假的网络错误（2026-09-24 踩过）。
  //    共享还有两个好处：只请求一次；`doCheck` 里的弹窗与记账也只发生一次。
  if (inflight) return inflight
  inflight = doCheck().then(
    (v) => { inflight = null; return v },
    (e) => { inflight = null; throw e }
  )
  return inflight
}

/**
 * 真正的检查逻辑（并发闸门在 {@link checkSetupReminder}）。
 *
 * **一次请求喂三处**（2026-09-24 修）：胶囊徽标数、小框里的待填项列表、以及下面的"新出现"提醒，
 * 读的都是这一份响应。⚠️ **别拆成两路各拉一次** —— 拆开就会出现"数字更新了、列表还是旧的"
 * （产品实际报过：刚填完的项在小框里还显示着要填），因为两路各写各的 data 字段、刷新时机也不同。
 *
 * 返回里 `pending` / `summaryText` / `onlineHint` 是**给小框列表直接用**的渲染数据
 * （已按 `done` 过滤），调用方不要再自己过滤一遍。
 *
 * **「新出现」的判据，以及一个刻意的例外（别当 bug 改）**：
 * - `seen` = **曾经处于未完成、且已经弹过的 key**（不是"完成过的 key"）。
 * - 所以**条件刚成立**的项一定算新出现：没有配送员时 `staffPayroll` 是 done（不在 pending、
 *   自然不在 seen），招了配送员它才变成 pending → 差集命中 → 弹。`ticketPackage` 同理
 *   （给某个商品开了水票却没配档位）。
 * - ⚠️ **例外**：一个 key 提醒过、后来补好、再被站长自己弄回未完成（例：他手动「清除水站坐标」），
 *   **不会再弹** —— 他刚亲手做的动作，再弹一次是骚扰。要改这个行为前先想清楚：
 *   重新弹的收益（提醒他）与代价（每次清理都弹）哪个大。
 */
async function doCheck() {
  const guide = await fetchSetupGuide()
  if (!guide) return null

  const pending = pendingOf(guide)
  // 徽标数字优先用后端算好的（边界 5）；后端没给（旧版本）才退回自己数，纯属兜底
  const pendingCount = Number(guide.pendingCount) >= 0
    ? Number(guide.pendingCount)
    : pending.length
  // 渲染数据一起带出去：调用方（behaviors/stationNavbar.js）拿到就写进小框，
  // 不必再拉一次、也不会和徽标数来自两份快照。
  const view = {
    pendingCount,
    pending,
    // 全部项（含已完成的）。小框里"显示已配好的"那个开关要用它 —— 只下发未完成的话，
    // 前端想列已配好的就得自己再拉一次（又是两份快照，正是本文件存在的理由）。
    all: Array.isArray(guide.items) ? guide.items : [],
    summaryText: guide.summaryText || '',
    onlineHint: guide.onlineHint || '',
    freshItems: []
  }

  // ---- 首次运行：只建基线，不弹（边界 4）----
  if (!hasBaseline()) {
    setSeenKeys(pending.map(it => it.key))
    wx.setStorageSync(BASELINE_KEY, true)
    return view
  }

  const seen = getSeenKeys()
  const fresh = pending.filter(it => seen.indexOf(it.key) < 0)
  if (!fresh.length) return view

  // 先记账再弹：万一弹窗期间被杀进程，也不会下次重复弹同一条（宁可漏弹一次，不要反复骚扰）
  setSeenKeys(seen.concat(fresh.map(it => it.key)))

  showFreshModal(fresh)
  view.freshItems = fresh
  return view
}

/**
 * 弹「有新待填项」。只有一条且后端给了 route 时，确认键直接跳过去
 * （本仓明令：弹窗的「确认」必须让流程继续走，只改 UI 状态 = 静默失败，见 §8.30）。
 */
function showFreshModal(fresh) {
  const first = fresh[0]
  const onlyOne = fresh.length === 1
  const lines = fresh.map(it => '· ' + it.label + '（' + (it.levelText || '') + '）\n  ' + (it.why || ''))
  const content = lines.join('\n')
    + (onlyOne ? '' : '\n\n补完可以在营业状态小框里看到进度。')
  const goable = onlyOne && first && first.route

  wx.showModal({
    title: fresh.length > 1 ? '有 ' + fresh.length + ' 项新的要填' : '有一项新的要填',
    content,
    showCancel: false,
    confirmText: goable ? '去填写' : '知道了',
    success: (res) => {
      if (res.confirm && goable) {
        wx.navigateTo({ url: first.route })
      }
    }
  })
}

module.exports = {
  SEEN_KEY,
  BASELINE_KEY,
  fetchSetupGuide,
  checkSetupReminder,
  // 下面两个只给测试/排查用，业务代码不要调（改了本地状态会让提醒行为变得看不懂）
  _getSeenKeys: getSeenKeys,
  _resetBaseline: () => {
    wx.removeStorageSync(SEEN_KEY)
    wx.removeStorageSync(BASELINE_KEY)
  }
}
