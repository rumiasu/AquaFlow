const { getNotices } = require('../../api/notice')

Page({
  data: {
    loading: true,
    notices: []
  },

  onShow() {
    this.loadNotices()
  },

  onPullDownRefresh() {
    this.loadNotices().then(() => wx.stopPullDownRefresh())
  },

  async loadNotices() {
    this.setData({ loading: true })
    try {
      const res = await getNotices()
      this.setData({ notices: res.data || [] })
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  onNoticeTap(e) {
    const { id } = e.currentTarget.dataset
    wx.navigateTo({ url: `/pages/home/notice?id=${id}` })
  }
})