const { getTicketAccounts, getTicketRecords, getTicketPackages, purchaseTicket, getTicketPurchaseResult, closeTicketPurchaseIntent } = require('../../api/ticket')
const { getStationProducts } = require('../../api/product')
const { getBarrelSummaryByType } = require('../../api/barrel')
const assetViewStation = require('../../utils/asset-view-station')
const { getBaseUrl, API } = require('../../config/api')
const { getAccessToken, getCustomerId, captureSession, isCurrentSession } = require('../../utils/token')
const { STORAGE_KEYS } = require('../../utils/storage-keys')

// 只清理错误的展示文字；原对象的 code、resultUnknown、businessRejected 均不改动。
function viewError(error, fallback = '暂时无法完成，请稍后重试') {
  // 通用请求层不再写死购票场景；本页仍提醒核对原购买，避免另起一次购买。
  if (error && error.sessionChanged) return '登录身份已变化，请重新进入后查询原购买记录'
  const text = error && typeof error.message === 'string' ? error.message.trim() : ''
  return /[\u3400-\u9fff]/.test(text)
    && !/(?:Exception|Error:|\bat\s+\S+\(|\bSQL\b|https?:\/\/|request:fail|errno|stack|[A-Z][A-Z_]{5,})/.test(text)
    ? text : fallback
}

Page({
  onExitHelp() { wx.showModal({ confirmColor: '#1264D6', cancelColor: '#637083',  title:'剩余水票退出',content:'请联系原购买水站，站长核实原购买批次、剩余张数及退款金额后办理。已用票不退；赠票及没有原款来源的批次须另行核实。退票与退桶、退押金分别办理。',showCancel:false }) },
  data: {
    loading: true,
    needLogin: false,
    accountsStale: false,
    recordsStale: false,
    productsFailed: false,
    packagesLoading: false,
    packagesLoaded: false,
    purchaseNotice: '',
    purchaseCopiesError: '',
    accounts: [],
    records: [],
    currentTab: 0,
    totalTickets: 0,
    totalValue: 0,
    tabs: [
      { id: 0, name: '水票明细' },
      { id: 1, name: '消费记录' }
    ],
    showPurchase: false,
    buyProducts: [],
    buyForm: {
      productId: null,
      productName: '',
      faceValue: 0,
      quantity: 1,
      totalPrice: 0,
      paymentMethod: 1,
      // 仅定制套餐传 id；旧单张兼容套餐和统一折扣保留各自原请求形状。
      packageId: null,
      // 选中的**统一折扣档张数**（null = 没选统一档）。与 packageId 互斥。
      // ⚠️ 只传张数、不传价：价格由后端按"这款水自己的价 × 该档折扣"现算
      // （产品口径「对应水怎么统一打折」），客户端算价就等于自己定价。
      unifiedQty: null,
      // 保留旧缓存属性供原购买恢复；新购买只选择套餐。
      looseFaceValue: 0
    },
    // 当前商品在本站能买的档位（定制档位 or 站级统一折扣折算出来的档位，见后端 TicketTierService）
    buyPackages: [],
    // [2026-09-26 产品口径] 购票是**自助预付**，不是「向水站申请、等水站同意」：
    // 客户自己下单、自己付钱，水站只负责「收到钱」这个事实。
    // 文案两处都要真：**不能**写「提交申请后由水站确认收款」（把收款确认说成审批，已改），
    // **也不能**写「无需水站确认」—— 真实微信渠道还没接入，当前部署里"到账"这一步确实由站长
    // 确认收到钱才完成。所以只描述渠道与结果，到没到账由提交后返回的真实 status 决定（见 onBuySubmit）。
    buyMethods: [
      { id: 1, name: '微信支付', desc: '微信收款，付款到账后水票即可使用' }
    ],
    submitting: false,
    // 在线购票幂等键：持久原购买的编号；未知结果复用，明确发起下一笔才新建。
    // 后端 v33 起必传 —— 无订单支付在数据库层没有任何防重，缺了它连点两次「买票」
    // 会落两条待收款流水，站长两条都确认就会入账两次。
    purchaseIdempotencyKey: '',
    pendingPurchase: null,
    purchaseRecoveryText: '',
    purchaseStorageError: '',
    recoveringPurchase: false,
    currentStationId: null,
    currentStation: null,
    showStationPicker: false,
    stationList: [],
    // ===== 失败标记（[2026-09-20 真机联调]，全部空串/ false = 一切正常）=====
    // 为什么需要：本页原来三处静默降级，失败时页面渲染成「可用水票 0 张 / 暂无水票 / 暂无记录」，
    // 与"这客户确实没有水票"完全无法区分（AGENTS §8.22）。水票是**花钱买来的资产**，
    // 显示成 0 张比直接报错更让人慌 —— 客户会以为票没了。
    loadError: '',          // 汇总提示（顶部提示条）
    accountsFailed: false,  // 水票余额没拉到 → 余额显示「—」而不是 0
    recordsFailed: false,   // 消费记录没拉到 → 空态改成"没加载出来"
    packagesError: '',      // 套餐未读取时提示重试，不另起无套餐购买
  },

  onLoad(options) { assetViewStation.init(this, options) },
  onHide() {
    assetViewStation.suspend(this)
    // 2026-10-26：旧档位回调离页后会失效，原加载锁却未释放，返回后无法就近重试。
    if (this.data.packagesLoading) this.setData({ packagesLoading: false, packagesLoaded: false,
      packagesError: '购票档位读取已暂停，请重新加载；所选商品保持不变。' })
  },
  onUnload() { this.onHide() },
  async onShow() {
    assetViewStation.activate(this)
    this.restorePurchaseIntent()
    await Promise.all([this.loadData(), this.onQueryPurchaseResult()])
  },

  onRetryAssets() { return this.loadData() },
  onLogin() { wx.navigateTo({ url: '/pages/login/index' }) },
  onAssetStationChange(e) {
    if (!assetViewStation.select(this, e.detail)) return
    this.onClosePurchase()
    this.setData({ packagesError: '' })
    return this.loadData()
  },

  // 2026-10-02 F-75：原键只活在页面中，丢响应/退出会重复记款。
  // 先持久化完整原请求，未知结果不换键、不覆盖；每个客户单独保存，存储失败就不提交。
  purchaseStorageKey(customerId) { return STORAGE_KEYS.TICKET_PURCHASE_INTENT + customerId },
  purchasePositiveInteger(value) {
    return (typeof value === 'number' || (typeof value === 'string' && /^[1-9]\d*$/.test(value)))
      && Number.isSafeInteger(Number(value)) && Number(value) > 0
  },
  validPurchaseIntent(saved, customerId) {
    const positive = value => this.purchasePositiveInteger(value)
    const b = saved && saved.body
    if (!b || String(saved.customerId) !== String(customerId) || !positive(customerId)
      || !['stationId', 'productId', 'quantity'].every(k => positive(b[k]))
      || !positive(b.paymentMethod) || ![1, 2].includes(Number(b.paymentMethod))
      || typeof b.idempotencyKey !== 'string' || !b.idempotencyKey.trim()
      || b.idempotencyKey.trim() !== b.idempotencyKey || b.idempotencyKey.length > 64
      || (b.packageId != null && !positive(b.packageId)) || (b.unifiedQty != null && !positive(b.unifiedQty))
      || (b.packageId != null && b.unifiedQty != null)
      || (b.unifiedQty != null && Number(b.quantity) % Number(b.unifiedQty) !== 0)) return false
    return !Object.prototype.hasOwnProperty.call(saved, 'result') || this.validPurchaseResult(saved, saved.result)
  },
  validPurchaseResult(saved, result) {
    if (!result || !this.purchasePositiveInteger(result.paymentId)
      || !['stationId', 'productId', 'quantity', 'paymentMethod', 'status'].every(k => this.purchasePositiveInteger(result[k]))
      || ![1, 2, 3, 4].includes(Number(result.status))
      || (result.packageId != null && !this.purchasePositiveInteger(result.packageId))
      || (result.unifiedQty != null && !this.purchasePositiveInteger(result.unifiedQty))
      || !['number', 'string'].includes(typeof result.amount) || result.amount === ''
      || !Number.isFinite(Number(result.amount)) || Number(result.amount) < 0) return false
    return ['stationId', 'productId', 'quantity', 'paymentMethod', 'packageId', 'unifiedQty']
      .every(k => String(result[k] == null ? '' : result[k]) === String(saved.body[k] == null ? '' : saved.body[k]))
  },
  restorePurchaseIntent() {
    let customerId
    let saved
    try {
      if (!captureSession().loggedIn) {
        this.setData({ pendingPurchase: null, purchaseIdempotencyKey: '', purchaseStorageError: '', purchaseRecoveryText: '' }); return null
      }
      customerId = getCustomerId()
      this._purchaseOwner = customerId
      saved = customerId && wx.getStorageSync(this.purchaseStorageKey(customerId))
      // 2026-10-02：删除抛错/读回失败后不能因下一次读取为空就放行另一款；保留本周期原凭据。
      if (!saved && this._purchaseSafetyHolds && this._purchaseSafetyHolds[String(customerId)]) {
        saved = this._purchaseSafetyHolds[String(customerId)]
        wx.setStorageSync(this.purchaseStorageKey(customerId), saved)
        if (!this.sameStoredIntent(saved, wx.getStorageSync(this.purchaseStorageKey(customerId)))) throw new Error('保存未成功')
      }
      if (saved && !this.validPurchaseIntent(saved, customerId)) throw new Error('原购买记录不完整')
    } catch (err) {
      this.setData({ pendingPurchase: saved && saved.body ? saved : null,
        purchaseStorageError: '原购买记录读取失败或不完整，请重试或联系水站核实；暂不能另买。' })
      return null
    }
    this.setData({ pendingPurchase: saved || null, purchaseIdempotencyKey: saved ? saved.body.idempotencyKey : '',
      purchaseStorageError: '', purchaseRecoveryText: saved ? this.purchaseSummary(saved) : '' })
    return saved || null
  },
  purchaseSummary(saved) {
    return '原购票：' + (saved.productName || '水票') + ' ' + saved.body.quantity + ' 张（' + (saved.stationName || '原水站') + '）。'
      + (saved.result ? (saved.result.statusText || '已登记，等待核实到账') + '，金额 ¥' + saved.result.amount
        : '结果尚未确认，请先查询或重试原购买。')
  },
  samePurchaseBody(a, b) {
    return ['stationId', 'productId', 'quantity', 'paymentMethod', 'packageId', 'unifiedQty']
      .every(k => String(a[k] == null ? '' : a[k]) === String(b[k] == null ? '' : b[k]))
  },
  sameStoredIntent(a, b) { return !!b && JSON.stringify(a) === JSON.stringify(b) },
  holdPurchaseStorageError(saved) {
    if (!this._purchaseSafetyHolds) this._purchaseSafetyHolds = {}
    this._purchaseSafetyHolds[String(saved.customerId)] = saved
    this.setData({ pendingPurchase: saved, purchaseIdempotencyKey: saved.body.idempotencyKey,
      purchaseRecoveryText: this.purchaseSummary(saved),
      purchaseStorageError: '原购买记录保存或清理未成功，请重新读取或联系水站核实；暂不能另买。' })
  },
  savePurchaseIntent(saved) {
    try {
      const latest = wx.getStorageSync(this.purchaseStorageKey(saved.customerId))
      if (latest && (!this.validPurchaseIntent(latest, saved.customerId)
        || latest.body.idempotencyKey !== saved.body.idempotencyKey || !this.samePurchaseBody(latest.body, saved.body))) {
        throw new Error('原记录已变化')
      }
      wx.setStorageSync(this.purchaseStorageKey(saved.customerId), saved)
      if (!this.sameStoredIntent(saved, wx.getStorageSync(this.purchaseStorageKey(saved.customerId)))) throw new Error('保存未成功')
    } catch (err) { this.holdPurchaseStorageError(saved); throw new Error('原购买记录保存失败，请重试；暂不能另买') }
    if (this._purchaseSafetyHolds) delete this._purchaseSafetyHolds[String(saved.customerId)]
    this.setData({ pendingPurchase: saved, purchaseIdempotencyKey: saved.body.idempotencyKey,
      purchaseStorageError: '', purchaseRecoveryText: this.purchaseSummary(saved) })
  },
  clearPurchaseIntent(saved) {
    try {
      const key = this.purchaseStorageKey(saved.customerId)
      const latest = wx.getStorageSync(key)
      if (!latest || !this.validPurchaseIntent(latest, saved.customerId)
        || latest.body.idempotencyKey !== saved.body.idempotencyKey || !this.samePurchaseBody(latest.body, saved.body)) throw new Error('原记录已变化')
      wx.removeStorageSync(key)
      if (wx.getStorageSync(key)) throw new Error('清理未成功')
    } catch (err) { this.holdPurchaseStorageError(saved); throw new Error('原购买记录清理失败，请重试；暂不能另买') }
    if (this._purchaseSafetyHolds) delete this._purchaseSafetyHolds[String(saved.customerId)]
    this.setData({ pendingPurchase: null, purchaseIdempotencyKey: '', purchaseRecoveryText: '', purchaseStorageError: '' })
  },
  applyPurchaseResult(saved, result) {
    if (String(getCustomerId()) !== String(saved.customerId)) return
    if (!this.validPurchaseResult(saved, result)) throw new Error('购买结果或内容暂不能确认，请保留原购买并查询')
    if (Number(result.status) === 2) {
      this.clearPurchaseIntent(saved)
      wx.showToast({ title: '购买成功，水票已到账', icon: 'success' })
    } else this.savePurchaseIntent(Object.assign({}, saved, { result }))
  },
  async onQueryPurchaseResult() {
    if (this.data.recoveringPurchase || this.data.submitting) return
    const saved = this.restorePurchaseIntent()
    if (!saved) return
    const session = captureSession()
    this.setData({ recoveringPurchase: true })
    try {
      const res = await getTicketPurchaseResult(saved.body.idempotencyKey)
      if (!isCurrentSession(session)) return
      if (!res || res.code !== 0) throw res || new Error('购买结果查询失败')
      if (res.data) { this.applyPurchaseResult(saved, res.data); await this.loadData() }
      else {
        // 缓存的登记结果不能压过本次未查到；降回待核实，但仍永久保留原 body/key。
        const unconfirmed = Object.assign({}, saved)
        delete unconfirmed.result
        this.savePurchaseIntent(unconfirmed)
        this.setData({ purchaseRecoveryText: this.purchaseSummary(unconfirmed) + ' 暂未查到原款，可重试原购买或核实后结束原购买。' })
      }
    } catch (err) {
      if (isCurrentSession(session)) this.setData({ purchaseRecoveryText: this.purchaseSummary(saved) + ' 查询失败，可稍后重试。' })
    } finally { this.setData({ recoveringPurchase: false }) }
  },
  async onRetryOriginalPurchase() {
    const saved = this.restorePurchaseIntent()
    if (saved && !saved.result) await this.submitPurchaseIntent(saved, true)
  },
  onCloseOriginalPurchase() {
    const saved = this.restorePurchaseIntent()
    if (!saved || saved.result || this.data.submitting || this.data.recoveringPurchase) return
    const session = captureSession()
    wx.showModal({ confirmColor: '#1264D6', cancelColor: '#637083',  title: '结束原购买', content: '仅在确认原购买没有登记款项后才能结束；如已登记款项，将为你查回原款，不办理取消或退款。',
      confirmText: '核实结束', success: r => {
        if (r.confirm && isCurrentSession(session)) this.closeOriginalPurchase(saved, session)
      } })
  },
  async closeOriginalPurchase(saved, session = captureSession()) {
    if (!isCurrentSession(session) || this.data.submitting || this.data.recoveringPurchase) return
    const latest = this.restorePurchaseIntent()
    if (!latest || latest.body.idempotencyKey !== saved.body.idempotencyKey || latest.result) return
    this.setData({ recoveringPurchase: true })
    try {
      const res = await closeTicketPurchaseIntent(saved.body.idempotencyKey)
      if (!isCurrentSession(session)) return
      const receipt = res && res.code === 0 && res.data
      // 关闭必须收到同一原编号的永久封锁凭据；普通拒绝、查询空结果、超时都不能清键。
      if (!receipt || receipt.idempotencyKey !== saved.body.idempotencyKey) throw new Error('原购买暂不能结束，请保留原记录')
      if (receipt.closed === true && receipt.payment == null) {
        this.clearPurchaseIntent(saved)
        wx.showToast({ title: '原购买已结束，可重新选择', icon: 'none' })
      } else if (receipt.closed === false && receipt.payment) this.applyPurchaseResult(saved, receipt.payment)
      else throw new Error('原购买暂不能结束，请保留原记录')
    } catch (err) {
      if (isCurrentSession(session)) wx.showToast({ title: viewError(err, '原购买结束未确认，请查询'), icon: 'none' })
    } finally { this.setData({ recoveringPurchase: false }) }
  },
  async onStartNextPurchase() {
    const saved = this.restorePurchaseIntent()
    if (!saved || !saved.result || this.data.submitting || this.data.recoveringPurchase) return
    const session = captureSession()
    const confirmed = await new Promise(resolve => wx.showModal({ confirmColor: '#1264D6', cancelColor: '#637083',  title: '另买一笔水票', content: this.purchaseSummary(saved)
      + ' 继续将登记另一笔购买，请勿为等待到账的原款重复购买。', confirmText: '另买一笔',
      success: r => resolve(r.confirm), fail: () => resolve(false) }))
    if (!confirmed || !isCurrentSession(session) || this.data.submitting || this.data.recoveringPurchase) return
    const latest = this.restorePurchaseIntent()
    if (!latest || latest.body.idempotencyKey !== saved.body.idempotencyKey || !latest.result) return
    this.setData({ recoveringPurchase: true })
    let ready = false
    try {
      // 2026-10-02：合法形状的缓存也可能损坏；另买前须再次查回原款，不能仅信本地 result。
      const res = await getTicketPurchaseResult(saved.body.idempotencyKey)
      if (!isCurrentSession(session)) return
      if (!res || res.code !== 0 || !this.validPurchaseResult(saved, res.data)) throw new Error('原款暂未核实，请先查询原购买')
      this.clearPurchaseIntent(latest)
      ready = true
    } catch (err) { if (isCurrentSession(session)) wx.showToast({ title: viewError(err), icon: 'none' }) }
    finally { this.setData({ recoveringPurchase: false }) }
    if (ready && isCurrentSession(session)) this.onShowPurchase()
  },
  purchaseEditBlocked() {
    if (this.data.submitting || this.data.recoveringPurchase || this.data.pendingPurchase || this.data.purchaseStorageError) {
      wx.showToast({ title: '请先处理原购买结果', icon: 'none' }); return true
    }
    return false
  },

  // 只在明确提交新购买且没有原购买占用时生成编号。
  genPurchaseIdempotencyKey() {
    return 'ticket_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9)
  },

  async loadData() {
    const context = assetViewStation.beginRead(this)
    const stationId = context.stationId
    const needLogin = !context.session.loggedIn || !context.session.customerId
    this.setData({ needLogin })
    if (needLogin) { this.setData({ loading: false, accounts: [], records: [], buyProducts: [], totalTickets: null, totalValue: null,
      accountsFailed: true, recordsFailed: true, loadError: '请先登录后查看本人水票与购买记录。' }); return }
    const keep = this._lastAssetScope && this._lastAssetScope.stationId === stationId && isCurrentSession(this._lastAssetScope.session)
    let previous = keep ? { accounts: this.data.accounts, records: this.data.records, totalTickets: this.data.totalTickets, totalValue: this.data.totalValue } : null
    this.setData({ currentStationId: stationId, currentStation: stationId ? { id: stationId, name: this.data.stationName } : null,
      loading: !keep, ...(keep ? {} : { accounts: [], records: [], totalTickets: null, totalValue: null }), buyProducts: [],
      accountsStale: false, recordsStale: false, productsFailed: false,
      accountsFailed: false, recordsFailed: false, loadError: '' })
    if (!stationId) { this.setData({ loading: false, accountsFailed: true, recordsFailed: true,
      loadError: '请在上方选择要查看的资产水站' }); return }
    const failed = []
    let denied = false
    const caught = label => err => { if (/无权|权限|登录/.test(err && err.message || '')) denied = true; failed.push(label); console.warn('[Ticket] ' + label, err && err.message); return null }
    try {
      // 商品仅影响新购入口；资产和记录不等待商品请求成功。
      const [accountsRes, recordsRes, productsRes] = await Promise.all([
        getTicketAccounts(stationId).catch(caught('水票余额')),
        getTicketRecords(stationId).catch(caught('消费记录')),
        getStationProducts(stationId).catch(caught('可购商品'))
      ])
      if (!assetViewStation.current(this, context)) return
      if (denied) previous = null
      const count = value => (typeof value === 'number' || typeof value === 'string' && value.trim())
        && Number.isSafeInteger(Number(value)) && Number(value) >= 0
      const accountsOK = !!(accountsRes && accountsRes.code === 0 && Array.isArray(accountsRes.data)
        && accountsRes.data.every(a => a && count(a.remainQuantity)))
      const recordsOK = !!(recordsRes && recordsRes.code === 0 && Array.isArray(recordsRes.data))
      const productsOK = !!(productsRes && productsRes.code === 0 && Array.isArray(productsRes.data))
      const accounts = accountsOK ? accountsRes.data : previous ? previous.accounts : []
      this.setData({ accounts, records: recordsOK ? recordsRes.data : previous ? previous.records : [],
        totalTickets: accountsOK ? accounts.reduce((sum, a) => sum + Number(a.remainQuantity), 0) : previous ? previous.totalTickets : null,
        // 延续原展示金额；不将当前售价估值称作可退款金额。
        totalValue: accountsOK ? (accounts.every(a => a.rightAmount != null && String(a.rightAmount).trim() !== '' && Number.isFinite(Number(a.rightAmount)) && Number(a.rightAmount) >= 0)
          ? accounts.reduce((sum, a) => sum + Number(a.rightAmount), 0) : null) : previous ? previous.totalValue : null,
        accountsFailed: !accountsOK, recordsFailed: !recordsOK,
        accountsStale: !accountsOK && !!previous && previous.totalTickets != null,
        recordsStale: !recordsOK && !!previous && previous.records.length > 0,
        productsFailed: !productsOK,
        buyProducts: productsOK ? productsRes.data.filter(p => p.ticketEnabled === 1 || p.category === 1) : [],
        loadError: !accountsOK || !recordsOK || !productsOK ? '部分水票数据未更新；标记“上次读取”的内容请重新核对，未加载项不代表 0。' : '' })
      this._lastAssetScope = context
    } catch (error) {
      if (assetViewStation.current(this, context)) this.setData({ accountsFailed: true, recordsFailed: true,
        loadError: viewError(error, '水票数据暂未加载，请重试') })
    } finally { if (assetViewStation.current(this, context)) this.setData({ loading: false }) }
  },

  // 兼容旧事件名，统一由本人资产关系选择器管理；不修改全局下单水站。
  loadStationList() { return this.onOpenStationPicker() },
  onOpenStationPicker() { const picker = this.selectComponent('#asset-station-view'); if (picker) return picker.openPicker() },
  onCloseStationPicker() { const picker = this.selectComponent('#asset-station-view'); if (picker) picker.close() },
  onSelectStation(e) { return this.onAssetStationChange(e) },

  onTabChange(e) {
    const { id } = e.currentTarget.dataset
    this.setData({ currentTab: id })
  },

  onShowPurchase() {
    this.restorePurchaseIntent()
    if (this.purchaseEditBlocked()) return
    if (!this.data.currentStationId) {
      wx.showToast({ title: '请先选择水站', icon: 'none' })
      return
    }
    this.setData({ showPurchase: true })
  },

  onClosePurchase() {
    this.setData({ showPurchase: false, buyPackages: [], packagesLoading: false, packagesLoaded: false, buyForm: { productId: null, productName: '', faceValue: 0, quantity: 1, totalPrice: 0, paymentMethod: 1, packageId: null, unifiedQty: null, looseFaceValue: 0 } })
  },

  /** 弹窗内容区吞掉点击，避免冒泡到遮罩触发关闭（wxml 用 catchtap 绑定） */
  stopPropagation() {},

  onBuyProductSelect(e) {
    if (this.purchaseEditBlocked()) return
    const { id } = e.currentTarget.dataset
    // dataset 类型可能是 string/number，统一按字符串比较，避免 === 恒 false
    const product = this.data.buyProducts.find(p => String(p.id) === String(id))
    if (!product) return
    // 面值 = 后端下发的本站水票价（与 /api/tickets/purchase 的计费完全同源），不再用零售价。
    // ⚠️ 走统一折扣的商品没配水票价 → 这个值可能是 0，此时**只能按档位买**（页面不会显示散买）。
    const rawPrice = product.effectiveTicketPrice != null ? product.effectiveTicketPrice : product.price
    const price = ['number', 'string'].includes(typeof rawPrice) && String(rawPrice).trim() && Number.isFinite(Number(rawPrice))
      ? Number(rawPrice) : null
    // 换商品必须清掉已选档位：档位是「本站 + 本商品」的，留着上一个商品的档位
    // 会被后端以"档位与本水站/本商品不匹配"拒绝
    this.setData({
      'buyForm.productId': product.id,
      'buyForm.productName': product.name,
      'buyForm.faceValue': price,
      'buyForm.looseFaceValue': price,
      'buyForm.packageId': null,
      'buyForm.unifiedQty': null,
      'buyForm.quantity': 1,
      'buyForm.totalPrice': null,
      'buyForm.selectedTierKey': '', 'buyForm.copies': 1, 'buyForm.packageQty': null, 'buyForm.packagePrice': null, purchaseCopiesError: ''
    })
    this.loadBuyPackages(product.id)
  },

  /**
   * 拉该商品在本站能买的档位。
   *
   * <p>后端返回的每一项带 {@code source}：{@code CUSTOM} = 站长给这款水挂的定制档位；
   * {@code UNIFIED} = 站级统一折扣按**这款水自己的价**折算出来的档位。两者在界面上
   * **长得一样**（产品口径：「在用户端看起来没区别」），前端只需记住选了哪一个。</p>
   *
   * <p>没有可用套餐时返回空数组并提示联系水站；旧单张配置由服务端投影为 1 张兼容套餐，
   * 新购买不再另走没有套餐的数量输入。</p>
   */
  async loadBuyPackages(productId) {
    const context = assetViewStation.beginRead(this, 'packages')
    this.setData({ buyPackages: [], packagesError: '', packagesLoading: true, packagesLoaded: false })
    try {
      const res = await getTicketPackages(context.stationId, productId)
      if (!assetViewStation.current(this, context) || String(this.data.buyForm.productId) !== String(productId) || !this.data.showPurchase) return
      if (!res || res.code !== 0 || !Array.isArray(res.data)) throw new Error('购票档位暂未加载，请重试')
      const amountKnown = value => ['number', 'string'].includes(typeof value) && String(value).trim() !== '' && Number.isFinite(Number(value)) && Number(value) >= 0
      if (res.data.some(row => !row || !Number.isSafeInteger(Number(row.qty)) || Number(row.qty) <= 0
        || !amountKnown(row.price) || !amountKnown(row.unitPrice)
        || !['CUSTOM', 'UNIFIED', 'RETAIL'].includes(row.source) || row.source === 'RETAIL' && Number(row.qty) !== 1
        || row.source === 'CUSTOM' && !(Number(row.packageId || row.id) > 0))) throw new Error('购票档位金额或张数尚未核实，请重试')
      this.setData({ buyPackages: res.data.map(row => ({ ...row, selectionKey: row.source + ':' + (row.packageId || row.id || row.qty) })), packagesLoaded: true })
    } catch (err) {
      if (!assetViewStation.current(this, context) || String(this.data.buyForm.productId) !== String(productId) || !this.data.showPurchase) return
      // [2026-09-20 真机联调] 这里原来是**刻意的静默降级**（"档位拉不到不该挡住买票"）——
      // 不阻断买票这条判据仍然成立（下面照旧退回散买），但"静默"是错的：档位 = 越买越便宜的
      // 价目表，拉不到时客户看到的是"这款水没有优惠"，与"确实没配档位"完全无法区分
      //（AGENTS §8.22）。套餐未核实时保留内容，并提示重新读取后再选择。
      console.warn('[Ticket] load ticket packages failed:', err && err.message)
      this.setData({ packagesError: '购票套餐尚未读取，请重试后选择套餐。' })
      wx.showToast({ title: '优惠档位没加载出来', icon: 'none' })
    } finally {
      if (assetViewStation.current(this, context) && String(this.data.buyForm.productId) === String(productId) && this.data.showPurchase) this.setData({ packagesLoading: false })
    }
  },

  onRetryPackages() { if (this.data.buyForm.productId && !this.data.packagesLoading) return this.loadBuyPackages(this.data.buyForm.productId) },

  /**
   * 选档位。金额一律用**服务端下发的档位价**，前端不做 price/qty 的算术 ——
   * 均价是快照进水票批次的值，前端算一遍就会出现"界面一个价、批次另一个价"。
   * 张数必须等于档位张数（后端会校验）。
   *
   * <p>定制档回传 {@code packageId}；统一折扣档**只回传张数** {@code unifiedQty}
   * （价格由服务端按该款水的价 × 折扣现算，客户端传不了价也不该传）。</p>
   */
  onBuyPackageSelect(e) {
    if (this.purchaseEditBlocked()) return
    const { id, source } = e.currentTarget.dataset
    const pkg = this.data.buyPackages.find(p => p.source === source && String(p.packageId || p.id || p.qty) === String(id))
    if (!pkg) return
    this.setData({
      'buyForm.packageId': pkg.source === 'CUSTOM' ? (pkg.packageId || pkg.id) : null,
      'buyForm.unifiedQty': pkg.source === 'UNIFIED' ? pkg.qty : null,
      'buyForm.selectedTierKey': pkg.selectionKey || pkg.source + ':' + (pkg.packageId || pkg.id || pkg.qty),
      'buyForm.packageQty': Number(pkg.qty), 'buyForm.packagePrice': Number(pkg.price),
      purchaseCopiesError: '', 'buyForm.copies': 1, 'buyForm.quantity': Number(pkg.qty),
      'buyForm.faceValue': pkg.unitPrice, 'buyForm.totalPrice': pkg.price
    })
  },

  onBuyQtyStep(e) {
    const current = Number(this.data.buyForm.copies) || 1
    this.applyPurchaseCopies(e.currentTarget.dataset.type === 'add' ? current + 1 : Math.max(1, current - 1))
  },
  onBuyQuantityInput(e) { this.applyPurchaseCopies(e.detail.value) },

  // 2026-10-26: preview uses whole copies of the selected server tier. The request
  // still carries total tickets; the server independently validates and prices it.
  applyPurchaseCopies(value) {
    if (this.purchaseEditBlocked()) return
    const form = this.data.buyForm
    if (!form.selectedTierKey) return
    const copies = /^[1-9]\d*$/.test(String(value)) ? Number(value) : null
    const quantity = copies != null ? copies * form.packageQty : null
    const cents = Math.round(form.packagePrice * 100)
    const totalCents = cents * copies
    const countValid = Number.isSafeInteger(quantity) && quantity > 0 && quantity <= 5000
    const moneyValid = Number.isSafeInteger(totalCents) && totalCents > 0 && totalCents <= 9999999999
    const valid = countValid && moneyValid
    // 2026-10-26: invalid/overflow input previously erased totals without explaining why.
    // Keep the selected tier and the exact input; block a new purchase until corrected.
    const purchaseCopiesError = copies == null ? '请填写大于 0 的整数份数'
      : !countValid ? '单次最多购买 5000 张，请减少份数'
      : !moneyValid ? '合计金额超出支持范围，请减少份数' : ''
    this.setData({ purchaseCopiesError, 'buyForm.copies': value, 'buyForm.quantity': valid ? quantity : null,
      'buyForm.totalPrice': valid ? (totalCents / 100).toFixed(2) : null })
  },

  /** 选择支付方式（tap 事件，data-id） */
  onBuyPaymentMethodSelect(e) {
    if (this.purchaseEditBlocked()) return
    const { id } = e.currentTarget.dataset
    this.setData({ 'buyForm.paymentMethod': parseInt(id) || 1 })
  },

  async onBuySubmit() {
    // 连点保护：按钮上的 `disabled` 只改背景色，**拦不住 tap**（见 index.wxss 的 .modal-btn.disabled）。
    // 同一个幂等键虽然能兜住"库里落两条流水"，但第二次请求会再弹一次结果、再刷新一次页面。
    if (this.data.submitting) {
      return
    }
    const { productId, quantity, paymentMethod, packageId, unifiedQty } = this.data.buyForm
    if (!productId && productId !== 0) {
      wx.showToast({ title: '请选择商品', icon: 'none' })
      return
    }
    if (!quantity || quantity <= 0) {
      wx.showToast({ title: '请输入正确数量', icon: 'none' })
      return
    }
    if (!this.data.currentStationId) {
      wx.showToast({ title: '请先选择水站', icon: 'none' })
      return
    }

    const saved = this.restorePurchaseIntent()
    if (this.data.purchaseStorageError) { wx.showToast({ title: '请先核实原购买记录', icon: 'none' }); return }
    const customerId = this._purchaseOwner
    if (!customerId) { wx.showToast({ title: '请先登录', icon: 'none' }); return }
    const body = { stationId: this.data.currentStationId, productId, quantity, paymentMethod,
      packageId: packageId || null, unifiedQty: unifiedQty || null }
    if (saved) {
      if (saved.result || !this.samePurchaseBody(saved.body, body)) {
        wx.showToast({ title: '请先处理原购买结果', icon: 'none' }); return
      }
      return this.submitPurchaseIntent(saved, true)
    }
    // New limits cannot invalidate an already persisted original purchase/query.
    if (!Number.isSafeInteger(Number(quantity)) || Number(quantity) > 5000) { wx.showToast({ title: '单次购买最多 5000 张', icon: 'none' }); return }
    if (this.data.packagesLoaded && !this.data.buyForm.selectedTierKey) { wx.showToast({ title: '请选择购票套餐', icon: 'none' }); return }
    if (this.data.buyForm.totalPrice === null) { wx.showToast({ title: '请先核实本次购票金额', icon: 'none' }); return }
    body.idempotencyKey = this.genPurchaseIdempotencyKey()
    return this.submitPurchaseIntent({ customerId, body, productName: this.data.buyForm.productName || '水票',
      stationName: (this.data.currentStation && this.data.currentStation.name) || '水站 ' + body.stationId }, false)
  },

  async submitPurchaseIntent(saved, replay) {
    if (this.data.submitting || this.data.recoveringPurchase) return
    const { productId } = saved.body
    const session = captureSession()
    this.setData({ submitting: true })
    try {
      if (!replay) {
        const rights = await getBarrelSummaryByType(saved.body.stationId)
        const holding = (rights.data || []).find(h => String(h.productId || h.waterTypeId) === String(productId))
        if (holding && holding.independentRights && !(holding.assetQty > 0)) throw new Error('请先购买该桶权益')
      }
      if (!isCurrentSession(session) || String(getCustomerId()) !== String(saved.customerId)) throw new Error('登录身份已变化，请重新进入')
      this.savePurchaseIntent(saved)
      const res = await purchaseTicket(saved.body)
      if (!isCurrentSession(session)) return
      if (!res || res.code !== 0) throw res || new Error('购买结果尚未确认')
      // 后端返回的是流水的**真实状态**，不要替它猜：
      //   2 = 已支付（票已入账，模拟微信渠道下当场就是这个）
      //   1 = 待收款（真实微信渠道未接入的部署里等水站确认收到钱 —— 那是收款确认，不是审批）
      this.applyPurchaseResult(saved, res.data)
      const status = Number(res.data.status)
      this.onClosePurchase()
      await this.loadData()
      if (!isCurrentSession(session)) return
      if (status === 1) {
        wx.showModal({ confirmColor: '#1264D6', cancelColor: '#637083',
          title: '等待到账',
          content: '水站还没确认收到这笔钱，确认后水票自动到账。如已付款较长时间仍未到账，请联系水站。',
          showCancel: false,
          confirmText: '知道了'
        })
      }
    } catch (error) {
      if (!isCurrentSession(session)) return
      console.error('Purchase ticket error:', error)
      if ((viewError(error, '')).includes('先') && (viewError(error, '')).includes('桶')) {
        wx.showModal({ confirmColor: '#1264D6', cancelColor: '#637083',  title: '先交桶押金', content: viewError(error, '请先核实该商品的用桶权益。'), confirmText: '交桶押金', success: (r) => {
          if (r.confirm) wx.navigateTo({ url: '/pages/barrel/purchase?stationId=' + saved.body.stationId + '&productId=' + productId })
        } })
      } else {
        this.setData({ purchaseNotice: viewError(error, '结果未确认，请查询原购买；已填写内容保留。') })
        wx.showToast({ title: '请核对购买结果', icon: 'none' })
      }
    } finally {
      this.setData({ submitting: false })
    }
  }
})
