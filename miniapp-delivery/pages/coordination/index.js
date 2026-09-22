const { get, post } = require('../../utils/request')
const { API } = require('../../config/api')
// directedReturn 必须在这里 import：本页的「调解退回原站」按钮调的就是它，
// 漏了 import 会让 onMediateReturn 抛 ReferenceError —— 点击**静默无反应**（AGENTS §6）。
const { getStaffList, getPoolOrders, claimPoolOrder, getDispatchTracking, cancelDispatch, directedReturn, approveDirectedReturn, rejectDirectedReturn, getDirectedIncoming, approveStaffReturn, rejectStaffReturn, getPendingApprovals, approveCancelRequest, rejectCancelRequest } = require('../../api/delivery')
// 自绘导航栏 + 水站营业状态胶囊（本页 navigationStyle=custom）：与「配送」页共用一份实现
// —— 结构与样式见 templates/station-navbar.wxml、styles/station-navbar.wxss
const stationNavbar = require('../../behaviors/stationNavbar')

// 转单中标记：站间转单 vs 配送员转单（退回站长/转让/重分配）
const DIRECTED_MARK = '[指定退回待确认]'
const STAFF_MARKS = ['[退回站长]', '[转让]', '[重分配]']

// 待办汇总：路径常量已并入 config/api.js（MANAGER_PENDING_SUMMARY）—— [2026-09-19]
// 原来说"config/api.js 被另一个工作流（商品图片库）占着"才写在这里，那个工作流早已合并，
// 现在「我的」页也要调同一个端点，再留一份副本就是同一条路径的两个定义。
// 口径：从 todo-summary 换成 pending-summary，后者带 level（P0/P1/P2）并覆盖
// "别人递过来的申请"（待分配/转单/取消/退桶/绑定申请…）。todo-summary 仍被保留在后端
// （它的 4 项与本站点部分重叠，桶异常那一项两边刻意用同一个服务方法保证一致）。
const PENDING_SUMMARY = API.MANAGER_PENDING_SUMMARY

/** 跨站外派风险查询（后端路由 /api/delivery/orders/{id}/cross-station-risk）。 */
const crossStationRiskUrl = (id) => `/api/delivery/orders/${id}/cross-station-risk`

/**
 * 取「跨站外派风险」提示 —— **文案与判据都来自后端**，前端只负责原样展示。
 *
 * 后端口径：涉押金/桶权益的单（押金不好划定、水站间的欠桶无处登记）返回 riskNote（含出路：
 * 建议直接拒单 / 如确需外派只能定向外派并由双方确认）；不涉风险的普通单返回 null。
 * 前端**绝不自己判断"这单算不算涉押金"**（那就是把后端规则抄成第二份，本仓"计价双轨"的同款形状）。
 *
 * 拿不到就算了：不阻断提交，后端会按同一判据拦下并把同一段文案回给用户（仍是后端下发的文案）。
 */
async function fetchCrossStationRisk(orderId) {
  try {
    const res = await get(crossStationRiskUrl(orderId))
    const d = res.data || {}
    return { risky: !!d.depositBarrelRisk, note: d.riskNote || '' }
  } catch (err) {
    console.warn('[cross-station-risk] 取风险提示失败，按"无提示"继续:', err && err.message)
    return { risky: false, note: '' }
  }
}

/**
 * 给「抢单池 / 他站外派」的订单补三个**纯展示**字段（金额与去向全部由后端下发）：
 *
 * 1. `deliveryFeeText` / `floorFeeText` / `totalAmountText` —— 金额文本。**只做定长格式化，
 *    不做任何算术**：配送费与楼层费是"下单那一刻按归属站站级配置算出来、快照进 orders 的"，
 *    在旁边再减一次/加一次，迟早会与订单详情页显示成两个数（本仓"计价双轨"事故的形状）。
 * 2. `hasFeeDetail` —— 有没有单独的配送费/楼层费（都为 0 时整块不显示）。
 * 3. `settleNote` / `feeStationName` —— 原样透传后端文案，**前端绝不自己编**
 *    「钱归你」这类口径句子（AGENTS §6：口径文案只有一个来源，就是后端）。
 * 4. `crossStationRiskNote`（后端只在抢单池下发；其它列表在提交前用
 *    `_confirmRisk` 现取）—— 同样是原样透传，本函数**不做任何判断**，`...o` 带过去即可。
 *
 * ⚠️ 必须做空值兜底：这两个页签的数据来自两个不同端点（抢单池返回 Map、他站外派返回实体），
 * 老版本后端没有这些字段时页面要照旧能看，不能显示成 ¥undefined。
 */
