const { createDraftGuard } = require('../../utils/draft-guard')
const { showBusinessModal } = require('../../utils/business-dialog')
const { getBarrelSummary, getBarrelRecords, getBarrelSummaryByType, requestBarrelReturn, previewBarrelReturn, confirmBarrelReturn, changeBarrelReturnArrangement, withdrawBarrelReturn } = require('../../api/barrel')
const { captureSession, isCurrentSession } = require('../../utils/token')
const { getOrders } = require('../../api/order')
const { getProductDetail } = require('../../api/product')
const assetViewStation = require('../../utils/asset-view-station')
const { listAssetStations } = require('../../api/asset-stations')
const returnIntent = require('./return-intent')
// 「当前服务水站」的**统一解析入口**（本地 → 回落 /api/orders/my-station）。
// 本页原先直接用 stationStorage.getId()，少了回落那一级：下过单但没在首页选过站的顾客
// 会被判成"没选水站"，而其实他的水站是查得出来的（utils/station.js 文件头写了为什么统一到这里）。
const { resolveStationId } = require('../../utils/station')
const accountNumber = value => {
  if (!['number', 'string'].includes(typeof value) || typeof value === 'string' && !value.trim()) return null
  return Number.isFinite(Number(value)) ? Number(value) : null
}
const accountQuantity = value => { const n = accountNumber(value); return n !== null && Number.isSafeInteger(n) && n >= 0 ? n : null }

// 2026-10-13：退还卡原只在 key/data-id 留申请ID，同数量记录缺少可见辨认线索。
// 只投影显示文字；名称仅取同次有效站别桶账，不用旧站缓存或相邻商品猜配。
const returnDisplayId = value => {
  if (typeof value === 'number') return Number.isSafeInteger(value) && value > 0 ? String(value) : null
  return typeof value === 'string' && /^[1-9]\d*$/.test(value) ? value : null
}
function presentReturnRecord(record, holdings, stationId) {
  if (!record || record.type !== 2) return record
  const id = returnDisplayId(record.id), station = returnDisplayId(stationId), product = returnDisplayId(record.productId)
  let name = ''
  if (station && product && returnDisplayId(record.stationId) === station) {
    const names = new Set(holdings.filter(h => h && returnDisplayId(h.productId) === product
      && (!Object.prototype.hasOwnProperty.call(h, 'stationId') || returnDisplayId(h.stationId) === station))
      .map(h => typeof h.productName === 'string' ? h.productName.trim() : '')
      .filter(n => n && n !== '未知商品'))
    if (names.size === 1) name = [...names][0]
  }
  return { ...record, returnIdentityText: id ? '退还申请 #' + id : '退还申请编号未加载', returnProductName: name }
}


