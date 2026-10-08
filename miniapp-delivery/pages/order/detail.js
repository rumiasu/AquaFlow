// 订单详情页
const { getOrderDetail, completeOrder, transferOrder, cancelTransferOrder, returnToStation, getStaffList, dispatchOrder, resolveOrder, requestCancel, confirmCollection } = require('../../api/delivery')
// 配送异常上报（原因文案 + 上报实现）：与首页「配送遇到问题」共用一份，见 utils/delivery-problem.js
const { reportDeliveryProblem } = require('../../utils/delivery-problem')
const businessRules = require('../../api/business-rules')
// ⚠️ 楼梯凭证（v43；原名"楼层凭证"，[2026-09-26] 改名）用 utils/request 直接调：路径写常量、不往 api/ 或 config/api.js 加
// —— 那两个文件正被另一个工作流（商品图片库）改动。
const { get, put, post } = require('../../utils/request')
const ORDER_IMAGE_BY_ORDER = '/api/order-images/by-order'
// 支付流水（2026-09-18 接线）：GET 按订单查流水（后端 PaymentController.listByOrderId），
// PUT 单笔退款（后端 PaymentController.refund，站长专属）。
// 与上面的 ORDER_IMAGE_BY_ORDER 同一惯例：常量写在页面 js 顶部，不加进 config/api.js。
const PAYMENTS_BY_ORDER = '/api/payments/by-order'
const PAYMENT_REFUND = '/api/payments/'
// 拒付结案（v60 接线）：手工发起异常单 → 核销认损。
// 与上面同一惯例：常量写页面顶部。`?orderId=` 是**查询参数**（后端 @RequestParam）。
const MANAGER_EXCEPTIONS = '/api/manager/exceptions'
// 楼层/电梯文案：与配送任务列表共用同一份实现（口径只有一处）
const { buildFloorText } = require('../../utils/address')
const { itemUnit, withItemUnits } = require('../../utils/order-item-view')

/**
 * 备货情况文案（契约 C4）：后端 `stockPrep` 投影 → 一句话。
 * 判据 = 凭据上的需求快照 − 已预留（不是 `inventory.quantity`）；这里**只展示**，
 * 真正拦住"少扣一点先把单结了"的是完成配送时那次出库校验（提示可能过期）。
 */
function buildStockPrepText(prep, orderItems = []) {
  if (!prep) return ''
  if (prep.ready === true) return '已备齐'
  const parts = []
  ;(prep.items || []).forEach((it) => {
    const matched = it.productId != null && orderItems.find(row => String(row.productId) === String(it.productId))
    parts.push(`${it.productName || '商品'} 还缺 ${it.shortage} ${itemUnit(matched || it)}`)
  })
  if (prep.itemsWithoutCredential > 0) parts.push('有商品还没登记备货')
  return parts.length ? ('还缺：' + parts.join('、')) : ''
}

/**
 * 「回桶」这一行的标签与数值。
 *
 * ⚠️ [2026-09-26] 这里以前读的是 `order.expectedReturnBarrels` —— **后端从来没有这个字段**
 * （全仓 grep 零命中），于是"预计回桶"永远渲染 `0个`：续购单看着像"不用回桶"。
 * 现改用真实字段，三种情形分别是：
 *   · **首单**（`firstBarrelOrder`，与完成配送页同一判据）：压根没有旧桶可回 —— 只说"无需回桶"、
 *     不给数字。产品原话：「第一次送达桶确实不需要回收，把第一次桶送达时的默认回桶值取消掉」。
 *   · **还没送到**：给的是**默认回收数**，逐条明细取后端算好的 `suggestedReturnQty`
 *     （= 客户手上已有的旧桶；**本单新买押金的桶不算**，见下面 buildReturnPlan 的注释），
 *     与完成配送页默认填的那个数是同一个口径。它是默认值、不是规则，实际收回多少由配送员填。
 *   · **已送达 / 已完成**：`returnBucketQty` 这时已被完成配送写成**实际回收数**，再叫"预计"就错了
 *     —— 改成"已回桶"，差量见桶异常单。
 */
function buildReturnPlan(order) {
  if (order.firstBarrelOrder === true) {
    return { returnLabel: '回桶', returnValue: '押金桶 · 无需回桶' }
  }
  const delivered = order.status === 3 || order.status === 4
  if (delivered) {
    const actual = Number(order.returnBucketQty) || 0
    return { returnLabel: '已回桶', returnValue: actual + ' 个' }
  }
  // 逐条累加后端的默认回收数（`barrelItem` 由后端按 util/BarrelScope 下发）。
  // ⚠️ 不能用 `deliveryBucketQty`（本单送出总桶数）：本单**新买押金**的那几个不回收，
  // 混合单（旧桶换水 + 新买押金桶）用送出数会多报 —— 产品口径：「新付押金买的桶不需要计入回收，
  // 但是非本次订单产生押金的桶则默认计入回收」。
  const expected = (order.items || [])
    .filter(it => it.barrelItem === true)
    .reduce((sum, it) => sum + (Number(it.suggestedReturnQty) || 0), 0)
  // 没有旧桶可回（首单之外：瓶装水单、客户手上没桶）→ 没有"回桶"这回事，整行留空由 wxml 隐藏
  if (expected <= 0) return { returnLabel: '', returnValue: '' }
  return { returnLabel: '预计回桶', returnValue: expected + ' 个' }
}

// 纯展示用：订单状态数字 → 徽章 CSS class（仅控制颜色，不承载业务逻辑）
const STATUS_CLASS_MAP = {
  1: 'pending',     // 待配送
  2: 'delivering',  // 配送中
  3: 'delivered',   // 已送达
  4: 'completed',   // 已完成
  5: 'cancelled'    // 已取消
}

// ISO-8601 → 「MM-DD HH:mm」。
// ⚠️ 这是**纯展示切分**，不是时间计算，也绝不能写成 `new Date(str.replace(/-/g,'/'))`：
// 仓库明令禁止那种写法（AGENTS.md §8.7，时间一律按 ISO 解析）。
// 解析失败就原样返回，不编造时间。
function formatTime(v) {
  if (!v || typeof v !== 'string') return ''
  const parts = v.split('T')
  if (parts.length < 2) return v
  const time = parts[1].slice(0, 5)
  const date = parts[0].length >= 10 ? parts[0].slice(5, 10) : parts[0]
  return date + ' ' + time
}

// 支付状态「已付款」= 2（PaymentStatus.PAID）。
// 只做**一个等值判断**（决定要不要出现「退款」按钮），不是映射表 ——
// 文案一律渲染后端下发的 statusText / methodText，前端不维护状态字典。
const PAY_STATUS_PAID = 2
const { orderActions } = require('../../utils/order-actions')

