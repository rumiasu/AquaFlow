const { showBusinessModal } = require('../../../../utils/business-dialog')
// 客户画像（站长视角）
const { getCustomerProfile, getCustomerAssets, updateOfflinePayment, returnEmptyBuckets } = require('../../../../api/station-mgmt')
const returnIntent = require('../../../../utils/return-empty-intent')
// ⚠️ 特权接口走 utils/request + 本文件内的路径常量，不往 api/station-mgmt.js 里加函数：
// 那个文件正被另一个工作流（商品图片库）改动，往里加东西会让两边未提交的改动纠缠在一起。
const { get: httpGet, post: httpPost, del: httpDel } = require('../../../../utils/request')
const privilegesPath = (customerId) => `/api/manager/customers/${customerId}/privileges`

Page({
  data: {
    loading: true,
    saving: false,
    id: null,
    profile: null,
    error: '',
    // ===== 本站资产 =====
    // 资产接口是站长专属（@RequireRole STATION_MANAGER）。本页的 canAccessStationBusiness()
    // 同时放行配送员，所以这里再判一次角色：非站长不请求、不渲染该区块，
    // 否则配送员进来会看到一条与页面无关的"权限不足"，容易被当成页面故障。
    canViewAssets: false,
    assets: null,
    assetsError: '',
    assetsLoading: false,
    // 流水默认只展示前若干条，展开仅显示本次已取回记录
    recordsExpanded: false,
    recordsPreviewCount: 5,
    // ===== 纯还桶（只冲减 over，不扣权益、不退款） =====
    showReturnEmpty: false,
    returnItems: [],
    returnSubmitting: false,
    returnPending: false,
    returnMessage: '',
    returnRecoveryError: '',
    // ===== 客户特权（v40） =====
    // 与资产接口同理，是站长专属：配送员不请求、不渲染该区块。
    privileges: [],
    // 可授予类型由**服务端下发**（只含已实现的），前端不写死枚举 ——
    // 否则后端新增一种特权，前端不改就永远看不到
    grantableTypes: [],
    privilegesLoading: false,
    privilegesError: ''
  },

  onLoad(options) {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    const id = options.id
    const canViewAssets = app.isStationManager()
    this.setData({ id, canViewAssets })
    this._customerOwner = this.customerContext()
    this._customerInitialShow = true
    return this.refreshCustomerData()
  },

  onRetryProfile() { return this.loadProfile(this.data.id) },

  onShow() {
    this._returnHidden = false
    if (this._customerOwner && !this.currentCustomerContext(this._customerOwner)) {
      this.setData({ loading: false, profile: null, assets: null, canViewAssets: false,
        assetsLoading: false, privileges: [], privilegesLoading: false, showReturnEmpty: false,
        error: '登录身份或水站已变化，请退出本页后重新进入' })
      return
    }
    if (this._returnModalContext && !this.currentReturnContext(this._returnModalContext)) {
      this._returnModalContext = null
      this.setData({ showReturnEmpty: false, returnItems: [] })
    }
    if (this.data.id && this.data.canViewAssets) this.restoreReturnIntent()
    if (this._customerInitialShow) { this._customerInitialShow = false; return }
    // 2026-10-23：调整/代客下单返回曾继续显示旧资产；只在返回时重读，首入沿用onLoad。
    if (this._customerOwner) return this.refreshCustomerData()
  },
  onHide() {
    this._returnHidden = true
    for (const key of ['_profileReadSeq', '_assetReadSeq', '_privilegeReadSeq']) this[key] = (this[key] || 0) + 1
  },
  onUnload() { this._returnDisposed = true; this.onHide() },

  customerContext() {
    const app = getApp(), data = app.globalData, user = data.userInfo || {}
    return { app, key: JSON.stringify([!!data.isLogin, app._loginGeneration || 0,
      user.staffId, user.stationId, user.role, user.bindStatus, String(this.data.id)]) }
  },
  currentCustomerContext(context) {
    const current = this.customerContext(), owner = this._customerOwner
    return !this._returnHidden && !this._returnDisposed && current.app === context.app && current.key === context.key
      && (!owner || owner.app === context.app && owner.key === context.key)
  },
  refreshCustomerData() {
    const tasks = [this.loadProfile(this.data.id)]
    if (this.data.canViewAssets) tasks.push(this.loadAssets(this.data.id), this.loadPrivileges(this.data.id))
    return Promise.all(tasks)
  },

  returnContext() {
    const app = getApp(), user = app.globalData.userInfo || {}
    if (!app.globalData.isLogin || !app.isStationManager() || !app.canAccessStationBusiness()
        || ![user.staffId, user.stationId, this.data.id].every(v => Number.isSafeInteger(Number(v)) && Number(v) > 0)) {
      throw new Error('当前身份或客户尚未核对，请退出本页重新进入')
    }
    return { app, generation: app._loginGeneration || 0, customerId: Number(this.data.id),
      actor: Number(user.staffId) + ':' + Number(user.stationId) }
  },
  currentReturnContext(context) {
    try {
      const current = this.returnContext()
      return !this._returnDisposed && current.app === context.app && current.generation === context.generation
        && current.actor === context.actor && current.customerId === context.customerId
    } catch (_) { return false }
  },
  readReturnIntent(context) {
    let saved = returnIntent.read(context.actor, context.customerId)
    const memory = this._returnIntent
    if (memory && memory.actor === context.actor && memory.customerId === context.customerId) {
      if (saved && saved.clientToken !== memory.clientToken) throw new Error('原还桶记录已变化，请联系负责人核实')
      // 存储临时丢失时只补回内存中的原键，绝不能生成另一笔登记。
      if (!saved) saved = returnIntent.save(memory)
    }
    this._returnIntent = saved
    return saved
  },
  restoreReturnIntent() {
    try {
      const pending = this.readReturnIntent(this.returnContext())
      this.setData({ returnPending: !!pending, returnRecoveryError: '', returnSubmitting: !!this._returnFlight,
        returnMessage: pending ? '原还桶登记结果待核实，请勿再次交桶。核对时只重试原登记。' : '' })
      return pending
    } catch (err) {
      this.setData({ returnRecoveryError: err.message, returnSubmitting: !!this._returnFlight })
      return null
    }
  },

  async loadProfile(id) {
    // 2026-10-23：保存货到付款期间的返回读取可能比保存更晚回旧值；等保存收尾后核对。
    if (this.data.saving) { this._profileRefreshAfterSave = true; return }
    this._profileRefreshAfterSave = false
    const context = this.customerContext(), seq = this._profileReadSeq = (this._profileReadSeq || 0) + 1
    const current = () => seq === this._profileReadSeq && this.currentCustomerContext(context)
    if (!current()) return
    this.setData({ loading: true, error: '' })
    try {
      const res = await getCustomerProfile(id)
      if (!current()) return
      if (res.code === 0 && res.data) {
        this.setData({ profile: this.decorate(res.data) })
      } else {
        this.setData({ error: res.message || '客户不存在', profile: null })
      }
    } catch (err) {
      if (!current()) return
      // 把真实错误暴露出来（例如后端未部署该接口会返回 404/网络错误），便于定位"空白"根因
      this.setData({ error: err.message || '加载失败', profile: null })
    } finally {
      if (current()) this.setData({ loading: false })
    }
  },

  /**
   * 客户特权（v40）。规格见 docs/design/20 §4。
   *
   * 产品决定（docs/design/16 D6）：不做个人/企业客户的显式区分，
   * 差异化一律落到「站长在客户画像里给特权」—— 这个区块就是那个落点。
   * ⚠️ 未实现的类型（折扣率/免配送次数/允许退票）后端会在授予时直接拒，
   * 且**不**出现在 grantableTypes 里，所以界面上根本不会出现"点了没反应"的开关。
   */
  async loadPrivileges(id) {
    const context = this.customerContext(), seq = this._privilegeReadSeq = (this._privilegeReadSeq || 0) + 1
    const current = () => seq === this._privilegeReadSeq && this.currentCustomerContext(context)
    if (!current()) return
    this.setData({ privilegesLoading: true, privilegesError: '' })
    try {
      const res = await httpGet(privilegesPath(id))
      if (!current()) return
      const d = res.data || {}
      this.setData({ privileges: d.privileges || [], grantableTypes: d.grantableTypes || [] })
    } catch (err) {
      if (!current()) return
      this.setData({ privilegesError: err.message || '特权加载失败' })
    } finally {
      if (current()) this.setData({ privilegesLoading: false })
    }
  },

  onGrantPrivilege(e) {
    const type = e.currentTarget.dataset.type
    const t = this.data.grantableTypes.find(x => x.type === type)
    if (!t) return
    showBusinessModal({ confirmColor: '#1264D6', cancelColor: '#637083',  confirmText: '确认授予',
      title: '授予特权',
      content: (t.text || '特权类型待核实') + '：' + (t.desc || ''),
      success: async (r) => {
        if (!r.confirm) return
        try {
          await httpPost(privilegesPath(this.data.id), { type })
          wx.showToast({ title: '已授予', icon: 'success' })
          await this.loadPrivileges(this.data.id)
        } catch (err) {
          wx.showToast({ title: err.message || '授予失败', icon: 'none' })
        }
      }
    })
  },

  /** 撤销。后端在"本来就没有"时会报错——如实弹出，不要无条件提示成功（AGENTS §8.20）。 */
  onRevokePrivilege(e) {
    const type = e.currentTarget.dataset.type
    showBusinessModal({ confirmColor: '#BD3328', cancelColor: '#637083',  confirmText: '确认撤销',
      title: '撤销特权？',
      content: '撤销后该客户在本站立即恢复默认规则。',
      success: async (r) => {
        if (!r.confirm) return
        try {
          await httpDel(privilegesPath(this.data.id) + '/' + type)
          wx.showToast({ title: '已撤销', icon: 'success' })
          await this.loadPrivileges(this.data.id)
        } catch (err) {
          wx.showToast({ title: err.message || '撤销失败', icon: 'none' })
        }
      }
    })
  },

  /**
   * 加载「本站资产」。
   * 独立于画像主接口：画像挂了（例如接口未部署）不应该连带把资产区块也变成一片空白，
   * 反之亦然，两个区块各自给出错误提示，便于定位。
   */
  async loadAssets(id) {
    const context = this.customerContext(), seq = this._assetReadSeq = (this._assetReadSeq || 0) + 1
    const current = () => seq === this._assetReadSeq && this.currentCustomerContext(context)
    if (!current()) return
    this.setData({ assetsLoading: true, assetsError: '' })
    try {
      const res = await getCustomerAssets(id)
      if (!current()) return
      if (res && res.code === 0 && res.data) {
        this.setData({ assets: this.decorateAssets(res.data), recordsExpanded: false })
      } else {
        this.setData({ assets: null, assetsError: (res && res.message) || '资产信息不可用' })
      }
    } catch (err) {
      if (!current()) return
      this.setData({ assets: null, assetsError: err.message || '资产加载失败' })
    } finally {
      if (current()) this.setData({ assetsLoading: false })
    }
  },

  /** 金额/文案统一在 JS 侧兜底，模板只做取值，避免出现 undefined/NaN */
  decorateAssets(a) {
    const money = (v) => v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? '待核实' : Number(v).toFixed(2)
    const int = (v) => Number(v || 0)

    a.heldBuckets = int(a.heldBuckets)
    a.inTransitBuckets = int(a.inTransitBuckets)
    a.owedBuckets = int(a.owedBuckets)
    a.ticketQuantity = int(a.ticketQuantity)
    a.depositBalanceText = money(a.depositBalance)
    a.ticketValueText = money(a.ticketValue)
    a.totalAssetValueText = money(a.totalAssetValue)
    a.barrelDepositTotalText = money(a.barrelDepositTotal)
    a.owedBucketsText = a.owedBucketsText || (a.owedBuckets > 0 ? a.owedBuckets + ' 个' : '无欠桶')
    a.hasOwed = a.owedBuckets > 0

    a.barrels = (a.barrels || []).map((b) => {
      const over = int(b.overQty)
      const occupied = b.occupiedQty != null ? int(b.occupiedQty) : (int(b.heldQty) + over)
      return {
        ...b,
        overQty: over,
        occupiedQty: occupied,
        // 还桶上限 = 占用（权益 + over）。over 可为负，负数表示多还的桶寄存在水站，
        // 那时占用小于权益，能还的也就更少——不能用权益数当上限，否则后端会拒。
        maxReturnableQty: Math.max(0, occupied),
        // 欠桶 / 暂存文案：over=0 时留空，模板用 wx:if 控制
        overText: over > 0 ? ('欠桶 ' + over + ' 个') : (over < 0 ? ('水站暂存 ' + (-over) + ' 个') : ''),
        hasOver: over !== 0,
        depositPerBucketText: money(b.depositPerBucket),
        depositAmountText: money(b.depositAmount)
      }
    })

    a.tickets = (a.tickets || []).map((t) => ({
      ...t,
      remainQuantity: int(t.remainQuantity),
      unitPriceText: money(t.unitPrice),
      totalValueText: money(t.totalValue)
    }))

    a.records = (a.records || []).map((r, i) => ({
      ...r,
      idx: i,
      // direction 由后端给（IN/OUT/FLAT），模板据此配色
      directionClass: r.direction === 'IN' ? 'in' : (r.direction === 'OUT' ? 'out' : 'flat')
    }))

    // 预切一份预览列表：WXML 里不让 wx:for 与 wx:if 同节点（官方不推荐），
    // 改成"展开/收起切换的是两个现成数组"，模板里只做取值。
    a.recordsPreview = a.records.slice(0, this.data.recordsPreviewCount)
    a.hasMoreRecords = a.records.length > this.data.recordsPreviewCount
    return a
  },

  onToggleRecords() {
    this.setData({ recordsExpanded: !this.data.recordsExpanded })
  },

  onRefreshAssets() {
    if (this.data.assetsLoading) return
    this.loadAssets(this.data.id)
  },

  /**
   * 资产调整单入口（站长专属，见 pages/station-mgmt/customers/adjust/）。
   * 调整是「人工补录/订正」的唯一入口：桶权益、欠桶、押金、水票的历史漏录都走这里，
   * 不再新开第二条改账路径。
   */
  onOpenAdjust() {
    wx.navigateTo({
      url: `/pages/station-mgmt/customers/adjust/index?customerId=${this.data.id}`
    })
  },

  /**
   * [2026-09-30 F-26] 代客下单入口 —— **做进客户里**（产品裁定：不再在首页单独拎一张卡）。
   *
   * <p>把当前客户带进 `place-order`（`?customerId=` 让它跳过"选客户"第一步）。这条路径存在的
   * 理由不是省一次点击：**电话单不录进系统，这批桶就不入桶账**（见 place-order 页头的说明）——
   * 站长的顺手程度直接决定桶账真不真。</p>
   *
   * <p>⚠️ 目标页自己要再校验一次"这个客户属于本站吗"（不依赖本页传参可信）：本页的 `this.data.id`
   * 来自上一个页面，而 `order-assist/customers` 只返回本站客户 —— 不在名单里时目标页会明确提示。</p>
   */
  onPlaceOrder() {
    wx.navigateTo({
      url: `/pages/station-mgmt/place-order/index?customerId=${this.data.id}`
    })
  },

  // ============ 纯还桶：只冲减 over，不扣权益、不退款 ============
  // 为什么必须有这个入口：旧模型里消掉欠桶的唯一途径是"下次配送时多还"，
  // 而欠桶超阈值又会被拒单 → 顾客一旦欠桶就永久锁死、押金退不出来。
  // 有了它，随时能把桶还回来，over 可以为负（多还的桶由水站暂存，合法）。

  onShowReturnEmpty() {
    if (this.data.returnSubmitting || this._returnFlight || this._returnHidden || this._returnDisposed) return
    const pending = this.restoreReturnIntent()
    if (this.data.returnRecoveryError) return
    this._returnModalContext = this.returnContext()
    if (pending) {
      const barrels = this.data.assets && this.data.assets.barrels || []
      this.setData({ showReturnEmpty: true, returnItems: pending.items.map(item => {
        const product = barrels.find(b => Number(b.productId) === item.productId)
        return { ...item, productName: product && product.productName || '原商品 #' + item.productId, max: item.qty, over: 0 }
      }) })
      return
    }
    if (this.data.assetsLoading || this.data.assetsError) return
    const barrels = (this.data.assets && this.data.assets.barrels) || []
    const items = barrels
      .filter(b => b.maxReturnableQty > 0)
      .map(b => ({
        productId: b.productId,
        productName: b.productName + (b.productSpec ? ' ' + b.productSpec : ''),
        max: b.maxReturnableQty,
        over: b.overQty || 0,
        // 有欠桶时预填欠桶数，站长点一下就能把欠桶冲平；没欠桶就留空让站长自己填
        qty: b.overQty > 0 ? Math.min(b.overQty, b.maxReturnableQty) : 0
      }))
    if (items.length === 0) {
      wx.showToast({ title: '该客户当前没有可还的桶', icon: 'none' })
      return
    }
    this.setData({ showReturnEmpty: true, returnItems: items })
  },

  onCloseReturnEmpty() {
    if (this.data.returnSubmitting || this._returnFlight) return
    this._returnModalContext = null
    this.setData({ showReturnEmpty: false })
  },

  onReturnEmptyQtyInput(e) {
    if (this.data.returnSubmitting || this.data.returnPending) return
    const idx = Number(e.currentTarget.dataset.idx)
    const max = Number(e.currentTarget.dataset.max)
    let v = parseInt(e.detail.value) || 0
    if (v < 0) v = 0
    if (v > max) v = max
    this.setData({ ['returnItems[' + idx + '].qty']: v })
  },

  async onSubmitReturnEmpty() {
    // 2026-10-23：仅置灰view曾仍能连点；同次交桶每次换键会重复减桶，必须先锁住原意图。
    if (this._returnFlight || this.data.returnSubmitting || !this.data.showReturnEmpty || this._returnHidden || this._returnDisposed) return
    if (!this._returnModalContext || !this.currentReturnContext(this._returnModalContext)) {
      this.setData({ showReturnEmpty: false, returnRecoveryError: '办理身份已变化，请重新打开并核对原还桶登记' })
      return
    }
    let context
    try { context = this.returnContext() }
    catch (err) { this.setData({ returnRecoveryError: err.message }); return }
    const flight = this._returnFlight = { context }
    let intent, hadPending = false, sent = false
    this.setData({ returnSubmitting: true })
    try {
      intent = this.readReturnIntent(context)
      hadPending = !!intent
      if (!intent && (this.data.assetsLoading || this.data.assetsError)) {
        this.setData({ returnMessage: '请先重新核对客户资产，再办理本次还桶' })
        return
      }
      if (!intent) intent = returnIntent.prepare(context.actor, context.customerId, (this.data.returnItems || []).filter(i => i.qty > 0))
      this._returnIntent = intent
      if (!this.currentReturnContext(context) || this._returnHidden) return
      this.setData({ returnPending: true, returnRecoveryError: '', returnMessage: '正在核对原还桶登记，请勿再次交桶。' })
      if (!this._returnKnown || this._returnKnown.clientToken !== intent.clientToken) {
        sent = true
        const res = await returnEmptyBuckets(intent.customerId, intent.items, intent.clientToken, intent.note)
        const data = res && res.data
        if (!res || ![0, 200].includes(res.code) || !data || !(data.duplicate === true
            || Array.isArray(data.changes) && data.changes.length === intent.items.length && intent.items.every(item =>
              data.changes.some(change => Number(change.productId) === item.productId && Number(change.qty) === item.qty)))) {
          throw new Error('还桶登记结果暂未查到')
        }
        if (!this.currentReturnContext(context)) return
        this._returnKnown = intent
      }
      returnIntent.clear(intent)
      this._returnIntent = null
      if (!this.currentReturnContext(context)) return
      this.setData({ showReturnEmpty: false, returnPending: false, returnMessage: '', returnRecoveryError: '' })
      if (!this._returnHidden) {
        wx.showToast({ title: hadPending ? '原还桶登记已确认' : '还桶已登记', icon: 'success' })
        this.loadAssets(context.customerId)
      }
    } catch (err) {
      if (!this.currentReturnContext(context)) return
      const knownFailure = sent && !hadPending && err && (err.businessRejected === true || err.requestMayHaveOccurred === false)
      if (knownFailure) {
        try { returnIntent.clear(intent); this._returnIntent = null }
        catch (storageError) { this.setData({ returnRecoveryError: storageError.message }); return }
        this.setData({ returnPending: false, returnMessage: err.message || '本次登记未通过，请核对数量后再办理' })
      } else if (intent) {
        this.setData({ returnPending: true, returnMessage: this._returnKnown && this._returnKnown.clientToken === intent.clientToken
          ? '登记已确认，本地记录尚未清理。请核对原登记，勿再次交桶。'
          : '原还桶登记结果待核实，请勿再次交桶；只能重试原登记核对。' })
      } else this.setData({ returnRecoveryError: err.message || '原还桶记录未能保存，请重新核对' })
    } finally {
      if (this._returnFlight === flight) {
        this._returnFlight = null
        if (this.currentReturnContext(context)) this.setData({ returnSubmitting: false })
      }
    }
  },

  // 金额格式化在 JS 里做（wxml 不做数值格式化，避免兼容问题）
  decorate(p) {
    const n = (v) => v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? '待核实' : Number(v).toFixed(2)
    p.avgOrderAmountText = n(p.avgOrderAmount)
    p.totalConsumptionText = n(p.totalConsumption)
    p.monthConsumptionText = n(p.monthConsumption)
    p.depositBalanceText = n(p.depositBalance)
    p.monthOrders = p.monthOrders === null || p.monthOrders === undefined || p.monthOrders === '' ? '待核实' : p.monthOrders
    p.totalOrders = p.totalOrders === null || p.totalOrders === undefined || p.totalOrders === '' ? '待核实' : p.totalOrders
    p.ticketBalance = p.ticketBalance === null || p.ticketBalance === undefined || p.ticketBalance === '' ? '待核实' : p.ticketBalance
    return p
  },

  onPullDownRefresh() {
    return this.refreshCustomerData().finally(() => wx.stopPullDownRefresh())
  },

  onCall(e) {
    const { phone } = e.currentTarget.dataset
    if (phone) wx.makePhoneCall({ phoneNumber: phone })
  },

  // 切换货到付款权限
  async onToggleCod(e) {
    const enabled = e.detail.value
    const context = this.customerContext()
    if (this.data.saving || this.data.loading || !this.data.profile || !this.currentCustomerContext(context)) return
    this._profileReadSeq = (this._profileReadSeq || 0) + 1
    this.setData({ saving: true })
    try {
      // 显式给全 body：`updateOfflinePayment` 只接受 payload 形态
      // （它曾在 api 模块里以 `(id, boolean)` 重载出现，两次声明直接让整个模块语法错误，2026-09-19）
      const res = await updateOfflinePayment(this.data.id, { offlinePaymentEnabled: enabled ? 1 : 0 })
      if (!this.currentCustomerContext(context)) return
      if (res.code === 0) {
        this.setData({
          'profile.codEnabled': enabled,
          'profile.offlinePaymentEnabled': enabled ? 1 : 0
        })
        wx.showToast({ title: enabled ? '已开通货到付款' : '已关闭货到付款', icon: 'success' })
      } else {
        wx.showToast({ title: res.message || '操作失败', icon: 'none' })
        this.setData({ 'profile.codEnabled': !enabled })
      }
    } catch (err) {
      if (!this.currentCustomerContext(context)) return
      wx.showToast({ title: err.message || '操作失败', icon: 'none' })
      this.setData({ 'profile.codEnabled': !enabled })
    } finally {
      this.setData({ saving: false })
      if (this._profileRefreshAfterSave && this.currentCustomerContext(context)) this.loadProfile(this.data.id)
    }
  }
})
