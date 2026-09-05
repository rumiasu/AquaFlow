// 我的反馈历史
const { getMyFeedbacks } = require('../../api/feedback')

Page({
  data: {
    loading: true,
    list: []
  },

  onShow() {
    this.loadData()
  },

  onPullDownRefresh() {
    this.loadData().then(() => wx.stopPullDownRefresh())
  },

  async loadData() {
    this.setData({ loading: true })
    try {
      const res = await getMyFeedbacks()
      this.setData({ list: res.data || [] })
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  }
})