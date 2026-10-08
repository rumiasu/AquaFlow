// 2026-10-06：台账与新建页共用本站客户选择；绑定资格不由前端推断。
const { searchAdjustmentCustomers, getCustomerAssets } = require('../api/station-mgmt')
const data = {
  customerKeyword: '', customerResults: [], customerSearchLoading: false,
  customerSearchDone: false, customerSearchError: '', customerAssets: null,
  customerAssetsLoading: false, customerAssetsError: '', customerName: '', customerPhone: ''
}
const methods = {
  onCustomerSearchInput(e) {
    this._customerSearchSeq = (this._customerSearchSeq || 0) + 1
    this.setData({ customerKeyword: e.detail.value, customerResults: [], customerSearchDone: false,
      customerSearchError: '', customerSearchLoading: false })
  },
  async searchCustomers() {
    if (this.data.denied) return
    const seq = this._customerSearchSeq = (this._customerSearchSeq || 0) + 1
    this.setData({ customerSearchLoading: true, customerSearchDone: false, customerSearchError: '', customerResults: [] })
    try {
      const res = await searchAdjustmentCustomers(String(this.data.customerKeyword || '').trim())
      if (seq !== this._customerSearchSeq) return
      if (!res || !Array.isArray(res.data)) throw new Error('客户搜索暂不可用')
      if (res.data.some(c => !c || typeof c.adjustmentEligible !== 'boolean')) throw new Error('客户绑定资格尚未核对，请重试')
      this.setData({ customerResults: res.data.filter(c => c.adjustmentEligible === true), customerSearchDone: true })
    } catch (e) {
      if (seq === this._customerSearchSeq) this.setData({ customerSearchError: e.message || '客户搜索失败，请重试' })
    } finally {
      if (seq === this._customerSearchSeq) this.setData({ customerSearchLoading: false })
    }
  },
  onSelectCustomer(e) {
    const id = Number(e.currentTarget.dataset.id)
    const customer = this.data.customerResults.find(c => Number(c.id) === id && c.adjustmentEligible === true)
    if (!customer) return
    return this.loadSelectedCustomer(id, customer)
  },
  async loadSelectedCustomer(id, customer) {
    if (this.data.denied || this.data.submitting || this.data.executing || !Number.isSafeInteger(id) || id <= 0) return
    const seq = this._customerSeq = (this._customerSeq || 0) + 1
    // 旧客户的预览、资产和幂等意图不能带到新客户；晚到的试算也不能恢复旧预览。
    this.setData({ customerId: id, missingCustomer: false, customerAssets: null,
      customerName: customer ? customer.name || '' : '', customerPhone: customer ? customer.phone || '' : '',
      customerAssetsLoading: true, customerAssetsError: '', preview: null, previewRows: [], previewExtra: null,
      previewing: false, form: { qty: '', amount: '', unitPrice: '', reason: '' },
      clientToken: 'ADJ-' + Date.now() + '-' + Math.random().toString(36).slice(2),
      customerResults: [], customerSearchDone: false })
    if (this.loadData) this.loadData()
    try {
      const res = await getCustomerAssets(id)
      if (seq !== this._customerSeq) return
      const assets = res && res.data
      if (!assets || Number(assets.customerId) !== id) throw new Error('客户资产尚未核对，请重试')
      if (typeof assets.adjustmentEligible !== 'boolean') throw new Error('客户绑定资格尚未核对，请重试')
      if (assets.adjustmentEligible !== true) throw new Error('该客户尚未绑定本站，不能办理资产调整')
      this.setData({ customerAssets: assets, customerName: assets.customerName || this.data.customerName,
        customerPhone: assets.phone || this.data.customerPhone })
    } catch (e) {
      if (seq === this._customerSeq) this.setData({ customerAssetsError: e.message || '客户资产加载失败，请重试' })
    } finally {
      if (seq === this._customerSeq) this.setData({ customerAssetsLoading: false })
    }
  },
  onRetryCustomerAssets() { return this.loadSelectedCustomer(Number(this.data.customerId)) },
  onUnload() {
    this._customerSeq = (this._customerSeq || 0) + 1
    this._customerSearchSeq = (this._customerSearchSeq || 0) + 1
    this._adjustmentListSeq = (this._adjustmentListSeq || 0) + 1
  }
}
module.exports = { data, methods }
