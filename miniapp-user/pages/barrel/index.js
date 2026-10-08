const { getBarrelSummary, getBarrelRecords, getBarrelSummaryByType, requestBarrelReturn, previewBarrelReturn, confirmBarrelReturn, withdrawBarrelReturn } = require('../../api/barrel')
const { getOrders } = require('../../api/order')
const { stationStorage } = require('../../utils/storage')
// 「当前服务水站」的**统一解析入口**（本地 → 回落 /api/orders/my-station）。
// 本页原先直接用 stationStorage.getId()，少了回落那一级：下过单但没在首页选过站的顾客
// 会被判成"没选水站"，而其实他的水站是查得出来的（utils/station.js 文件头写了为什么统一到这里）。
const { resolveStationId } = require('../../utils/station')

Page({
  data: {
    loading: true,
    // 占位初值：真实数据由 getBarrelSummary 整体替换。
    // [2026-09-16] 字段名必须与后端 BarrelServiceImpl.getBarrelSummary 的下发键一致 ——
    // 这里原先留着已删除的 deliveryBuckets（含已送达行，口径错误，后端已移除），
    // 会让后来者以为它是有效字段。配送中请一律用 pendingDeliveryBuckets。
    // 2026-10-07：旧“权益=已到手”只描述历史送达建权益路径；新购先形成有效容量。
    // held 含遗留配送中；occupied=权益汇总+over 是账面 H，不是活动用途占用。
    // availableRights 扣活动用途和遗留占用，不能当物理数；现“实际在手”标签待业务核对，见 design/36 §5.4。
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
    // 试算算不出来时的**可读原因**（常驻在试算框里，比只弹一次 toast 更容易被看到）。
    // 典型场景：顾客还没选服务水站 ⇒ 见 refreshPreview 的注释（2026-09-27 修）。
    previewHint: '',
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
        map[pid].availableRights = h.availableRights != null ? h.availableRights : base
        map[pid].assetQty += base
        map[pid].heldTotalQty += (h.heldTotalQty != null ? h.heldTotalQty : base)
        map[pid].inTransitQty += (h.inTransitQty || 0)
        map[pid].occupiedQty += (h.occupiedQty != null ? h.occupiedQty : base)
        map[pid].owedQty += (h.owedQty || 0)
        map[pid].storageQty += (h.storageQty || 0)
      })
      Object.keys(map).forEach(k => customerBarrelAsset.push(map[k]))

      this.setData({ customerBarrelAsset })

      // 历史物理交回上限 = occupied（权益汇总 + over），不是含配送中的 held；新申请下方另用 availableRights。
      // 配送中的桶还没到客户手上，后端 returnEmpty 也是按占用校验的
      //（BarrelLedgerService：qty <= rightQty + over）。用持有当上限会在有在途桶时
      // 允许多报，提交后被后端以「交回数超过该客户当前持有数」拒绝 —— 顾客以为是 bug。
      // 历史路径再减待处理申请；新路径扣活动用途后的可用权益已由后端下发，不在此重复扣。
      const { occupiedBuckets, rightBuckets, pendingReturns } = this.data.summary
      const ceiling = occupiedBuckets !== undefined && occupiedBuckets !== null
        ? occupiedBuckets
        : (rightBuckets || 0)
      this.setData({ maxReturnQty: Math.max(0, (ceiling || 0) - (pendingReturns || 0)) })
      if (this.data.summary.independentRights) this.setData({ maxReturnQty: this.data.summary.availableRights || 0 })
    } finally {
      this.setData({ loading: false })
    }
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
      'returnForm.pickupModeText': '到店退桶',
      'returnForm.companionOrderId': null,
      preview: null,
      previewHint: ''
    })
    if (first) this.refreshPreview()
  },

  onCloseReturnModal() {
    // 连提示一起清掉：下次打开时不该看到上一次留下的原因
    this.setData({ showReturnModal: false, preview: null, previewHint: '' })
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
    const { productId, quantity } = this.data.returnForm
    if (!productId || !quantity || quantity <= 0) {
      this.setData({ preview: null, previewHint: '' })
      return
    }
    const stationId = await resolveStationId()
    if (!stationId) {
      // 不发请求，并把原因**写在试算框里**（比只弹一次 toast 更持久，顾客回头还能看到）
      this.setData({ preview: null, previewHint: '要算能退多少押金，得先选服务水站：回首页点顶部水站名选一个。' })
      wx.showToast({ title: '请先选择服务水站', icon: 'none' })
      return
    }
    this.setData({ previewing: true, previewHint: '' })
    try {
      const res = await previewBarrelReturn(productId, quantity, stationId)
      this.setData({ preview: (res && res.data) || null })
    } catch (e) {
      // 出声，但**别把技术原因糊给顾客**：后端给的话术已经面向用户，优先用它。
      console.warn('[Barrel] 退桶试算失败:', e.message)
      this.setData({ preview: null, previewHint: (e && e.message) || '暂时算不出能退多少，请稍后重试' })
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
    const stationId = await resolveStationId()
    if (!stationId) {
      wx.showToast({ title: '请先选择服务水站', icon: 'none' })
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
      wx.removeStorageSync('barrel-return-intent')
      wx.showToast({ title: '退桶申请已提交', icon: 'success' })
      this.setData({ showReturnModal: false })
      this.loadData()
    } catch (error) {
      console.error('[Barrel] 退桶失败:', error)
      wx.showToast({ title: '提交失败: ' + (error.message || '请稍后重试'), icon: 'none', duration: 3000 })
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
      content: '1. 水桶需保持完好，无严重破损\n2. 提交申请后，水站会先确认收到空桶\n3. 每桶金额以页面预览为准，具体退款方式请向水站确认\n4. 请勿将水桶用于非饮用水用途',
      showCancel: false,
      confirmText: '我知道了'
    })
  },

  // 押金流水入口（本页顶部「押金」格子）。余额与流水必须取同一个水站，
  // 两边都读 stationStorage.getId()，否则客户会看到"余额没变但流水在动"。
  onDepositRecords() {
    wx.navigateTo({ url: '/pages/deposit/records/index' })
  },
  onPurchaseRights() { wx.navigateTo({ url: '/pages/barrel/purchase' }) },
  onRefundFeedback(e) {
    const record = this.data.records.find(r => r.id === Number(e.currentTarget.dataset.id))
    if (record && record.statusText) wx.navigateTo({ url: '/pages/service/index?refundType=BARREL_RETURN&refundId=' + record.id })
  },
  async onReturnConfirm(e) {
    const record = this.data.records.find(r => r.id === Number(e.currentTarget.dataset.id))
    if (!record || !record.returnDetail) return
    const detail = record.returnDetail
    wx.showModal({ title: '确认退桶安排', content: '退押金 ¥' + record.depositRefund + '；需交回 ' + detail.requiredBarrels + ' 个桶；' + detail.pickupModeText + '；另付取桶费 ¥' + detail.pickupFee + '。按批准的安排交桶，实际退款另行确认。',
      success: async (res) => {
        if (!res.confirm) return
        try { await confirmBarrelReturn(record.id); await this.loadData() }
        catch (err) { wx.showToast({ title: err.message || '确认失败', icon: 'none' }) }
      } })
  },
  onReturnWithdraw(e) {
    wx.showModal({ title: '撤回退桶申请', content: '尚未交接的申请可撤回，已锁定权益恢复使用。', success: async (res) => {
      if (!res.confirm) return
      try { await withdrawBarrelReturn(e.currentTarget.dataset.id); await this.loadData() }
      catch (err) { wx.showToast({ title: err.message || '撤回失败', icon: 'none' }) }
    } })
  },
  onPickupMode() {
    wx.showActionSheet({ itemList: ['到店退桶', '单独上门收桶（费用须先确认）', '随送水订单顺路收桶'], success: async (res) => {
      const modes = ['STORE', 'PICKUP', 'COMBINED'], texts = ['到店退桶', '单独上门收桶', '随送水订单收桶']
      if (res.tapIndex !== 2) { this.setData({ 'returnForm.pickupMode': modes[res.tapIndex], 'returnForm.pickupModeText': texts[res.tapIndex], 'returnForm.companionOrderId': null }); return }
      try {
        const stationId = await resolveStationId()
        const response = await getOrders({ stationId, page: 1, size: 100 })
        const rows = (Array.isArray(response.data) ? response.data : (response.data.records || []))
          .filter(o => o.stationId === stationId && (o.status === 1 || o.status === 2))
        if (!rows.length) { wx.showToast({ title: '暂无进行中的送水订单，请选到店或单独上门', icon: 'none' }); return }
        wx.showActionSheet({ itemList: rows.slice(0, 6).map(o => '订单 ' + (o.orderNo || o.id)), success: (picked) => {
          this.setData({ 'returnForm.pickupMode': 'COMBINED', 'returnForm.pickupModeText': '随送水订单收桶', 'returnForm.companionOrderId': rows[picked.tapIndex].id })
        } })
      } catch (err) { wx.showToast({ title: err.message || '订单加载失败', icon: 'none' }) }
    } })
  },

})
