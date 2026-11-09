const { discoveryScope, addressDiscovery } = require('../../utils/station-discovery')
const { parseRegion } = require('../../utils/address')
const { captureSession, isCurrentSession } = require('../../utils/token')
const { getAddresses } = require('../../api/address')
const { deliveryAddressStorage } = require('../../utils/storage')

// Native chooseLocation can hide the page. Keep only its own pending result until return;
// closing the selector, changing city, detaching, or changing account invalidates that result.
Component({
  properties: {
    active: { type: Boolean, value: false, observer(value) {
      if (!value) this.invalidate()
      else if (this._alive) this.initializeAddress()
    } },
    scope: { type: Object, value: null }
  },
  data: { locating: false, addressLoading: false, source: '', message: '', region: [] },
  lifetimes: {
    attached() {
      this._alive = true; this._visible = true
      if (this.properties.active) this.initializeAddress()
    },
    detached() { this._alive = false; this.invalidate() }
  },
  pageLifetimes: {
    hide() { this._visible = false; if (!this._mapOpen) this.invalidate() },
    show() {
      this._visible = true
      if (this._addressSession && !isCurrentSession(this._addressSession) && this.properties.active) return this.initializeAddress()
      this.applyPendingLocation()
    }
  },
  methods: {
    invalidate() {
      this._sequence = (this._sequence || 0) + 1
      this._pendingLocation = null
      this._mapOpen = false
      if (this._alive) this.setData({ locating: false, addressLoading: false })
    },
    /** 2026-11-09: Manual-city-first ignored delivery addresses. Re-read owned addresses on each opening;
     * any newer manual choice, close, navigation or account change must invalidate this automatic result. */
    async initializeAddress() {
      if (!this._alive || !this._visible || !this.properties.active) return
      this.invalidate()
      const sequence = this._sequence, session = this._addressSession = captureSession()
      const current = () => this._alive && this._visible && this.properties.active
        && sequence === this._sequence && isCurrentSession(session)
      this.setData({ source: '', message: '', region: [] })
      if (this.properties.scope) this.triggerEvent('scopechange', null)
      if (!session.loggedIn || !session.customerId) return
      this.setData({ addressLoading: true })
      try {
        const response = await getAddresses()
        if (!current()) return
        if (!response || response.code !== 0 || !Array.isArray(response.data)) throw new Error('address unavailable')
        // Home may finish loading its address while this read is pending. Use the latest
        // same-session ID from the freshly owned list, instead of abandoning valid discovery.
        const resolved = addressDiscovery(response.data, deliveryAddressStorage.getId(session))
        if (!resolved) { this.setData({ message: '地址位置未完善，可在地图选点。' }); return }
        const scope = resolved.scope
        this.setData({ source: resolved.coordinates ? (resolved.source === 'active' ? '正在使用的地址附近' : '默认地址附近') : '地址所在城市',
          region: scope.city ? [scope.province, scope.city] : [],
          message: resolved.coordinates ? '' : '地址无坐标，暂按同城查找；可选地图位置。' })
        this.triggerEvent('scopechange', scope)
      } catch (_) {
        if (current()) this.setData({ message: '地址暂未加载，可重试或在地图选点。' })
      } finally {
        if (this._alive && sequence === this._sequence) this.setData({ addressLoading: false })
      }
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
      this.setData({ source: '', region, message: changedCity && typeof previous.lat === 'number' ? '已切换城市，原地图位置已清除。'
        : typeof scope.lat === 'number' ? '已确认地图所在城市。' : '已选择城市，可补选位置核对配送范围。' })
      this.triggerEvent('scopechange', scope)
    },
    applyPendingLocation() {
      const pending = this._pendingLocation
      if (!pending || !this._alive || !this._visible || !this.properties.active) return
      this._pendingLocation = null
      if (pending.sequence !== this._sequence || !isCurrentSession(pending.session)) return
      this.setData({ source: '地图位置附近', message: pending.scope.city ? ''
        : '位置已选，城市暂未识别，可补选城市查找同城水站。', region: pending.scope.city ? [pending.scope.province, pending.scope.city] : [] })
      this.triggerEvent('scopechange', pending.scope)
    },
    onChooseLocation() {
      if (!this._alive || !this.properties.active || this.data.locating) return
      this.invalidate()
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
            this.setData({ message: /cancel/i.test(message) ? '已取消选点，保留原查找范围。'
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
