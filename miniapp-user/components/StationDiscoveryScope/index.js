const { discoveryScope } = require('../../utils/station-discovery')
const { parseRegion } = require('../../utils/address')
const { captureSession, isCurrentSession } = require('../../utils/token')

// Native chooseLocation can hide the page. Keep only its own pending result until return;
// closing the selector, changing city, detaching, or changing account invalidates that result.
Component({
  properties: {
    active: { type: Boolean, value: false, observer(value) { if (!value) this.invalidate() } },
    scope: { type: Object, value: null }
  },
  data: { locating: false, message: '', region: [] },
  lifetimes: {
    attached() { this._alive = true; this._visible = true },
    detached() { this._alive = false; this.invalidate() }
  },
  pageLifetimes: {
    hide() { this._visible = false; if (!this._mapOpen) this.invalidate() },
    show() { this._visible = true; this.applyPendingLocation() }
  },
  methods: {
    invalidate() {
      this._sequence = (this._sequence || 0) + 1
      this._pendingLocation = null
      this._mapOpen = false
      if (this._alive) this.setData({ locating: false })
    },
    onCityChange(event) {
      if (!this._alive || !this.properties.active) return
      const region = event && event.detail && event.detail.value
      let scope = Array.isArray(region) ? discoveryScope({ province: region[0], city: region[1] }) : null
      if (!scope) { this.setData({ message: '请选择具体城市。' }); return }
      const previous = discoveryScope(this.properties.scope)
      const changedCity = previous && previous.city && (previous.city !== scope.city || (previous.province && previous.province !== scope.province))
      if (previous && typeof previous.lat === 'number' && !changedCity) scope = { ...scope, lat: previous.lat, lng: previous.lng }
      this.invalidate()
      this.setData({ region, message: changedCity && typeof previous.lat === 'number' ? '已切换城市，原地图位置已清除，请重新选择该城市内的位置。'
        : typeof scope.lat === 'number' ? '已确认地图所在城市，结合位置与城市查找。' : '已选择城市；补选地图位置可进一步核对水站配送范围。' })
      this.triggerEvent('scopechange', scope)
    },
    applyPendingLocation() {
      const pending = this._pendingLocation
      if (!pending || !this._alive || !this._visible || !this.properties.active) return
      this._pendingLocation = null
      if (pending.sequence !== this._sequence || !isCurrentSession(pending.session)) return
      this.setData({ message: pending.scope.city ? '已选择地图位置及所在城市。'
        : '地图位置已选，城市暂未识别；可补选地图所在城市，查看更多同城水站。', region: pending.scope.city ? [pending.scope.province, pending.scope.city] : [] })
      this.triggerEvent('scopechange', pending.scope)
    },
    onChooseLocation() {
      if (!this._alive || !this.properties.active || this.data.locating) return
      const session = captureSession(), sequence = this._sequence = (this._sequence || 0) + 1
      const current = () => this._alive && this.properties.active && sequence === this._sequence && isCurrentSession(session)
      if (typeof wx.chooseLocation !== 'function') {
        this.setData({ message: '地图暂不可用，请选择城市查找。' }); return
      }
      this._mapOpen = true
      this.setData({ locating: true, message: '' })
      this.triggerEvent('locationstart')
      const finish = () => {
        if (sequence !== this._sequence) return
        this._mapOpen = false
        if (this._alive) { this.setData({ locating: false }); this.triggerEvent('locationend') }
      }
      try {
        wx.chooseLocation({
          success: result => {
            if (!current()) return
            // 2026-11-07: The address parser may join a prefecture and its county-level city
            // (e.g. 延边朝鲜族自治州延吉市). Never use that ambiguous text as the discovery city.
            // Require explicit city selection instead; ordinary names such as 杭州市 remain valid.
            const parsed = parseRegion(typeof result.address === 'string' ? result.address : '')
            const ambiguousCity = /自治州|地区|盟|自治区|直辖县级|州.+市/.test(parsed.city)
            const region = parsed.province && parsed.city && !ambiguousCity
              ? { province: parsed.province, city: parsed.city } : {}
            const scope = discoveryScope({ lat: result.latitude, lng: result.longitude, ...region })
            if (!scope) { this.setData({ message: '地图位置不完整，请重新选择位置或选择城市。' }); return }
            this._pendingLocation = { scope, session, sequence }
            this.applyPendingLocation()
          },
          fail: error => {
            if (!current()) return
            const message = error && error.errMsg || ''
            this.setData({ message: /cancel/i.test(message) ? '已取消选择位置，原查找范围保留。'
              : /auth|deny|permission/i.test(message) ? '位置权限未开启，可选择城市查找。' : '地图暂不可用，请选择城市查找。' })
          },
          complete: finish
        })
      } catch (_) {
        if (current()) this.setData({ message: '地图暂不可用，请选择城市查找。' })
        finish()
      }
    }
  }
})
