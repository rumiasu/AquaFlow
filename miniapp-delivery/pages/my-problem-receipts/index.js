const { get, captureSession, currentSession } = require('../../utils/request')
Page({
  data: { loading: false, error: '', items: [] },
  onShow() {
    this._visible = true
    const app = getApp()
    if (!app.canAccessStationBusiness()) { app.routeByRole(true); return }
    return this.load()
  },
  onHide() { this._visible = false; this._version = (this._version || 0) + 1; this.setData({ items: [], error: '' }) },
  onUnload() { this._visible = false; this._version = (this._version || 0) + 1 },
  async load() {
    const version = this._version = (this._version || 0) + 1, session = captureSession()
    const current = () => this._visible && version === this._version && currentSession(session) && getApp().canAccessStationBusiness()
    this.setData({ loading: true, error: '', items: [] })
    try {
      const response = await get('/api/delivery/my-problem-receipts')
      if (!current()) return
      if (!response || !Array.isArray(response.data)) throw new Error('反馈结果格式异常')
      this.setData({ items: response.data, loading: false })
    } catch (e) {
      if (current()) this.setData({ error: (e && e.message) || '反馈加载失败', loading: false })
    }
  }
})