function feeView(o) {
  const money = (v) => (v === null || v === undefined || v === '' ? '' : Number(v).toFixed(2))
  const has = (v) => Number(v) > 0
  return {
    ...o,
    deliveryFeeText: money(o.deliveryFee),
    floorFeeText: money(o.floorFee),
    totalAmountText: money(o.totalAmount),
    hasFeeDetail: has(o.deliveryFee) || has(o.floorFee)
  }
}

/**
 * 给「待分配」订单补**客户信用标** —— 站长一眼看出"这单的客户欠不欠钱"。
 *
 * 三个字段全部由后端下发（`DeliveryController.attachCustomerRisk` → `CustomerRiskService`）：
 *   · `customerRiskLevel`      NORMAL 正常 / WATCH 关注 / ALERT 预警 / FREEZE 冻结
 *   · `customerRiskLevelText`  中文（正常 / 关注 / 预警 / 冻结）
 *   · `customerRiskNote`       整句话，如"有 ¥320.00 挂账，都在账期内"
 *
 * ⚠️ 前端**不判断"这算不算欠钱"、也不自己拼那句话**（判据与文案都只有后端一份）。
 * 这里只做两件纯展示的事：
 *   ① 等级代号 → 一个 CSS 类。**颜色是"呈现"、不是口径**：红 = 预警/冻结，
 *      黄 = 关注，其余（含将来新增的等级）走中性灰 —— 认不出来就别乱标红，也别静默不显示。
 *   ② NORMAL 不标：后端文档化的默认等级就是"没有未结欠款"，每行都挂一个「正常」是噪音。
 * ⚠️ 文案缺失时**什么都不标**（老版本后端没有这几个字段，页面要照旧能看），
 * 绝不把 `ALERT` 这种代号直接甩给站长看。
 */
function riskView(o) {
  const level = String(o.customerRiskLevel || '').toUpperCase()
  const text = o.customerRiskLevelText || ''
  const cls = (level === 'ALERT' || level === 'FREEZE') ? 'risk-danger'
    : (level === 'WATCH' ? 'risk-warn' : 'risk-plain')
  return {
    ...o,
    showRisk: !!text && level !== 'NORMAL',
    riskText: text,
    riskNote: o.customerRiskNote || '',
    riskClass: cls
  }
}

