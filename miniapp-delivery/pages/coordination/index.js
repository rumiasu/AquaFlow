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

// ⚠️ 直接写路径常量、不往 config/api.js 里加：那个文件正被另一个工作流（商品图片库）改动，
// 共用会让两边未提交的改动纠缠在一起（与站长端各新页同一处理）。
const TODO_SUMMARY = '/api/manager/todo-summary'

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
    // 待办聚合（站长首页）：由 /api/manager/todo-summary 下发，前端不自己算
    todo: null
  },

  /**
   * 待办项 key → 页面。key 是服务端给的稳定标识，label 由服务端下发；
   * **这里只做路由**（后端不认识小程序路径），所以它不是"前端自带映射表"。
   */
  TODO_ROUTES: {
    overdueReceivable: '/pages/station-mgmt/receivables/index',
    costNotFilled: '/pages/station-mgmt/gross-profit/index',
    draftPayroll: '/pages/station-mgmt/payroll/index',
    pendingBarrelException: '/pages/station-mgmt/barrel-exceptions/index'
  },

  /**
   * 拉待办聚合。故意**不阻塞**主列表：它挂了也只是少一张卡片，
   * 但失败必须出声（静默失败会让站长以为"今天没事"）。
   */
  async loadTodo() {
    const app = getApp()
    const stationId = (app.globalData.userInfo || {}).stationId
    if (!stationId) return
    try {
      const res = await get(TODO_SUMMARY)
      const d = res.data || {}
      const items = (d.items || []).map(it => Object.assign({}, it, {
        // 金额只有"逾期应收"那一项有；格式化放在这里做（wxml 不能调方法）
        amountText: it.amount === null || it.amount === undefined ? '' : Number(it.amount).toFixed(2)
      }))
      this.setData({ todo: Object.assign({}, d, { items }) })
    } catch (err) {
      console.warn('[todo-summary] 加载失败:', err && err.message)
      wx.showToast({ title: (err && err.message) || '待办加载失败', icon: 'none' })
    }
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

      // 并行加载各 tab 数据
      // 待分配 = station-pending（未分配的）+ station-transfer（转单请求，合并进来）
      const [pendingRes, transferRes, poolRes, dispatchRes, incomingRes, approvalsRes] = await Promise.all([
        get(API.DELIVERY_ORDERS + '/station-pending'),
        get(API.DELIVERY_ORDERS + '/station-transfer'),
        getPoolOrders(),
        getDispatchTracking(),
        getDirectedIncoming(),
        getPendingApprovals()
      ])

      let staffList = []
      if (stationId) {
        try {
          const staffRes = await getStaffList(stationId)
          staffList = staffRes.data || []
        } catch (e) { console.error('加载配送员失败:', e) }
      }

      // 待分配：合并未分配 + 转单请求（含「转单中」订单）
      // 状态检测：special_note 带 [指定退回待确认] => 转单中，前端渲染「同意/拒绝」而非「分配/外派」
      const pendingList = [
        ...(pendingRes.data || []),
        ...(transferRes.data || [])
      ].map(o => {
        const note = o.specialNote || o.special_note || ''
        let transferKind = ''
        if (note.indexOf(DIRECTED_MARK) >= 0) transferKind = 'directed'
        else if (STAFF_MARKS.some(m => note.indexOf(m) >= 0)) transferKind = 'staff'
        return { ...o, transferPending: transferKind !== '', transferKind }
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

      this.setData({ lists, tabs, staffList })
      wx.hideLoading()
    } catch (err) {
      console.error('加载数据失败:', err)
      wx.hideLoading()
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
