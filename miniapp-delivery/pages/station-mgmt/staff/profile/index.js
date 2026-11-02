// 员工画像（站长视角）
const { getStaffProfile } = require('../../../../api/station-mgmt')

Page({
  data: {
    loading: true,
    id: null,
    profile: null,
    metricsExpanded: false,
    error: ''
  },

  onLoad(options) {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    const id = options.id
    if (!/^[1-9][0-9]*$/.test(String(id))) { this.setData({ loading: false, error: '员工编号无效，请返回列表重新进入' }); return }
    this.setData({ id })
    this._profileOwner = this.profileContext()
    this._initialShow = true
    return this.loadProfile(id)
  },

  profileContext() {
    const app = getApp(), data = app.globalData || {}, u = data.userInfo || {}
    return JSON.stringify([!!data.isLogin, app._loginGeneration || 0, u.staffId, u.role, u.stationId, u.bindStatus, this.data.id])
  },
  profileCurrent(context, seq) { return !this._hidden && !this._disposed && seq === this._profileSeq
    && context === this.profileContext() && (!this._profileOwner || context === this._profileOwner) },
  onShow() {
    this._hidden = false
    if (this._profileOwner && this._profileOwner !== this.profileContext()) {
      this.setData({ profile: null, loading: false, error: '登录身份或水站已变化，请返回列表重新进入' }); return
    }
    if (this._initialShow) { this._initialShow = false; return }
    if (this.data.id) return this.loadProfile(this.data.id)
  },
  onHide() { this._hidden = true; this._profileSeq = (this._profileSeq || 0) + 1 },
  onUnload() { this._disposed = true; this.onHide() },

  onRetryProfile() { return this.loadProfile(this.data.id) },
  onToggleMetrics() { this.setData({ metricsExpanded: !this.data.metricsExpanded }) },

  async loadProfile(id) {
    // 2026-11-02：旧读取曾覆盖下拉后的新绩效，离页/换身份也不能应用旧员工资料。
    const context = this.profileContext(), seq = this._profileSeq = (this._profileSeq || 0) + 1
    const current = () => this.profileCurrent(context, seq)
    if (!current()) return
    this.setData({ loading: true, error: '' })
    try {
      const res = await getStaffProfile(id)
      if (!current()) return
      if (res.code === 0 && res.data) {
        this.setData({ profile: this.decorate(res.data) })
      } else {
        this.setData({ error: res.message || '员工不存在', profile: null })
      }
    } catch (err) {
      if (!current()) return
      this.setData({ error: err.message || '加载失败', profile: null })
    } finally {
      if (current()) this.setData({ loading: false })
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
    if (this._hidden || this.data.loading || this.data.error || this._profileOwner !== this.profileContext()) return
    const { phone } = e.currentTarget.dataset
    if (phone) wx.makePhoneCall({ phoneNumber: phone })
  },

  onOrderTap(e) {
    if (this._hidden || this.data.loading || this.data.error || this._profileOwner !== this.profileContext()) return
    const id = e.currentTarget.dataset.id
    if (/^[1-9][0-9]*$/.test(String(id))) wx.navigateTo({ url: `/pages/order/detail?id=${id}` })
  }
})