// 2026-10-07：读取只认当前页面/订单/登录身份；正常续期换 token 不视为换人。
function staffSession() {
  const app = getApp(), g = app.globalData || {}, u = g.userInfo || {}
  return { app, generation: app._loginGeneration || 0,
    key: JSON.stringify([!!g.accessToken, g.isLogin, u.staffId, u.role, u.stationId, u.bindStatus]) }
}
const MORE_ACTIONS = {
  onReport: 'canReport', onTransfer: 'canTransfer', onCancelTransfer: 'canWithdrawTransfer',
  onReturnToStation: 'canReturn', onRequestCancel: 'canRequestCancel'
}
// 与现有 confirm-offline-pay 的角色、履约站、自身分配、现金及状态守卫一致。
function canCollect(order, user = {}) {
  const same = (a, b) => a != null && b != null && String(a) === String(b)
  const manager = user.role === 'STATION_MANAGER' || user.role === 'manager'
  const delivery = user.role === 'DELIVERY' || user.role === 'delivery'
  const station = order.deliveryStationId == null ? order.stationId : order.deliveryStationId
  return !!(order.needCollect && order.paymentMethod === 2
    && (order.paymentStatus === 0 || order.paymentStatus === 1)
    && (order.status === 2 || order.status === 3) && same(station, user.stationId)
    && (manager || (delivery && same(order.deliveryStaffId, user.staffId))))
}

