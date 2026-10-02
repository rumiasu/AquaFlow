const { getBarrelRecords } = require('../../api/delivery')
const { STORAGE_KEYS } = require('../../utils/storage-keys')
function validId(value) { return /^[1-9][0-9]*$/.test(String(value)) }
function validTime(value) { return value == null || value === '' || (typeof value === 'string' && Number.isFinite(new Date(value).getTime())) }

Page({
  data: { records: [], loading: true, loaded: false, error: '', totalDeliveries: 0, totalReturn: 0, totalDiscrepancy: 0, },

  onLoad() { this._unloaded = false },
  // 2026-10-02：首屏只在 onShow 读取，重试也走同一访问守卫。
  onShow() { return this.loadData() },
  onRetry() { return this.loadData() },
  _context() {
    const app = getApp(), u = app.globalData.userInfo || wx.getStorageSync(STORAGE_KEYS.USER_INFO) || {}
    return JSON.stringify([u.staffId || u.id || '', u.role || '', u.stationId || '', u.bindStatus || ''])
  },
  _current(version, context) {
    return !this._unloaded && version === this._requestVersion && getApp().canAccessStationBusiness() && context === this._context()
  },

  async loadData() {
    if (this._unloaded) return
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      this._requestVersion = (this._requestVersion || 0) + 1
      this._dataContext = null
      this.setData({ records: [], loaded: false, totalDeliveries: 0, totalReturn: 0, totalDiscrepancy: 0, loading: false, error: '请先完成身份及水站绑定，再重新进入。' })
      app.routeByRole(true)
      return
    }
    const context = this._context(), version = this._requestVersion = (this._requestVersion || 0) + 1
    // 2026-10-02：旧筛选/身份响应曾覆盖新页；先清跨上下文数据，再守 success/catch/finally。
    if (this._dataContext !== context) this.setData({ records: [], loaded: false, totalDeliveries: 0, totalReturn: 0, totalDiscrepancy: 0 })
    this._dataContext = context
    this.setData({ loading: true, error: '' })
    try {
      const res = await getBarrelRecords()
      if (!this._current(version, context)) return
      if (!res || (res.code !== 0 && res.code !== 200) || !Array.isArray(res.data)
          || !res.data.every(r => r && typeof r === 'object' && !Array.isArray(r) && validId(r.id) && validTime(r.updateTime)
            && ['returnBucketQty', 'barrelDiscrepancy'].every(k => r[k] == null || Number.isInteger(r[k])))) {
        throw new Error('收到的数据不完整，请重试。')
      }
      const records = res.data.map(r => {
        const disc = r.barrelDiscrepancy || 0
        return { ...r, timeText: this.formatDate(r.updateTime), discAbsText: Math.abs(disc) + '',
          discLabel: disc > 0 ? '欠桶' : '多还', discTagClass: disc > 0 ? 'owe' : 'extra' }
      })
      const totalReturn = records.reduce((s, r) => s + (r.returnBucketQty || 0), 0)
      const totalDiscrepancy = records.reduce((s, r) => s + (r.barrelDiscrepancy || 0), 0)
      this.setData({ records, totalDeliveries: records.length, totalReturn, totalDiscrepancy, loaded: true })
    } catch (err) {
      if (!this._current(version, context)) return
      const reason = err && typeof err.message === 'string' && err.message.trim() ? err.message : '暂时无法加载，请重试。'
      this.setData({ error: (this.data.loaded ? '刷新失败，显示上次成功加载的结果。' : '空桶回收记录加载失败：') + reason })
    } finally {
      // 2026-10-02：仅丢旧响应会卡住最后一份 flight；无新请求时明确收尾并提示重读。
      // 已有新版本或卸载时仍不能写页，否则旧 finally 会清掉新请求的加载状态。
      if (!this._unloaded && version === this._requestVersion) {
        if (this._current(version, context)) this.setData({ loading: false })
        else {
          this._dataContext = null
          this.setData({ records: [], loaded: false, totalDeliveries: 0, totalReturn: 0, totalDiscrepancy: 0, loading: false, error: '身份或水站已变化，请重试或重新进入。' })
        }
      }
    }
  },
  async onPullDownRefresh() {
    const version = this._pullVersion = (this._pullVersion || 0) + 1
    this._pullRefreshing = true
    try { await this.loadData() } finally {
      if (version === this._pullVersion) { this._pullRefreshing = false; wx.stopPullDownRefresh() }
    }
  },
  onUnload() {
    this._unloaded = true
    this._requestVersion = (this._requestVersion || 0) + 1
    this._pullVersion = (this._pullVersion || 0) + 1
    if (this._pullRefreshing) { this._pullRefreshing = false; wx.stopPullDownRefresh() }
  },

  formatDate(d) {
    if (!d) return ''
    const t = new Date(d)
    const pad = n => n.toString().padStart(2, '0')
    return `${t.getFullYear()}-${pad(t.getMonth() + 1)}-${pad(t.getDate())} ${pad(t.getHours())}:${pad(t.getMinutes())}`
  }
})
