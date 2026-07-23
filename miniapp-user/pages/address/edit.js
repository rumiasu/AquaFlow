const { getAddressDetail, createAddress, updateAddress, getAddresses } = require('../../api/address')
const { validateForm, rules } = require('../../utils/validator')

Page({
  data: {
    loading: false,
    submitting: false,
    isEdit: false,
    addressId: '',
    tagSuggestions: [],
    labels: ['家', '公司', '父母家', '其他'],
    formData: {
      name: '',
      phone: '',
      detail: '',
      tag: '',
      label: '',
      isDefault: false
    }
  },

  onLoad(options) {
    this.loadTagSuggestions()
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
            detail: res.data.detail || '',
            tag: res.data.tag || '',
            label: res.data.label || '',
            isDefault: !!res.data.isDefault
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

  onTagSuggest(e) {
    const { tag } = e.currentTarget.dataset
    this.setData({ 'formData.tag': tag })
  },

  async loadTagSuggestions() {
    try {
      const res = await getAddresses()
      if (res.data && res.data.length) {
        // 从已有地址中提取不重复的 tag 作为快捷建议
        const tags = [...new Set(res.data.map(a => a.tag).filter(Boolean))]
        this.setData({ tagSuggestions: tags })
      }
    } catch (e) {
      // 静默失败
    }
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

  onChooseLocation() {
    // 先检查并请求位置权限
    wx.getSetting({
      success: (res) => {
        if (res.authSetting['scope.userLocation'] === false) {
          // 用户之前拒绝过，引导去设置页
          wx.showModal({
            title: '需要位置权限',
            content: '请在设置中开启位置权限，以便获取收货地址',
            confirmText: '去设置',
            success: (modalRes) => {
              if (modalRes.confirm) wx.openSetting()
            }
          })
          return
        }
        // 有权限或未决定，直接调用
        this._doChooseLocation()
      },
      fail: () => {
        this._doChooseLocation()
      }
    })
  },

  _doChooseLocation() {
    wx.chooseLocation({
      success: (res) => {
        if (res.address) {
          this.setData({ 'formData.detail': res.address + (res.name || '') })
        }
      },
      fail: (err) => {
        console.warn('chooseLocation fail:', err)
        const msg = (err.errMsg || '')
        if (msg.includes('auth') || msg.includes('deny')) {
          wx.showModal({
            title: '需要位置权限',
            content: '请在设置中开启位置权限，以便获取收货地址',
            confirmText: '去设置',
            success: (res) => {
              if (res.confirm) wx.openSetting()
            }
          })
        } else {
          wx.showToast({ title: '定位不可用，请手动输入', icon: 'none' })
        }
      }
    })
  },

  async onSubmit() {
    const { formData, isEdit, addressId } = this.data

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
    // isDefault: 前端用 boolean，后端要 Integer (0/1)
    const submitData = {
      ...formData,
      isDefault: formData.isDefault ? 1 : 0
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
      wx.showToast({ title: '保存失败', icon: 'none' })
    } finally {
      this.setData({ submitting: false })
    }
  }
})
