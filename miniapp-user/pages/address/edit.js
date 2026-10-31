const { createDraftGuard } = require('../../utils/draft-guard')
const { showBusinessModal } = require('../../utils/business-dialog')
const { getAddressDetail, getAddresses, createAddress, updateAddress } = require('../../api/address')
const { validateForm, rules } = require('../../utils/validator')
const { getCustomerId, captureSession, isCurrentSession } = require('../../utils/token')
// 与首页/列表/下单页共用同一份地址解析逻辑（含直辖市修正），避免各自维护导致数据再次污染
const { parseRegion } = require('../../utils/address')

// One authorized list read before retrying an unacknowledged create. It confirms that the desired
// address exists, not which request created it. No broad customer enumeration or generated ID.
function sameAddress(row, payload) {
  if (!row || !Number.isSafeInteger(Number(row.id)) || Number(row.id) <= 0) return false
  const text = value => value == null ? '' : String(value)
  const number = value => value == null || value === '' ? null : Number(value)
  return ['name','phone','province','city','district','detail','label'].every(key => text(row[key]) === text(payload[key]))
    && ['lat','lng','floor','hasElevator'].every(key => number(row[key]) === number(payload[key]))
    && Number(row.isDefault || 0) === Number(payload.isDefault || 0)
}

Page({
  data: {
    loading: false,
    loaded: false,
    formReady: true,
    loadError: '',
    accessDenied: false,
    sessionChanged: false,
    saveMessage: '',
    saveUnknown: false,
    saveSucceeded: false,
    locationMessage: '',
    locating: false,
    submitting: false,
    isEdit: false,
    addressId: '',
    labels: ['家', '公司', '父母家', '其他'], draftState: 'empty', draftMessage: '',
    formData: {
      name: '',
      phone: '',
      province: '',
      city: '',
      district: '',
      detail: '',
      supplement: '',
      label: '',
      isDefault: false,
      lat: null,
      lng: null,
      // 楼层（可留空）与有无电梯（1 有 / 0 无 / null 未确认）。见 migration_v34。
      floor: '',
      hasElevator: null
    }
  },

  onLoad(options = {}) {
    if (typeof wx.setNavigationBarTitle === 'function') wx.setNavigationBarTitle({ title: options.id ? '编辑地址' : '新增地址' })
    this._savedForm = JSON.stringify(this.data.formData)
    if (options.id) {
      this.setData({
        isEdit: true,
        formReady: false,
        addressId: options.id
      })
      this.initDraft()
      this.loadAddress(options.id)
    } else this.initDraft()
  },

  onShow() {
    this._disposed = false
    if (!this.ensurePageSession()) return
    if (this.data.isEdit && !this.data.loaded && !this.data.loading) this.loadAddress(this.data.addressId)
  },
  onHide() { this._readId = (this._readId || 0) + 1; this.setData({ loading: false }) },
  onUnload() {
    this._disposed = true
    this._readId = (this._readId || 0) + 1
    this._invalidateLocation()
    if (this._backTimer) clearTimeout(this._backTimer)
  },
  onRetry() { return this.loadAddress(this.data.addressId) },
  onLogin() { wx.navigateTo({ url: '/pages/login/index' }) },
  onBackToList() {
    wx.navigateBack({ fail: () => wx.redirectTo({ url: '/pages/address/list' }) })
  },
  isAccessError(error) {
    const code = error && (error.code || error.statusCode)
    return String(code) === '401' || String(code) === '403' || /登录|权限|无权|客户账号|客户身份|HTTP\s*(401|403)|UNAUTHORIZED|FORBIDDEN/i.test(String(error && error.message || ''))
  },
  draftScope() {
    const session = captureSession()
    return session.loggedIn && session.customerId ? { app: 'miniapp-user', actor: session.customerId, station: 'personal', route: 'pages/address/edit', entity: this.data.isEdit ? 'address:' + this.data.addressId : 'new', generation: session.epoch } : {}
  },
  validDraft(d) {
    const fields = ['name', 'phone', 'province', 'city', 'district', 'detail', 'label', 'floor']
    return !!d && fields.every(k => typeof d[k] === 'string') && typeof d.isDefault === 'boolean'
      && (d.lat === null || typeof d.lat === 'number') && (d.lng === null || typeof d.lng === 'number')
      && (d.hasElevator === null || d.hasElevator === 0 || d.hasElevator === 1)
  },
  initDraft() {
    this._draftGuard = createDraftGuard(() => this.draftScope()); this._draftToken = this._draftGuard.begin()
    this._draftOwned = false; this._saveCompleted = false; this.readDraftOffer()
  },
  readDraftOffer() {
    const result = this._draftGuard.read(this._draftToken), valid = this.validDraft(result.draft)
    this._draftOffered = result.state === 'saved' && valid
    this.setData({ draftState: this._draftOffered ? 'saved' : result.state === 'saved' ? 'unknown' : result.state,
      draftMessage: result.state === 'unknown' || result.state === 'saved' && !valid ? '草稿暂无法读取，请重试。' : '' })
  },
  onRetryDraft() { if (this._saveCompleted && this._draftOwned) { if (this.clearSavedDraft()) this.setData({ saveMessage: '地址已保存。' }); return } this.readDraftOffer(); if (!this._draftOffered && this.data.draftState === 'empty' && this._hasUserInput) this.saveEditableDraft() },
  saveEditableDraft() {
    if (!this._draftGuard || !this._draftToken || this._draftOffered || this.data.draftState === 'unknown') {
      this.setData({ draftMessage: '草稿尚未确认保存，请先处理已有草稿或重试。' }); return
    }
    this._draftOwned = true; this._draftSnapshot = JSON.stringify(this.data.formData)
    const result = this._draftGuard.save(this._draftToken, this.data.formData)
    this.setData({ draftState: result.state === 'saved' ? 'active' : result.state,
      draftMessage: result.state === 'saved' ? '' : '草稿尚未确认保存，请稍后重试。' })
  },
  onRestoreDraft() {
    const token = this._draftToken, revision = this._inputRevision || 0, result = this._draftGuard.read(token)
    if (result.state !== 'saved' || !this.validDraft(result.draft)) { this.readDraftOffer(); return }
    const restore = () => {
      const current = this._draftGuard.read(token)
      if (this._disposed || revision !== (this._inputRevision || 0) || current.state !== 'saved' || JSON.stringify(current.draft) !== JSON.stringify(result.draft) || this.data.submitting || !this.data.formReady || this.data.accessDenied) return
      this._draftOffered = false; this._draftOwned = true; this._draftSnapshot = JSON.stringify(result.draft); this._inputRevision = revision + 1; this._hasUserInput = true
      this.setData({ formData: result.draft, draftState: 'active', draftMessage: '' }, () => this.updateLeaveWarning())
    }
    if (this._hasUserInput) return showBusinessModal({ title: '恢复之前的地址？', content: '当前未保存的输入会被替换。', confirmText: '恢复', cancelText: '继续填写', success: res => { if (res.confirm) restore() } })
    restore()
  },
  onDiscardSavedDraft() {
    const token = this._draftToken, revision = this._inputRevision || 0; const offered = this._draftGuard.read(token)
    showBusinessModal({ title: '放弃之前的地址？', content: '确认后将清除已保存的草稿。', confirmText: '放弃', cancelText: '保留', success: res => {
      if (!res.confirm || this._disposed || revision !== (this._inputRevision || 0)) return
      const current = this._draftGuard.read(token)
      if (offered.state !== 'saved' || current.state !== 'saved' || JSON.stringify(current.draft) !== JSON.stringify(offered.draft)) { this.setData({ draftState: 'unknown', draftMessage: '草稿已有变化或暂无法读取，请重试。' }); return }
      const result = this._draftGuard.clear(token)
      if (result.state !== 'empty') { this.setData({ draftState: result.state, draftMessage: '草稿尚未确认清除，请重试。' }); return }
      this._draftOffered = false; this._draftOwned = false; this.setData({ draftState: 'empty', draftMessage: '' }); this.updateLeaveWarning()
    } })
  },
  clearSavedDraft() {
    if (!this._draftGuard || !this._draftOwned) return true
    const current = this._draftGuard.read(this._draftToken)
    if (current.state === 'empty') { this._draftOwned = false; this.setData({ draftState: 'empty', draftMessage: '' }); return true }
    if (current.state !== 'saved' || JSON.stringify(current.draft) !== this._draftSnapshot) { this.setData({ draftState: 'unknown', draftMessage: '草稿已有变化或暂无法读取，请重试。' }); return false }
    const result = this._draftGuard.clear(this._draftToken)
    if (result.state !== 'empty') { this.setData({ draftState: result.state, draftMessage: '地址已保存，但草稿尚未确认清除，请重试。' }); return false }
    this._draftOwned = false; this.setData({ draftState: 'empty', draftMessage: '' }); return true
  },
  updateLeaveWarning() {
    const dirty = this._savedForm != null && this._savedForm !== JSON.stringify(this.data.formData) && !this.data.saveSucceeded
    if (dirty || (this._draftOwned && !this._saveCompleted)) this.saveEditableDraft()
    if (dirty && wx.enableAlertBeforeUnload) wx.enableAlertBeforeUnload({ message: '地址尚未保存，确定离开吗？' })
    else if (!dirty && wx.disableAlertBeforeUnload) wx.disableAlertBeforeUnload()
  },
  markInput() {
    if (this.data.submitting || this.data.saveSucceeded || this.data.accessDenied || !this.data.formReady) return false
    this._inputRevision = (this._inputRevision || 0) + 1
    this._hasUserInput = true
    return true
  },
  ensurePageSession() {
    if (!this._pageSession) { this._pageSession = captureSession(); return true }
    if (isCurrentSession(this._pageSession)) return true
    this._readId = (this._readId || 0) + 1
    this._invalidateLocation()
    const patch = { loading: false, locating: false, accessDenied: true, sessionChanged: true, loadError: '登录身份已变化，请退出本页后重新进入。' }
    if (this.data.submitting) { patch.saveUnknown = false; patch.saveMessage = '登录身份已变化，输入已保留，请重新登录后保存。' }
    this.setData(patch)
    return false
  },
  async loadAddress(id) {
    if (!this.ensurePageSession()) return
    const readId = this._readId = (this._readId || 0) + 1
    const inputRevision = this._inputRevision || 0
    this.setData({ loading: true, loadError: '' })
    try {
      const res = await getAddressDetail(id)
      if (this._disposed || readId !== this._readId || !this.ensurePageSession()) return
      if (!res || !res.data || typeof res.data !== 'object' || Array.isArray(res.data) || String(res.data.id) !== String(id)) throw new Error('地址资料不完整')
      this.setData({ loaded: true, formReady: true, accessDenied: false })
      if (!this._hasUserInput && inputRevision === (this._inputRevision || 0)) {
        this.setData({
          loaded: true, formReady: true, accessDenied: false,
          formData: {
            name: res.data.name || '',
            phone: res.data.phone || '',
            province: res.data.province || '',
            city: res.data.city || '',
            district: res.data.district || '',
            detail: res.data.detail || '',
            supplement: '',
            label: res.data.label || '',
            isDefault: !!res.data.isDefault,
            lat: res.data.lat == null ? null : res.data.lat,
            lng: res.data.lng == null ? null : res.data.lng,
            // floor 在后端是可空 int；空值回填成 '' 才能在输入框里正常编辑
            floor: (res.data.floor === null || res.data.floor === undefined) ? '' : String(res.data.floor),
            // hasElevator 三态：null 不能被 `|| null` 之类顺手写成 0
            hasElevator: (res.data.hasElevator === null || res.data.hasElevator === undefined)
              ? null : res.data.hasElevator
          }
        }, () => { this._savedForm = JSON.stringify(this.data.formData) })
      }
    } catch (error) {
      if (this._disposed || readId !== this._readId || !this.ensurePageSession()) return
      console.error('Load address error:', error)
      const denied = this.isAccessError(error)
      this.setData({ accessDenied: denied, loadError: denied
        ? '当前账号无法查看此地址，请使用所属客户账号登录。'
        : '原地址未能读取，请重试。读取成功前无法编辑或保存，原地址不会被空表单覆盖。' })
    } finally {
      if (!this._disposed && readId === this._readId) this.setData({ loading: false })
    }
  },

  onInputChange(e) {
    if (!this.markInput()) return
    const { field } = e.currentTarget.dataset
    const value = e.detail.value
    const mainAddressChanged = ['province', 'city', 'district', 'detail'].includes(field) && value !== this.data.formData[field]
    const patch = { [`formData.${field}`]: value }
    if (mainAddressChanged) {
      this._invalidateLocation()
      Object.assign(patch, { 'formData.lat': null, 'formData.lng': null, locating: false,
        locationMessage: this.data.formData.lat != null || this.data.formData.lng != null
          ? '主要地址已修改，原地图位置已清除；可重新选择地图位置。' : '可选择地图位置，也可继续手动填写。' })
    }
    this.setData(patch, () => this.updateLeaveWarning())
  },

  onLabelSelect(e) {
    if (!this.markInput()) return
    const { label } = e.currentTarget.dataset
    this.setData({ 'formData.label': label }, () => this.updateLeaveWarning())
  },

  onDefaultChange(e) {
    if (!this.markInput()) return
    this.setData({ 'formData.isDefault': e.detail.value }, () => this.updateLeaveWarning())
  },

  /**
   * 有无电梯：三态选择（1 有 / 0 无 / null 未确认）。
   *
   * ⚠️ 不要把「不确定」写成 0 —— 后端据此决定收不收楼层费，
   * 二者混同的后果是向客户乱收钱（见 docs/design/17 §4.4）。
   */
  onElevatorSelect(e) {
    if (!this.markInput()) return
    const raw = e.currentTarget.dataset.value
    this.setData({ 'formData.hasElevator': raw === '' ? null : Number(raw) }, () => this.updateLeaveWarning())
  },

  onToggleDefault() {
    if (!this.markInput()) return
    this.setData({ 'formData.isDefault': !this.data.formData.isDefault }, () => this.updateLeaveWarning())
  },

  onAutoLocate() {
    if (!this.ensurePageSession()) return
    if (this.data.submitting || this.data.locating || !this.data.formReady || this.data.saveSucceeded || this.data.accessDenied) return
    const operation = { id: (this._locationSequence || 0) + 1, session: captureSession(), phase: 'settings' }
    this._locationSequence = operation.id
    this._locationOperation = operation
    this.setData({ locating: true, locationMessage: '正在打开地图，可选择收货位置…' })
    wx.getSetting({
      success: (res) => {
        if (!this._isCurrentLocation(operation) || operation.phase !== 'settings') return
        if (res.authSetting['scope.userLocation'] === false) {
          this.setData({ locating: false, locationMessage: '位置权限未开启，可继续手动填写地址。' })
          this._showLocationPermission(operation)
          return
        }
        this._doAutoLocate(operation)
      },
      fail: () => {
        if (!this._isCurrentLocation(operation) || operation.phase !== 'settings') return
        this._doAutoLocate(operation)
      }
    })
  },

  _invalidateLocation() {
    this._locationSequence = (this._locationSequence || 0) + 1
    this._locationOperation = null
  },
  _isCurrentLocation(operation) {
    if (!operation || this._disposed || this._locationOperation !== operation || operation.id !== this._locationSequence) return false
    if (isCurrentSession(operation.session)) return true
    this.ensurePageSession()
    return false
  },
  _showLocationPermission(operation) {
    if (!this._isCurrentLocation(operation)) return
    operation.phase = 'permission'
    showBusinessModal({ confirmColor: '#1264D6', cancelColor: '#637083',
      title: '需要位置权限',
      content: '请在设置中开启位置权限，也可继续手动填写地址。',
      confirmText: '去设置',
      success: (res) => {
        if (!this._isCurrentLocation(operation) || operation.phase !== 'permission') return
        operation.phase = 'finished'
        if (res.confirm) wx.openSetting()
      }
    })
  },
  _doAutoLocate(operation) {
    if (!this._isCurrentLocation(operation) || operation.phase !== 'settings') return
    operation.phase = 'map'
    wx.chooseLocation({
      success: (res) => {
        if (!this._isCurrentLocation(operation) || operation.phase !== 'map') return
        operation.phase = 'finished'
        if (res.address && typeof res.latitude === 'number' && Number.isFinite(res.latitude) && Math.abs(res.latitude) <= 90
          && typeof res.longitude === 'number' && Number.isFinite(res.longitude) && Math.abs(res.longitude) <= 180) {
          this._inputRevision = (this._inputRevision || 0) + 1
          this._hasUserInput = true
          const fullAddr = res.address + (res.name || '')
          const region = parseRegion(fullAddr)
          this.setData({
            locationMessage: '位置已填入，请核对门牌与联系人后保存。',
            'formData.province': region.province,
            'formData.city': region.city,
            'formData.district': region.district,
            'formData.detail': region.detail,
            'formData.lat': res.latitude,
            'formData.lng': res.longitude
          }, () => this.updateLeaveWarning())
        } else {
          this.setData({ locationMessage: '位置资料不完整，已填内容保留，请重新选择或手动填写。' })
        }
      },
      fail: (err) => {
        if (!this._isCurrentLocation(operation) || operation.phase !== 'map') return
        operation.phase = 'finished'
        console.warn('chooseLocation fail:', err)
        const msg = (err.errMsg || '')
        if (msg.includes('cancel')) {
          this.setData({ locationMessage: '已取消选择位置，已填内容保留，可手动填写。' })
        } else if (msg.includes('auth') || msg.includes('deny')) {
          this.setData({ locationMessage: '位置权限未开启，可继续手动填写地址。' })
          this._showLocationPermission(operation)
        } else {
          this.setData({ locationMessage: '暂时无法获取位置，已填内容保留，请手动填写。' })
          wx.showToast({ title: '定位不可用，请手动输入', icon: 'none' })
        }
      },
      complete: () => { if (this._isCurrentLocation(operation)) this.setData({ locating: false }) }
    })
  },

  async onSubmit() {
    if (!this.ensurePageSession()) return
    if (this.data.submitting || this.data.saveSucceeded || this.data.loading || this.data.loadError || this.data.accessDenied || !this.data.formReady || (this.data.isEdit && !this.data.loaded)) return
    const { formData, isEdit, addressId } = this.data

    if (!formData.province && !formData.city && !formData.district) {
      if (!formData.detail) {
        this.setData({ saveMessage: '请输入详细地址。' })
        return
      }
    }

    const { supplement, ...savedForm } = formData
    const extra = String(supplement || '').trim()
    const detail = extra ? String(formData.detail || '') + ' ' + extra : formData.detail
    const validation = validateForm({ ...formData, detail }, {
      name: rules.name,
      phone: rules.phone,
      detail: rules.address
    })

    if (!validation.valid) {
      const firstError = Object.values(validation.errors)[0]
      wx.showToast({ title: firstError, icon: 'none' })
      this.setData({ saveMessage: firstError })
      return
    }

    const locationMessage = this.data.locating ? '选点已暂停，当前地址内容保留。' : this.data.locationMessage
    this._invalidateLocation()
    this.setData({ submitting: true, locating: false, locationMessage, saveMessage: '正在保存地址…' })
    const customerId = getCustomerId()
    const submitData = {
      ...savedForm, detail,
      // 楼层：输入框给的是字符串，留空要转成 null（后端列是可空 int，
      // 传 '' 会被 Jackson 当成非法数字直接 400）
      floor: (formData.floor === '' || formData.floor === null || formData.floor === undefined)
        ? null : Number(formData.floor),
      // 电梯三态原样透传：null 表示未确认，不能顺手写成 0
      hasElevator: (formData.hasElevator === null || formData.hasElevator === undefined)
        ? null : formData.hasElevator,
      isDefault: formData.isDefault ? 1 : 0,
      ...(customerId ? { customerId } : {})
    }
    try {
      if (isEdit) {
        await updateAddress(addressId, submitData)
      } else {
        let present = false
        if (this._lastCreatePayload === JSON.stringify(submitData)) {
          try {
            const res = await getAddresses()
            if (this._disposed || !this.ensurePageSession()) return
            present = !!(res && Array.isArray(res.data) && res.data.some(row => sameAddress(row, submitData)))
          } catch (readError) {
            if (this._disposed || !this.ensurePageSession()) return
            if (this.isAccessError(readError)) throw readError
            // A failed lookup is not success. Only this explicit user retry may submit again.
          }
        }
        if (!present) await createAddress(submitData)
      }
      if (this._disposed || !this.ensurePageSession()) return
      this._lastCreatePayload = null
      this._saveCompleted = true
      const cleared = this.clearSavedDraft()
      this.setData({ saveSucceeded: true, saveMessage: cleared ? '地址已保存，即将返回地址列表。' : '地址已保存，草稿尚未确认清除。' }, () => this.updateLeaveWarning())
      wx.showToast({ title: '地址已保存', icon: 'success' })
      if (cleared) this._backTimer = setTimeout(() => { if (!this._disposed) this.onBackToList() }, 1000)
    } catch (error) {
      if (this._disposed || !this.ensurePageSession()) return
      console.error('Save address error:', error)
      const denied = this.isAccessError(error)
      const rejected = error && (error.businessRejected === true || String(error.code || error.statusCode) === '401' || String(error.code || error.statusCode) === '403')
      if (!isEdit && !rejected && !denied) this._lastCreatePayload = JSON.stringify(submitData)
      this.setData({ accessDenied: denied, saveUnknown: false, saveMessage: denied
        ? '当前账号无权保存此地址，输入已保留，请使用客户账号登录。'
        : rejected ? '地址未保存，输入已保留，请核对信息后重试。'
          : '暂未收到保存结果，输入已保留，可直接重试。' })
      if (denied) {
        showBusinessModal({ confirmColor: '#1264D6', cancelColor: '#637083',
          title: '暂时无法保存',
          content: '当前账号无法保存此地址，请使用所属客户账号重新登录。已填内容保留。',
          showCancel: false,
          confirmText: '去登录',
          success: () => wx.navigateTo({ url: '/pages/login/index' })
        })
      }
    } finally {
      if (!this._disposed) this.setData({ submitting: false })
    }
  }
})
