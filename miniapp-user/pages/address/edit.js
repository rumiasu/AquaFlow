const { getAddressDetail, createAddress, updateAddress, getAddresses } = require('../../api/address')
const { validateForm, rules } = require('../../utils/validator')
const { getCustomerId } = require('../../utils/token')
// 与首页/列表/下单页共用同一份地址解析逻辑（含直辖市修正），避免各自维护导致数据再次污染
const { parseRegion } = require('../../utils/address')

Page({
  data: {
    loading: false,
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

  onLoad(options) {
    if (options.id) {
      this.setData({
        isEdit: true,
        addressId: options.id
      })
      this.loadAddress(options.id)
    }
  },

  async loadAddress(id) {
    this.setData({ loading: true })
    try {
      const res = await getAddressDetail(id)
      if (res.data) {
        this.setData({
          formData: {
            name: res.data.name || '',
            phone: res.data.phone || '',
            province: res.data.province || '',
            city: res.data.city || '',
            district: res.data.district || '',
            detail: res.data.detail || '',
            label: res.data.label || '',
            isDefault: !!res.data.isDefault,
            lat: res.data.lat || null,
            lng: res.data.lng || null,
            // floor 在后端是可空 int；空值回填成 '' 才能在输入框里正常编辑
            floor: (res.data.floor === null || res.data.floor === undefined) ? '' : String(res.data.floor),
            // hasElevator 三态：null 不能被 `|| null` 之类顺手写成 0
            hasElevator: (res.data.hasElevator === null || res.data.hasElevator === undefined)
              ? null : res.data.hasElevator
          }
        })
      }
    } catch (error) {
      console.error('Load address error:', error)
      wx.showToast({ title: '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  onInputChange(e) {
    const { field } = e.currentTarget.dataset
    this.setData({ [`formData.${field}`]: e.detail.value })
  },

  onLabelSelect(e) {
    const { label } = e.currentTarget.dataset
    this.setData({ 'formData.label': label })
  },

  onDefaultChange(e) {
    this.setData({ 'formData.isDefault': e.detail.value })
  },

  /**
   * 有无电梯：三态选择（1 有 / 0 无 / null 未确认）。
   *
   * ⚠️ 不要把「不确定」写成 0 —— 后端据此决定收不收楼层费，
   * 二者混同的后果是向客户乱收钱（见 docs/design/17 §4.4）。
   */
  onElevatorSelect(e) {
    const raw = e.currentTarget.dataset.value
    this.setData({ 'formData.hasElevator': raw === '' ? null : Number(raw) })
  },

  onToggleDefault() {
    this.setData({ 'formData.isDefault': !this.data.formData.isDefault })
  },

  onAutoLocate() {
    wx.getSetting({
      success: (res) => {
        if (res.authSetting['scope.userLocation'] === false) {
          wx.showModal({
            title: '需要位置权限',
            content: '请在设置中开启位置权限，以便自动识别收货地址',
            confirmText: '去设置',
            success: (modalRes) => {
              if (modalRes.confirm) wx.openSetting()
            }
          })
          return
        }
        this._doAutoLocate()
      },
      fail: () => { this._doAutoLocate() }
    })
  },

  _doAutoLocate() {
    wx.chooseLocation({
      success: (res) => {
        if (res.address) {
          const fullAddr = res.address + (res.name || '')
          const region = parseRegion(fullAddr)
          this.setData({
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
        console.warn('chooseLocation fail:', err)
        const msg = (err.errMsg || '')
        if (msg.includes('auth') || msg.includes('deny')) {
          wx.showModal({
            title: '需要位置权限',
            content: '请在设置中开启位置权限，以便自动识别收货地址',
            confirmText: '去设置',
            success: (res) => { if (res.confirm) wx.openSetting() }
          })
        } else {
          wx.showToast({ title: '定位不可用，请手动输入', icon: 'none' })
        }
      }
    })
  },

  async onSubmit() {
    const { formData, isEdit, addressId } = this.data

    if (!formData.province && !formData.city && !formData.district) {
      if (!formData.detail) {
        wx.showToast({ title: '请输入详细地址', icon: 'none' })
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
      return
    }

    this.setData({ submitting: true })
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
      wx.showToast({ title: isEdit ? '更新成功' : '添加成功', icon: 'success' })
      setTimeout(() => wx.navigateBack(), 1500)
    } catch (error) {
      console.error('Save address error:', error)
      const msg = error.message || '保存失败'
      if (msg.includes('权限不足') || msg.includes('请用客户账号登录')) {
        wx.showModal({
          title: '登录身份错误',
          content: '当前是员工账号，请切换到客户账号登录后再试',
          showCancel: false,
          confirmText: '去登录',
          success: () => wx.navigateTo({ url: '/pages/login/index' })
        })
      } else {
        wx.showToast({ title: msg, icon: 'none' })
      }
    } finally {
      this.setData({ submitting: false })
    }
  }
})