Page({
  behaviors: [stationNavbar],

  data: {
    isManager: false,
    activeTab: 'pending',
    // 「审批」大页签内的子页签：customer 客户发起 / station 站内（配送员）发起
    approvalTab: 'customer',
    tabs: [
      { key: 'pending', label: '待分配', count: 0 },
      { key: 'approval', label: '审批', count: 0 },
      { key: 'pool', label: '抢单池', count: 0 },
      { key: 'dispatch', label: '外派', count: 0 },
      { key: 'incoming', label: '他站外派', count: 0 }
    ],
    lists: {
      pending: [],
      pool: [],
      dispatch: [],
      incoming: [],
      // 待审批申请：客户发起（取消申请）/ 站内发起（退回站长、转让、重分配、取消申请）
      approvalCustomer: [],
      approvalStation: []
    },
    staffList: [],
    showAssignModal: false,
    currentOrderId: null,
    // ===== 失败标记（[2026-09-20 真机联调]）=====
    // loadError：本页**部分**数据没加载出来时的页面提示（空串 = 全部正常）。
    // staffListError：配送员名单没加载出来的原因（空串 = 加载成功）。
    // ⚠️ 后者是本页最容易骗人的地方：staffList 为空既可能是"本站真的没有配送员"，
    // 也可能是"接口失败"—— 原来两者都渲染成「暂无配送员，请先添加配送员」（见 onClaimPool），
    // 站长会真的去建员工，而问题在网络（AGENTS §8.22 / §8.17）。
    loadError: '',
    staffListError: '',
    // 「其他待办」视图模型（只含不在本页页签里的项），由 loadTodo() 组装
    todo: null
  },

  /**
   * 「其他待办」**收录哪些 key** —— 这是首页顶部唯一需要维护的清单。
   *
   * [2026-09-19 收敛，当天两次修订] 判据只有一条：**本页 tab 覆盖不到、且没有立刻可见的计数**。
   * ⚠️ 修订前先**把整张卡删光了**，产品随即质疑"都是那种重复的吗" —— 不是：15 项里只有 7 项
   * 真重复。别再删整卡（wxml 里那段注释记着同一件事）：
   *   · **剔除**（本页页签已有角标 / 宫格已有卡）：pendingAssign · pendingTransfer · customerCancel ·
   *     stationCancel · poolClaimable · directedIncoming · barrelReturn；
   *   · **保留**（只活在「水站管理」里，首页不报就没人知道）：下面这 8 项。
   *
   * ⚠️ 被剔除的 7 项里 **6 项是 P0**（待分配/转单/客户取消/站内取消/他站定向外派/退桶审批，
   * 只有「抢单池」是 P2）—— 按级别它们"应该"在首页。之所以仍剔除：它们各自的页签/页面上一眼
   * 就能看到数，在这里再报一遍正是本次要消除的那种重复。**P0 并没有丢**：tab 红点算的是完整
   * payload 的 p0Total（这 6 项全在内），只是换成"一个红点"而不是"6 个数字"。
   * 若产品认为 P0 必须逐项上门，**加回一项要动两处**：本清单添 key **且** TODO_ROUTES 补路由 ——
   * 其中 4 项（待分配/转单/客户取消/站内取消）本身就是本页页签，得走 switchTab 而非 navigateTo。
   */
  TODO_KEYS: [
    'overdueReceivable', 'pendingPayment', 'staffBinding', 'enterpriseApply',
    'draftPayroll', 'costNotFilled', 'barrelException', 'operationAlert'
  ],

  /**
   * 待办项 key → 页面。key 是服务端给的稳定标识，label 由服务端下发；
   * **这里只做路由**（后端不认识小程序路径），所以它不是"前端自带映射表"。
   */
  TODO_ROUTES: {
    overdueReceivable: '/pages/station-mgmt/receivables/index',
    pendingPayment: '/pages/station-mgmt/payments/index',
    staffBinding: '/pages/station-mgmt/staff/index',
    enterpriseApply: '/pages/station-mgmt/customers/index',
    draftPayroll: '/pages/station-mgmt/payroll/index',
    costNotFilled: '/pages/station-mgmt/gross-profit/index',
    barrelException: '/pages/station-mgmt/exceptions/index',
    // 处理留痕（原「运营告警」页，2026-09-19 并入「异常订单」页的页签 2）
    operationAlert: '/pages/station-mgmt/exceptions/index?tab=alerts'
  },

  /**
   * 拉待办汇总，两件事：① 组装「其他待办」卡；② 刷新首页 tab 红点。
   *
   * 刻意**不阻塞**主列表、失败也不弹红字：它只是附加信号，取不到就不显示卡、不亮红点，
   * 由下面 console.warn 留痕（静默失败会让"没数据"与"真没待办"无法区分）。
   */
  async loadTodo() {
    const app = getApp()
    const stationId = (app.globalData.userInfo || {}).stationId
    if (!stationId) return
    try {
      const res = await get(PENDING_SUMMARY)
      if (!res || res.code !== 0) {
        console.warn('[pending-summary] 非成功响应:', res && res.message)
        return
      }
      const d = res.data || {}
      this.setData({ todo: this.decorateTodo(d) })
      // 红点判据（P0 且非零）由 utils/pending-reminder 统一持有，页面不自己判断。
      // ⚠️ 红点看的是**全部** P0（含被本卡剔除的那几项），不是只看卡里这 8 项 —— 别改成用 todo.items 推。
      require('../../utils/pending-reminder').applyRedDot(d)
    } catch (err) {
      console.warn('[pending-summary] 取待办汇总失败（不显示卡、不亮红点）:', err && err.message)
    }
  },

  /**
   * 待办数据 → 「其他待办」视图模型。
   *
   * ⚠️ 只收 {@link #TODO_KEYS} 里的项 —— 其余项由 tab 角标负责，不在这里重复。
   * 金额格式化放在这里做（wxml 不能调方法）。
   * 保留项**按 0 也显示**（与 tab 角标"0 就不显示"语义不同：角标是"有几条要办"，
   * 这里是"系统有哪些事项"，不显示站长就不知道有这个功能）。
   */
  decorateTodo(d) {
    const byKey = {}
    ;(d.items || []).forEach(it => { byKey[it.key] = it })
    const items = []
    this.TODO_KEYS.forEach(key => {
      const it = byKey[key]
      if (!it) return   // 后端没下发这一项（如企业身份功能关着）→ 不硬造
      items.push(Object.assign({}, it, {
        amountText: (it.amount === null || it.amount === undefined) ? '' : Number(it.amount).toFixed(2),
        hasCount: Number(it.count) > 0
      }))
    })
    return { items }
  },

  onTodoTap(e) {
    const key = e.currentTarget.dataset.key
    const url = this.TODO_ROUTES[key]
    if (!url) {
      // 服务端加了新的待办项而这里还没接页面：出声，不要静默无反应
      wx.showToast({ title: '该待办暂未接入页面', icon: 'none' })
      return
    }
    wx.navigateTo({ url })
  },

  checkRole() {
    const app = getApp()
    const userInfo = app.globalData.userInfo || {}
    const role = userInfo.role || ''
    const isManager = role === 'STATION_MANAGER' || role === 'manager'
    this.setData({ isManager })
    return isManager
  },

  /**
   * 提交前的「风险提示 → 用户确认」两步（产品要求：押金/桶权益风险文案由后端出，
   * 外派方与接收站**双方都特别提醒后同意**才提交）。
   *
   * @returns {{ok: boolean, acknowledged: boolean}} ok=false 表示用户点了取消，
   *          调用方必须直接返回、**不要提交**；acknowledged=true 表示本次提交要带
   *          riskAcknowledged=true（后端只对涉押金/桶权益的单校验这个字段，
   *          普通单不看不加摩擦）。
   */
  async _confirmRisk(orderId, confirmText) {
    const risk = await fetchCrossStationRisk(orderId)
    if (!risk.note) return { ok: true, acknowledged: false }
    const res = await new Promise((resolve) => {
      wx.showModal({
        title: '押金/桶权益风险',
        content: risk.note,
        confirmText: confirmText || '我已确认',
        cancelText: '取消',
        success: resolve,
        fail: () => resolve({ confirm: false })
      })
    })
    return { ok: !!(res && res.confirm), acknowledged: true }
  },

  /** 无权限兜底卡片的「返回配送页」按钮
   *  coordination 本身是 tabBar 页，回到另一个 tabBar 页必须用 switchTab
   *  （navigateBack 对 tabBar 页不可靠）。原先 wxml 绑了此方法但 js 未定义 →
   *  点了没反应，配送员会被卡在「您无权限」页出不去。 */
  onGoBack() {
    wx.switchTab({
      url: '/pages/home/index',
      fail: () => wx.reLaunch({ url: '/pages/home/index' })
    })
  },

  onLoad() {
    // 自绘导航栏尺寸（状态胶囊要贴着「首页」字样左边，尺寸先算好再渲染，避免闪一下）
    // 实现与「配送」页共用，见 behaviors/stationNavbar.js
    this.initNavMetrics()
    if (!this.checkRole()) {
      wx.switchTab({ url: '/pages/home/index' })
      return
    }
    wx.setNavigationBarTitle({ title: '首页' })
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    if (this.checkRole()) {
      // 营业状态（软状态 v32：只提示不阻断）：与「配送」页共用实现，见 behaviors/stationNavbar.js
      this.loadStationStatus((app.globalData.userInfo || {}).stationId)
      this.loadTodo()
      this.loadAllData()
    }
  },

  onPullDownRefresh() {
    if (this.checkRole()) {
      this.loadTodo()
      this.loadAllData().then(() => { wx.stopPullDownRefresh() })
    } else {
      wx.stopPullDownRefresh()
    }
  },

  async loadAllData() {
    wx.showLoading({ title: '加载中...' })
    try {
      const app = getApp()
      const userInfo = app.globalData.userInfo || {}
      const stationId = userInfo.stationId

      // [2026-09-20 真机联调] 原来这里是 Promise.all：**任何一个**请求失败都会让整页数据
      // 一个都不落地，而页面上五个页签照旧渲染成「暂无待分配 / 暂无抢单池…」—— 与"确实没有单"
      // 完全无法区分（AGENTS §8.22）。而且 HTTP 200 + code!=0 的业务失败原来被直接忽略
      // （`res.data || []` 拿到 undefined → 空数组），等于把失败当成功（AGENTS §8.1）。
      // 现在改成 allSettled + 逐个判 code：成功的那几项照常展示，失败项汇总到 loadError 提示条。
      const failed = []
      const unwrap = (r, tag) => {
        if (r.status === 'fulfilled' && r.value && r.value.code === 0) return r.value
        failed.push(tag)
        const why = r.status === 'rejected'
          ? ((r.reason && r.reason.message) || '网络异常')
          : ((r.value && r.value.message) || '服务端返回异常')
        console.warn('[coordination] ' + tag + ' 加载失败:', why)
        return { data: null }
      }
      const settled = await Promise.allSettled([
        get(API.DELIVERY_ORDERS + '/station-pending'),
        get(API.DELIVERY_ORDERS + '/station-transfer'),
        getPoolOrders(),
        getDispatchTracking(),
        getDirectedIncoming(),
        getPendingApprovals()
      ])
      const pendingRes = unwrap(settled[0], '待分配')
      const transferRes = unwrap(settled[1], '转单请求')
      const poolRes = unwrap(settled[2], '抢单池')
      const dispatchRes = unwrap(settled[3], '外派追踪')
      const incomingRes = unwrap(settled[4], '他站外派')
      const approvalsRes = unwrap(settled[5], '待审批')

      let staffList = []
      let staffListError = ''
      if (stationId) {
        // 拉不到配送员名单时必须留下标记 —— onClaimPool 与分配弹窗都靠它区分
        //「本站没有配送员」与「名单没查到」（见 data.staffListError 的注释）
        try {
          const staffRes = await getStaffList(stationId)
          if (staffRes && staffRes.code === 0 && staffRes.data) {
            staffList = staffRes.data
          } else {
            staffListError = (staffRes && staffRes.message) || '服务端返回异常'
            failed.push('配送员名单')
          }
        } catch (e) {
          staffListError = (e && e.message) || '网络异常'
          failed.push('配送员名单')
        }
        if (staffListError) console.error('[coordination] 加载配送员名单失败:', staffListError)
      }

      // 待分配：合并未分配 + 转单请求（含「转单中」订单）
      // 状态检测：special_note 带 [指定退回待确认] => 转单中，前端渲染「同意/拒绝」而非「分配/外派」
      // 信用标（riskView）只加在这里：抢单池 / 他站外派是**跨站可见面**，
      // "这个客户欠多少钱"是归属站的经营信息，后端也不下发（见 DeliveryController）。
      const pendingList = [
        ...(pendingRes.data || []),
        ...(transferRes.data || [])
      ].map(o => {
        const note = o.specialNote || o.special_note || ''
        let transferKind = ''
        if (note.indexOf(DIRECTED_MARK) >= 0) transferKind = 'directed'
        else if (STAFF_MARKS.some(m => note.indexOf(m) >= 0)) transferKind = 'staff'
        return riskView({ ...o, transferPending: transferKind !== '', transferKind })
      })

      const approvalData = approvalsRes.data || {}
      const lists = {
        pending: pendingList,
        // 「抢单池」与「他站外派」是同一件事的两个入口（放池谁都能抢 / 定向派给某个站），
        // 两个页签都必须在动手前看到"这单值多少钱、价是谁定的、钱归谁" —— 同一份映射，不各写一遍。
        pool: (poolRes.data || []).map(feeView),
        dispatch: dispatchRes.data || [],
        incoming: (incomingRes.data || []).map(feeView),
        approvalCustomer: approvalData.customer || [],
        approvalStation: approvalData.station || []
      }

      const tabs = this.data.tabs.map(t => ({
        ...t,
        // 「审批」角标 = 客户 + 站内 两组待审批之和
        count: t.key === 'approval'
          ? lists.approvalCustomer.length + lists.approvalStation.length
          : (lists[t.key] || []).length
      }))

      this.setData({
        lists,
        tabs,
        staffList,
        staffListError,
        loadError: failed.length
          ? '有 ' + failed.length + ' 项没加载出来（' + failed.join('、') + '），下面可能是空的，别当成"确实没有"'
          : ''
      })
      // ⚠️ 顺序要紧：wx.showToast 与 wx.showLoading 共用同一个浮层实例，
      // 先 toast 再 hideLoading 会把刚弹出的提示一起关掉 —— 必须先 hideLoading。
      wx.hideLoading()
      if (failed.length) wx.showToast({ title: '部分数据没加载出来', icon: 'none' })
    } catch (err) {
      // 兜底：走到这里只可能是本地代码出错（每个请求都已单独判过）
      console.error('[coordination] 加载数据失败:', err)
      wx.hideLoading()
      // [2026-09-20] 原来只有一句立刻消失的 toast，页面随后是五个空页签。
      // 现在同时留下持久提示，站长不会把"没加载出来"读成"没有待办"（AGENTS §8.22）
      this.setData({ loadError: '数据没加载出来（' + ((err && err.message) || '本地异常') + '），请稍后重试' })
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    }
  },

  switchTab(e) {
    this.setData({ activeTab: e.currentTarget.dataset.tab })
  },

  // 「审批」页签内切换子页签：customer 客户 / station 站内
  switchApprovalTab(e) {
    this.setData({ approvalTab: e.currentTarget.dataset.tab })
  },

  // 审批 · 同意取消申请 —— 同意即走完整退款链并取消订单，不可撤销
  onApproveCancel(e) {
    const id = e.currentTarget.dataset.id
    wx.showModal({
      title: '同意取消',
      content: '同意后将取消该订单，并退还水票/押金、回补库存。此操作不可撤销。',
      confirmText: '同意取消',
      confirmColor: '#FF3B30',
      success: async (res) => {
        if (!res.confirm) return
        wx.showLoading({ title: '处理中...' })
        try {
          await approveCancelRequest(id)
          wx.hideLoading()
          wx.showToast({ title: '已同意，订单已取消', icon: 'success' })
          this.loadAllData()
        } catch (err) {
          wx.hideLoading()
          wx.showToast({ title: err.message || '操作失败', icon: 'none' })
        }
      }
    })
  },

  // 审批 · 驳回取消申请 —— 订单保持原状态，由原配送员继续履约
  onRejectCancel(e) {
    const id = e.currentTarget.dataset.id
    wx.showModal({
      title: '驳回取消',
      content: '驳回后订单保持原状态，由原配送员继续配送。',
      confirmText: '驳回',
      success: async (res) => {
        if (!res.confirm) return
        wx.showLoading({ title: '处理中...' })
        try {
          await rejectCancelRequest(id)
          wx.hideLoading()
          wx.showToast({ title: '已驳回', icon: 'none' })
          this.loadAllData()
        } catch (err) {
          wx.hideLoading()
          wx.showToast({ title: err.message || '操作失败', icon: 'none' })
        }
      }
    })
  },

  onOrderTap(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: `/pages/order/detail?id=${id}` })
  },

  /**
   * 「看全部订单 ›」→ 站长端订单台账（pages/station-mgmt/orders）。
   *
   * 这是**状态视角**的台账（全部/待配送/配送中/已完成），与本页五个页签的**动作视角**
   * （待分配/审批/抢单池/外派/他站外派）互补：一个订单可能同时在"待分配"和台账里，
   * 所以不要指望它们互斥，也别把状态页签并进本页那一排（两种维度混排会让站长分不清
   * "待配送"与"待分配"的差别）。
   *
   * ⚠️ 本页是 **tabBar 页**：`switchTab` 会把本页从页面栈里销毁，所以**只能用 navigateTo**；
   * 但来回点几次会堆栈，所以先查一眼栈里有没有它，有就 navigateBack。
   */
  onOpenOrders() {
    const pages = getCurrentPages()
    for (let i = pages.length - 1; i >= 0; i--) {
      // delta 必须算出来、不能写死 1：栈里中间可能还夹着别的页（如从台账进过订单详情）
      if (pages[i] && pages[i].route === 'pages/station-mgmt/orders/index') {
        wx.navigateBack({ delta: pages.length - 1 - i })
        return
      }
    }
    wx.navigateTo({ url: '/pages/station-mgmt/orders/index' })
  },

  onShowAssign(e) {
    this.setData({
      showAssignModal: true,
      currentOrderId: e.currentTarget.dataset.id
    })
  },

  async onConfirmAssign(e) {
    const staffId = e.currentTarget.dataset.id
    const name = e.currentTarget.dataset.name
    const orderId = this.data.currentOrderId

    // 接收站确认：他站定向外派给本站的涉押金/桶权益单，分配（= 本站受理这一单）前要再确认一次。
    // 文案来自后端；不涉风险的普通单这里什么都不会弹（_confirmRisk 拿不到文案就放行）。
    const risk = await this._confirmRisk(orderId, '已确认，分配')
    if (!risk.ok) return

    wx.showLoading({ title: '分配中...' })
    try {
      const body = { deliveryStaffId: staffId }
      // 漏传它就是"用户确认了、后端当没确认"（后端会拒），与 §8.15 静默丢字段同款
      if (risk.acknowledged) body.riskAcknowledged = true
      await post(`${API.DELIVERY_ASSIGN}/${orderId}`, body)
      wx.hideLoading()
      wx.showToast({ title: `已分配给 ${name}`, icon: 'success' })
      this.setData({ showAssignModal: false, currentOrderId: null })
      this.loadAllData()
    } catch (err) {
      wx.hideLoading()
      wx.showToast({ title: err.message || '分配失败', icon: 'none' })
    }
  },

  // 外派：从待分配tab触发，可选「放入抢单池」或「指定水站外派」
  onOutsource(e) {
    const id = e.currentTarget.dataset.id
    wx.showActionSheet({
      itemList: ['放入抢单池', '指定水站外派'],
      success: async (res) => {
        if (res.tapIndex === 0) {
          wx.showModal({
            title: '外派抢单池',
            content: '将此订单放入抢单池，附近水站可抢单配送。是否继续？',
            confirmText: '确认外派',
            success: async (m) => { if (m.confirm) await this._doOutsource(id, null) }
          })
        } else if (res.tapIndex === 1) {
          this._pickStationAndOutsource(id)
        }
      }
    })
  },

  // 指定水站外派：拉取其他营业中的水站并选择
  async _pickStationAndOutsource(id) {
    const app = getApp()
    const myStationId = (app.globalData.userInfo || {}).stationId
    wx.showLoading({ title: '加载水站...' })
    try {
      const res = await get(API.STATION_SEARCH, {})
      wx.hideLoading()
      const stationList = (res.data || []).filter(s => s.id !== myStationId && s.status === 1)
      if (stationList.length === 0) {
        wx.showModal({ title: '无可外派水站', content: '暂无其他营业中的水站可外派', showCancel: false })
        return
      }
      const itemList = stationList.map(s => s.name || ('水站' + s.id))
      wx.showActionSheet({
        itemList: itemList,
        success: async (r) => {
          const target = stationList[r.tapIndex]
          wx.showModal({
            title: '外派确认',
            content: `将订单外派给「${target.name}」配送。确定吗？`,
            confirmText: '确定外派',
            confirmColor: '#34C759',
            success: async (m) => { if (m.confirm) await this._doOutsource(id, target.id) }
          })
        }
      })
    } catch (err) {
      wx.hideLoading()
      wx.showToast({ title: err.message || '加载水站失败', icon: 'none' })
    }
  },

  async _doOutsource(id, targetStationId) {
    // 提交前把后端下发的风险提示摆给站长看，确认后才提交（押金风险文案由后端出，前端不自编）。
    // 涉押金/桶权益的单：入池会被后端直接拒（文案里写着出路），指定外派则带上这次确认。
    const risk = await this._confirmRisk(id, targetStationId != null ? '确认外派' : '仍要入池')
    if (!risk.ok) return
    wx.showLoading({ title: '外派中...' })
    try {
      const body = targetStationId != null ? { targetStationId } : {}
      if (risk.acknowledged) body.riskAcknowledged = true
      await post(`${API.DELIVERY_TRANSFER}/${id}/outsource`, body)
      wx.hideLoading()
      wx.showToast({ title: targetStationId != null ? '已指定水站外派' : '已放入抢单池', icon: 'success' })
      this.loadAllData()
    } catch (err) {
      wx.hideLoading()
      wx.showToast({ title: err.message || '外派失败', icon: 'none' })
    }
  },

  // 抢单池 - 跳过
  onSkipPool(e) {
    const id = e.currentTarget.dataset.id
    const pool = this.data.lists.pool.filter(item => item.id !== id)
    this.setData({ 'lists.pool': pool })
  },

  // 抢单池 - 抢单
  onClaimPool(e) {
    const id = e.currentTarget.dataset.id
    const colleagues = this.data.staffList || []
    if (colleagues.length === 0) {
      // [2026-09-20 真机联调] 这条链读的是**页面已加载的** staffList。原来只要它是空的就断言
      // 「暂无配送员 / 请先添加配送员」—— 但 staffList 为空有两种原因：本站确实没有配送员，
      // 或者 loadAllData 里那次 getStaffList **失败了**。后者被说成前者，站长会真的去重新添加
      // 员工（人早就在库里），把人往错误方向带（AGENTS §8.17 的判据：宁可失败出声）。
      // 所以先看 staffListError 再决定文案，并给出可执行的下一步。
      if (this.data.staffListError) {
        wx.showModal({
          title: '配送员名单没加载出来',
          content: '没能取到本站配送员名单（' + this.data.staffListError + '）。'
            + '这不代表本站没有配送员 —— 请切到「配送」页再切回来重新加载后重试。',
          showCancel: false,
          confirmText: '知道了'
        })
        return
      }
      wx.showModal({ title: '暂无配送员', content: '请先添加配送员', showCancel: false })
      return
    }
    const itemList = colleagues.map(s => s.name || ('配送员' + s.id))
    wx.showActionSheet({
      itemList: itemList,
      success: async (res) => {
        const target = colleagues[res.tapIndex]
        wx.showModal({
          title: '抢单确认',
          content: `确认抢单并分配给 ${target.name || '配送员'}？`,
          confirmText: '确认抢单',
          success: async (modalRes) => {
            if (modalRes.confirm) {
              // 押金/桶权益单在池里是**历史遗留**（新规则下入不了池）：后端会拒并回同一段文案，
              // 这里先把文案摆出来，避免"点了才被拒、还不知道为什么"。
              const risk = await this._confirmRisk(id, '确认抢单')
              if (!risk.ok) return
              wx.showLoading({ title: '抢单中...' })
              try {
                await claimPoolOrder(id, { deliveryStaffId: target.id })
                wx.hideLoading()
                wx.showToast({ title: '抢单成功', icon: 'success' })
                this.loadAllData()
              } catch (err) {
                wx.hideLoading()
                wx.showToast({ title: err.message || '抢单失败', icon: 'none' })
              }
            }
          }
        })
      }
    })
  },

  // 外派追踪 - 取消外派
  async onCancelDispatch(e) {
    const id = e.currentTarget.dataset.id
    wx.showModal({
      title: '取消外派',
      content: '确定取消此订单的外派？订单将恢复为本站待分配。',
      confirmText: '确认取消',
      confirmColor: '#FF3B30',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '取消中...' })
          try {
            await cancelDispatch(id)
            wx.hideLoading()
            wx.showToast({ title: '已取消外派', icon: 'success' })
            this.loadAllData()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '取消失败', icon: 'none' })
          }
        }
      }
    })
  },

  // 外派追踪 - 重新外派
  onReDispatch(e) {
    const id = e.currentTarget.dataset.id
    const app = getApp()
    const myStationId = (app.globalData.userInfo || {}).stationId

    let stationList = []
    get(API.STATION_SEARCH, {}).then(res => {
      stationList = (res.data || []).filter(s => s.id !== myStationId && s.status === 1)
      if (stationList.length === 0) {
        wx.showModal({ title: '无可外派水站', content: '暂无其他营业中的水站可外派', showCancel: false })
        return
      }
      const itemList = stationList.map(s => s.name || ('水站' + s.id))
      wx.showActionSheet({
        itemList: itemList,
        success: async (res) => {
          const targetStation = stationList[res.tapIndex]
          wx.showModal({
            title: '外派确认',
            content: `将订单外派给「${targetStation.name}」配送。确定吗？`,
            confirmText: '确定外派',
            confirmColor: '#34C759',
            success: async (modalRes) => {
              if (modalRes.confirm) {
                // 重新外派同样要过风险确认那一步（与 _doOutsource 同一口径）
                const risk = await this._confirmRisk(id, '确认外派')
                if (!risk.ok) return
                wx.showLoading({ title: '外派中...' })
                try {
                  const body = { targetStationId: targetStation.id, reason: '站长重新外派' }
                  if (risk.acknowledged) body.riskAcknowledged = true
                  await post(`${API.DELIVERY_ORDERS}/${id}/dispatch`, body)
                  wx.hideLoading()
                  wx.showToast({ title: '外派成功', icon: 'success' })
                  this.loadAllData()
                } catch (err) {
                  wx.hideLoading()
                  wx.showToast({ title: err.message || '外派失败', icon: 'none' })
                }
              }
            }
          })
        }
      })
    }).catch(err => {
      // [2026-09-20] 原来这条链是**全端唯一没有 .catch() 的**：拉水站列表失败 = 未处理的
      // promise rejection —— 站长点「重新外派」什么都不发生（无 loading、无弹窗、无 toast），
      // 控制台也不留线索。外派是本页的核心动作，失败必须出声（AGENTS §8.17）。
      console.error('[coordination] 重新外派拉取水站列表失败:', err)
      wx.showToast({ title: err.message || '获取水站列表失败，请重试', icon: 'none' })
    })
  },

  // 转单中 - 站长「同意」=> 变回普通待分配（可分配配送员/外派）
  async onApproveReturn(e) {
    const id = e.currentTarget.dataset.id
    const api = e.currentTarget.dataset.kind === 'directed' ? approveDirectedReturn : approveStaffReturn
    wx.showModal({
      title: '同意转单',
      content: '同意后订单将变为普通待分配状态，届时可分配配送员或外派。',
      confirmText: '同意',
      confirmColor: '#34C759',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' })
          try {
            await api(id)
            wx.hideLoading()
            wx.showToast({ title: '已同意，订单已退回待分配', icon: 'success' })
            this.loadAllData()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '操作失败', icon: 'none' })
          }
        }
      }
    })
  },

  // 转单中 - 站长「拒绝」=> 回到配送中，由原配送员继续完成配送
  async onRejectReturn(e) {
    const id = e.currentTarget.dataset.id
    const api = e.currentTarget.dataset.kind === 'directed' ? rejectDirectedReturn : rejectStaffReturn
    wx.showModal({
      title: '拒绝转单',
      content: '拒绝后订单将回到配送中，由原配送员继续完成配送。',
      confirmText: '拒绝',
      confirmColor: '#FF3B30',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' })
          try {
            await api(id)
            wx.hideLoading()
            wx.showToast({ title: '已拒绝，订单回到配送中', icon: 'none' })
            this.loadAllData()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '操作失败', icon: 'none' })
          }
        }
      }
    })
  },

  // 他站外派给我：作为目标水站，将订单「调解退回」原归属站，等待原站长同意
  async onMediateReturn(e) {
    const id = e.currentTarget.dataset.id
    wx.showModal({
      title: '调解退回原站',
      content: '将此订单退回原归属水站，由其站长决定是否重新分配。',
      confirmText: '退回原站',
      confirmColor: '#FF9500',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '退回中...' })
          try {
            await directedReturn(id)
            wx.hideLoading()
            wx.showToast({ title: '已退回原站，等待对方确认', icon: 'success' })
            this.loadAllData()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '退回失败', icon: 'none' })
          }
        }
      }
    })
  },

  onCloseModal() {
    this.setData({ showAssignModal: false, currentOrderId: null })
  },

  stopPropagation() {}
})
