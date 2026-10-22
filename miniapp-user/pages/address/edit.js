const { getAddressDetail, createAddress, updateAddress } = require('../../api/address')
const { validateForm, rules } = require('../../utils/validator')
const { getCustomerId, captureSession, isCurrentSession } = require('../../utils/token')
// 与首页/列表/下单页共用同一份地址解析逻辑（含直辖市修正），避免各自维护导致数据再次污染
const { parseRegion } = require('../../utils/address')

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
    labels: ['家', '公司', '父母家', '其他'],
    formData: {
      name: '',
      phone: '',
      province: '',
      city: '',
      district: '',
      detail: '',
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
    if (options.id) {
      this.setData({
        isEdit: true,
        formReady: false,
        addressId: options.id
      })
      this.loadAddress(options.id)
    }
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
    if (this.data.submitting) { patch.saveUnknown = true; patch.saveMessage = '登录身份已变化，原保存结果待核实。输入已保留，请使用原账号核对。' }
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
        })
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
    this.setData({ [`formData.${field}`]: e.detail.value })
  },

  onLabelSelect(e) {
    if (!this.markInput()) return
    const { label } = e.currentTarget.dataset
    this.setData({ 'formData.label': label })
  },

  onDefaultChange(e) {
    if (!this.markInput()) return
    this.setData({ 'formData.isDefault': e.detail.value })
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
    this.setData({ 'formData.hasElevator': raw === '' ? null : Number(raw) })
  },

  onToggleDefault() {
    if (!this.markInput()) return
    this.setData({ 'formData.isDefault': !this.data.formData.isDefault })
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
    wx.showModal({
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
        if (res.address) {
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
          })
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
    if (this.data.submitting || this.data.saveSucceeded || this.data.saveUnknown || this.data.loading || this.data.loadError || this.data.accessDenied || !this.data.formReady || (this.data.isEdit && !this.data.loaded)) return
    const { formData, isEdit, addressId } = this.data

    if (!formData.province && !formData.city && !formData.district) {
      if (!formData.detail) {
        this.setData({ saveMessage: '请输入详细地址。' })
        return
      }
    }

    const validation = validateForm(formData, {
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
      ...formData,
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
        await createAddress(submitData)
      }
      if (this._disposed) return
      this.setData({ saveSucceeded: true, saveMessage: '地址已保存，即将返回地址列表。' })
      wx.showToast({ title: '地址已保存', icon: 'success' })
      this._backTimer = setTimeout(() => { if (!this._disposed) this.onBackToList() }, 1000)
    } catch (error) {
      if (this._disposed || !this.ensurePageSession()) return
      console.error('Save address error:', error)
      const denied = this.isAccessError(error)
      const rejected = error && (error.businessRejected === true || String(error.code || error.statusCode) === '401' || String(error.code || error.statusCode) === '403')
      this.setData({ accessDenied: denied, saveUnknown: !rejected, saveMessage: denied
        ? '当前账号无权保存此地址，输入已保留，请使用客户账号登录。'
        : rejected ? '地址未保存，输入已保留，请核对信息后重试。'
          : '保存结果尚未确认，输入已保留。请先返回地址列表核对，暂勿重复提交。' })
      if (denied) {
        wx.showModal({
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
