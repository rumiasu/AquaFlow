const { get, post } = require('../../utils/request')
const { API } = require('../../config/api')
// 自绘底栏（tabBar.custom=true）：本页是 tab 页，onShow 必须同步一次 —— 各页组件实例独立，
// 不调就会出现"切过来了高亮还在别的页签"，而且角色是登录后才确定的（见 utils/tabbar.js）
const { syncTabBar } = require('../../utils/tabbar')
// directedReturn 必须在这里 import：本页的「退回原水站」按钮调的就是它
// （后端 javadoc 里叫「调解退回」），
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
 * 给「抢单池 / 指定外派」的订单补三个**纯展示**字段（金额与去向全部由后端下发）：
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
 * ⚠️ 必须做空值兜底：这两个页签的数据来自两个不同端点（抢单池返回 Map、指定外派返回实体），
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
 * 给订单卡补「商品」一行的文案（`goodsText`）—— [2026-09-26] 后端新增逐明细摘要投影。
 *
 * 三个判据（改这里之前逐条确认）：
 *  1. **`itemSummary` 有值就用它**（后端按 `order_item` 逐行拼：`纯净水 3桶，矿泉水 1瓶`，
 *     单位由 `product.category` 决定 —— 1 桶装水 / 2 瓶装水 / 3 饮水器，与 `util/BarrelScope` 同源）。
 *     ⚠️ 目前**只有 `GET /api/delivery/orders/station-pending` 与配送员「待接单」这两个端点下发**它，
 *     抢单池 / 外派追踪 / 转单 / 待审批这几张列表是 **null**（见 `Orders#itemSummary` 的 javadoc）。
 *  2. **`itemSummary` 为 null 时只报第一个商品名**，数量**绝不**再拿 `quantity` 乘「桶」：
 *     `quantity` 是**全单总件数**（含瓶装水 / 饮水器），混合单会显示成「纯净水 × 4桶」——
 *     那是错的（3 桶水 + 1 瓶水的 quantity 也是 4）。这就是本函数存在的唯一理由。
 *  3. `deliveryBucketQty`（配送桶数，`orders` 的真实列）**有值才**敢写「等 N 桶」；
 *     它由完成配送时写入，未配送的单是空的 —— 空就只显示商品名，不编数量。
 *
 * ⚠️ 这是纯展示映射，不做任何请求、不改变任何列表的取数口径。
 */
