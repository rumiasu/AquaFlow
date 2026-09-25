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
    const n = (v) => Number(v || 0).toFixed(2)
    p.totalAmountText = n(p.totalAmount)
    p.monthAmountText = n(p.monthAmount)
    p.todayOrders = p.todayOrders || 0
    p.monthOrders = p.monthOrders || 0
    p.totalOrders = p.totalOrders || 0
    p.deliveringOrders = p.deliveringOrders || 0
    p.cancelledOrders = p.cancelledOrders || 0
    p.returnCount = p.returnCount || 0
    p.exceptionCount = p.exceptionCount || 0
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
