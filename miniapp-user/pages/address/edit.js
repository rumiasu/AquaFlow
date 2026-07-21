const { getAddressDetail, createAddress, updateAddress } = require('../../api/address')
const { validateForm, rules } = require('../../utils/validator')
const { ADDRESS_TAGS } = require('../../config/constant')

Page({
  data: {
    loading: false,
    submitting: false,
    isEdit: false,
    addressId: '',
    tags: ADDRESS_TAGS,
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
            isDefault: res.data.isDefault || false
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

  onTagSelect(e) {
    const { tag } = e.currentTarget.dataset
    this.setData({ 'formData.tag': tag })
  },

  onLabelSelect(e) {
    const { label } = e.currentTarget.dataset
    this.setData({ 'formData.label': label })
  },

  onDefaultChange(e) {
    this.setData({ 'formData.isDefault': e.detail.value })
  },

  onChooseLocation() {
    wx.chooseLocation({
      success: (res) => {
        if (res.address) {
          this.setData({ 'formData.detail': res.address + (res.name || '') })
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
    try {
      if (isEdit) {
        await updateAddress(addressId, formData)
      } else {
        await createAddress(formData)
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