function goodsView(o) {
  const qty = Number(o.deliveryBucketQty)
  return {
    ...o,
    goodsText: o.itemSummary
      ? o.itemSummary
      : (qty > 0 ? o.firstProductName + ' 等 ' + qty + ' 桶' : (o.firstProductName || '—'))
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
    /**
     * 首页四个大页签（[2026-09-26] 从五个收敛：原独立的「他站外派」页签并进「外派」）。
     *
     * ⚠️ 「外派」**一个页签内置两个子页签**（见 dispatchTab）：一键外派 / 指定外派。
     * 别站指定本店为履约站，本来就是"指定外派"的一种，单开一个页签会让站长以为系统里有两套外派。
     */
    tabs: [
      { key: 'pending', label: '待分配', count: 0 },
      { key: 'approval', label: '审批', count: 0 },
      { key: 'pool', label: '抢单池', count: 0 },
      // 角标 = 一键外派 + 指定外派（两个方向）之和，由 loadAllData 算好（wxml 不做算术）
      { key: 'dispatch', label: '外派', count: 0 }
    ],
    /**
     * 「外派」页签内的子页签（[2026-09-26] 产品原话："正常外派有两种形式，
     * 一种是一键外派不用管的，一种是指定外派水站，可能往往有一些业务牵扯"）：
     *   · `pool` —— 一键外派：放进抢单池，谁抢谁送，归属站不用管（只在还没被接单前能召回）；
     *   · `directed` —— 指定外派：指定到具体水站（本站指定给别站的 + 别站指定本店的）。
     */
    dispatchTab: 'pool',
    lists: {
      pending: [],
      pool: [],
      // 本站外派出去的单，按**后端下发**的 dispatchKind 分成两份（前端不解析 specialNote）
      dispatchPool: [],
      // 指定外派一个列表装两个方向，靠 _dir 区分动作（in = 别站指定本店 / out = 本站指定给别站）
      dispatchDirected: [],
      // 待审批申请：客户发起（取消申请）/ 站内发起（退回站长、转让、重分配、取消申请）
      approvalCustomer: [],
      approvalStation: []
    },
    // 外派子页签的角标（在 js 里算好：wxml 里不做加法，也别让它读到 undefined.length）
    dispatchCount: { pool: 0, directed: 0 },
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
    // 「其他待处理」视图模型（只含不在本页页签里的项），由 loadTodo() 组装
    todo: null
  },

  /**
   * 「其他待处理」**收录哪些 key** —— 这是首页顶部唯一需要维护的清单。
   *
   * [2026-09-19 收敛，当天两次修订] 判据只有一条：**本页 tab 覆盖不到、且没有立刻可见的计数**。
   * ⚠️ 修订前先**把整张卡删光了**，产品随即质疑"都是那种重复的吗" —— 不是：15 项里只有 7 项
   * 真重复。别再删整卡（wxml 里那段注释记着同一件事）：
   *   · **剔除**（本页页签已有角标 / 宫格已有卡）：pendingAssign · pendingTransfer · customerCancel ·
   *     stationCancel · poolClaimable · directedIncoming · barrelReturn；
   *   · **保留**（只活在「水站管理」里，首页不报就没人知道）：下面这 8 项。
   * ⚠️ [2026-09-26 Wave1 轨道 G] 本清单与 `decorateTodo` 的"**0 也保留**"口径**都没动**：
   * 「卡在零计数时是否仍显示」是已登记的产品冲突（docs/design/29 §9 待拍板第 1 条），未拍板前不改代码。
   *
   * ⚠️ 被剔除的 7 项里 **6 项是 P0**（待分配/转单/客户取消/站内取消/指定外派待确认/退桶审批，
   * 只有「抢单池」是 P2）—— 按级别它们"应该"在首页。之所以仍剔除：它们各自的页签/页面上一眼
   * 就能看到数，在这里再报一遍正是本次要消除的那种重复。**P0 并没有丢**：tab 红点算的是完整
   * payload 的 p0Total（这 6 项全在内），只是换成"一个红点"而不是"6 个数字"。
   * [2026-09-26] 「指定外派待确认」（key 仍是 directedIncoming，后端标签已改）现在落在
   * 「外派 → 指定外派」子页签的角标里 —— 角标按两个方向之和算，P0 照样一眼能看到。
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
   * 拉待办汇总，两件事：① 组装「其他待处理」卡；② 刷新首页 tab 红点。
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
   * 待办数据 → 「其他待处理」视图模型。
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
   * ⚠️ **confirmText 必须 ≤ 4 个字符**（2026-09-27 实测事故）：微信 `wx.showModal` 的
   * confirmText / cancelText 超过 4 字时**既不弹窗、也不走 fail 回调** —— 本方法等的是一个
   * 永不 resolve 的 Promise，于是调用方（点配送员 / 抢单 / 外派）**点击后毫无反应**，
   * 连一句"分配失败"的 toast 都不会出现。当时「分配」传的是 6 个字的 `'已确认，分配'`。
   * 这里按上限硬截断：文案短一点，总比"点了没反应"可诊断。**别把这段护栏删掉。**
   * （同一约束的静态扫描见 tests/js/modal-copy-limit.test.js；
   * 真流程用例见 tests/js/coordination-assign-flow.test.js。）
   *
   * @returns {{ok: boolean, acknowledged: boolean}} ok=false 表示用户点了取消，
   *          调用方必须直接返回、**不要提交**；acknowledged=true 表示本次提交要带
   *          riskAcknowledged=true（后端只对涉押金/桶权益的单校验这个字段，
   *          普通单不看不加摩擦）。
   */
  async _confirmRisk(orderId, confirmText) {
    const risk = await fetchCrossStationRisk(orderId)
    if (!risk.note) return { ok: true, acknowledged: false }
    const MAX_MODAL_BTN = 4
    let confirmLabel = confirmText || '确认'
    if ([...confirmLabel].length > MAX_MODAL_BTN) {
      console.warn('[coordination] showModal 的 confirmText「' + confirmLabel
        + '」超过平台上限 ' + MAX_MODAL_BTN + ' 字，已截断 —— 超长时微信既不弹窗也不报错')
      confirmLabel = [...confirmLabel].slice(0, MAX_MODAL_BTN).join('')
    }
    const res = await new Promise((resolve) => {
      wx.showModal({
        title: '押金/桶权益风险',
        content: risk.note,
        confirmText: confirmLabel,
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
    // 自绘底栏：站长看到 首页/配送/我的，配送员只看到 配送/我的（本页对配送员不显示）
    syncTabBar(this, '/pages/coordination/index')
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    if (this.checkRole()) {
      // 营业状态（软状态 v32：只提示不阻断）：与「配送」页共用实现，见 behaviors/stationNavbar.js
      this.loadStationStatus((app.globalData.userInfo || {}).stationId)
      // 待填项徽标 + 「条件项刚成立」提醒（见 behaviors/stationNavbar.js；
      // 非站长会直接 return，不发请求 —— setup-guide 是站长专属接口）
      this.loadStationPending()
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
      // 一个都不落地，而页面上四个页签照旧渲染成「暂无待分配 / 暂无抢单池…」—— 与"确实没有单"
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
      const dispatchRes = unwrap(settled[3], '外派')
      const incomingRes = unwrap(settled[4], '指定外派（别站指定本店）')
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
      // 信用标（riskView）只加在这里：抢单池 / 指定外派是**跨站可见面**，
      // "这个客户欠多少钱"是归属站的经营信息，后端也不下发（见 DeliveryController）。
      const pendingList = [
        ...(pendingRes.data || []),
        ...(transferRes.data || [])
      ].map(o => {
        const note = o.specialNote || o.special_note || ''
        let transferKind = ''
        if (note.indexOf(DIRECTED_MARK) >= 0) transferKind = 'directed'
        else if (STAFF_MARKS.some(m => note.indexOf(m) >= 0)) transferKind = 'staff'
        // goodsView：本页签是两个端点合并的 —— station-pending 有 itemSummary，转单那份没有，
        // 所以逐行判空（见 goodsView 注释，别在这里假设一定有摘要）
        return goodsView(riskView({ ...o, transferPending: transferKind !== '', transferKind }))
      })

      // ===== 外派：一键外派 / 指定外派 =====
      // 形态由**后端**下发（`dispatchKind`：POOL / DIRECTED，判据是备注，见 constant/DispatchKind）——
      // 前端**不解析 specialNote**：同一段自由文本两端各判一次，迟早分叉（本仓"计价双轨"的同形问题）。
      const dispatchRows = dispatchRes.data || []
      // ⚠️ 这个列表是"**曾经**外派过的单"的台账（后端不按当前状态筛），所以召回回来的单也留在里面。
      // 召回后 delivery_station_id 又变回本站 —— 那种行**不能说成"在抢单池里"**（它已回到本站待分配）。
      // 两个纯展示标记在这里一次算好（wxml 不写比较逻辑）：
      //   `_inPool`   = 还没人接（delivery_station_id 为空）→ 在池中等别站抢
      //   `_backHome` = 履约站已经是本站 → 已召回/已退回，本站待分配
      const dispatchPool = dispatchRows
        .filter(o => o.dispatchKind === 'POOL')
        .map(o => goodsView({
          ...o,
          _inPool: o.deliveryStationId == null,
          _backHome: o.deliveryStationId != null && String(o.deliveryStationId) === String(stationId)
        }))
      // 除 POOL 之外一律归「指定外派」：**与后端 DispatchKind.ofNote 的兜底同向**
      //（认不出的备注默认判成"指定外派"—— 需要站长盯着的那一栏，比静默藏进"不用管"安全），
      // 顺带让"老版本后端没下发 dispatchKind"时单子仍全部可见（只是都落在指定外派里）。
      const dispatchDirectedOut = dispatchRows
        .filter(o => o.dispatchKind !== 'POOL')
        .map(o => goodsView({ ...o, _dir: 'out' }))   // 本站指定给别站：可召回 / 重新指定
      // 「指定外派」子页签 = 两个方向合成一个列表，靠 _dir 区分动作与标签：
      //   in  = 别站指定本店（可退回原水站，钱与去向整块由后端下发）
      //   out = 本站指定给别站
      // 别站指定本店的那些排前面：它们等本站回话（P0），本站派出去的只需盯着。
      const dispatchDirected = [
        ...(incomingRes.data || []).map(o => goodsView({ ...feeView(o), _dir: 'in' })),
        ...dispatchDirectedOut
      ]

      const approvalData = approvalsRes.data || {}
      const lists = {
        pending: pendingList,
        // 「抢单池」与「指定外派」是同一件事的两个入口（放池谁都能抢 / 指定派给某个站），
        // 两个页签都必须在动手前看到"这单值多少钱、价是谁定的、钱归谁" —— 同一份映射，不各写一遍。
        // goodsView 同理：商品行全页签同一份文案（这几个端点都没有 itemSummary，见其注释）
        pool: (poolRes.data || []).map(feeView).map(goodsView),
        dispatchPool,
        dispatchDirected,
        approvalCustomer: (approvalData.customer || []).map(goodsView),
        approvalStation: (approvalData.station || []).map(goodsView)
      }

      const tabs = this.data.tabs.map(t => ({
        ...t,
        // 「审批」角标 = 客户 + 站内 两组待审批之和；
        // 「外派」角标 = 两个子页签之和（一键外派 + 指定外派两个方向）——
        // P0 的"指定外派待确认"就在这个数里，别改成只看某一个子页签。
        count: t.key === 'approval'
          ? lists.approvalCustomer.length + lists.approvalStation.length
          : (t.key === 'dispatch'
            ? lists.dispatchPool.length + lists.dispatchDirected.length
            : (lists[t.key] || []).length)
      }))
      const dispatchCount = {
        pool: lists.dispatchPool.length,
        directed: lists.dispatchDirected.length
      }

      this.setData({
        lists,
        tabs,
        dispatchCount,
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

  // 「外派」页签内切换子页签：pool 一键外派（放抢单池）/ directed 指定外派
  switchDispatchTab(e) {
    this.setData({ dispatchTab: e.currentTarget.dataset.tab })
  },

  // 审批 · 同意取消申请 —— 同意即走完整退款链并取消订单，不可撤销
  onApproveCancel(e) {
    const id = e.currentTarget.dataset.id
    wx.showModal({
      title: '同意取消',
      content: '同意后将取消该订单，并退还水票/押金、回补库存。此操作不可撤销。',
      confirmText: '同意取消',
      confirmColor: '#B5442C',
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
   * 这是**状态视角**的台账（全部/待配送/配送中/已完成），与本页页签的**动作视角**
   * （待分配/审批/抢单池/外派[一键外派·指定外派]）互补：一个订单可能同时在"待分配"和台账里，
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

  /**
   * 常驻「水站管理」入口（wxml 里紧跟导航栏的那一条）→ 站长端宫格（完整功能目录）。
   *
   * ⚠️ 它是本页**唯一不受服务端事项影响**的入口：**不要**给它接任何计数、也不要在 wxml 里
   * 加 `wx:if` —— 提醒区（「其他待处理」卡 / 页签角标）取不到数或全为零时它也必须照旧在，
   * 否则站长一遇到加载失败就再也进不去完整功能（docs/design/29 §5「完整功能入口另行常驻」）。
   * 宫格是**非 tabBar 页**，只能用 navigateTo（`switchTab` 只认 app.json 里的 tab 页）。
   */
  onGoStationMgmt() {
    wx.navigateTo({ url: '/pages/station-mgmt/index' })
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

    // 接收站确认：别站**指定外派**给本站的涉押金/桶权益单，分配（= 本站受理这一单）前要再确认一次。
    // 文案来自后端；不涉风险的普通单这里什么都不会弹（_confirmRisk 拿不到文案就放行）。
    // ⚠️ confirmText 上限 4 字：这里原先是 6 个字的「已确认，分配」⇒ 真机上弹窗被平台丢弃、
    //    Promise 永不 resolve，点配送员**完全没反应**（2026-09-27 实测）。别再加长。
    const risk = await this._confirmRisk(orderId, '确认分配')
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

  /**
   * 外派：从「待分配」页签触发，两个选项就是「外派」页签里的两个子页签
   * （[2026-09-26] 统一叫法：一键外派 = 放进抢单池 / 指定外派 = 指定水站）。
   */
  onOutsource(e) {
    const id = e.currentTarget.dataset.id
    wx.showActionSheet({
      itemList: ['一键外派（放抢单池）', '指定外派（选水站）'],
      success: async (res) => {
        if (res.tapIndex === 0) {
          wx.showModal({
            title: '一键外派',
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

  // 指定外派：拉取其他营业中的水站并选择
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
            title: '指定外派确认',
            content: `将订单指定外派给「${target.name}」配送。确定吗？`,
            confirmText: '确定外派',
            confirmColor: '#2E9E6B',
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
      wx.showToast({ title: targetStationId != null ? '已指定外派' : '已放入抢单池', icon: 'success' })
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

  // 外派页签 - 取消外派（召回）。一键外派 / 指定外派两种形态共用：
  // 后端只收「待配送(1)」—— 被接单站接单之后这单归接单站管，本站不能再召回。
  async onCancelDispatch(e) {
    const id = e.currentTarget.dataset.id
    wx.showModal({
      title: '取消外派',
      content: '确定取消此订单的外派？订单将恢复为本站待分配。',
      confirmText: '确认取消',
      confirmColor: '#B5442C',
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

  // 外派页签 - 重新外派（改指定别的站；一键外派的单也可以直接改成指定外派）
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
            title: '指定外派确认',
            content: `将订单指定外派给「${targetStation.name}」配送。确定吗？`,
            confirmText: '确定外派',
            confirmColor: '#2E9E6B',
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
      confirmColor: '#2E9E6B',
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
      confirmColor: '#B5442C',
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

  /**
   * 指定外派（别站指定本店）：作为目标水站，把这单退回原归属站（后端叫「调解退回」，
   * 只打「指定退回待确认」标记），等原站长同意 / 拒绝。
   *
   * [2026-09-26 文案] 按钮与弹窗原文「调解退回原站」——「调解」是后端 javadoc 与旧文档的词，
   * 站长看不懂（docs/design/29 §7）。**只改文案**：动作、端点、判据全不变，
   * 仍是 POST /api/orders/{id}/directed-return（`api/delivery.js` 的 `directedReturn`）。
   * ⚠️ `confirmText` 必须 ≤ 4 字（超了真机上既不弹窗也不报错，见 tests/js/modal-copy-limit.test.js）。
   */
  async onMediateReturn(e) {
    const id = e.currentTarget.dataset.id
    wx.showModal({
      title: '退回原水站',
      content: '本单由别站指定给本站配送。退回后等原水站站长决定：他同意就由原水站重新安排配送，不同意则仍由本站继续配送。',
      confirmText: '确定退回',
      confirmColor: '#C9764B',
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
