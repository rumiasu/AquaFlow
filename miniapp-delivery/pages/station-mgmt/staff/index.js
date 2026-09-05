const { getStaffList } = require('../../../api/delivery')
const { createStaff, detachStaff } = require('../../../api/station-mgmt')

Page({
  data: {
    list: [],
    showModal: false,
    formName: '',
    formPhone: ''
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this.loadData()
  },

  async loadData() {
    try {
      const app = getApp()
      const stationId = app.globalData.userInfo?.stationId
      if (!stationId) return
      const res = await getStaffList(stationId)
      this.setData({ list: res.data || [] })
    } catch (err) {
      console.error(err)
    }
  },

  onShowAdd() {
    this.setData({ showModal: true, formName: '', formPhone: '' })
  },

  async onSubmitAdd() {
    const { formName, formPhone } = this.data
    if (!formName) return wx.showToast({ title: '请输入姓名', icon: 'none' })
    if (!formPhone) return wx.showToast({ title: '请输入电话', icon: 'none' })

    wx.showLoading({ title: '添加中...' })
    try {
      await createStaff({ name: formName, phone: formPhone, role: 'DELIVERY' })
      wx.hideLoading()
      wx.showToast({ title: '添加成功', icon: 'success' })
      this.setData({ showModal: false })
      this.loadData()
    } catch (err) {
      wx.hideLoading()
      wx.showToast({ title: err.message || '添加失败', icon: 'none' })
    }
  },

  onDetach(e) {
    const { id, name } = e.currentTarget.dataset
    wx.showModal({
      title: '解除所属',
      content: `确定解除配送员「${name}」的所属关系？`,
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' })
          try {
            await detachStaff(id)
            wx.hideLoading()
            wx.showToast({ title: '已解除', icon: 'success' })
            this.loadData()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '操作失败', icon: 'none' })
          }
        }
      }
    })
  },

  onCloseModal() { this.setData({ showModal: false }) },
  stopPropagation() {}
})
