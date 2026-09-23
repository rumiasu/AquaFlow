const { getBarrelSummary, getBarrelRecords, getBarrelSummaryByType, requestBarrelReturn, previewBarrelReturn } = require('../../api/barrel')
const { stationStorage } = require('../../utils/storage')

Page({
  data: {
    loading: true,
    // 占位初值：真实数据由 getBarrelSummary 整体替换。
    // [2026-09-16] 字段名必须与后端 BarrelServiceImpl.getBarrelSummary 的下发键一致 ——
    // 这里原先留着已删除的 deliveryBuckets（含已送达行，口径错误，后端已移除），
    // 会让后来者以为它是有效字段。配送中请一律用 pendingDeliveryBuckets。
    // 口径：持有 = 权益 + 配送中（展示）；占用 = 权益 + over（还桶上限）；权益 = 已到手。
    summary: {
      heldBuckets: 0,
      rightBuckets: 0,
      owedBuckets: 0,
      pendingDeliveryBuckets: 0,
      occupiedBuckets: 0,
      storageBuckets: 0,
      returnBuckets: 0,
      actualBuckets: 0,
      pendingReturns: 0,
      confirmedReturns: 0,
      depositBalance: 0,
      depositPerBucket: 0
    },
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
    previewing: false,
    maxReturnQty: 0,
    submitting: false
  },

  onLoad() {
    this.loadData()
  },

  onShow() {
    this.loadData()
  },

  onPullDownRefresh() {
    this.loadData().then(() => wx.stopPullDownRefresh())
  },

  async loadData() {
    // 优先读取本地存储的水站
    let stationId = stationStorage.getId()
    // 站名供顶部提示条使用：同 pages/deposit/records 的做法（选站时就把整个 station 存下来了）
    const station = stationStorage.get()

    this.setData({ loading: true, stationName: (station && station.name) || '' })
    try {
      // [2026-09-20 真机联调] 原来三个请求各自 `.catch(e => { console.warn(...); return null })`，
      // 失败被吞成 null → 页面照常渲染「权益 0 · 占用 0」，与"这客户确实没有桶"完全无法区分
      // （AGENTS §8.22）。弱网/后端没起时顾客会以为自己一张桶都没有。
      // 现在失败照旧降级（部分成功仍然可用），但**必须出声**：页面顶部提示 + toast。
      let failedCount = 0
      const softCatch = (tag) => (e) => {
        failedCount++
        console.warn('[Barrel] ' + tag + ' 失败:', e.message)
        return null
      }
      const [summaryRes, recordsRes, holdingsRes] = await Promise.all([
        getBarrelSummary(stationId).catch(softCatch('getBarrelSummary')),
        getBarrelRecords(stationId).catch(softCatch('getBarrelRecords')),
        getBarrelSummaryByType(stationId).catch(softCatch('getBarrelSummaryByType'))
      ])
      if (failedCount > 0) {
        this.setData({ loadError: '桶数据有 ' + failedCount + ' 项没加载出来，下面数字可能不准' })
        wx.showToast({ title: '桶数据加载不完整，请下拉刷新', icon: 'none' })
      } else {
        this.setData({ loadError: '' })
      }

      if (summaryRes && summaryRes.data) {
        this.setData({ summary: summaryRes.data })
      }
      if (recordsRes && recordsRes.data) {
        this.setData({ records: recordsRes.data })
      }

      let holdings = []
      if (holdingsRes && holdingsRes.data) {
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
            assetQty: 0,
            heldTotalQty: 0,
            inTransitQty: 0,
            occupiedQty: 0,
            owedQty: 0,
            storageQty: 0,
            deposit: h.deposit || 0
          }
        }
        // 后端一行即一个商品，这里的累加是防御性写法（历史上有按 holding 多条返回的版本）。
        // 各字段口径见 BarrelServiceImpl.getBarrelSummaryByType 的注释；
        // 缺少 heldTotalQty/occupiedQty 时回退到 assetQty（只影响展示与上限，不参与下单抵扣）。
        const base = h.assetQty != null ? h.assetQty : ((h.holdingQty || 0) - (h.confirmedQty || 0))
        map[pid].assetQty += base
        map[pid].heldTotalQty += (h.heldTotalQty != null ? h.heldTotalQty : base)
        map[pid].inTransitQty += (h.inTransitQty || 0)
        map[pid].occupiedQty += (h.occupiedQty != null ? h.occupiedQty : base)
        map[pid].owedQty += (h.owedQty || 0)
        map[pid].storageQty += (h.storageQty || 0)
      })
      Object.keys(map).forEach(k => customerBarrelAsset.push(map[k]))

      this.setData({ customerBarrelAsset })

      // 还桶上限 = **占用**（权益 + over），不是「持有」（权益 + 配送中）：
      // 配送中的桶还没到客户手上，后端 returnEmpty 也是按占用校验的
      //（BarrelLedgerService：qty <= rightQty + over）。用持有当上限会在有在途桶时
      // 允许多报，提交后被后端以「交回数超过该客户当前持有数」拒绝 —— 顾客以为是 bug。
      // 再减去已提交待处理的退桶申请数，避免同一批桶被重复申请两次。
      const { occupiedBuckets, rightBuckets, pendingReturns } = this.data.summary
      const ceiling = occupiedBuckets !== undefined && occupiedBuckets !== null
        ? occupiedBuckets
        : (rightBuckets || 0)
      this.setData({ maxReturnQty: Math.max(0, (ceiling || 0) - (pendingReturns || 0)) })
    } finally {
      this.setData({ loading: false })
    }
  },

  onShowReturnModal() {
    if (this.data.maxReturnQty <= 0) {
      wx.showToast({ title: '暂无可退水桶', icon: 'none' })
      return
    }
    // 默认选中第一个**可退**的商品（占用 > 0）。
    // 不能用 assetQty：桶全在配送中时权益也可能 > 0，但此时占用为 0，其实退不了，
    // 默认选中它只会让顾客点提交后被拒。省得顾客等报错。
    const first = (this.data.customerBarrelAsset || [])
      .find(i => (i.occupiedQty || 0) > 0) || (this.data.customerBarrelAsset || [])[0]
    this.setData({
      showReturnModal: true,
      'returnForm.productId': first ? first.productId : null,
      'returnForm.quantity': 1,
      'returnForm.note': '',
      preview: null
    })
    if (first) this.refreshPreview()
  },

  onCloseReturnModal() {
    this.setData({ showReturnModal: false })
  },

  /**
   * 退桶试算：调后端 /return/preview。
   * 退款金额只认后端按押金条批次算出来的值 —— 前端自己用「数量 × 押金单价」估是错的：
   * 顾客当年买桶的价和现在不一定一样，那是柜台吵架的经典导火索。
   */
  async refreshPreview() {
    const { productId, quantity } = this.data.returnForm
    if (!productId || !quantity || quantity <= 0) {
      this.setData({ preview: null })
      return
    }
    this.setData({ previewing: true })
    try {
      const res = await previewBarrelReturn(productId, quantity, stationStorage.getId())
      this.setData({ preview: (res && res.data) || null })
    } catch (e) {
      console.warn('[Barrel] 退桶试算失败:', e.message)
      this.setData({ preview: null })
    } finally {
      this.setData({ previewing: false })
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

    this.setData({ submitting: true })
    try {
      // 不传 depositRefund：金额由服务端按押金条批次核销决定，顾客填多少都不算数
      await requestBarrelReturn({ productId, waterTypeId: productId, quantity, note })
      wx.showToast({ title: '退桶申请已提交', icon: 'success' })
      this.setData({ showReturnModal: false })
      this.loadData()
    } catch (error) {
      console.error('[Barrel] 退桶失败:', error)
      wx.showToast({ title: '提交失败: ' + (error.message || '请检查后端'), icon: 'none', duration: 3000 })
    } finally {
      this.setData({ submitting: false })
    }
  },

  onEcoRuleTap() {
    wx.showModal({
      title: '水桶回收规则',
      content: '1. 水桶需保持完好，无严重破损\n2. 退桶时请联系配送员或到站点办理\n3. 押金将在确认后退还至您的账户\n4. 请勿将水桶用于非饮用水用途',
      showCancel: false,
      confirmText: '我知道了'
    })
  },

  // 押金流水入口（本页顶部「押金」格子）。余额与流水必须取同一个水站，
  // 两边都读 stationStorage.getId()，否则客户会看到"余额没变但流水在动"。
  onDepositRecords() {
    wx.navigateTo({ url: '/pages/deposit/records/index' })
  },

})