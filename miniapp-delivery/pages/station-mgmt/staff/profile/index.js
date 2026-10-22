// 员工画像（站长视角）
const { getStaffProfile } = require('../../../../api/station-mgmt')

Page({
  data: {
    loading: true,
    id: null,
    profile: null,
    error: ''
  },

  onLoad(options) {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    const id = options.id
    this.setData({ id })
    this.loadProfile(id)
  },

  onRetryProfile() { return this.loadProfile(this.data.id) },

  async loadProfile(id) {
    this.setData({ loading: true, error: '' })
    try {
      const res = await getStaffProfile(id)
      if (res.code === 0 && res.data) {
        this.setData({ profile: this.decorate(res.data) })
      } else {
        this.setData({ error: res.message || '员工不存在', profile: null })
      }
    } catch (err) {
      this.setData({ error: err.message || '加载失败', profile: null })
    } finally {
      this.setData({ loading: false })
    }
  },

  decorate(p) {
    const n = (v) => v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? '待核实' : Number(v).toFixed(2)
    p.totalAmountText = n(p.totalAmount)
    p.monthAmountText = n(p.monthAmount)
    p.todayOrders = p.todayOrders === null || p.todayOrders === undefined || p.todayOrders === '' ? '待核实' : p.todayOrders
    p.monthOrders = p.monthOrders === null || p.monthOrders === undefined || p.monthOrders === '' ? '待核实' : p.monthOrders
    p.totalOrders = p.totalOrders === null || p.totalOrders === undefined || p.totalOrders === '' ? '待核实' : p.totalOrders
    p.deliveringOrders = p.deliveringOrders === null || p.deliveringOrders === undefined || p.deliveringOrders === '' ? '待核实' : p.deliveringOrders
    p.cancelledOrders = p.cancelledOrders === null || p.cancelledOrders === undefined || p.cancelledOrders === '' ? '待核实' : p.cancelledOrders
    p.returnCount = p.returnCount === null || p.returnCount === undefined || p.returnCount === '' ? '待核实' : p.returnCount
    p.exceptionCount = p.exceptionCount === null || p.exceptionCount === undefined || p.exceptionCount === '' ? '待核实' : p.exceptionCount
    return p
  },

  onPullDownRefresh() {
    this.loadProfile(this.data.id).then(() => wx.stopPullDownRefresh())
  },

  onCall(e) {
    const { phone } = e.currentTarget.dataset
    if (phone) wx.makePhoneCall({ phoneNumber: phone })
  }
})
