const { getCompanyInfo, updateCompanyInfo } = require('../../api/company')
const { formatTime } = require('../../utils/format')

Page({
  data: {
    loading: true,
    saving: false,
    editing: false,
    info: null,
    form: {
      companyName: '',
      contactPerson: '',
      contactPhone: '',
      paymentMethod: '',
      dueDays: ''
    }
  },

  onShow() {
    this.loadInfo()
  },

  async loadInfo() {
    this.setData({ loading: true })
    try {
      const res = await getCompanyInfo()
      const info = res.data || null
      if (info) {
        info.updateTimeText = formatTime(info.updateTime)
        this.setData({
          info,
          form: {
            companyName: info.companyName || '',
            contactPerson: info.contactPerson || '',
            contactPhone: info.contactPhone || '',
            paymentMethod: info.paymentMethod || '',
            dueDays: info.dueDays ? String(info.dueDays) : ''
          }
        })
      }
    } catch (err) {
      console.warn('[Company] 加载企业资料失败:', err.message)
      this.setData({ info: null })
    } finally {
      this.setData({ loading: false })
    }
  },

  onStartEdit() {
    this.setData({ editing: true })
  },

  onInput(e) {
    const field = e.currentTarget.dataset.field
    this.setData({ [`form.${field}`]: e.detail.value })
  },

  onCancelEdit() {
    const { info } = this.data
    this.setData({
      editing: false,
      form: {
        companyName: info ? info.companyName || '' : '',
        contactPerson: info ? info.contactPerson || '' : '',
        contactPhone: info ? info.contactPhone || '' : '',
        paymentMethod: info ? info.paymentMethod || '' : '',
        dueDays: info && info.dueDays ? String(info.dueDays) : ''
      }
    })
  },

  async onSave() {
    const { form } = this.data
    if (!form.companyName.trim()) {
      wx.showToast({ title: '请填写公司名称', icon: 'none' })
      return
    }
    this.setData({ saving: true })
    try {
      await updateCompanyInfo({
        companyName: form.companyName.trim(),
        contactPerson: form.contactPerson.trim(),
        contactPhone: form.contactPhone.trim(),
        paymentMethod: form.paymentMethod.trim(),
        dueDays: form.dueDays ? parseInt(form.dueDays) : null
      })
      wx.showToast({ title: '保存成功', icon: 'success' })
      this.setData({ editing: false })
      this.loadInfo()
    } catch (error) {
      wx.showToast({ title: error.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ saving: false })
    }
  }
})