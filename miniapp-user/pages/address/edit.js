const { getAddressDetail, createAddress, updateAddress, getAddresses } = require('../../api/address')
const { validateForm, rules } = require('../../utils/validator')
const { getCustomerId } = require('../../utils/token')

function parseRegion(addressStr) {
  if (!addressStr) return { province: '', city: '', district: '', detail: '' }
  let province = '', city = '', district = '', detail = addressStr
  const m4 = addressStr.match(/^(北京市|天津市|上海市|重庆市)(.*?)(省|市|区|县|$)/)
  if (m4) {
    province = m4[1]; city = m4[1]; district = m4[2] || ''
    detail = addressStr.substring(m4[0].length)
    return { province, city, district, detail }
  }
  const m1 = addressStr.match(/^(.{2,8}省)(.{2,10}?市)(.{2,10}?(?:区|县))(.*)/)
  if (m1) {
    return { province: m1[1], city: m1[2], district: m1[3], detail: m1[4] }
  }
  const m2 = addressStr.match(/^(.{2,8}省)(.{2,10}?市)(.*)/)
  if (m2) {
    return { province: m2[1], city: m2[2], district: '', detail: m2[3] }
  }
  const m3 = addressStr.match(/^(.{2,10}?市)(.{2,10}?(?:区|县))(.*)/)
  if (m3) {
    return { province: '', city: m3[1], district: m3[2], detail: m3[3] }
  }
  const m5 = addressStr.match(/^(.{2,10}?市)(.*)/)
  if (m5) {
    return { province: '', city: m5[1], district: '', detail: m5[2] }
  }
  return { province: '', city: '', district: '', detail: addressStr }
}

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
      lng: null
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
            lng: res.data.lng || null
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
          success: () => wx.navigateTo({ url: '/pages/login/login' })
        })
      } else {
        wx.showToast({ title: msg, icon: 'none' })
      }
    } finally {
      this.setData({ submitting: false })
    }
  }
})
