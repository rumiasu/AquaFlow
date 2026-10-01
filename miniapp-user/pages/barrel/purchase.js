const { getStationProducts } = require('../../api/product')
const { quoteBarrelRight, purchaseBarrelRight, getBarrelRightPurchases, withdrawBarrelRightPurchase } = require('../../api/barrel')
const { resolveStationId } = require('../../utils/station')
const { stationStorage } = require('../../utils/storage')
const { getCustomerId } = require('../../utils/token')

Page({
  data: { stationId: null, stationName: '', products: [], index: 0, quantity: 1,
    quote: null, purchases: [], paymentMethod: 2, busy: false, error: '' },
  async onLoad(options) {
    this.initialProductId = Number(options.productId) || null
    this.setData({ quantity: Math.max(1, Math.min(1000, Number(options.quantity) || 1)) })
    try {
      const stationId = Number(options.stationId) || await resolveStationId()
      if (!stationId) throw new Error('请先选择水站')
      const station = stationStorage.get()
      const res = await getStationProducts(stationId)
      const products = (res.data || []).filter(p => p.category === 1)
      if (!products.length) throw new Error('该站暂无可购买押金的桶装水')
      const index = Math.max(0, products.findIndex(p => p.id === this.initialProductId))
      this.setData({ stationId, stationName: (station && station.name) || '', products, index })
      await this.refresh()
    } catch (err) { this.setData({ error: err.message || '加载失败' }) }
  },
  async refresh() {
    const product = this.data.products[this.data.index]
    if (!product) return
    const seq = this._quoteSeq = (this._quoteSeq || 0) + 1
    this.setData({ quote: null, error: '' })
    try {
      const res = await quoteBarrelRight(this.data.stationId, product.id, this.data.quantity)
      if (seq !== this._quoteSeq) return
      this.setData({ quote: res.data, stationName: res.data.stationName })
      const records = await getBarrelRightPurchases(this.data.stationId)
      this.setData({ purchases: records.data || [] })
    } catch (err) { if (seq === this._quoteSeq) this.setData({ error: err.message || '金额暂时不可用' }) }
  },
  onProduct(e) { this.setData({ index: Number(e.detail.value) }); this.refresh() },
  onQuantity(e) { this.setData({ quantity: Math.max(1, Math.min(1000, parseInt(e.detail.value) || 1)) }); this.refresh() },
  onMethod(e) { this.setData({ paymentMethod: Number(e.currentTarget.dataset.method) }) },
  onWithdraw(e) {
    wx.showModal({ title: '撤回未付款的押金购买', content: '已交钱但尚未确认的，请先联系水站核实收款；已确认押金须走退还申请。', success: async r => {
      if (!r.confirm) return
      try { await withdrawBarrelRightPurchase(e.currentTarget.dataset.id); await this.refresh() }
      catch (err) { wx.showToast({ title: err.message || '撤回失败', icon: 'none' }) }
    } })
  },
  async onPurchase() {
    if (this.data.busy || !this.data.quote) return
    const product = this.data.products[this.data.index]
    const customerId=getCustomerId()
    if(!customerId){wx.showToast({title:'请先登录',icon:'none'});return}
    const intent = [customerId, this.data.stationId, product.id, this.data.quantity, this.data.paymentMethod].join(':')
    const saved = wx.getStorageSync('barrel-right-intent')
    const key = saved && saved.intent === intent ? saved.key : 'br-' + Date.now() + '-' + Math.random().toString(36).slice(2)
    wx.setStorageSync('barrel-right-intent', { intent, key })
    this.setData({ busy: true })
    try {
      const res = await purchaseBarrelRight({ stationId: this.data.stationId, productId: product.id,
        quantity: this.data.quantity, paymentMethod: this.data.paymentMethod, idempotencyKey: key })
      wx.removeStorageSync('barrel-right-intent')
      await this.refresh()
      wx.showModal({ title: '桶押金购买已登记', content: (res.data && res.data.status === 2)
        ? '押金已确认，可继续买水票或下单。水桶随下一次送水送达，本次新领的桶无需回空桶。'
        : '请向该水站交付押金并索取收据。水站确认实际收款后，才可以使用这份权益买票和下单。', showCancel: false })
    } catch (err) { wx.showToast({ title: err.message || '购买失败，请重试原请求', icon: 'none' }) }
    finally { this.setData({ busy: false }) }
  },
  onBack() { wx.navigateBack() }
})
