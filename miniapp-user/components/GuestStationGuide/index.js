const { getPublicStations } = require('../../api/station')
const { captureSession, isCurrentSession } = require('../../utils/token')
const { stationName, stationText } = require('../../utils/station-view')

// 游客只读公开水站，不预设下单站或请求需要鉴权的商品目录。
Component({
  data: { open: false, loading: false, error: false, stations: [] },
  lifetimes: {
    attached() { this._alive = true },
    detached() { this._alive = false; this._read = (this._read || 0) + 1 }
  },
  pageLifetimes: {
    hide() { this.close() }
  },
  methods: {
    open() {
      if (this.data.open) return
      this.setData({ open: true })
      return this.load()
    },
    close() {
      this._read = (this._read || 0) + 1
      if (this._alive) this.setData({ open: false, loading: false, error: false, stations: [] })
    },
    retry() { if (!this.data.loading) return this.load() },
    async load() {
      const session = captureSession()
      const seq = this._read = (this._read || 0) + 1
      const current = () => this._alive && this.data.open && seq === this._read && isCurrentSession(session)
      this.setData({ loading: true, error: false, stations: [] })
      try {
        const res = await getPublicStations()
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
          console.warn('[guestStationGuide] 公开水站列表读取失败:', error && error.message)
          this.setData({ stations: [], error: true })
        }
      } finally {
        if (current()) this.setData({ loading: false })
      }
    },
    login() { this.triggerEvent('login') }
  }
})
