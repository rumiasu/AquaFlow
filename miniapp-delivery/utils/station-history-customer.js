const { get } = require('./request')
// 2026-10-06：历史查询复用绑定∪本站订单搜索，不套用资产调整的绑定专属资格。
const data = { customerId: null, customerName: '', customerKeyword: '', customerResults: [], customerSearchError: '', customerSearching: false, customerSearchDone: false }
const methods = {
  onHistoryCustomerInput(e) {
    this._historySearchSeq = (this._historySearchSeq || 0) + 1
    this.setData({ customerKeyword: e.detail.value, customerResults: [], customerSearchDone: false, customerSearchError: '', customerSearching: false })
  },
  async searchHistoryCustomers() {
    const seq = this._historySearchSeq = (this._historySearchSeq || 0) + 1
    this.setData({ customerSearching: true, customerSearchError: '', customerSearchDone: false, customerResults: [] })
    try {
      const res = await get('/api/manager/order-assist/customers', { keyword: String(this.data.customerKeyword || '').trim() })
      if (seq !== this._historySearchSeq) return
      if (!Array.isArray(res.data)) throw new Error('客户搜索暂不可用')
      this.setData({ customerResults: res.data, customerSearchDone: true })
    } catch (e) {
      if (seq === this._historySearchSeq) this.setData({ customerSearchError: e.message || '客户搜索失败，请重试' })
    } finally { if (seq === this._historySearchSeq) this.setData({ customerSearching: false }) }
  },
  onSelectHistoryCustomer(e) {
    const row = this.data.customerResults.find(x => String(x.id) === String(e.currentTarget.dataset.id))
    if (!row) return
    this.setData({ customerId: row.id, customerName: [row.name || '客户', row.phone || ''].filter(Boolean).join(' · '), customerResults: [], customerSearchDone: false })
    return this.loadData()
  },
  onClearHistoryCustomer() {
    this.setData({ customerId: null, customerName: '', customerResults: [], customerSearchDone: false })
    return this.loadData()
  },
  onUnload() {
    this._historySearchSeq = (this._historySearchSeq || 0) + 1
    this._ordersSeq = (this._ordersSeq || 0) + 1
    this._paymentsSeq = (this._paymentsSeq || 0) + 1
  }
}
module.exports = { data, methods }
