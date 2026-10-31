const { createDraftGuard } = require('../../../utils/draft-guard')
const { getTransportConfig } = require('../../../config/api')
const { showBusinessModal } = require('../../../utils/business-dialog')
const { jumpToSettingSection } = require('../../../utils/settings-sections')
const { initialModuleData, refreshModuleTasks } = require('../../../utils/setup-progress')
// 水站资料与地址坐标通过 profile 一次保存；旧版服务无此接口时保留输入并提示。
const { getMyStation, updateStationProfile } = require('../../../api/station-mgmt')
function stationSession() {
  const app = getApp(), g = app.globalData || {}, u = g.userInfo || {}
  return { app, generation: app._loginGeneration || 0, stationId: Number(u.stationId),
    identity: JSON.stringify([!!g.isLogin, u.staffId, u.role, u.stationId, u.bindStatus]) }
}
function sameStationSession(proof) {
  const now = stationSession()
  return proof && proof.app === now.app && proof.generation === now.generation && proof.identity === now.identity && now.app.canAccessStationBusiness()
}

Page({
  onSettingsSection(e) { jumpToSettingSection(this, e) },
  data: {
    settingsSections: [{"target": "info-basics", "label": "联系资料"}, {"target": "setup-station-position", "label": "地图坐标"}],
    ...initialModuleData(),
    loading: true, loadError: '',
    // 原值（含后端下发但本页不编辑的 status），保存时原样带回
    origin: null,
    form: { name: '', phone: '', address: '' },
    saving: false,
    // lat 为 null = 还没选点，此时配送范围校验会跳过（不是拒单）
    coord: { lat: null, lng: null },
    coordSaving: false, coordinateMode: 'KEEP', addressSupplement: '', saveMessage: '', infoDirty: false, infoDraftNotice: '', infoDraftAvailable: false, infoDraftStatus: ''
  },

  refreshModuleSetup() { return refreshModuleTasks(this, '/pages/station-mgmt/station-info/index') },
  onModuleSetupTask(e) {
    const key = e.currentTarget.dataset.key
    if (!(this.data.moduleSetupTasks || []).some(it => it.key === key)) return
    const origin = this.data.origin || {}, coord = this.data.coord || {}
    const addressMissing = !String(origin.address || '').trim()
    const coordinatesKnown = coord.lat != null && coord.lng != null
    const target = key === 'stationPhone' ? '#setup-station-phone'
      : addressMissing && coordinatesKnown ? '#info-basics' : '#setup-station-position'
    if (typeof wx.pageScrollTo === 'function') wx.pageScrollTo({ selector: target, duration: 180 })
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    if (!sameStationSession(this._stationSession)) {
      this._coordinateVersion = (this._coordinateVersion || 0) + 1
      this._coordinatePick = null
      this.setData({ saving: false, coordSaving: false, infoDirty: false, origin: null, form: { name: '', phone: '', address: '' }, infoDraftNotice: '' })
    }
    this._stationSession = stationSession()
    // 原生地图返回与同身份再显示时保留未保存资料。
    if (this.data.origin && this.data.infoDirty && sameStationSession(this._stationSession)) return this.refreshModuleSetup()
    if (!this._coordinatePick || !this.coordinateCurrent(this._coordinatePick)) return this.loadData()
  },

  onUnload() {
    this._moduleSetupClosed = true
    this._stationUnloaded = true
    this._coordinateVersion = (this._coordinateVersion || 0) + 1
    this._stationReadVersion = (this._stationReadVersion || 0) + 1
    this._coordinatePick = null
  },

  coordinateCurrent(proof) {
    return !this._stationUnloaded && this._coordinatePick === proof && proof.version === this._coordinateVersion
      && sameStationSession(proof.session) && Number(this.data.origin && this.data.origin.id) === proof.session.stationId
  },

  onRetry() {
    if (!this.data.infoDirty) return this.loadData()
    showBusinessModal({ title: '重新读取资料', content: '重新读取会替换尚未保存的资料输入。是否继续？', confirmText: '重新读取', cancelText: '保留输入',
      success: result => { if (result.confirm) { this.setData({ infoDirty: false, infoDraftNotice: '' }); this.onDiscardInfoDraft(); this.loadData() } } })
  },

  async loadData() {
    this.refreshModuleSetup()
    if (this._stationUnloaded) return
    const session = stationSession(), version = this._stationReadVersion = (this._stationReadVersion || 0) + 1
    const current = () => !this._stationUnloaded && sameStationSession(session) && version === this._stationReadVersion
    this.setData({ loading: true, loadError: '' })
    try {
      const res = await getMyStation()
      if (!current()) return
      const d = res.data
      if (!d || !d.id) throw new Error('水站资料尚未读取')
      this.setData({
        infoDirty: false, infoDraftNotice: '', coordinateMode: 'KEEP', addressSupplement: '', saveMessage: '', origin: d,
        form: {
          name: d.name || '',
          phone: d.phone || '',
          address: d.address || ''
        },
        coord: {
          // 三态别压扁：null 表示未设置，不能被 `|| null` 之外的写法顺手变成 0
          lat: (d.lat === null || d.lat === undefined) ? null : d.lat,
          lng: (d.lng === null || d.lng === undefined) ? null : d.lng
        }
      })
      this.offerInfoDraft()
      if (typeof wx.disableAlertBeforeUnload === 'function') wx.disableAlertBeforeUnload()
    } catch (err) {
      if (!current()) return
      // 静默失败会让页面显示成空白表单，站长一保存就把站名冲没了 —— 必须出声。
      this.setData({ loadError: '水站资料尚未读取，请重新读取后修改' })
      console.error('[StationInfo] 读取水站资料失败:', err)
      wx.showToast({ title: (err && err.message) || '读取水站资料失败', icon: 'none' })
    } finally {
      if (current()) this.setData({ loading: false })
    }
  },

  infoDraftGuard() {
    if (!this._infoDraftGuard) this._infoDraftGuard = createDraftGuard(() => {
      const session = stationSession(), u = session.app.globalData.userInfo || {}, transport = getTransportConfig()
      if (!sameStationSession(session) || !u.staffId || !session.stationId) return null
      return { app: 'miniapp-delivery', actor: u.staffId, station: session.stationId,
        route: 'pages/station-mgmt/station-info', entity: JSON.stringify([transport.mode, transport.env, transport.service, transport.baseUrl, session.stationId]), generation: session.generation }
    })
    return this._infoDraftGuard
  },
  offerInfoDraft() {
    const guard = this.infoDraftGuard(), token = guard.begin(), result = guard.read(token)
    this._infoDraftToken = token
    const form = result.draft && result.draft.form
    const valid = result.state === 'saved' && form && ['name', 'phone', 'address'].every(k => typeof form[k] === 'string')
    this._savedInfoDraft = valid ? result.draft : null
    this.setData({ infoDraftAvailable: !!valid, infoDraftStatus: result.state === 'unknown' || result.state === 'saved' && !valid ? '草稿尚未确认，请保留当前输入后核对。' : '' })
  },
  onRestoreInfoDraft() {
    if (!this.data.infoDraftAvailable || !this._savedInfoDraft || this.data.saving || this.data.coordSaving) return
    if (this.infoDraftGuard().read(this._infoDraftToken).state !== 'saved') return
    const draft = this._savedInfoDraft, form = draft.form
    const restoredMode = ['KEEP', 'SET', 'CLEAR'].includes(draft.coordinateMode) ? draft.coordinateMode :
      (String(form.address || '') !== String((this.data.origin || {}).address || '') ? 'CLEAR' : 'KEEP')
    const coord = draft.coord || {}, validPoint = Number.isFinite(coord.lat) && Math.abs(coord.lat) <= 90 && Number.isFinite(coord.lng) && Math.abs(coord.lng) <= 180
    this.setData({ form: { ...form }, addressSupplement: typeof draft.addressSupplement === 'string' ? draft.addressSupplement : '',
      coordinateMode: restoredMode === 'SET' && !validPoint || restoredMode === 'KEEP' && String(form.address || '') !== String((this.data.origin || {}).address || '') ? 'CLEAR' : restoredMode,
      ...(restoredMode === 'SET' && validPoint ? { coord: { lat: coord.lat, lng: coord.lng } } : {}), infoDraftAvailable: false })
    this.markInfoDraft()
  },
  onDiscardInfoDraft() {
    const result = this.infoDraftGuard().clear(this._infoDraftToken)
    this._savedInfoDraft = null
    this.setData({ infoDraftAvailable: false, infoDraftStatus: result.state === 'empty' ? '' : '草稿清理尚未确认，请稍后核对。' })
  },
  markInfoDraft() {
    const origin = this.data.origin || {}, form = this.data.form
    const dirty = ['name', 'phone', 'address'].some(k => String(form[k] || '') !== String(origin[k] || '')) || !!String(this.data.addressSupplement || '').trim() || this.data.coordinateMode !== 'KEEP'
    this.setData({ infoDirty: dirty, infoDraftAvailable: false, infoDraftNotice: dirty ? '资料和位置有未保存修改。' : '' })
    const guard = this.infoDraftGuard(), token = this._infoDraftToken || guard.begin()
    this._infoDraftToken = token
    const result = dirty ? guard.save(token, { form: { ...form }, coordinateMode: this.data.coordinateMode, coord: { ...this.data.coord }, addressSupplement: this.data.addressSupplement }) : guard.clear(token)
    this.setData({ infoDraftStatus: result.state === 'saved' ? '输入草稿已保留，正式保存后生效。' : result.state === 'empty' ? '' : '草稿尚未确认保存，请留在本页完成操作。' })
    if (dirty && typeof wx.enableAlertBeforeUnload === 'function') wx.enableAlertBeforeUnload({ message: '水站资料尚未保存，离开将丢失修改' })
    if (!dirty && typeof wx.disableAlertBeforeUnload === 'function') wx.disableAlertBeforeUnload()
  },
  onNameInput(e) { if (!this.data.saving) { this.setData({ 'form.name': e.detail.value }); this.markInfoDraft() } },
  onPhoneInput(e) { if (!this.data.saving) { this.setData({ 'form.phone': e.detail.value }); this.markInfoDraft() } },
  onAddressInput(e) {
    if (this.data.saving || this.data.coordSaving) return
    const value = e.detail.value
    if (value === this.data.form.address) return
    this.setData({ 'form.address': value, coordinateMode: 'CLEAR', saveMessage: '' }); this.markInfoDraft()
  },
  onSupplementInput(e) {
    if (this.data.saving || this.data.coordSaving) return
    this.setData({ addressSupplement: e.detail.value, saveMessage: '' }); this.markInfoDraft()
  },

  // 地址和坐标由服务端同一次事务写入；旧接口不可回退串行写入。
  async onSave() {
    if (this.data.saving || this.data.coordSaving || !sameStationSession(this._stationSession)) return
    const proof = this._stationSession, origin = this.data.origin || {}, form = this.data.form
    const user = proof.app.globalData.userInfo || {}
    if (user.role !== 'STATION_MANAGER' || Number(origin.id) !== proof.stationId) { this.setData({ saveMessage: '当前身份无法修改此水站资料。' }); return }
    const name = String(form.name || '').trim(), base = String(form.address || '').trim(), extra = String(this.data.addressSupplement || '').trim()
    const address = extra ? base + ' ' + extra : base
    if (!name || !base || address.length > 200) { this.setData({ saveMessage: !name ? '请填写水站名称。' : !base ? '请填写或选择水站地址。' : '地址不能超过200个字。' }); return }
    const mode = this.data.coordinateMode, point = this.data.coord || {}
    if (!['SET','KEEP','CLEAR'].includes(mode) || mode === 'SET' && (!Number.isFinite(point.lat) || Math.abs(point.lat) > 90 || !Number.isFinite(point.lng) || Math.abs(point.lng) > 180)) { this.setData({ saveMessage: '地图位置不完整，请重新选择。' }); return }
    const phone = String(form.phone || '').trim(), oldPhone = String(origin.phone || '').trim()
    if (!phone && oldPhone) { this.setData({ saveMessage: '当前无法清空联系电话，请填写后保存。' }); return }
    const payload = { address, coordinateMode: mode, ...(name !== String(origin.name || '').trim() ? { name } : {}),
      ...(phone && phone !== oldPhone ? { phone } : {}), ...(mode === 'SET' ? { lat: point.lat, lng: point.lng } : {}) }
    this.setData({ saving: true, saveMessage: '正在保存…' })
    try {
      const res = await updateStationProfile(origin.id, payload)
      if (!sameStationSession(proof) || this._stationUnloaded) return
      const d = res && res.data
      const lat = d && d.lat, lng = d && d.lng
      const paired = (lat == null && lng == null) || (typeof lat === 'number' && Number.isFinite(lat) && typeof lng === 'number' && Number.isFinite(lng))
      const matchesPoint = mode === 'CLEAR' ? lat == null && lng == null : mode === 'SET'
        ? paired && lat != null && Math.abs(lat - point.lat) <= 0.00000051 && Math.abs(lng - point.lng) <= 0.00000051 : paired
      if (!res || res.code !== 0 || !d || Number(d.id) !== Number(origin.id) || d.address !== address || typeof d.name !== 'string'
        || !Object.prototype.hasOwnProperty.call(d, 'lat') || !Object.prototype.hasOwnProperty.call(d, 'lng') || !matchesPoint) throw Object.assign(new Error('保存结果尚未核实，请重新读取资料。'), { resultUnknown: true })
      this.onDiscardInfoDraft()
      this.setData({ origin: d, form: { name: d.name || '', phone: d.phone || '', address: d.address },
        coord: { lat: d.lat == null ? null : d.lat, lng: d.lng == null ? null : d.lng }, coordinateMode: 'KEEP', addressSupplement: '',
        infoDirty: false, infoDraftNotice: '', saveMessage: '水站资料已保存。' })
      if (typeof wx.disableAlertBeforeUnload === 'function') wx.disableAlertBeforeUnload()
      this.refreshModuleSetup()
    } catch (err) {
      if (!sameStationSession(proof) || this._stationUnloaded) return
      const unavailable = err && (Number(err.statusCode) === 404 || Number(err.statusCode) === 405)
      this.setData({ saveMessage: unavailable ? '当前服务尚不支持地址和位置一起保存，请联系维护人员更新服务。输入已保留。'
        : err && err.businessRejected ? (err.message || '保存未通过，请核对后重试。') : '暂未确认保存结果，输入已保留；请重新读取资料核对。' })
    } finally { if (sameStationSession(proof) && !this._stationUnloaded) this.setData({ saving: false }) }
  },

  onPickCoordinates() {
    const session = stationSession()
    if (this._stationUnloaded || this.data.coordSaving || this.data.saving || !sameStationSession(session)
      || Number(this.data.origin && this.data.origin.id) !== session.stationId) return
    const proof = { session, version: this._coordinateVersion = (this._coordinateVersion || 0) + 1, used: false }
    this._stationSession = session; this._coordinatePick = proof
    this.setData({ coordSaving: true, saveMessage: '' })
    wx.chooseLocation({
      success: res => {
        if (!this.coordinateCurrent(proof) || proof.used) return
        proof.used = true
        const address = String(res.address || '').trim(), name = String(res.name || '').trim()
        const picked = address && name && !address.includes(name) ? address + ' ' + name : address || name
        if (!picked || !Number.isFinite(res.latitude) || Math.abs(res.latitude) > 90 || !Number.isFinite(res.longitude) || Math.abs(res.longitude) > 180) {
          this._coordinatePick = null; this.setData({ coordSaving: false, saveMessage: '地图位置不完整，原输入已保留，请重新选择。' }); return
        }
        this.setData({ 'form.address': picked, coord: { lat: res.latitude, lng: res.longitude }, coordinateMode: 'SET', saveMessage: '位置已选择，请核对地址后保存。' })
        this.markInfoDraft()
        this._coordinatePick = null; this.setData({ coordSaving: false })
      },
      fail: err => {
        if (!this.coordinateCurrent(proof)) return
        const text = String(err && err.errMsg || '')
        this._coordinatePick = null; this.setData({ coordSaving: false, saveMessage: /cancel/i.test(text) ? '已取消选点，原输入已保留。' : '暂时无法选择位置，原输入已保留。' })
      },
      complete: () => { if (this.coordinateCurrent(proof)) { this._coordinatePick = null; this.setData({ coordSaving: false }) } }
    })
  }
})