Page({
  data: {
    orderId: null,
    order: {},
    loading: true,
    detailError: '',
    detailOutcomeText: '',
    writePending: false,
    // 楼梯凭证（v43）：站长与配送员都能看、都能补传；不强制，所以"没有也不拦"
    floorPhotos: [],
    floorUploading: false,
    /**
     * 送达凭证（[2026-09-26] 原名「签收凭证」）：配送员在完成配送页拍的 `order_image.type = 1`
     * 那几张。**只读**（站长要看"到底送到了没有"，不该由站长替配送员补拍送达现场）。
     */
    deliveryPhotos: [],
    /** 已送达/已完成但没有送达照片时，给一句实话（判据在 loadOrderDetail 里算，见那里的注释） */
    showNoDeliveryProofTip: false,
    // 支付流水（2026-09-18）：只对站长展示（后端端点本身也是 STATION_MANAGER 专属）
    payments: [],
    canRefundPayment: false,
    // 店员角色（决定要不要给「客户拒付」入口；后端端点本身是 STATION_MANAGER 专属，前端只是别画出来）
    isManager: false,
    refusalBusy: false,
    // 撤回转单（2026-09-29 清单2）：防连点，文案在按钮上换「撤回中…」
    cancelTransferBusy: false,
    // 更多操作弹层（2026-10-07 #11，docs/design/36 §3）：纯 UI 状态。
    // hasMoreActions 在 loadOrderDetail 里按既有 can* 判据算好 —— 判据一处（utils/order-actions），
    // 这里只是"有没有任何一个低频动作可显示"，不产生新业务判断。
    showMoreActions: false,
    actionBusy: false,
    hasMoreActions: false
  },

  onLoad(options) {
    if (options.id) {
      this.setData({ orderId: options.id })
    }
  },

  onShow() {
    if (this._unloaded) return
    this._hidden = false
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    // 角色判定与其它站长页同源（coordination 的 checkRole）
    const role = (app.globalData.userInfo || {}).role || ''
    this.setData({ isManager: role === 'STATION_MANAGER' || role === 'manager' })
    // 从完成配送页返回时刷新
    if (this.data.orderId) {
      return this.loadOrderDetail(this.data.orderId)
    }
  },

  // 原生返回离页走 onUnload；进入其他页/后台走 onHide，统一关闭弹层并失效旧回调。
  onHide() {
    this._hidden = true
    this._lifeVersion = (this._lifeVersion || 0) + 1
    this.setData({ showMoreActions: false, actionBusy: false, cancelTransferBusy: false, refusalBusy: false, writePending: false })
    ;(this._dialogs || new Set()).forEach(cancel => cancel())
    this._dialogs = new Set()
    this._actionTask = null
    if (this._loadingTask) { wx.hideLoading(); this._loadingTask = null }
  },

  onUnload() {
    this.onHide()
    this._unloaded = true
  },

  _context(id = this.data.orderId) {
    return { id, life: this._lifeVersion || 0, session: staffSession() }
  },

  _isCurrent(ctx) {
    if (!ctx || this._hidden || this._unloaded || ctx.life !== (this._lifeVersion || 0)
      || String(ctx.id) !== String(this.data.orderId)) return false
    const now = staffSession()
    return ctx.session.app === now.app && ctx.session.generation === now.generation
      && ctx.session.key === now.key && now.app.canAccessStationBusiness()
      && (ctx.readVersion == null || ctx.readVersion === (this._detailVersion || 0))
  },

  // 页面失效只取消 UI 回调；已经发出的请求须等响应，不能在返回页面后再次提交。
  _sameOrderSession(ctx) {
    if (!ctx || String(ctx.id) !== String(this.data.orderId)) return false
    const now = staffSession()
    return ctx.session.app === now.app && ctx.session.generation === now.generation
      && ctx.session.key === now.key && now.app.canAccessStationBusiness()
  },

  _pendingWrite() {
    return Array.from(this._pendingWrites || []).find(ctx => this._sameOrderSession(ctx))
  },

  _syncWriteBusy() {
    const pending = this._pendingWrite()
    this.setData({ writePending: !!pending, actionBusy: !!pending || !!this._actionTask,
      cancelTransferBusy: !!pending && pending.actionName === 'onCancelTransfer',
      refusalBusy: !!pending && pending.actionName === 'onRefusalWriteOff' })
  },

  _blockPendingWrite() {
    if (!this._pendingWrite()) return false
    wx.showToast({ title: '操作仍在处理中，请稍候', icon: 'none' })
    return true
  },

  _actionAllowed(name) {
    const order = this.data.order || {}, user = getApp().globalData.userInfo || {}
    if (MORE_ACTIONS[name]) return !!orderActions(order, user)[MORE_ACTIONS[name]]
    if (name === 'onConfirmCollection') return canCollect(order, user)
    if (name === 'onAcceptTransfer' || name === 'onRejectTransfer') return !!orderActions(order, user).canAcceptTransfer
    return name === 'onRefusalWriteOff' && (user.role === 'STATION_MANAGER' || user.role === 'manager')
      && order.status === 3 && !!order.needCollect
  },

  async _runOrderAction(name, work) {
    if (this._hidden || this._unloaded) return
    if (this._blockPendingWrite()) return
    if (this.data.loading || this.data.detailError || this._actionTask || !this._actionAllowed(name)
      || (this._orderContext && !this._isCurrent(this._orderContext))) return
    const ctx = this._context()
    ctx.actionName = name
    ctx.actionVersion = this._actionVersion = (this._actionVersion || 0) + 1
    ctx.current = () => this._isCurrent(ctx) && this._actionAllowed(name)
    this._actionTask = ctx
    this.setData({ showMoreActions: false, actionBusy: true })
    try { await work(ctx) } catch (err) {
      if (this._isCurrent(ctx)) wx.showToast({ title: err.message || '操作失败，请重试', icon: 'none' })
    } finally {
      if (this._actionTask === ctx) {
        this._actionTask = null
        if (this._isCurrent(ctx)) this.setData({ actionBusy: false, cancelTransferBusy: false, refusalBusy: false })
      }
    }
  },

  _actionDialog(ctx, options, sheet = false) {
    if (!ctx.current()) return Promise.resolve({ confirm: false })
    return new Promise(resolve => {
      const pending = this._dialogs || (this._dialogs = new Set())
      let settled = false
      const finish = result => {
        if (settled) return
        settled = true; pending.delete(cancel)
        resolve(ctx.current() ? result : { confirm: false })
      }
      const cancel = () => finish({ confirm: false })
      pending.add(cancel)
      try { wx[sheet ? 'showActionSheet' : 'showModal']({ ...options, success: finish, fail: cancel }) }
      catch (err) { cancel(); throw err }
    })
  },

  async _actionWrite(ctx, write) {
    if (!ctx.current() || this._blockPendingWrite()) return false
    const pending = this._pendingWrites || (this._pendingWrites = new Set())
    pending.add(ctx)
    this._syncWriteBusy()
    this._loadingTask = ctx
    wx.showLoading({ title: '处理中…', mask: true })
    let failure
    try {
      await write()
      if (ctx.actionName === 'onCancelTransfer') this._withdrawOutcome = ctx
      return this._isCurrent(ctx)
    } catch (err) {
      failure = err
      throw err
    } finally {
      pending.delete(ctx)
      if (this._loadingTask === ctx) { wx.hideLoading(); this._loadingTask = null }
      if (!this._hidden && !this._unloaded && this._sameOrderSession(ctx)) {
        this._syncWriteBusy()
        // 已返回同一订单时，由新页面读取最终结果，旧 handler 不再继续弹窗或导航。
        if (!this._isCurrent(ctx)) {
          await this.loadOrderDetail(ctx.id)
          if (failure && !this._hidden && !this._unloaded && this._sameOrderSession(ctx)) {
            wx.showToast({ title: failure.message || '操作失败，请重试', icon: 'none' })
          }
        }
      }
    }
  },

  _actionBack(ctx) {
    setTimeout(() => { if (this._isCurrent(ctx) && ctx.actionVersion === this._actionVersion) wx.navigateBack() }, 1500)
  },

  /**
   * 【v60 接线】客户拒付 → 手工发起异常单 → 核销认损（**两步确认**）。
   *
   * <p>为什么在订单详情页做：拒付是**对着某一张单**发生的（已送达(3) + 待收款(1)），
   * 而「异常订单」页只能看到**已经存在**的异常 —— 它没法凭空知道你指的是哪张单。</p>
   *
   * <p>⚠️ 为什么分两步：第一步只**建单**（把事情记下来，可反悔），第二步才**核销**
   * ——历史核销会应收出账 / 撤销该单权益 / 等量记客户欠桶，且**不可撤销**；
   * 新凭据路径仅确认信用风险，保留欠款与独立资产（independentBusinessRules）。
   * 合成一步就是"点错一下钱和桶账一起动了"。这与响应里 `WRITE_OFF` 是终态、
   * 重复点会报错（而不是幂等跳过）是同一套口径。</p>
   */
  onRefusalWriteOff() {
    return this._runOrderAction('onRefusalWriteOff', async ctx => {
      const independent = this.data.order.independentBusinessRules
      const first = await this._actionDialog(ctx, {
        title: '客户拒付', content: independent ? '第 1 步：登记客户收货后拒付的异常证据，随后由站长确认风险。' : '登记客户拒付异常，随后由站长确认结案。', confirmText: '记异常'
      })
      if (!first.confirm || !ctx.current()) return
      this.setData({ refusalBusy: true })
      let response
      if (!await this._actionWrite(ctx, async () => { response = await post(MANAGER_EXCEPTIONS + '?orderId=' + ctx.id, { category: 'CUSTOMER_REFUSE', staffNote: '客户拒付' }) })) return
      const exId = response && response.data && response.data.id
      if (!exId) {
        await this._actionDialog(ctx, { title: '异常单没有返回编号', content: '请到「异常订单」页确认这条异常是否记上了。', showCancel: false })
        return
      }
      const second = await this._actionDialog(ctx, {
        title: independent ? '确认拒付风险' : '核销认损',
        content: independent ? '欠款继续保留并追收；关闭线下付款，支付新单前须补款；暂停退押金，不扣押金、不撤桶权益。跨站押金冻结另由归属站核实。' : '确认后应收出账、撤销未归还桶权益并等量记欠桶。请核实本次实际损失。',
        confirmText: '确认处理', confirmColor: '#B5442C', cancelText: '先不核销'
      })
      if (!ctx.current()) return
      if (!second.confirm) {
        wx.showToast({ title: '已记异常，未核销', icon: 'none' })
        await this.loadOrderDetail(ctx.id); return
      }
      if (!await this._actionWrite(ctx, () => post(MANAGER_EXCEPTIONS + '/' + exId + '/write-off', {
        managerNote: independent ? '客户拒付，站长确认信用风险；欠款继续追收' : '客户拒付，站长核销认损'
      }))) return
      wx.showToast({ title: '已登记处理', icon: 'success' })
      await this.loadOrderDetail(ctx.id)
    })
  },

  // 加载订单详情
  async loadOrderDetail(id) {
    if (this._hidden || this._unloaded) return
    if (this.data.orderId == null) this.setData({ orderId: id })
    const ctx = this._context(id)
    ctx.readVersion = this._detailVersion = (this._detailVersion || 0) + 1
    if (!this._isCurrent(ctx)) return
    if (this._actionTask && !this._isCurrent(this._actionTask)) {
      ;(this._dialogs || new Set()).forEach(cancel => cancel())
      this._actionTask = null
      if (this._loadingTask) { wx.hideLoading(); this._loadingTask = null }
      this.setData({ actionBusy: false, cancelTransferBusy: false, refusalBusy: false })
    }
    this._syncWriteBusy()
    if (!this._sameOrderSession(this._withdrawOutcome)) this._withdrawOutcome = null
    this.setData({ loading: true, detailError: '',
      detailOutcomeText: this._withdrawOutcome ? '已撤回转单，订单仍由你配送' : '',
      showMoreActions: false, floorPhotos: [], deliveryPhotos: [], payments: [], canRefundPayment: false })

    try {
      const res = await getOrderDetail(id)
      if (!this._isCurrent(ctx)) return
      const order = res.data

      // 状态文案、支付方式文案、是否需现场收款、转单状态：全部由后端计算下发。
      // 注意：此前这里读取的 transferStatus / returnStatus / isTransferTarget 三个字段
      // 后端 Orders 根本不存在（与 collected 同类问题），导致「转单中/退回申请/待你确认」
      // 标签永远不显示。现改用后端 transferPending / transferText。
      let notes = []
      if (order.specialNote) {
        notes = order.specialNote.split('\n').filter(n => n.trim())
      }

      const labels = []
      if (order.needCollect) labels.push({ type: 'offline', text: '线下' })
      const viewerRole = (getApp().globalData.userInfo || {}).role
      const managerView = viewerRole === 'STATION_MANAGER' || viewerRole === 'manager'
      if (order.transferPending && !(managerView && order.transferPendingSubKind === 'TRANSFER')) {
        labels.push({ type: 'transfer', text: order.transferText })
      }

      // 动作判据一处（utils/order-actions）：订单对象里展开的同一份 acts 既驱动主按钮，
      // 也驱动「更多操作」弹层有没有内容（hasMoreActions = 任一低频动作可显示）。
      const acts = orderActions(order, getApp().globalData.userInfo)
      this._orderContext = ctx

      this.setData({
        order: {
          ...order,
          items: withItemUnits(order.items),
          ...acts,
          canCollect: canCollect(order, getApp().globalData.userInfo),
          statusText: order.statusText || '',
          statusClass: STATUS_CLASS_MAP[order.status] || 'default',
          notes,
          paymentMethodText: order.payMethodText || '',
          isOffline: !!order.needCollect,
          labels,
          isTransfer: !!order.transferPending,
          floorText: buildFloorText(order),
          // 事实卡金额行（2026-10-07 #19 R4）：金额只做定长格式化，口径是后端 totalAmount；
          // 收款章的文案是后端 payStateText，章的配色（need/paid/plain）由后端投影
          // needCollect / payState 选出 —— 与完成配送页 _moneyView 同一套判据，前端不自推。
          amountText: (order.totalAmount === null || order.totalAmount === undefined || order.totalAmount === '')
            ? '' : '¥' + Number(order.totalAmount).toFixed(2),
          payChipClass: order.needCollect ? 'need' : (order.payState === 'PAID' ? 'paid' : 'plain'),
          // 备货情况（契约 C4）：出发前/上门前用一句话说清"这单备齐了没、还缺哪些商品"。
          // 与金额、状态文案一样取自后端投影（stockPrep），前端不拿 inventory.quantity 自己推算。
          stockPrepText: buildStockPrepText(order.stockPrep, order.items || []),
          // 楼层上报（v43）：显示成两行（配送员上报 / 地址里填的），不一致时打一个提示标。
          // ⚠️ 这只是**给人看的提示**；"标记"的权威记录在收益明细的 note 里（后端生成，见 docs/design/18 §4）。
          reportedFloorText: order.reportedFloor ? ('配送员上报 ' + order.reportedFloor + ' 层') : '',
          floorMismatch: !!(order.reportedFloor && order.addressFloor
            && Number(order.reportedFloor) !== Number(order.addressFloor)),
          // 「送达凭证」的空态提示条件（[2026-09-26]）：只在这单**已经送到**（已送达 3 / 已完成 4）
          // 却又没有照片时，才说"配送员没拍"。
          // ⚠️ 这是**展示判据**，不是状态文案映射表 —— 状态中文仍一律渲染后端下发的 `statusText`；
          //    在 js 里比而不是在 wxml 里比，是为了让 wxml 不出现数字含义（改状态编号时只改这一处）。
          showNoDeliveryProofTip: order.status === 3 || order.status === 4,
          // 回桶行（见 buildReturnPlan 的注释：这一行以前永远显示 0 个）
          ...buildReturnPlan(order)
        },
        loading: false, detailError: '', detailOutcomeText: '',
        // 「更多操作」按钮有没有内容：任一低频动作可显示即 true（判据与弹层条目一一对应）。
        // 拒付三件套与 wxml 同一组条件：站长 + 已送达(3) + 还要收款。
        hasMoreActions: !!(acts.canReport || acts.canTransfer || acts.canWithdrawTransfer
          || acts.canReturn || acts.canRequestCancel
          || (managerView && order.status === 3 && order.needCollect)),
        showMoreActions: false
      })
      this._withdrawOutcome = null
      this.loadFloorPhotos(id, ctx)
      // 送达凭证（原名"签收凭证"，[2026-09-26] 改名）：配送员在完成配送页拍的那几张，
      // 客户说"没收到水"时站长能在这里看到（此前只有顾客端能看到，站长端一张都看不到）
      this.loadDeliveryPhotos(id, ctx)
      this.loadPayments(id, ctx)
      return true
    } catch (err) {
      if (!this._isCurrent(ctx)) return
      console.error('加载订单详情失败:', err)
      this.setData({ loading: false, detailError: err.message || '加载失败' })
      wx.showToast({ title: this._withdrawOutcome ? '已撤回，但详情刷新失败，请重新加载' : (err.message || '加载失败'), icon: 'none' })
      return false
    }
  },

  onRetryDetail() {
    if (this._hidden || this._unloaded || this.data.loading || this._blockPendingWrite()) return
    return this.loadOrderDetail(this.data.orderId)
  },

  // 拨打电话
  onCallPhone() {
    const phone = this.data.order.receiverPhone || this.data.order.customerPhone
    if (phone) {
      wx.makePhoneCall({ phoneNumber: phone })
    }
  },

  // 复制地址
  onCopyAddress() {
    const address = this.data.order.addressSnapshot || this.data.order.addressDetail
    if (address) {
      wx.setClipboardData({
        data: address,
        success: () => {
          wx.showToast({ title: '地址已复制', icon: 'success' })
        }
      })
    }
  },

  // ══ 更多操作弹层（2026-10-07 #11，docs/design/36 §3）══
  // 纯 UI 状态切换；业务动作仍走原处理器（onReport/onTransfer/…，一行未动）。
  onOpenMoreActions() {
    if (this._hidden || this._unloaded) return
    if (this._blockPendingWrite()) return
    if (this.data.loading || this.data.detailError || this._actionTask || !this.data.hasMoreActions) return
    const ctx = this._context()
    if (!this._isCurrent(ctx) || (this._orderContext && !this._isCurrent(this._orderContext))) return
    this._sheetContext = ctx
    this.setData({ showMoreActions: true })
  },

  onCloseMoreActions() {
    this._sheetContext = null
    this.setData({ showMoreActions: false })
  },

  onSheetTouchMove() {},

  // 2026-10-07：只分发真实条目；关闭后到达的第二次 tap、换身份后的旧条目均不执行。
  onMoreAction(e) {
    const name = e && e.currentTarget && e.currentTarget.dataset.act
    if (!this.data.showMoreActions || !this._isCurrent(this._sheetContext) || !this._actionAllowed(name)) return
    this.onCloseMoreActions()
    return this[name]()
  },

  onConfirmCollection() {
    return this._runOrderAction('onConfirmCollection', async ctx => {
      const answer = await this._actionDialog(ctx, {
        title: '确认收款', content: '确认已收到此订单款项？', confirmText: '确认收款', confirmColor: '#2E4A68'
      })
      if (!answer.confirm || !ctx.current()) return
      if (!await this._actionWrite(ctx, () => confirmCollection(ctx.id))) return
      wx.showToast({ title: '收款成功', icon: 'success' })
      await this.loadOrderDetail(ctx.id)
    })
  },

  // 导航（优先使用下单时地址快照，避免客户改地址后导错）
  onNavigate() {
    const order = this.data.order
    const lat = Number(order.addressSnapshotLat)
    const lng = Number(order.addressSnapshotLng)
    const addressText = order.addressSnapshot || order.addressDetail || ''
    if (lat && lng) {
      wx.openLocation({
        latitude: lat,
        longitude: lng,
        name: addressText,
        address: addressText,
        scale: 18
      })
    } else {
      // 如果没有坐标，使用地址搜索
      wx.chooseLocation({
        success: (res) => {
          wx.openLocation({
            latitude: res.latitude,
            longitude: res.longitude,
            name: res.name,
            address: res.address,
            scale: 18
          })
        }
      })
    }
  },

  // 完成配送
  onComplete() {
    if (this._hidden || this._unloaded) return
    if (this._blockPendingWrite()) return
    if (this.data.loading || this.data.detailError || this._actionTask
      || (this._orderContext && !this._isCurrent(this._orderContext))) return
    if (!orderActions(this.data.order, getApp().globalData.userInfo).canComplete) {
      wx.showToast({ title: '当前订单不可完成配送，请刷新', icon: 'none' })
      return
    }
    wx.navigateTo({
      url: `/pages/order/complete?id=${this.data.orderId}`
    })
  },

  // 异常反馈（[2026-09-27] 改为调用共享实现：原因文案与上报逻辑与首页「配送遇到问题」共用一份，
  // 见 utils/delivery-problem.js —— 原因会被后端原样写进订单备注、站长照着那行字看，
  // 两处各维护一份迟早对不上。）
  onReport() {
    return this._runOrderAction('onReport', ctx => reportDeliveryProblem(ctx.id, {
      isCurrent: ctx.current, dialog: (options, sheet) => this._actionDialog(ctx, options, sheet),
      write: operation => this._actionWrite(ctx, operation)
    }))
  },

  onAcceptTransfer() {
    return this._runOrderAction('onAcceptTransfer', async ctx => {
      const answer = await this._actionDialog(ctx, { title: '同意转单', content: '确定接手此转单？接手后你将成为该订单配送员。', confirmText: '同意', confirmColor: '#2E4A68' })
      if (!answer.confirm || !ctx.current()) return
      const { API } = require('../../config/api')
      if (!await this._actionWrite(ctx, () => post(`${API.DELIVERY_TRANSFER}/${ctx.id}/claim`))) return
      wx.showToast({ title: '已接手', icon: 'success' })
      this._actionBack(ctx)
    })
  },

  onRejectTransfer() {
    return this._runOrderAction('onRejectTransfer', async ctx => {
      const answer = await this._actionDialog(ctx, { title: '拒绝转单', content: '确定拒绝此转单申请？', confirmText: '拒绝', confirmColor: '#B5442C' })
      if (!answer.confirm || !ctx.current()) return
      const { API } = require('../../config/api')
      if (!await this._actionWrite(ctx, () => post(`${API.DELIVERY_TRANSFER}/${ctx.id}/reject`))) return
      wx.showToast({ title: '已拒绝', icon: 'success' })
      this._actionBack(ctx)
    })
  },

  // 撤回转单（2026-09-29 拍板 清单2）：双方同意制下发起人反悔的正门。
  // 详情此前只有「转给同事」没有任何反悔出口 —— 转单申请发出去就只能等对方拒绝。
  // 后端判权：发起人本人或本站站长（cancelTransfer），前端不判、点了由后端回话。
  // 入口只画在 transferPendingSubKind === 'TRANSFER'（同事转让）时：退回站长 / 站内取消申请
  // 同为 STAFF 类型但各有各的决策路径（审批页签的同意/拒绝、取消申请的撤销），
  // 站间指定退回走协调页的「召回/退回」另一套动作 —— 都不挂这个按钮。
  onCancelTransfer() {
    return this._runOrderAction('onCancelTransfer', async ctx => {
      const answer = await this._actionDialog(ctx, {
        title: '撤回转单', content: '撤回后这单仍归原配送员配送，对方的待确认列表里也不会再有这条申请。', confirmText: '撤回', confirmColor: '#B5442C'
      })
      if (!answer.confirm || !ctx.current()) return
      this.setData({ cancelTransferBusy: true })
      if (!await this._actionWrite(ctx, () => cancelTransferOrder(ctx.id))) return
      const refreshed = await this.loadOrderDetail(ctx.id)
      if (refreshed && this._isCurrent(ctx)) wx.showToast({ title: '已撤回，订单仍归你配送', icon: 'success' })
    })
  },

  // 转给同事（直转本站配送员）
  onTransfer() {
    return this._runOrderAction('onTransfer', async ctx => {
      const user = getApp().globalData.userInfo || {}
      let result
      if (!await this._actionWrite(ctx, async () => { result = await getStaffList(user.stationId) })) return
      if (!ctx.current()) return
      const colleagues = (result.data || []).filter(s => String(s.id) !== String(user.staffId))
      if (!colleagues.length) {
        await this._actionDialog(ctx, { title: '暂无同事', content: '本站暂无其他在职配送员可转让', showCancel: false }); return
      }
      const picked = await this._actionDialog(ctx, { itemList: colleagues.map(s => s.name || ('配送员' + s.id)) }, true)
      const target = colleagues[picked.tapIndex]
      if (!target || !ctx.current()) return
      const answer = await this._actionDialog(ctx, { title: '转给同事', content: `确认将订单转给 ${target.name}？`, confirmText: '确认' })
      if (!answer.confirm || !ctx.current()) return
      if (!await this._actionWrite(ctx, () => transferOrder(ctx.id, { deliveryStaffId: target.id, reason: '配送员转让' }))) return
      // 后端只建立待确认申请，订单仍由原配送员负责；不能宣称已经改派。
      wx.showToast({ title: '已申请，等 ' + target.name + ' 同意', icon: 'none' })
      this._actionBack(ctx)
    })
  },

  // 退回站长
  onReturnToStation() {
    return this._runOrderAction('onReturnToStation', async ctx => {
      const answer = await this._actionDialog(ctx, {
        title: '退回站长', content: '提交退回申请，待站长确认后重新分配。确认前订单仍由你负责。', confirmText: '确认退回'
      })
      if (!answer.confirm || !ctx.current()) return
      if (!await this._actionWrite(ctx, () => returnToStation(ctx.id, { reason: '配送员退回站长' }))) return
      wx.showToast({ title: '已申请，等待站长确认', icon: 'success' })
      this._actionBack(ctx)
    })
  },

  // 外派订单：临时指派给其他水站配送
  async onDispatchOrder() {
    const id = this.data.orderId
    const app = getApp()
    const myStationId = (app.globalData.userInfo || {}).stationId

    // 获取其他水站列表
    const { get } = require('../../utils/request')
    const { API } = require('../../config/api')
    let stationList = []
    // [2026-09-20] 同 home/index.js 的 onMediateToColleague：原来失败只 console.error，
    // 然后把"没查到"说成「暂无其他营业中的水站可外派」—— 站长会以为真的没有可派的水站。
    let loadError = ''
    try {
      const res = await get(API.STATION_SEARCH, {})
      stationList = (res.data || []).filter(s => s.id !== myStationId && s.status === 1)
    } catch (e) {
      loadError = (e && e.message) || '网络异常'
      console.error('加载水站列表失败:', e)
    }

    if (loadError) {
      wx.showModal({
        title: '加载失败',
        content: '没能取到水站列表（' + loadError + '），请稍后重试',
        showCancel: false
      })
      return
    }
    if (stationList.length === 0) {
      wx.showModal({ title: '无可外派水站', content: '暂无其他营业中的水站可外派', showCancel: false })
      return
    }

    const itemList = stationList.map(s => s.name || ('水站' + s.id))
    wx.showActionSheet({
      itemList: itemList,
      success: async (res) => {
        const targetStation = stationList[res.tapIndex]
        // 二次确认：显示风险提醒
        const confirm = await new Promise(resolve => {
          wx.showModal({
            title: '⚠️ 外派确认',
            content: `将订单外派给「${targetStation.name}」配送。\n\n` +
              '重要提醒：\n' +
              '1. 本单归属仍在本站，客户资产（桶/水票/押金）不转移\n' +
              '2. 客户下次下单仍在本站，需手动切站才会用外派站资产\n' +
              '3. 外派站仅负责本次配送，不建立客户归属关系\n\n' +
              '确定外派吗？',
            confirmText: '确定外派',
            confirmColor: '#2E9E6B',
            cancelText: '取消',
            success: (r) => resolve(r.confirm)
          })
        })
        if (!confirm) return

        wx.showLoading({ title: '外派中...' })
        try {
          await dispatchOrder(id, { targetStationId: targetStation.id, reason: '外派配送' })
          wx.hideLoading()
          wx.showToast({ title: '外派成功', icon: 'success' })
          setTimeout(() => { wx.navigateBack() }, 1500)
        } catch (err) {
          wx.hideLoading()
          wx.showToast({ title: err.message || '外派失败', icon: 'none' })
        }
      }
    })
  },

  // 申请取消订单（配送员）：**只有「配送中(2)」**能申请（wxml 的按钮条件与后端门槛一致），
  // 提交后订单状态不变，由站长审批；站长同意才走退款链。
  // ⚠️ 已送达(3) 申请不了（后端 isCancellable 排除，异常走「配送异常」）；
  //    待配送(1) 也不需要申请 —— 走「拒单」即可（不经审批）。
  onRequestCancel() {
    return this._runOrderAction('onRequestCancel', async ctx => {
      const answer = await this._actionDialog(ctx, {
        title: '申请取消订单', content: '该订单已被接单，需要站长同意才能取消。\n\n提交后订单保持当前状态，等待站长审批；站长同意后才会取消并退款。', confirmText: '提交申请'
      })
      if (!answer.confirm || !ctx.current()) return
      const reason = await this._actionDialog(ctx, {
        title: '取消原因', content: '请填写取消原因，将一并提交给站长：', editable: true, placeholderText: '如：客户临时取消、车辆故障'
      })
      if (!ctx.current() || !reason.confirm) return
      if (!reason.content || !reason.content.trim()) { wx.showToast({ title: '请填写取消原因', icon: 'none' }); return }
      if (!await this._actionWrite(ctx, () => requestCancel(ctx.id, { reason: reason.content.trim() }))) return
      wx.showToast({ title: '已提交，等待站长审批', icon: 'success' })
      this._actionBack(ctx)
    })
  },

  // 解决订单：拒单并取消，触发退款
  async onResolveOrder() {
    const id = this.data.orderId
    // 严重警告：拒单会导致订单取消、退款、客户可能流失
    const confirm = await new Promise(resolve => {
      wx.showModal({
        title: '⚠️ 严重警告：解决/拒单',
        content: '此操作将：\n\n' +
          '1. 取消订单，状态变为「已取消」\n' +
          '2. 触发退款，款项原路退回（微信支付需几分钟到账）\n' +
          '3. 客户需重新下单，体验极差，极大概率导致客户流失\n\n' +
          '建议优先考虑：「外派」给其他水站，或内部协调配送。\n\n' +
          '确定要解决（拒单）吗？',
        confirmText: '确定解决',
        confirmColor: '#B5442C',
        cancelText: '去外派',
        success: (r) => resolve(r.confirm)
      })
    })
    if (!confirm) return

    // 必填拒单原因
    const reason = await new Promise(resolve => {
      wx.showModal({
        title: '拒单原因 (必填)',
        content: '请输入拒单原因，将记录在订单备注中：',
        editable: true,
        placeholderText: '如：地址偏远无法配送、暂时缺货',
        success: (r) => resolve(r.confirm ? r.content : ''),
        fail: () => resolve('')
      })
    })
    if (!reason || !reason.trim()) {
      wx.showToast({ title: '拒单原因不能为空', icon: 'none' })
      return
    }

    wx.showLoading({ title: '处理中...' })
    try {
      await resolveOrder(id, { reason: reason.trim() })
      wx.hideLoading()
      wx.showToast({ title: '已解决，订单取消并触发退款', icon: 'success' })
      setTimeout(() => { wx.navigateBack() }, 1500)
    } catch (err) {
      wx.hideLoading()
      wx.showToast({ title: err.message || '解决失败', icon: 'none' })
    }
  },

  /* ==================== 楼梯凭证（v43；[2026-09-26] 由「楼层凭证」改名）====================
   * 为什么站长也要能传：楼层补贴是给配送员的钱，与客户就楼层/爬楼有争议时（"你说的 6 楼呢"）
   * 这张照片是唯一的凭证；配送员当时没拍，站长可以事后补。
   * 照片**不强制**（产品决定），所以这里没有也不拦、只提示。
   * ⚠️ 名字跟配送员端对齐（那边叫「楼层与楼梯凭证」），别一处叫楼层、一处叫楼梯。
   */
  async loadFloorPhotos(orderId, context) {
    if (!orderId) return
    const ctx = context || this._context(orderId)
    if (ctx.readVersion == null) ctx.readVersion = this._detailVersion || 0
    const version = this._loadFloorPhotosVersion = (this._loadFloorPhotosVersion || 0) + 1
    const current = () => this._isCurrent(ctx) && version === this._loadFloorPhotosVersion
    if (!current()) return
    try {
      const res = await get(ORDER_IMAGE_BY_ORDER + '/' + orderId)
      const photos = (res.data || [])
        .filter(img => Number(img.type) === 3)
        .map(img => img.url || img.objectName)
        .filter(Boolean)
      if (!current()) return
      this.setData({ floorPhotos: photos })
    } catch (e) {
      // 拉不到就不显示，不编造"没照片"
      if (!current()) return
      this.setData({ floorPhotos: [] })
    }
  },

  onPreviewFloorPhoto(e) {
    const { index } = e.currentTarget.dataset
    wx.previewImage({ current: this.data.floorPhotos[index], urls: this.data.floorPhotos })
  },

  /**
   * 送达凭证（[2026-09-26] 加）：配送员完成配送时拍的 `order_image.type = 1`。
   *
   * <p>为什么站长端要能看：客户打电话说"没收到水"时，站长此前**一张照片都看不到**
   * （只有顾客端订单详情会列出全部图片）—— 这个凭证就成了只写给配送员自己看的东西。
   * 这里只读：送达现场该由当时在场的人拍，站长补拍没有证明力。</p>
   *
   * <p>与楼梯凭证共用同一个端点（`/api/order-images/by-order/{id}`，按 type 过滤），
   * 所以同一个响应查两次是无意义的重复请求 —— 但两个块的判据不同（type 1 / type 3），
   * 合成一个方法会让"哪个 type 是哪张图"散在参数里，宁可两次小请求。</p>
   */
  async loadDeliveryPhotos(orderId, context) {
    if (!orderId) return
    const ctx = context || this._context(orderId)
    if (ctx.readVersion == null) ctx.readVersion = this._detailVersion || 0
    const version = this._loadDeliveryPhotosVersion = (this._loadDeliveryPhotosVersion || 0) + 1
    const current = () => this._isCurrent(ctx) && version === this._loadDeliveryPhotosVersion
    if (!current()) return
    try {
      const res = await get(ORDER_IMAGE_BY_ORDER + '/' + orderId)
      const photos = (res.data || [])
        .filter(img => Number(img.type) === 1)
        .map(img => img.url || img.objectName)
        .filter(Boolean)
      if (!current()) return
      this.setData({ deliveryPhotos: photos })
    } catch (e) {
      // 拉不到就不显示，不编造"没照片"（同 loadFloorPhotos 的口径）
      if (!current()) return
      this.setData({ deliveryPhotos: [] })
    }
  },

  onPreviewDeliveryPhoto(e) {
    const { index } = e.currentTarget.dataset
    wx.previewImage({ current: this.data.deliveryPhotos[index], urls: this.data.deliveryPhotos })
  },

  onAddFloorPhoto() {
    const { upload } = require('../../utils/upload')
    const { API } = require('../../config/api')
    wx.chooseImage({
      count: 3,
      sizeType: ['compressed'],
      success: async (res) => {
        this.setData({ floorUploading: true })
        const uploads = res.tempFilePaths.map(p => upload({
          filePath: p,
          url: API.ORDER_IMAGE_UPLOAD,
          name: 'file',
          // 3 = 楼层凭证（1 正常送达 / 2 异常）
          formData: { orderId: this.data.orderId, type: 3 }
        }).then(r => r.data))
        try {
          await Promise.all(uploads)
          await this.loadFloorPhotos(this.data.orderId)
          wx.showToast({ title: '已补传', icon: 'success' })
        } catch (err) {
          wx.showToast({ title: err.message || '上传失败', icon: 'none' })
        } finally {
          this.setData({ floorUploading: false })
        }
      }
    })
  },

  /* ==================== 支付流水与手工退款（2026-09-18 接线）====================
   * 为什么只给站长看：两个端点都是后端 @RequireRole({"STATION_MANAGER"}) 专属
   * （GET /api/payments/by-order、PUT /api/payments/{id}/refund），
   * 配送员点了只会拿到 code=1「权限不足」—— 那属于"让用户白点"。
   *
   * ⚠️ 展示口径：方式 / 状态**一律渲染后端下发的 methodText / statusText**，金额用后端给的
   * amount 原值，前端**不做任何金额加减**（包括"合计已退多少"这类派生数字一律不做）——
   * 金额口径的唯一真相源是 orders / payment_record，见 AGENTS.md §6。
   * 这里唯一用到的数字判断是"状态是不是 2（已付款）"，它只决定「退款」按钮出不出现。
   */
  async loadPayments(orderId, context) {
    if (!orderId) return
    const ctx = context || this._context(orderId)
    if (ctx.readVersion == null) ctx.readVersion = this._detailVersion || 0
    const version = this._loadPaymentsVersion = (this._loadPaymentsVersion || 0) + 1
    const current = () => this._isCurrent(ctx) && version === this._loadPaymentsVersion
    if (!current()) return
    const app = getApp()
    // 非站长直接跳过，连请求都不发（端点本身也会拒）
    if (!app.isStationManager || !app.isStationManager()) {
      if (!current()) return
      this.setData({ payments: [], canRefundPayment: false })
      return
    }
    try {
      const res = await get(PAYMENTS_BY_ORDER, { orderId: orderId })
      const list = (res.data || []).map(p => ({
        id: p.id,
        methodText: p.methodText || '',
        statusText: p.statusText || '',
        amount: p.amount,
        amountText: p.amount === null || p.amount === undefined ? '' : ('¥' + p.amount),
        // 负数（退款冲正流水）标红，纯展示
        isRefund: Number(p.amount) < 0,
        // 2026-09-18：后端下的时间统一 ISO-8601，前端只做展示切分（见文件头 formatTime）
        timeText: formatTime(p.createTime),
        note: p.note || '',
        canRefund: Number(p.status) === PAY_STATUS_PAID
      }))
      if (!current()) return
      this.setData({ payments: list, canRefundPayment: true })
    } catch (err) {
      // 拉不到就整块不显示，不编造"没有流水"（同 loadFloorPhotos 的处理）
      console.error('加载支付流水失败:', err)
      if (!current()) return
      this.setData({ payments: [], canRefundPayment: false })
    }
  },

  /**
   * 手工退款（站长）：二次确认 → 调 PUT /api/payments/{id}/refund → 成功后刷新本区块。
   *
   * ⚠️ 失败必须把**后端 message 原样显示出来**（用 showModal 而不是一闪而过的 toast）：
   * 「微信支付渠道未接入，无法自动原路退回，请线下退款并登记」这类文案是站长唯一的操作指引，
   * 弹个 toast 就消失等于没告诉他下一步该干什么。
   */
  onRefundPayment(e) {
    const { id, index } = e.currentTarget.dataset
    const pay = this.data.payments[index]
    if (!pay) return
    if (this.data.order.independentBusinessRules) {
      this.chooseRefundScope(id)
      return
    }

    wx.showModal({
      title: '退款确认',
      content: '确定为这笔支付退款吗？\n\n支付方式：' + pay.methodText
        + '\n退款金额：' + pay.amountText
        + '\n\n退款按原支付方式退回：水票支付的会把票原路补回客户账户；'
        + '现金由你当面退还客户；微信渠道未接入，无法自动退回。',
      confirmText: '确认退款',
      confirmColor: '#B5442C',
      success: (res) => {
        if (!res.confirm) return
        this.doRefundPayment(id)
      }
    })
  },

  async chooseRefundScope(paymentId) {
    try {
      const res = await businessRules.refundPreview(paymentId)
      const scopes = res.data.scopes || []
      if (!scopes.length) { wx.showToast({ title: '消费费用已经退完', icon: 'none' }); return }
      wx.showActionSheet({ itemList: scopes.map(s => s.label), success: (picked) => {
        const selected = scopes[picked.tapIndex]
        if (!selected) return
        const confirmation = {}
        if (selected.expectedRefundAmount != null) {
          const amount = Number(selected.expectedRefundAmount)
          if (!Number.isFinite(amount) || amount <= 0) {
            wx.showModal({ title: '重新核实', content: '退款金额无效，请重新预览并确认', showCancel: false }); return
          }
          confirmation.expectedRefundAmount = selected.expectedRefundAmount
        } else if (selected.confirmationRequired === true) {
          wx.showModal({ title: '重新核实', content: '未取得确认金额，请重新预览并确认', showCancel: false }); return
        }
        if (res.data.expectedTicketQty != null) confirmation.expectedTicketQty = res.data.expectedTicketQty
        if (res.data.expectedTicketAmount != null) confirmation.expectedTicketAmount = res.data.expectedTicketAmount
        wx.showModal({ title: selected.label, content: res.data.notice, confirmText: '实际退款', success: (r) => {
          if (r.confirm) this.doRefundPayment(paymentId, selected.scope, confirmation)
        } })
      } })
    } catch (err) { wx.showModal({ title: '暂不能退款', content: err.message || '金额核实失败', showCancel: false }) }
  },
  async onEditDispatchQuote() {
    try {
      const res = await businessRules.getAgreement(this.data.orderId)
      const current = res.data
      if (!current || !Object.keys(current).length) { wx.showToast({ title: '请先外派本单，再调整外包报价', icon: 'none' }); return }
      wx.showModal({ title: '调整本单外包服务报价', editable: true, content: '当前 ¥' + current.serviceAmount + '，含水费、配送费、楼层费。桶补偿安排：' + current.barrelNote + '。接收站确认后报价固定。', placeholderText: '本单服务总报价（元）',
        success: async (r) => {
          if (!r.confirm) return
          const value = Number(r.content)
          if (!Number.isFinite(value) || value < 0) { wx.showToast({ title: '报价不合法', icon: 'none' }); return }
          try { await businessRules.quoteAgreement(this.data.orderId, { serviceAmount: value, barrelMode: current.barrelMode,
            barrelAmount: current.barrelAmount, note: '归属站调整本单外包报价' }); this.loadOrderDetail(this.data.orderId) }
          catch (err) { wx.showToast({ title: err.message || '报价未修改', icon: 'none' }) }
        } })
    } catch (err) { wx.showToast({ title: err.message || '报价不可用', icon: 'none' }) }
  },
  async onEditBarrelTerms() {
    try {
      const current=(await businessRules.getAgreement(this.data.orderId)).data
      wx.showActionSheet({ itemList: ['净送桶补同型空桶', '净送桶按约定总金额折款'], success: picked => {
        const mode=picked.tapIndex===0?'RETURN_EMPTY':'SETTLE_BARREL'
        const save=async amount=>{
          try { await businessRules.quoteAgreement(this.data.orderId,{serviceAmount:current.serviceAmount,barrelMode:mode,barrelAmount:amount,note:'归属站明确本单净送桶补偿安排'}); this.loadOrderDetail(this.data.orderId) }
          catch(err){wx.showToast({title:err.message||'安排未修改',icon:'none'})}
        }
        wx.showModal({title:'确定净送桶补偿安排',editable:mode==='SETTLE_BARREL',placeholderText:'桶折款总金额（元）',content:'预计净送 '+current.netBarrels+' 个桶；服务报酬仍为 ¥'+current.serviceAmount+'；客户押金仍在归属站。接收站确认后固定安排。',success:r=>{
          if(!r.confirm)return
          if(mode==='RETURN_EMPTY'){save(0);return}
          if(!/^\d+(\.\d{1,2})?$/.test(r.content||'')){wx.showToast({title:'金额不合法',icon:'none'});return}
          save(Number(r.content))
        }})
      }})
    }catch(err){wx.showToast({title:err.message||'安排不可用',icon:'none'})}
  },
  async doRefundPayment(paymentId, scope, confirmation = {}) {
    wx.showLoading({ title: '退款中...' })
    try {
      await put(PAYMENT_REFUND + paymentId + '/refund', { note: '站长手工退款', ...(scope ? { scope } : {}), ...confirmation })
      wx.hideLoading()
      wx.showToast({ title: '已退款', icon: 'success' })
      // 刷新支付流水区块（订单支付状态可能一起变了）
      await this.loadPayments(this.data.orderId)
      await this.loadOrderDetail(this.data.orderId)
    } catch (err) {
      wx.hideLoading()
      const msg = (err && err.message) ? err.message : '退款失败，请稍后重试'
      console.error('手工退款失败:', msg)
      wx.showModal({ title: '退款未成功', content: msg, showCancel: false, confirmText: '知道了' })
    }
  }
})
