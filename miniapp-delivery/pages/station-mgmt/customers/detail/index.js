// 客户画像（站长视角）
const { getCustomerProfile, updateOfflinePayment } = require('../../../api/station-mgmt')

Page({
  data: {
    loading: true,
    saving: false,
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
      const res = await getCustomerProfile(id)
      if (res.code === 0 && res.data) {
        this.setData({ profile: this.decorate(res.data) })
      } else {
        this.setData({ error: res.message || '客户不存在', profile: null })
      }
    } catch (err) {
      // 把真实错误暴露出来（例如后端未部署该接口会返回 404/网络错误），便于定位"空白"根因
      this.setData({ error: err.message || '加载失败', profile: null })
    } finally {
      this.setData({ loading: false })
    }
  },

  // 金额格式化在 JS 里做（wxml 不做数值格式化，避免兼容问题）
  decorate(p) {
    const n = (v) => Number(v || 0).toFixed(2)
    p.avgOrderAmountText = n(p.avgOrderAmount)
    p.totalConsumptionText = n(p.totalConsumption)
    p.monthConsumptionText = n(p.monthConsumption)
    p.depositBalanceText = n(p.depositBalance)
    p.monthOrders = p.monthOrders || 0
    p.totalOrders = p.totalOrders || 0
    p.ticketBalance = p.ticketBalance || 0
    return p
  },

  onPullDownRefresh() {
    this.loadProfile(this.data.id).then(() => wx.stopPullDownRefresh())
  },

  onCall(e) {
    const { phone } = e.currentTarget.dataset
    if (phone) wx.makePhoneCall({ phoneNumber: phone })
  },

  // 切换货到付款权限
  async onToggleCod(e) {
    const enabled = e.detail.value
    if (this.data.saving) return
    this.setData({ saving: true })
    try {
      const res = await updateOfflinePayment(this.data.id, enabled)
      if (res.code === 0) {
        this.setData({
          'profile.codEnabled': enabled,
          'profile.offlinePaymentEnabled': enabled ? 1 : 0
        })
        wx.showToast({ title: enabled ? '已开通货到付款' : '已关闭货到付款', icon: 'success' })
      } else {
        wx.showToast({ title: res.message || '操作失败', icon: 'none' })
        this.setData({ 'profile.codEnabled': !enabled })
      }
    } catch (err) {
      wx.showToast({ title: err.message || '操作失败', icon: 'none' })
      this.setData({ 'profile.codEnabled': !enabled })
    } finally {
      this.setData({ saving: false })
    }
  }
})
