const { getAllBarrelRecords, updateBarrelRecordStatus } = require('../../../api/station-mgmt')

Page({
  data: { list: [] },

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
      const res = await getAllBarrelRecords()
      this.setData({ list: res.data || [] })
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    }
  },

  onApprove(e) {
    const id = e.currentTarget.dataset.id
    wx.showModal({
      title: '确认收到空桶',
      content: '确定客户已退回空桶？',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' })
          try {
            await updateBarrelRecordStatus(id, 2)
            wx.hideLoading()
            wx.showToast({ title: '已确认', icon: 'success' })
            this.loadData()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '操作失败', icon: 'none' })
          }
        }
      }
    })
  },

  onReject(e) {
    const id = e.currentTarget.dataset.id
    wx.showModal({
      title: '驳回退桶申请',
      content: '确定驳回该申请？',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' })
          try {
            await updateBarrelRecordStatus(id, 4)
            wx.hideLoading()
            wx.showToast({ title: '已驳回', icon: 'success' })
            this.loadData()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '操作失败', icon: 'none' })
          }
        }
      }
    })
  }
})
