const { listAssetStations } = require('../../api/asset-stations')
const { captureSession, isCurrentSession } = require('../../utils/token')

Component({
  properties: {
    compact: { type: Boolean, value: false },
    stationId: { type: Number, value: null },
    stationName: { type: String, value: '' },
    statusText: { type: String, value: '' },
    orderStationName: { type: String, value: '' },
    busy: { type: Boolean, value: false }
  },
  data: { open: false, loading: false, errorText: '', stations: [], hasMore: false, nextStationId: 0 },
  lifetimes: { attached() { this._alive = true }, detached() { this._alive = false; this._read = (this._read || 0) + 1 } },
  pageLifetimes: { hide() { this.close() } },
  methods: {
    openPicker() {
      if (this.properties.busy) { wx.showToast({ title: '请等待当前办理完成', icon: 'none' }); return }
      if (this.data.open && this.data.loading) return
      const errorText = this._errorSession && isCurrentSession(this._errorSession) ? this.data.errorText : ''
      this.setData({ open: true, stations: [], hasMore: false, nextStationId: 0, errorText })
      return this.load(false)
    },
    close() { this._read = (this._read || 0) + 1; this.setData({ open: false, loading: false }) },
    stopPropagation() {},
    retry() { if (!this.data.loading) return this.load(this.data.stations.length > 0) },
    more() { if (!this.data.loading && this.data.hasMore) return this.load(true) },
    async load(append) {
      const session = captureSession(), sequence = this._read = (this._read || 0) + 1
      const cursor = append ? this.data.nextStationId : 0
      const current = () => this._alive && this.data.open && sequence === this._read && isCurrentSession(session)
      this.setData({ loading: true })
      try {
        const res = await listAssetStations(cursor, 20)
        if (!current()) return
        const result = res && res.data
        if (!res || res.code !== 0 || !result || !Array.isArray(result.stations)
          || typeof result.hasMore !== 'boolean'
          || (result.hasMore && (!Number.isSafeInteger(result.nextStationId) || result.nextStationId <= cursor))
          || result.stations.some(s => !s || !Number.isSafeInteger(s.id) || s.id <= cursor || typeof s.name !== 'string')) {
          throw new Error('水站列表暂未加载，请重试')
        }
        this.setData({ stations: (append ? this.data.stations : []).concat(result.stations),
          hasMore: result.hasMore, nextStationId: result.nextStationId || 0, errorText: '' })
        this._errorSession = null
        this._session = session
      } catch (err) {
        if (current()) {
          // 2026-10-22：含中文的 HTTP 异常仍属技术信息；保留原异常供排查，不覆盖中文业务拒绝。
          console.warn('[assetStationView] 水站列表加载失败:', err)
          this._errorSession = session
          const reason = err && typeof err.message === 'string' ? err.message.trim() : ''
          const readable = /[\u4e00-\u9fff]/.test(reason) && !/https?:|HTTP\s*\d{3}|request:fail|stack|TypeError|SyntaxError/i.test(reason)
          this.setData({ errorText: readable ? reason : '水站列表暂未加载，请重试' })
        }
      }
      finally { if (current()) this.setData({ loading: false }) }
    },
    choose(e) {
      if (this.properties.busy || !this._session || !isCurrentSession(this._session)) return
      const station = this.data.stations.find(s => String(s.id) === String(e.currentTarget.dataset.id))
      if (!station) return
      this.triggerEvent('change', station)
      this.close()
    }
  }
})
