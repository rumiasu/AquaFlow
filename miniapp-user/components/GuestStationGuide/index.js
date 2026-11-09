const { getDiscoverStations } = require('../../api/station')
const { captureSession, isCurrentSession } = require('../../utils/token')
const { stationName, stationText } = require('../../utils/station-view')
const { discoveryScope, discoveryKey } = require('../../utils/station-discovery')

// 游客只读所选范围的水站，不预设下单站或请求需要鉴权的商品目录。
Component({
  data: { open: false, loading: false, error: false, stations: [], scope: null, scopeReady: false },
  lifetimes: {
    attached() { this._alive = true },
    detached() { this._alive = false; this._read = (this._read || 0) + 1 }
  },
  pageLifetimes: {
    hide() { this._visible = false; if (!this._mapOpen) this.close(); else { this._read = (this._read || 0) + 1; this.setData({ loading: false }) } },
    show() { this._visible = true; if (this.data.open && !this.data.loading) return this.load() }
  },
  methods: {
    open() {
      if (this.data.open) return
      this.setData({ open: true })
      return this.load()
    },
    close() {
      this._mapOpen = false
      this._read = (this._read || 0) + 1
      if (this._alive) this.setData({ open: false, loading: false, error: false, stations: [] })
    },
    retry() { if (!this.data.loading) return this.load() },
    onLocationStart() { this._mapOpen = true },
    onLocationEnd() { this._mapOpen = false },
    onScopeChange(event) {
      this._scopeSession = captureSession()
      const scope = discoveryScope(event && event.detail)
      this.setData({ scope, scopeReady: !!scope, stations: [], error: false })
      return this.load()
    },
    async load() {
      const session = captureSession()
      if (this._scopeSession && !isCurrentSession(this._scopeSession)) this.setData({ scope: null, stations: [] })
      const seq = this._read = (this._read || 0) + 1
      const scope = discoveryScope(this.data.scope), scopeKey = discoveryKey(scope)
      this.setData({ scopeReady: !!scope })
      if (!scope) { this.setData({ loading: false, error: false, stations: [] }); return }
      const current = () => this._alive && this._visible !== false && this.data.open && seq === this._read && isCurrentSession(session)
        && scopeKey === discoveryKey(this.data.scope)
      if (this._visible === false) { this.setData({ loading: false }); return }
      this.setData({ loading: true, error: false, stations: [] })
      try {
        const res = await getDiscoverStations(scope)
        if (!current()) return
        if (!res || res.code !== 0 || !Array.isArray(res.data)
          || res.data.some(s => !s || !Number.isSafeInteger(s.id) || s.id <= 0 || !Number.isInteger(s.status))) {
          throw new Error('invalid station response')
        }
        const stations = res.data.filter(s => s.status === 1)
          .map(s => ({ id: s.id, name: stationName(s), address: stationText(s.address, '') }))
        this.setData({ stations, error: false })
      } catch (error) {
        if (current()) {
          console.warn('[guestStationGuide] 所选范围水站读取失败:', error && error.message)
          this.setData({ stations: [], error: true })
        }
      } finally {
        if (current()) this.setData({ loading: false })
      }
    },
    login() { this.triggerEvent('login') }
  }
})
