const { getBarrelSummary, getBarrelRecords, getBarrelSummaryByType, requestBarrelReturn, previewBarrelReturn, confirmBarrelReturn, changeBarrelReturnArrangement, withdrawBarrelReturn } = require('../../api/barrel')
const { captureSession, isCurrentSession } = require('../../utils/token')
const { getOrders } = require('../../api/order')
const assetViewStation = require('../../utils/asset-view-station')
// 「当前服务水站」的**统一解析入口**（本地 → 回落 /api/orders/my-station）。
// 本页原先直接用 stationStorage.getId()，少了回落那一级：下过单但没在首页选过站的顾客
// 会被判成"没选水站"，而其实他的水站是查得出来的（utils/station.js 文件头写了为什么统一到这里）。
const { resolveStationId } = require('../../utils/station')
const accountNumber = value => {
  if (!['number', 'string'].includes(typeof value) || typeof value === 'string' && !value.trim()) return null
  return Number.isFinite(Number(value)) ? Number(value) : null
}
const accountQuantity = value => { const n = accountNumber(value); return n !== null && Number.isSafeInteger(n) && n >= 0 ? n : null }

Page({
  data: {
    loading: true,
    // null 是未加载；成功读取后才显示数值，失败不能冒充零资产。
    // [2026-09-16] 字段名必须与后端 BarrelServiceImpl.getBarrelSummary 的下发键一致 ——
    // 这里原先留着已删除的 deliveryBuckets（含已送达行，口径错误，后端已移除），
    // 会让后来者以为它是有效字段。配送中请一律用 pendingDeliveryBuckets。
    // 2026-10-07：旧“权益=已到手”只描述历史送达建权益路径；新购先形成有效容量。
    // held 含遗留配送中；occupied=权益汇总+over 是账面 H，不是活动用途占用。
    // availableRights 扣活动用途和遗留占用，不能当物理数；账面 H 也不称作实物盘点。
    summary: null,
    summaryFailed: false,
    holdingsFailed: false,
    recordsFailed: false,
    expandedProductId: null,
    records: [],
    customerBarrelAsset: [],
    // 当前水站名（页面顶部提示条用）：桶权益与押金按站隔离，必须让客户看见这是哪个站的账
    stationName: '',
    // 桶数据部分加载失败时的提示（空串 = 全部正常）。见 loadData 里的说明。
    loadError: '',
    showReturnModal: false,
    returnForm: {
      productId: null,
      quantity: 1,
      note: ''
    },
    // 退桶试算结果（后端按押金条批次 FIFO 算出，前端不自行计算金额）
    preview: null,
    previewReady: false,
    // 试算算不出来时的**可读原因**（常驻在试算框里，比只弹一次 toast 更容易被看到）。
    // 典型场景：顾客还没选服务水站 ⇒ 见 refreshPreview 的注释（2026-09-27 修）。
    previewHint: '',
    previewing: false,
    maxReturnQty: 0,
    submitting: false
  },

  onLoad(options) { assetViewStation.init(this, options) },

  onShow() {
    assetViewStation.activate(this)
    this._arrangementHidden = false
    this._arrangementLife = (this._arrangementLife || 0) + 1
    return this.loadData()
  },

  onHide() {
    assetViewStation.suspend(this)
    this._arrangementHidden = true
    this._arrangementLife = (this._arrangementLife || 0) + 1
    this._returnPreviewVersion = (this._returnPreviewVersion || 0) + 1
    this._previewIntent = null
    this.setData({ preview: null, previewReady: false, previewing: false, previewHint: '' })
  },
  onUnload() { this.onHide() },

  onPullDownRefresh() {
    this.loadData().then(() => wx.stopPullDownRefresh())
  },

  async loadData() {
    const context = assetViewStation.beginRead(this)
    const stationId = context.stationId
    this.setData({ loading: true, summary: null, records: [], customerBarrelAsset: [],
      summaryFailed: false, holdingsFailed: false, recordsFailed: false, loadError: '', maxReturnQty: 0 })
    if (!stationId) { this.setData({ loading: false, summaryFailed: true, holdingsFailed: true, recordsFailed: true,
      loadError: '请在上方选择要查看的资产水站' }); return }
    try {
      // [2026-09-20 真机联调] 原来三个请求各自 `.catch(e => { console.warn(...); return null })`，
      // 失败被吞成 null → 页面照常渲染「权益 0 · 占用 0」，与"这客户确实没有桶"完全无法区分
      // （AGENTS §8.22）。弱网/后端没起时顾客会以为自己一张桶都没有。
      // 部分成功仍然可用；失败部分已清空，显示失败与重试。
      const softCatch = (tag) => (e) => {
        console.warn('[Barrel] ' + tag + ' 失败:', e.message)
        return null
      }
      const [summaryRes, recordsRes, holdingsRes] = await Promise.all([
        getBarrelSummary(stationId).catch(softCatch('getBarrelSummary')),
        getBarrelRecords(stationId).catch(softCatch('getBarrelRecords')),
        getBarrelSummaryByType(stationId).catch(softCatch('getBarrelSummaryByType'))
      ])
      if (!assetViewStation.current(this, context)) return
      const rawSummary = summaryRes && summaryRes.data
      const summaryOK = !!(summaryRes && summaryRes.code === 0 && rawSummary && typeof rawSummary === 'object'
        && !Array.isArray(rawSummary) && accountNumber(rawSummary.depositBalance) !== null
        && accountQuantity(rawSummary.owedBuckets) !== null
        && accountQuantity(rawSummary.independentRights ? rawSummary.rightBuckets : rawSummary.heldBuckets) !== null
        && (!rawSummary.independentRights || accountQuantity(rawSummary.availableRights) !== null))
      const recordsOK = !!(recordsRes && recordsRes.code === 0 && Array.isArray(recordsRes.data))
      const holdingsOK = !!(holdingsRes && holdingsRes.code === 0 && Array.isArray(holdingsRes.data)
        && holdingsRes.data.every(h => h && (h.productId || h.waterTypeId)
          && ['assetQty', 'heldTotalQty', 'inTransitQty', 'occupiedQty', 'owedQty', 'storageQty', 'availableRights']
            .every(k => accountQuantity(h[k]) !== null)))
      this.setData({ summaryFailed: !summaryOK, recordsFailed: !recordsOK, holdingsFailed: !holdingsOK })
      if (!summaryOK || !recordsOK || !holdingsOK) {
        this.setData({ loadError: '部分桶与押金数据暂未加载；未加载项不代表 0，请重试' })
        wx.showToast({ title: '桶数据加载不完整，请下拉刷新', icon: 'none' })
      } else {
        this.setData({ loadError: '' })
      }

      if (summaryOK) {
        const summary = { ...summaryRes.data }
        ;['heldBuckets', 'rightBuckets', 'owedBuckets', 'pendingDeliveryBuckets', 'occupiedBuckets',
          'storageBuckets', 'pendingReturns', 'availableRights'].forEach(k => { summary[k] = accountQuantity(summary[k]) })
        summary.depositBalance = accountNumber(summary.depositBalance)
        this.setData({ summary })
      }
      if (recordsOK) {
        this.setData({ records: recordsRes.data })
      }

      let holdings = []
      if (holdingsOK) {
        holdings = holdingsRes.data
      }

      const customerBarrelAsset = []
      const map = {}
      ;(holdings || []).forEach(h => {
        const pid = h.productId || h.waterTypeId
        if (!pid) return
        if (!map[pid]) {
          map[pid] = {
            productId: pid,
            productName: h.productName || h.waterTypeName || '',
            productSpec: h.productSpec || h.waterTypeSpec || '',
            independentRights: h.independentRights === true,
            assetQty: 0,
            heldTotalQty: 0,
            inTransitQty: 0,
            occupiedQty: 0,
            owedQty: 0,
            storageQty: 0,
            deposit: accountNumber(h.deposit)
          }
        }
        // 后端一行即一个商品，这里的累加是防御性写法（历史上有按 holding 多条返回的版本）。
        // 各字段口径见 BarrelServiceImpl.getBarrelSummaryByType 的注释；
        // 必需数量缺失已在 holdingsOK 拒绝，不能猜成资产量或真实 0。
        const base = Number(h.assetQty)
        map[pid].availableRights = (map[pid].availableRights || 0) + Number(h.availableRights)
        map[pid].assetQty += base
        map[pid].heldTotalQty += Number(h.heldTotalQty)
        map[pid].inTransitQty += Number(h.inTransitQty)
        map[pid].occupiedQty += Number(h.occupiedQty)
        map[pid].owedQty += Number(h.owedQty)
        map[pid].storageQty += Number(h.storageQty)
      })
      Object.keys(map).forEach(k => customerBarrelAsset.push(map[k]))

      this.setData({ customerBarrelAsset })

      // 历史物理交回上限 = occupied（权益汇总 + over），不是含配送中的 held；新申请下方另用 availableRights。
      // 配送中的桶还没到客户手上，后端 returnEmpty 也是按占用校验的
      //（BarrelLedgerService：qty <= rightQty + over）。用持有当上限会在有在途桶时
      // 允许多报，提交后被后端以「交回数超过该客户当前持有数」拒绝 —— 顾客以为是 bug。
      // 历史路径再减待处理申请；新路径扣活动用途后的可用权益已由后端下发，不在此重复扣。
      if (!summaryOK || !holdingsOK) return
      const { occupiedBuckets, rightBuckets, pendingReturns } = this.data.summary
      const ceiling = occupiedBuckets !== undefined && occupiedBuckets !== null
        ? occupiedBuckets
        : (rightBuckets || 0)
      this.setData({ maxReturnQty: Math.max(0, (ceiling || 0) - (pendingReturns || 0)) })
      if (this.data.summary.independentRights) this.setData({ maxReturnQty: this.data.summary.availableRights || 0 })
    } finally {
      if (assetViewStation.current(this, context)) this.setData({ loading: false })
    }
  },

  onRetryAssets() { return this.loadData() },
  onAssetStationChange(e) {
    if (!assetViewStation.select(this, e.detail)) return
    this.setData({ showReturnModal: false, preview: null, previewReady: false, previewHint: '', previewing: false, expandedProductId: null })
    return this.loadData()
  },
  onToggleBarrelDetail(e) {
    const id = Number(e.currentTarget.dataset.id)
    this.setData({ expandedProductId: this.data.expandedProductId === id ? null : id })
  },

  onShowReturnModal() {
    if (this.data.maxReturnQty <= 0) {
      wx.showToast({ title: '暂无可退水桶', icon: 'none' })
      return
    }
    // 2026-10-07：现选中规则用 availableRights，不是物理 H。
    // 新路径已付未领容量也可申请退出；历史物理交回仍受 occupied 上限和后端校验约束。
    // 本轮仅校正旧注释，不调整选中、数量或提交行为。
    const first = (this.data.customerBarrelAsset || [])
      .find(i => (i.availableRights || 0) > 0) || (this.data.customerBarrelAsset || [])[0]
    this.setData({
      showReturnModal: true,
      'returnForm.productId': first ? first.productId : null,
      'returnForm.quantity': 1,
      'returnForm.note': '',
      'returnForm.pickupMode': 'STORE',
      // [2026-10-10] STORE办理兼容未领权益，不表示免交应退实物；交接规则仍由原安排决定。
      'returnForm.pickupModeText': this.data.summary.independentRights ? '到店办理' : '到店退桶',
      'returnForm.companionOrderId': null,
      preview: null,
      previewHint: ''
    })
    if (first) this.refreshPreview()
  },

  onCloseReturnModal() {
    // 连提示一起清掉：下次打开时不该看到上一次留下的原因
    this._returnPreviewVersion = (this._returnPreviewVersion || 0) + 1
    this._previewIntent = null
    this.setData({ showReturnModal: false, preview: null, previewHint: '', previewing: false, previewReady: false })
  },

  /**
   * 退桶试算：调后端 /return/preview。
   * 退款金额只认后端按押金条批次算出来的值 —— 前端自己用「数量 × 押金单价」估是错的：
   * 顾客当年买桶的价和现在不一定一样，那是柜台吵架的经典导火索。
   *
   * ⚠️ [2026-09-27 修] 这里必须**先解析出服务水站**，与提交路径（{@link #onSubmitReturn}）同一判据：
   * 后端对顾客只认 dto（顾客 JWT 里没有水站），漏传恒回「请先选择服务水站」，而
   * `api/barrel.js` 的 `previewBarrelReturn` 拿不到站就**不传该参数** ⇒ 必然失败。
   * 旧实现既不判空、也不走回落，失败又被下面这个 catch 吞成 `preview: null`，
   * 顾客看到的是**「押金金额算不出来」**，而真正的原因是没选水站 —— 两件事长得一模一样（本仓惯犯形状）。
   *
   * 用 `resolveStationId()` 而不是 `stationStorage.getId()`：本地没选过站时它会回落到
   * 「上次下单的水站」，能救回"下过单但没在首页显式选过站"的顾客；
   * 它**只读不写**，不会把首页的用户选择改掉。
   */
  async refreshPreview() {
    const version = this._returnPreviewVersion = (this._returnPreviewVersion || 0) + 1
    this._previewIntent = null
    this.setData({ preview: null, previewReady: false, previewing: false, previewHint: '' })
    const { productId, quantity } = this.data.returnForm
    if (!productId || !quantity || quantity <= 0) {
      this.setData({ preview: null, previewHint: '' })
      return
    }
    const context = assetViewStation.beginRead(this, 'preview')
    const current = () => version === this._returnPreviewVersion && assetViewStation.current(this, context)
    const stationId = context.stationId || await resolveStationId()
    if (!current()) return
    if (!stationId) {
      // 不发请求，并把原因**写在试算框里**（比只弹一次 toast 更持久，顾客回头还能看到）
      this.setData({ preview: null, previewHint: '请先在本页上方选择要办理的资产水站。' })
      wx.showToast({ title: '请先选择服务水站', icon: 'none' })
      return
    }
    this.setData({ previewing: true, previewHint: '' })
    try {
      const res = await previewBarrelReturn(productId, quantity, stationId)
      if (!current()) return
      const preview = res && res.data
      if (!preview || (!preview.blocked && (preview.refundAmount == null || !Number.isFinite(Number(preview.refundAmount))))) {
        throw new Error('暂时算不出能退多少，请重试')
      }
      this._previewIntent = { stationId, productId, quantity, version, context }
      this.setData({ preview, previewReady: true })
    } catch (e) {
      // 出声，但**别把技术原因糊给顾客**：后端给的话术已经面向用户，优先用它。
      console.warn('[Barrel] 退桶试算失败:', e.message)
      if (current()) this.setData({ preview: null, previewReady: false, previewHint: (e && e.message) || '暂时算不出能退多少，请稍后重试' })
    } finally {
      if (current()) this.setData({ previewing: false })
    }
  },

  onReturnQtyChange(e) {
    const { type } = e.currentTarget.dataset
    let qty = this.data.returnForm.quantity
    if (type === 'add' && qty < this.data.maxReturnQty) {
      qty++
    } else if (type === 'minus' && qty > 1) {
      qty--
    }
    this.setData({ 'returnForm.quantity': qty })
    this.refreshPreview()
  },

  onReturnQtyInput(e) {
    const qty = parseInt(e.detail.value) || 1
    this.setData({
      'returnForm.quantity': Math.min(this.data.maxReturnQty, Math.max(1, qty))
    })
    this.refreshPreview()
  },

  onReturnNoteInput(e) {
    this.setData({ 'returnForm.note': e.detail.value })
  },

  onSelectProduct(e) {
    const { id } = e.currentTarget.dataset
    this.setData({ 'returnForm.productId': parseInt(id) || null })
    this.refreshPreview()
  },

  async onSubmitReturn() {
    if (this.data.submitting) return
    const { returnForm } = this.data
    const { productId, quantity, note } = returnForm

    if (!productId) {
      wx.showToast({ title: '请选择要退的商品', icon: 'none' })
      return
    }
    if (quantity <= 0) {
      wx.showToast({ title: '请输入退桶数量', icon: 'none' })
      return
    }

    // 试算已经把「权益不足 / 有欠桶」拦在前面了，这里只是最后一道提示
    if (this.data.preview && this.data.preview.blocked) {
      wx.showToast({ title: this.data.preview.blockedReason || '当前无法退桶', icon: 'none', duration: 3000 })
      return
    }

    // 水站是提交的必需上下文（后端对顾客只认 dto；顾客 JWT 里没有站）。
    // 拿不到站就**别发请求** —— 后端只会回一句「请先选择服务水站」，让顾客白等一次失败。
    // [2026-09-27] 与 refreshPreview 统一走 resolveStationId()：多一级「上次下单的水站」回落，
    // 少一次"明明查得到却提示没选站"。
    const context = assetViewStation.beginRead(this, 'returnSubmit')
    const stationId = context.stationId || await resolveStationId()
    if (!assetViewStation.current(this, context)) return
    if (!stationId) {
      wx.showToast({ title: '请先选择服务水站', icon: 'none' })
      return
    }

    const previewIntent = this._previewIntent
    if (this.data.previewing || !this.data.previewReady || !previewIntent
      || previewIntent.version !== this._returnPreviewVersion
      || !assetViewStation.current(this, previewIntent.context)
      || previewIntent.stationId !== stationId || previewIntent.productId !== productId || previewIntent.quantity !== quantity
      || this.data.returnForm.productId !== productId || this.data.returnForm.quantity !== quantity) {
      wx.showToast({ title: '请先核实当前商品和数量的试算金额', icon: 'none' })
      return
    }

    this.setData({ submitting: true })
    try {
      // 不传 depositRefund：金额由服务端按押金条批次核销决定，顾客填多少都不算数
      const intent = [stationId, productId, quantity, returnForm.pickupMode, returnForm.companionOrderId].join(':')
      const saved = wx.getStorageSync('barrel-return-intent')
      const key = saved && saved.intent === intent ? saved.key : 'ret-' + Date.now() + '-' + Math.random().toString(36).slice(2)
      wx.setStorageSync('barrel-return-intent', { intent, key })
      await requestBarrelReturn(productId, quantity, note, stationId, { idempotencyKey: key,
        pickupMode: returnForm.pickupMode || 'STORE', companionOrderId: returnForm.companionOrderId || null })
      if (!assetViewStation.current(this, context)) return
      wx.removeStorageSync('barrel-return-intent')
      wx.showToast({ title: '退桶申请已提交', icon: 'success' })
      this.setData({ showReturnModal: false })
      this.loadData()
    } catch (error) {
      console.error('[Barrel] 退桶失败:', error)
      if (assetViewStation.current(this, context)) wx.showToast({ title: '提交失败: ' + (error.message || '请稍后重试'), icon: 'none', duration: 3000 })
    } finally {
      this.setData({ submitting: false })
    }
  },

  onEcoRuleTap() {
    wx.showModal({
      title: '水桶回收规则',
      // 退桶扣减押金后的钱**不留站内余额**（原「待拍板」项已于 2026-09-27 拍板收口，勿再挂）：
      // 产品口径「不现场给钱的不要退」= 核销与"钱交到顾客手上"是同一次操作 —— 优先原路退回，
      // 通道不可用（微信退款未接入）就由水站在确认收桶时当面交付现金（refundChannel=CASH）。
      // 正本 `docs/design/35-退押金实际交付-决策件.md` §7.2/§7.3。故本弹窗只说"以页面预览为准、
      // 具体退款方式向水站确认"，不承诺"退到余额"；index.wxml 的押金说明同此口径。
      content: '1. 水桶需保持完好，无严重破损\n2. 水站核实退桶或未领桶权益；应交回的空桶须确认收到\n3. 每桶金额以页面预览为准，具体退款方式请向水站确认\n4. 请勿将水桶用于非饮用水用途',
      showCancel: false,
      confirmText: '我知道了'
    })
  },

  // 押金流水入口（本页顶部「押金」格子）。余额与流水必须取同一个水站，
  // 两边都传独立资产查看站，否则客户会看到"余额没变但流水在动"。
  onDepositRecords() {
    wx.navigateTo({ url: assetViewStation.url(this, '/pages/deposit/records/index') })
  },
  onPurchaseRights() { wx.navigateTo({ url: assetViewStation.url(this, '/pages/barrel/purchase') }) },
  onRefundFeedback(e) {
    const record = this.data.records.find(r => r.id === Number(e.currentTarget.dataset.id))
    if (record && record.statusText) wx.navigateTo({ url: '/pages/service/index?refundType=BARREL_RETURN&refundId=' + record.id })
  },
  async onReturnConfirm(e) {
    const record = this.data.records.find(r => r.id === Number(e.currentTarget.dataset.id))
    if (!record || !record.returnDetail) return
    const detail = record.returnDetail
    if (this._returnConfirmFlight || detail.status !== 'APPROVED' || detail.customerConfirmationRequired === false || detail.customerConfirmationCurrent) return
    const session=captureSession(), version=detail.arrangementVersion, life=this._arrangementLife || 0
    if (!Number.isInteger(version) || version < 1) return
    const current = () => !this._arrangementHidden && life === (this._arrangementLife || 0) && isCurrentSession(session)
    this._returnConfirmFlight = true
    wx.showModal({ title: '确认取桶费与安排', content: '退押金 ¥' + record.depositRefund + '；需交回 ' + detail.requiredBarrels + ' 个桶；' + detail.pickupModeText + '；另付取桶费 ¥' + detail.pickupFee + '。交接后由水站按原渠道实际退款；如未收到，可提出异议。',
      success: async (res) => {
        if (!res.confirm || !current()) { this._returnConfirmFlight = false; return }
        try { await confirmBarrelReturn(record.id, version); if (current()) await this.loadData() }
        catch (err) { if (current()) wx.showToast({ title: err.message || '确认失败', icon: 'none' }) }
        finally { this._returnConfirmFlight = false }
      }, fail: () => { this._returnConfirmFlight = false } })
  },
  async onReturnArrangement(e) {
    if (this._arrangementFlight) return
    const record=this.data.records.find(r => String(r.id) === String(e.currentTarget.dataset.id)), d=record && record.returnDetail
    if (!d || !['APPLIED','APPROVED'].includes(d.status) || !Number.isInteger(d.arrangementVersion)) return
    if (d.feePaymentStatus === 2) { wx.showToast({ title: '请先联系水站按原款退还旧服务费，再更改安排', icon: 'none' }); return }
    const session=captureSession(), life=this._arrangementLife || 0, current=() => !this._arrangementHidden && life === (this._arrangementLife || 0) && isCurrentSession(session)
    this._arrangementFlight=true
    try {
      const picked=await this.arrangementDialog({ itemList: ['到店退桶','单独上门收桶（新费用另行确认）','随送水订单顺路收桶'] }, true)
      if (!picked || !current()) return
      const mode=['STORE','PICKUP','COMBINED'][picked.tapIndex]
      if (!mode) return
      let companionOrderId=null
      if (mode === 'COMBINED') {
        const response=await getOrders({ stationId: record.stationId, page: 1, pageSize: 500 })
        if (!current()) return
        const data=response.data, rows=(Array.isArray(data)?data:data && data.records || [])
          .filter(o => String(o.stationId) === String(record.stationId) && (o.status === 1 || o.status === 2))
        if (!rows.length) { wx.showToast({ title: '暂无进行中的送水订单，可选择到店或独立上门', icon: 'none' }); return }
        const selected=await this.arrangementDialog({ itemList: rows.map(o => '订单 ' + (o.orderNo || o.id)) }, true)
        if (!selected || !current() || !rows[selected.tapIndex]) return
        companionOrderId=rows[selected.tapIndex].id
      }
      const reason=await this.arrangementDialog({ title: '改为' + ({ STORE:'到店退桶', PICKUP:'单独上门', COMBINED:'随所选订单收桶' })[mode], editable:true, placeholderText:'填写变更原因；水站将重新批准安排', confirmText:'提交更改', content:'' })
      if (!reason || !current()) return
      const text=(reason.content || '').trim()
      if (!text || text.length > 200) { wx.showToast({ title:'请填写200字以内变更原因', icon:'none' }); return }
      const body={ pickupMode:mode, companionOrderId, expectedVersion:d.arrangementVersion, reason:text, idempotencyKey:'ret-arr-' + Date.now() + '-' + Math.random().toString(36).slice(2) }
      await changeBarrelReturnArrangement(record.id,body)
      if (current()) { await this.loadData(); wx.showToast({ title:'原申请安排已更改，等待水站批准', icon:'none' }) }
    } catch (err) { if (current()) { wx.showToast({ title:err.message || '更改未完成，请核对原申请', icon:'none' }); await this.loadData() } }
    finally { this._arrangementFlight=false }
  },
  arrangementDialog(options,sheet=false) {
    return new Promise(resolve => wx[sheet?'showActionSheet':'showModal']({ ...options,
      success:res => resolve(sheet || res.confirm ? res : null), fail:() => resolve(null) }))
  },
  onReturnWithdraw(e) {
    const record = this.data.records.find(r => String(r.id) === String(e.currentTarget.dataset.id))
    if (!record) return
    const context = assetViewStation.beginRead(this, 'withdrawDialog')
    wx.showModal({ title: '撤回退桶申请', content: '尚未交接的申请可撤回，已锁定权益恢复使用。', success: async (res) => {
      if (!res.confirm || !assetViewStation.current(this, context)) return
      try { await withdrawBarrelReturn(record.id); if (assetViewStation.current(this, context)) await this.loadData() }
      catch (err) { if (assetViewStation.current(this, context)) wx.showToast({ title: err.message || '撤回失败', icon: 'none' }) }
    } })
  },
  onPickupMode() {
    const context = assetViewStation.beginRead(this, 'pickupDialog')
    wx.showActionSheet({ itemList: ['到店办理', '单独上门收桶（费用须先确认）', '随送水订单顺路收桶'], success: async (res) => {
      if (!assetViewStation.current(this, context)) return
      const modes = ['STORE', 'PICKUP', 'COMBINED'], texts = ['到店办理', '单独上门收桶', '随送水订单收桶']
      if (res.tapIndex !== 2) { this.setData({ 'returnForm.pickupMode': modes[res.tapIndex], 'returnForm.pickupModeText': texts[res.tapIndex], 'returnForm.companionOrderId': null }); return }
      try {
        const stationId = context.stationId || await resolveStationId()
        if (!stationId || !assetViewStation.current(this, context)) return
        const response = await getOrders({ stationId, page: 1, size: 100 })
        if (!assetViewStation.current(this, context)) return
        const rows = (Array.isArray(response.data) ? response.data : (response.data.records || []))
          .filter(o => o.stationId === stationId && (o.status === 1 || o.status === 2))
        if (!rows.length) { wx.showToast({ title: '暂无进行中的送水订单，请选到店或单独上门', icon: 'none' }); return }
        wx.showActionSheet({ itemList: rows.slice(0, 6).map(o => '订单 ' + (o.orderNo || o.id)), success: (picked) => {
          if (!assetViewStation.current(this, context) || !rows[picked.tapIndex]) return
          this.setData({ 'returnForm.pickupMode': 'COMBINED', 'returnForm.pickupModeText': '随送水订单收桶', 'returnForm.companionOrderId': rows[picked.tapIndex].id })
        } })
      } catch (err) { if (assetViewStation.current(this, context)) wx.showToast({ title: err.message || '订单加载失败', icon: 'none' }) }
    } })
  },

})
