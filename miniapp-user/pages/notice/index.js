const { captureSession, isCurrentSession } = require('../../utils/token')
const { getNotices } = require('../../api/notice')
Page({
  data: { loading: true, loaded: false, loadError: '', accessDenied: false, notices: [] },
  onShow() { this._disposed = false; this.loadNotices() },
  onHide() { this._readId = (this._readId || 0) + 1; this.setData({ loading: false }) },
  onUnload() { this._disposed = true; this._readId = (this._readId || 0) + 1 },
  onPullDownRefresh() { return this.loadNotices().finally(() => wx.stopPullDownRefresh()) },
  onRetry() { return this.loadNotices() },
  onLogin() { wx.navigateTo({ url: '/pages/login/index' }) },
  ensurePageSession() {
    if (!this._pageSession) { this._pageSession = captureSession(); return true }
    if (isCurrentSession(this._pageSession)) return true
    this._readId = (this._readId || 0) + 1
    const patch = { loading: false, accessDenied: true, loadError: '登录身份已变化，请退出本页后重新进入。' }

    this.setData(patch)
    return false
  },
  async loadNotices() {
    if (!this.ensurePageSession()) return
    const readId = this._readId = (this._readId || 0) + 1
    this.setData({ loading: true, loadError: '' })
    try {
      const res = await getNotices()
      if (readId !== this._readId || this._disposed || !this.ensurePageSession()) return
      if (!res || !Array.isArray(res.data)) throw new Error('公告数据不完整')
      this.setData({ notices: res.data.map(item => ({ ...item,
        typeLabel: item.type === 1 ? '系统公告' : item.type === 2 ? '水站通知' : item.type === 3 ? '活动公告' : '公告',
        timeLabel: item.createTime || '发布时间待核实'
      })), loaded: true, accessDenied: false })
    } catch (error) {
      if (readId !== this._readId || this._disposed || !this.ensurePageSession()) return
      console.warn('Load notices error:', error)
      const message = String(error && error.message || '')
      const code = error && (error.code || error.statusCode)
      const denied = String(code) === '401' || String(code) === '403' || /登录|权限|无权|客户账号|HTTP\s*(401|403)|UNAUTHORIZED|FORBIDDEN/i.test(message)
      this.setData({ accessDenied: denied, loadError: denied ? '当前账号无法查看公告，请重新登录后重试。' : '公告未能读取，请检查网络后重试。' })
    } finally {
      if (readId === this._readId && !this._disposed) this.setData({ loading: false })
    }
  },
  onNoticeTap(e) {
    if (this.data.accessDenied) return
    wx.navigateTo({ url: '/pages/home/notice?id=' + e.currentTarget.dataset.id,
      fail: () => wx.showToast({ title: '公告详情未能打开，请重试', icon: 'none' }) })
  }
})