// 只清理错误的展示文字；原对象的 code、resultUnknown、businessRejected 均不改动。
function viewError(error, fallback = '暂时无法完成，请稍后重试') {
  const text = error && typeof error.message === 'string' ? error.message.trim() : ''
  return /[\u3400-\u9fff]/.test(text)
    && !/(?:Exception|Error:|\bat\s+\S+\(|\bSQL\b|https?:\/\/|request:fail|errno|stack|[A-Z][A-Z_]{5,})/.test(text)
    ? text : fallback
}

// 展示字段不回写商品资料；历史占位规格只向顾客显示“待确认”。
function barrelDisplaySpec(value) {
  const text = typeof value === 'string' ? value.trim() : ''
  return !text || /^(null|undefined|未知|未填写|未设置|待确认|规格待确认)$/i.test(text)
    || /按实物|以实物标签/.test(text) ? '规格待确认' : text
}
function barrelPhoto(value) {
  return typeof value === 'string' && (/^https:\/\/[^\s]+$/.test(value) || /^\/assets\/product\/[^.][^\s]*\.(png|jpe?g|webp)$/i.test(value)) ? value : ''
}

Page({
  data: {
    loading: true, uiDraftAvailable: false, uiDraftError: '',
    needLogin: false,
    refreshing: false,
    summaryStale: false, holdingsStale: false, recordsStale: false, returnError: '',
    pendingReturn: null, pendingReturnProductText: '', returnRecoveryError: '',
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
    processingRecordsCount: 0, showAllRecords: false, showReturnRules: false, showPreviewLots: false, returnQuantityError: '',
    records: [],
    customerBarrelAsset: [], returnProductOptions: [], hasOtherAssetStation: false,
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
    this.readUiDraft()
    this._arrangementHidden = false
    this._arrangementLife = (this._arrangementLife || 0) + 1
    return this.loadData()
  },

  onHide() {
    this.persistUiDraft()
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
    // 2026-10-22：资产重读即作废旧试算，保留输入但不能沿用刷新前的办理资格。
    this._returnPreviewVersion = (this._returnPreviewVersion || 0) + 1
    this._previewIntent = null
    this.setData({ preview: null, previewReady: false, previewing: false, previewHint: '桶与押金正在重新核实，请稍后重新试算。' })
    this.syncReturnRecovery(context)
    const stationId = context.stationId
    this.checkOtherAssetStation(context)
    const needLogin = !context.session.loggedIn || !context.session.customerId
    this.setData({ needLogin })
    if (needLogin) { this.setData({ loading: false, refreshing: false, summary: null, records: [], customerBarrelAsset: [], returnProductOptions: [],
      summaryFailed: true, holdingsFailed: true, recordsFailed: true, maxReturnQty: 0, loadError: '请先登录后查看本人水桶与押金。' }); return }
    const keep = this._lastAssetScope && this._lastAssetScope.stationId === stationId && isCurrentSession(this._lastAssetScope.session)
    this.setData({ loading: !keep, refreshing: !!keep, ...(keep ? {} : { summary: null, records: [], customerBarrelAsset: [], returnProductOptions: [] }),
      summaryStale: false, holdingsStale: false, recordsStale: false,
      summaryFailed: false, holdingsFailed: false, recordsFailed: false, loadError: '', maxReturnQty: 0 })
    if (!stationId) { this.setData({ loading: false, refreshing: false, summaryFailed: true, holdingsFailed: true, recordsFailed: true,
      loadError: '请在上方选择要查看的资产水站' }); return }
    try {
      // [2026-09-20 真机联调] 原来三个请求各自 `.catch(e => { console.warn(...); return null })`，
      // 失败被吞成 null → 页面照常渲染「权益 0 · 占用 0」，与"这客户确实没有桶"完全无法区分
      // （AGENTS §8.22）。弱网/后端没起时顾客会以为自己一张桶都没有。
      // 部分成功仍然可用；失败部分已清空，显示失败与重试。
      let denied = false
      const softCatch = (tag) => (e) => {
        if (/无权|权限|登录/.test(e && e.message || '')) denied = true
        console.warn('[Barrel] ' + tag + ' 失败:', e.message)
        return null
      }
      const [summaryRes, recordsRes, holdingsRes] = await Promise.all([
        getBarrelSummary(stationId).catch(softCatch('getBarrelSummary')),
        getBarrelRecords(stationId).catch(softCatch('getBarrelRecords')),
        getBarrelSummaryByType(stationId).catch(softCatch('getBarrelSummaryByType'))
      ])
      if (!assetViewStation.current(this, context)) return
      if (denied) {
        this.setData({ summary: null, records: [], customerBarrelAsset: [], returnProductOptions: [], summaryFailed: true, holdingsFailed: true, recordsFailed: true,
          loadError: '当前账号无法查看本站桶与押金，请核对登录账号和所选水站。' }); return
      }
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
      this.setData({ summaryFailed: !summaryOK, recordsFailed: !recordsOK, holdingsFailed: !holdingsOK,
        summaryStale: !summaryOK && !!keep && !!this.data.summary,
        holdingsStale: !holdingsOK && !!keep && this.data.customerBarrelAsset.length > 0,
        recordsStale: !recordsOK && !!keep && this.data.records.length > 0 })
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
        this.setData({ records: recordsRes.data.map(record => presentReturnRecord(record, holdingsOK ? holdingsRes.data : [], stationId)),
          processingRecordsCount: recordsRes.data.filter(record => record && (record.returnDetail && ['APPLIED', 'APPROVED', 'RECEIVED'].includes(record.returnDetail.status)
            || record.refundProgress && (Number(record.refundProgress.remaining) > 0 || record.refundProgress.verified === false)
            || record.type === 2 && !record.returnDetail && ![3, 4].includes(record.status))).length })
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
            productSpec: h.productSpec || h.waterTypeSpec || '', imageUrl: h.imageUrl || '',
            displaySpec: barrelDisplaySpec(h.productSpec || h.waterTypeSpec),
            assetImageUrl: barrelPhoto(h.imageUrl), assetImageFailed: false,
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

      if (holdingsOK) this.setData({ customerBarrelAsset, returnProductOptions: customerBarrelAsset.filter(item => (item.independentRights ? item.availableRights : item.occupiedQty) > 0).map(item => ({
        id: item.productId, name: item.productName || '商品名称待确认',
        spec: item.productSpec || '',
        priceText: '可退 ' + (item.independentRights ? item.availableRights + ' 份权益' : item.occupiedQty + ' 桶') + ' · '
          + (item.deposit == null ? '当前押金未加载' : '当前押金 ¥' + item.deposit + '/桶'), image: item.imageUrl || '' })), pendingReturnProductText: this.returnProductText(this.data.pendingReturn, customerBarrelAsset) })
      if (holdingsOK) this._loadAssetPhotos(customerBarrelAsset, context)
      this._lastAssetScope = context

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
      if (assetViewStation.current(this, context)) this.setData({ loading: false, refreshing: false })
    }
  },

  // 现有桶账接口不下发图片；按同一商品ID和资产站读取已有商品详情/COS地址。
  // 图片是独立展示读取，失败只降级，不影响桶账、试算或退还资格。
  async _loadAssetPhotos(items, context) {
    if (typeof getProductDetail !== 'function') return
    await Promise.all(items.filter(item => !item.assetImageUrl).map(async item => {
      try {
        const res = await getProductDetail(item.productId, context.stationId)
        if (!assetViewStation.current(this, context) || !res || res.code !== 0 || !res.data
          || String(res.data.id) !== String(item.productId)) return
        const url = barrelPhoto(res.data.imageUrl)
        if (!url) return
        this.setData({ customerBarrelAsset: this.data.customerBarrelAsset.map(row =>
          String(row.productId) === String(item.productId) ? { ...row, assetImageUrl: url, assetImageFailed: false } : row) })
      } catch (_) { /* Original asset facts remain usable; render the common barrel placeholder. */ }
    }))
  },
  onAssetPhotoError(e) {
    if (this._assetHidden) return
    const { id, src } = e.currentTarget.dataset
    this.setData({ customerBarrelAsset: this.data.customerBarrelAsset.map(row =>
      String(row.productId) === String(id) && row.assetImageUrl === src ? { ...row, assetImageFailed: true } : row) })
  },

  onRetryAssets() { return this.loadData() },
  async checkOtherAssetStation(context) {
    // 2026-10-30：未查到站点时不能把失败当作有其他站，避免给出不可用的切换入口。
    this.setData({ hasOtherAssetStation: false })
    if (!context.stationId || !context.session.loggedIn || !context.session.customerId) return
    try {
      const res = await listAssetStations(0, 20), data = res && res.data
      if (!assetViewStation.current(this, context)) return
      if (!res || res.code !== 0 || !data || !Array.isArray(data.stations) || typeof data.hasMore !== 'boolean') return
      const other = data.stations.some(s => s && Number.isSafeInteger(s.id) && s.id > 0 && s.id !== context.stationId)
      this.setData({ hasOtherAssetStation: other || (data.hasMore && data.stations.some(s => s && s.id === context.stationId)) })
    } catch (error) { /* 站点未核实，仍可通过顶部入口主动查看。 */ }
  },
  onLogin() { wx.navigateTo({ url: '/pages/login/index' }) },
  onChooseAssetStation() {
    if (this._assetHidden || this.data.needLogin || this.data.submitting || this.data.recoveringPurchase) return
    const session = captureSession()
    if (!session.loggedIn || !session.customerId) return this.onLogin()
    const picker = this.selectComponent('#asset-station-view')
    if (picker) picker.openPicker()
  },
  // 2026-10-24：空桶账的下一步只导航；资产查看站与下单站继续各自保留，不自动互相覆盖。
  onGoOrderHome() {
    if (this._assetHidden || this.data.needLogin || !this.data.viewStationId || this.data.loading || this.data.refreshing
      || this.data.summaryFailed || this.data.holdingsFailed || this.data.loadError || this.data.customerBarrelAsset.length) return
    const session = captureSession()
    if (!session.loggedIn || !session.customerId) return this.onLogin()
    wx.switchTab({ url: '/pages/home/index' })
  },
  onAssetStationChange(e) {
    if (!assetViewStation.select(this, e.detail)) return
    this.readUiDraft()
    this.setData({ showReturnModal: false, preview: null, previewReady: false, previewHint: '', previewing: false, expandedProductId: null })
    return this.loadData()
  },
  onToggleBarrelDetail(e) {
    const id = Number(e.currentTarget.dataset.id)
    this.setData({ expandedProductId: this.data.expandedProductId === id ? null : id })
  },

  syncReturnRecovery(context) {
    const scope = context.session.customerId + ':' + context.stationId
    if (scope !== this._returnRecoveryScope) {
      this._returnRecoveryScope = scope
      this.setData({ pendingReturn: null, returnRecoveryError: '', returnError: '', showReturnModal: false,
        returnForm: { productId: null, quantity: 1, note: '' } })
    }
    if (!context.session.loggedIn || !context.session.customerId || !context.stationId) return null
    try {
      const saved = returnIntent.read(context.session.customerId, context.stationId)
      this.setData({ pendingReturn: saved, pendingReturnProductText: this.returnProductText(saved), returnRecoveryError: '' })
      return saved
    } catch (error) {
      this.setData({ pendingReturn: null, returnRecoveryError: viewError(error, '原申请暂不能读取，请保留记录并联系办理水站。') })
      return null
    }
  },

  returnProductText(saved, assets) {
    if (!saved || !saved.body) return ''
    // 2026-10-24：仅用本页有效资产辅助识别；首发未知、重试和资产重读都不能修改原申请身份。
    const scope = this._lastAssetScope
    const rows = assets || (scope && String(scope.stationId) === String(saved.body.stationId) && assetViewStation.current(this, scope)
      ? this.data.customerBarrelAsset : [])
    const product = rows.find(row => String(row.productId) === String(saved.body.productId))
    return product && product.productName ? product.productName + (product.productSpec ? ' ' + product.productSpec : '')
      : '商品编号：' + saved.body.productId
  },

  uiDraftGuard() {
    if (!this._uiDraftGuard) this._uiDraftGuard = createDraftGuard(() => {
      const session = captureSession()
      return { app: 'miniapp-user', actor: session.loggedIn ? session.customerId : null,
        station: this.data.viewStationId, route: 'pages/barrel/index', entity: 'new', generation: session.epoch }
    })
    return this._uiDraftGuard
  },
  readUiDraft() {
    const guard = this.uiDraftGuard(), token = guard.begin(), result = guard.read(token)
    this.setData({ uiDraftAvailable: result.state === 'saved', uiDraftError: result.state === 'unknown' ? '暂存填写尚未读取，请重试；原记录仍保留。' : '' })
    return result
  },
  persistUiDraft() {
    if (this._uiDraftRestoringApply || !this._uiDraftEdited || !this._uiDraftFormToken || this.data.submitting || this.data.pendingReturn || this.data.returnRecoveryError) return
    const result = this.uiDraftGuard().save(this._uiDraftFormToken, { form: { ...this.data.returnForm } })
    if (result.state === 'stale') return
    this.setData({ uiDraftAvailable: result.state === 'saved', uiDraftError: result.state === 'unknown' ? '草稿尚未确认保存，请保留本页输入后重试。' : '' })
  },
  clearUiDraft() {
    if (!this._uiDraftFormToken) return
    const result = this.uiDraftGuard().clear(this._uiDraftFormToken)
    if (result.state === 'stale') return
    this._uiDraftEdited = false
    this.setData({ uiDraftAvailable: false, uiDraftError: result.state === 'unknown' ? '暂存填写尚未确认清理，办理结果不受影响。' : '' })
  },
  onRestoreUiDraft() {
    if (this.data.submitting || this.data.pendingReturn || this.data.returnRecoveryError || this._uiDraftRestoring) return
    const guard = this.uiDraftGuard(), token = guard.begin(), result = guard.read(token)
    if (result.state !== 'saved') return this.readUiDraft()
    const inputBefore = JSON.stringify({ form: { ...this.data.returnForm } })
    this._uiDraftRestoring = true
    showBusinessModal({ title: '恢复暂存填写', content: '恢复会替换本页尚未提交的输入。仍需按当前商品与金额核实后正式提交。', confirmText: '恢复填写', cancelText: '取消',
      success: async response => {
        if (!response.confirm || this.data.submitting || this.data.pendingReturn || this.data.returnRecoveryError || inputBefore !== JSON.stringify({ form: { ...this.data.returnForm } })) return
        const latest = guard.read(token)
        if (latest.state !== 'saved') return
        this._uiDraftFormToken = token
        this._uiDraftRestoringApply = true
        try {
        const form = latest.draft && latest.draft.form
        if (!form || !this.data.customerBarrelAsset.some(p => String(p.productId) === String(form.productId))) {
          this.setData({ uiDraftError: '原商品尚未读取或已不可办理，请重新加载后核实；暂存仍保留。' }); return
        }
        this.setData({ returnForm: { ...form }, showReturnModal: true, returnError: '', preview: null, previewReady: false })
        this._uiDraftEdited = true
        this.onReturnQtyInput({ detail: { value: form.quantity } })
        } finally { this._uiDraftRestoringApply = false; this._uiDraftRestoring = false }
      }, complete: () => { if (!this._uiDraftRestoringApply) this._uiDraftRestoring = false } })
  },

  onToggleRecords() { this.setData({ showAllRecords: !this.data.showAllRecords }) },
  onToggleReturnRules() { this.setData({ showReturnRules: !this.data.showReturnRules }) },
  onTogglePreviewLots() { this.setData({ showPreviewLots: !this.data.showPreviewLots }) },

  onShowReturnModal() {
    this._uiDraftFormToken = this.uiDraftGuard().begin()
    this._uiDraftEdited = false
    const pending = this.syncReturnRecovery(assetViewStation.beginRead(this, 'returnRecovery'))
    if (this.data.returnRecoveryError) return
    if (pending) {
      this.setData({ showReturnModal: true, returnError: '原申请结果尚未确认。请原样重试或联系本站核对，核实前不能另发申请。',
        pendingReturnProductText: this.returnProductText(pending),
        returnForm: { ...pending.body, pickupModeText: pending.pickupModeText }, preview: null, previewReady: false,
        previewHint: '原申请金额与办理结果仍待核实，重试不会修改原内容。' })
      return
    }
    if (this.data.refreshing || this.data.summaryFailed || this.data.holdingsFailed) {
      this.setData({ loadError: '退还前需重新核实桶与押金，请重新加载。' }); return
    }
    if (this.data.maxReturnQty <= 0) {
      wx.showToast({ title: '暂无可退水桶', icon: 'none' })
      return
    }
    if (this._returnDraft && this._returnDraft.scope === this._returnRecoveryScope && isCurrentSession(this._returnDraft.session)) {
      this.setData({ showReturnModal: true, returnForm: { ...this._returnDraft.form }, returnError: '', preview: null, previewReady: false })
      this.refreshPreview()
      return
    }
    // 2026-10-30：默认商品与可选列表使用同一真实资产范围，避免落在不可办理商品。
    const first = (this.data.returnProductOptions || [])[0]
    this.setData({
      showReturnModal: true, returnError: '',
      'returnForm.productId': first ? first.id : null,
      'returnForm.quantity': 1,
      'returnForm.note': '',
      'returnForm.pickupMode': 'STORE',
      // [2026-10-10] STORE办理兼容未领权益，不表示免交应退实物；交接规则仍由原安排决定。
      'returnForm.pickupModeText': this.data.summary.independentRights ? '到店办理' : '到店退桶',
      'returnForm.companionOrderId': null,
      preview: null,
      previewHint: ''
    })
    this._returnInitial = JSON.stringify(this.data.returnForm)
    if (first) this.refreshPreview()
  },

  onCloseReturnModal() {
    if (this.data.submitting || this._closeReturnPrompt) return
    const context = assetViewStation.beginRead(this, 'closeReturn')
    const close = () => {
      if (!assetViewStation.current(this, context)) return
      this.persistUiDraft()
      // 2026-10-28：关闭曾直接丢弃填写；草稿仅留在本页同账号同水站，不覆盖原申请凭据。
      if (!this.data.pendingReturn) this._returnDraft = { scope: this._returnRecoveryScope, session: captureSession(), form: { ...this.data.returnForm } }
      this._returnPreviewVersion = (this._returnPreviewVersion || 0) + 1
      this._previewIntent = null
      this.setData({ showReturnModal: false, preview: null, previewHint: '', previewing: false, previewReady: false })
    }
    if (this.data.pendingReturn || JSON.stringify(this.data.returnForm) === this._returnInitial) return close()
    this._closeReturnPrompt = true
    showBusinessModal({ title: '暂存这次填写？', content: '商品、数量和备注会保留在本页，再次打开可继续。尚未提交退还申请。',
      confirmText: '暂存关闭', cancelText: '继续填写',
      success: res => { if (res.confirm) close() }, complete: () => { this._closeReturnPrompt = false } })
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
    if (this.data.pendingReturn || this.data.returnRecoveryError) {
      this.setData({ previewHint: '原申请结果尚未确认，请先核对原申请。' }); return
    }
    if (this.data.loading || this.data.refreshing || this.data.summaryFailed || this.data.holdingsFailed) {
      this.setData({ previewHint: '请先重新加载桶与押金，再核实试算金额。' }); return
    }
    const { productId, quantity } = this.data.returnForm
    if (!productId || !Number.isSafeInteger(quantity) || quantity <= 0) {
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
      if (current()) this.setData({ preview: null, previewReady: false, previewHint: viewError(e, '暂时算不出能退多少，请稍后重试') })
    } finally {
      if (current()) this.setData({ previewing: false })
    }
  },

  onReturnQtyChange(e) {
    if (this.data.submitting || this.data.pendingReturn || this.data.returnRecoveryError) return
    const { type } = e.currentTarget.dataset
    let qty = Number(this.data.returnForm.quantity) || 1
    if (type === 'add' && qty < this.data.maxReturnQty) {
      qty++
    } else if (type === 'minus' && qty > 1) {
      qty--
    }
    this.setData({ 'returnForm.quantity': qty, returnQuantityError: '' })
    this.refreshPreview()
  },

  onReturnQtyInput(e) {
    if (this.data.submitting || this.data.pendingReturn || this.data.returnRecoveryError) return
    this._uiDraftEdited = true
    const value = e.detail.value
    const quantity = /^[1-9]\d*$/.test(String(value)) && Number.isSafeInteger(Number(value)) ? Number(value) : value
    const valid = Number.isSafeInteger(quantity) && quantity > 0 && quantity <= this.data.maxReturnQty
    // 2026-10-28：按键时夹成1会阻碍清空重输；保留原输入，非法值不试算、不提交。
    this.setData({ 'returnForm.quantity': quantity, returnQuantityError: valid ? '' : '请填写 1 至 ' + this.data.maxReturnQty + ' 的整数' })
    this.persistUiDraft()
    if (!valid) {
      this._returnPreviewVersion = (this._returnPreviewVersion || 0) + 1
      this._previewIntent = null
      this.setData({ preview: null, previewReady: false, previewing: false, previewHint: '' })
      return
    }
    this.refreshPreview()
  },

  onReturnNoteInput(e) {
    if (this.data.submitting || this.data.pendingReturn || this.data.returnRecoveryError) return
    this._uiDraftEdited = true
    this.setData({ 'returnForm.note': e.detail.value })
    this.persistUiDraft()
  },

  onReturnProductSelect(e) { return this.onSelectProduct({ currentTarget: { dataset: { id: e.detail.id } } }) },

  onSelectProduct(e) {
    if (this.data.submitting || this.data.pendingReturn || this.data.returnRecoveryError) return
    const { id } = e.currentTarget.dataset
    this._uiDraftEdited = true
    this.setData({ 'returnForm.productId': parseInt(id) || null })
    this.persistUiDraft()
    this.refreshPreview()
  },

  async onSubmitReturn() {
    if (this.data.submitting || this._pickupFlight) return
    const context = assetViewStation.beginRead(this, 'returnSubmit')
    const flight = this._returnSubmitFlight = {}
    const current = () => this._returnSubmitFlight === flight && assetViewStation.current(this, context)
    // 2026-10-22：解析水站前先锁住；未知结果只能恢复已保存的原 body/key。
    this.setData({ submitting: true })
    let saved = null, replay = false
    try {
      const stationId = context.stationId || await resolveStationId()
      if (!current()) return
      if (!stationId || !context.session.loggedIn || !context.session.customerId) throw new Error('请先核对登录账号和办理水站')
      try { saved = returnIntent.read(context.session.customerId, stationId) }
      catch (storageError) {
        this.setData({ returnRecoveryError: viewError(storageError, '原申请记录暂不能读取，请保留记录并联系办理水站。') })
        throw storageError
      }
      replay = !!saved
      if (!saved) {
        if (this.data.loading || this.data.refreshing || this.data.summaryFailed || this.data.holdingsFailed
          || !this._lastAssetScope || !assetViewStation.current(this, this._lastAssetScope)) {
          throw new Error('退还前需重新核实桶与押金，请重新加载')
        }
        const { productId, quantity, note, pickupMode, companionOrderId, pickupModeText } = this.data.returnForm
        if (!productId || !Number.isSafeInteger(quantity) || quantity <= 0) throw new Error('请选择商品并填写有效的退还数量')
        const previewIntent = this._previewIntent
        if (this.data.previewing || !this.data.previewReady || !previewIntent
          || previewIntent.version !== this._returnPreviewVersion
          || !assetViewStation.current(this, previewIntent.context)
          || previewIntent.stationId !== stationId || previewIntent.productId !== productId || previewIntent.quantity !== quantity) {
          throw new Error('请先核实当前商品和数量的试算金额')
        }
        if (this.data.preview && this.data.preview.blocked) throw new Error(this.data.preview.blockedReason || '当前无法退还')
        // 不传 depositRefund：金额仍由原有办理规则决定。
        saved = returnIntent.prepare(context.session.customerId, { productId, quantity, note: note || '', stationId,
          pickupMode: pickupMode || 'STORE', companionOrderId: companionOrderId || null }, pickupModeText)
      }
      this.setData({ pendingReturn: saved, pendingReturnProductText: this.returnProductText(saved), returnRecoveryError: '', returnError: '' })
      const body = saved.body
      const response = await requestBarrelReturn(body.productId, body.quantity, body.note, body.stationId,
        { idempotencyKey: body.idempotencyKey, pickupMode: body.pickupMode, companionOrderId: body.companionOrderId })
      if (!current()) return
      if (!response || response.code !== 0 || !response.data || !returnDisplayId(response.data.recordId)) {
        throw response || new Error('申请结果尚未确认，请核对本站水桶记录或重试原内容')
      }
      returnIntent.clear(saved)
      this.clearUiDraft()
      this._returnDraft = null
      this._returnInitial = null
      this.setData({ pendingReturn: null, showReturnModal: false, returnError: '' })
      wx.showToast({ title: '退桶申请已提交', icon: 'success' })
      this.loadData()
    } catch (error) {
      console.error('[Barrel] 退桶失败:', error)
      if (!current()) return
      // 后续拒绝不能证明先前超时未建立申请；不改原错误对象的任何标记。
      if (saved && !replay && error.businessRejected && !error.resultUnknown) {
        try { returnIntent.clear(saved); saved = null }
        catch (storageError) { this.setData({ returnRecoveryError: viewError(storageError, '原申请记录清理未成功，请保留原内容并重新核对。') }) }
      }
      this.setData({ pendingReturn: saved, pendingReturnProductText: this.returnProductText(saved), returnError: (saved ? '原申请结果尚未确认，请原样重试或联系本站核对。' : '')
        + viewError(error, saved ? '原内容和编号已保留。' : '本次未能提交，请核实后重试。') })
    } finally {
      if (this._returnSubmitFlight === flight) { this._returnSubmitFlight = null; this.setData({ submitting: false }) }
    }
  },

  onEcoRuleTap() {
    showBusinessModal({ confirmColor: '#1264D6', cancelColor: '#637083',
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
    if (this.data.refreshing || this.data.recordsFailed) return
    // 2026-10-27：记录编号可能以字符串返回；严格比较数字会让可见确认按钮静默失效。
    const record = this.data.records.find(r => String(r.id) === String(e.currentTarget.dataset.id))
    if (!record || !record.returnDetail) return
    const detail = record.returnDetail
    if (this._returnConfirmFlight || detail.status !== 'APPROVED' || detail.customerConfirmationRequired === false || detail.customerConfirmationCurrent) return
    const session=captureSession(), version=detail.arrangementVersion, life=this._arrangementLife || 0
    if (!Number.isInteger(version) || version < 1) { wx.showToast({ title: '收桶安排未核实，请重新加载', icon: 'none' }); return }
    if (accountNumber(record.depositRefund) === null || accountNumber(record.depositRefund) < 0
      || accountNumber(detail.pickupFee) === null || accountNumber(detail.pickupFee) < 0 || accountQuantity(detail.requiredBarrels) === null) {
      wx.showToast({ title: '费用或交桶数量尚未核实，请重新加载', icon: 'none' }); return
    }
    const current = () => !this._arrangementHidden && life === (this._arrangementLife || 0) && isCurrentSession(session)
    this._returnConfirmFlight = true
    showBusinessModal({ confirmColor: '#1264D6', cancelColor: '#637083', title: '确认取桶费与安排',
      content: '请核对费用和新安排。确认安排不代表已经收到退款；实际未收到时可提出异议。',
      details: [{ label: '应退押金', value: '¥' + record.depositRefund },
        { label: '需交回', value: detail.requiredBarrels + ' 个桶' },
        { label: '取桶费', value: '¥' + detail.pickupFee },
        { label: '交接方式', value: detail.pickupModeText || '待核实' }],
      success: async (res) => {
        if (!res.confirm || !current()) { this._returnConfirmFlight = false; return }
        try { await confirmBarrelReturn(record.id, version); if (current()) await this.loadData() }
        catch (err) { if (current()) wx.showToast({ title: viewError(err, '确认失败'), icon: 'none' }) }
        finally { this._returnConfirmFlight = false }
      }, fail: () => { this._returnConfirmFlight = false } })
  },
  async onReturnArrangement(e) {
    if (this.data.refreshing || this.data.recordsFailed) return
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
        const selected=await this.pickCompanionOrder(rows, current)
        if (!selected || !current()) return
        companionOrderId=selected.id
      }
      const reason=await this.arrangementDialog({ title: '改为' + ({ STORE:'到店退桶', PICKUP:'单独上门', COMBINED:'随所选订单收桶' })[mode], editable:true, description:'水站将重新批准安排', initialValue:'', placeholderText:'填写200字以内变更原因', confirmText:'提交更改' })
      if (!reason || !current()) return
      const text=(reason.content || '').trim()
      if (!text || text.length > 200) { wx.showToast({ title:'请填写200字以内变更原因', icon:'none' }); return }
      const body={ pickupMode:mode, companionOrderId, expectedVersion:d.arrangementVersion, reason:text, idempotencyKey:'ret-arr-' + Date.now() + '-' + Math.random().toString(36).slice(2) }
      await changeBarrelReturnArrangement(record.id,body)
      if (current()) { await this.loadData(); wx.showToast({ title:'原申请安排已更改，等待水站批准', icon:'none' }) }
    } catch (err) { if (current()) { wx.showToast({ title:viewError(err, '更改未完成，请核对原申请'), icon:'none' }); await this.loadData() } }
    finally { this._arrangementFlight=false }
  },
  arrangementDialog(options, sheet = false) {
    return new Promise(resolve => {
      let settled = false
      const finish = value => { if (!settled) { settled = true; resolve(value) } }
      const args = { ...options, success: res => finish(sheet || res.confirm ? res : null), fail: () => finish(null) }
      if (sheet) wx.showActionSheet(args)
      else showBusinessModal({ confirmColor: '#1264D6', cancelColor: '#637083', ...args })
    })
  },

  onReturnWithdraw(e) {
    if (this.data.refreshing || this.data.recordsFailed || this._withdrawFlight) return
    const record = this.data.records.find(r => String(r.id) === String(e.currentTarget.dataset.id))
    if (!record) return
    const context = assetViewStation.beginRead(this, 'withdrawDialog')
    this._withdrawFlight = true
    const release = () => { this._withdrawFlight = false }
    showBusinessModal({ confirmColor: '#1264D6', cancelColor: '#637083',  title: '撤回退桶申请', content: '尚未交接的申请可撤回，已锁定权益恢复使用。', fail: release, success: async (res) => {
      if (!res.confirm || !assetViewStation.current(this, context)) { release(); return }
      try { await withdrawBarrelReturn(record.id); if (assetViewStation.current(this, context)) await this.loadData() }
      catch (err) { if (assetViewStation.current(this, context)) wx.showToast({ title: viewError(err, '撤回失败'), icon: 'none' }) }
      finally { release() }
    } })
  },
  // 原生菜单每次只展示少量选项；分页保留本次已读到的全部可选订单。
  async pickCompanionOrder(rows, current) {
    let page = 0
    while (current()) {
      const choices = rows.slice(page * 4, page * 4 + 4).map(order => ({ order, label: '订单 ' + (order.orderNo || order.id) }))
      if (page > 0) choices.push({ direction: -1, label: '上一页订单' })
      if ((page + 1) * 4 < rows.length) choices.push({ direction: 1, label: '下一页订单' })
      const result = await this.arrangementDialog({ itemList: choices.map(c => c.label) }, true)
      if (!result || !current() || !choices[result.tapIndex]) return null
      const chosen = choices[result.tapIndex]
      if (chosen.order) return chosen.order
      page += chosen.direction
    }
    return null
  },
  async onPickupMode() {
    if (this.data.submitting || this.data.pendingReturn || this.data.returnRecoveryError || this._pickupFlight) return
    const context = assetViewStation.beginRead(this, 'pickupDialog')
    const current = () => !this.data.pendingReturn && !this.data.submitting && assetViewStation.current(this, context)
    this._pickupFlight = true
    try {
      const result = await this.arrangementDialog({ itemList: ['到店办理', '单独上门收桶（费用须先确认）', '随送水订单顺路收桶'] }, true)
      if (!result || !current()) return
      const modes = ['STORE', 'PICKUP', 'COMBINED'], texts = ['到店办理', '单独上门收桶', '随送水订单收桶']
      if (!modes[result.tapIndex]) return
      if (result.tapIndex !== 2) {
        this._uiDraftEdited = true
        this.setData({ 'returnForm.pickupMode': modes[result.tapIndex], 'returnForm.pickupModeText': texts[result.tapIndex], 'returnForm.companionOrderId': null })
        this.persistUiDraft()
        return
      }
      const stationId = context.stationId || await resolveStationId()
      if (!stationId || !current()) return
      const response = await getOrders({ stationId, page: 1, size: 100 })
      if (!current()) return
      const data = response && response.data
      if (!Array.isArray(data) && !(data && Array.isArray(data.records))) throw new Error('送水订单暂未加载，请重试')
      const rows = (Array.isArray(data) ? data : data.records).filter(o => o.stationId === stationId && (o.status === 1 || o.status === 2))
      if (!rows.length) { wx.showToast({ title: '暂无进行中的送水订单，请选到店或单独上门', icon: 'none' }); return }
      const picked = await this.pickCompanionOrder(rows, current)
      if (!picked || !current()) return
      this._uiDraftEdited = true
      this.setData({ 'returnForm.pickupMode': 'COMBINED', 'returnForm.pickupModeText': '随送水订单收桶', 'returnForm.companionOrderId': picked.id })
      this.persistUiDraft()
    } catch (error) { if (current()) wx.showToast({ title: viewError(error, '送水订单暂未加载，请重试'), icon: 'none' }) }
    finally { this._pickupFlight = false }
  },

})
