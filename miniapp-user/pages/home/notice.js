const { getNoticeDetail } = require('../../api/notice')

Page({
  data: {
    notice: {
      title: '公告标题',
      content: '公告内容...',
      createTime: ''
    }
  },

  onLoad(options) {
    if (options.id) {
      this.loadNotice(options.id)
    }
  },

  async loadNotice(id) {
    try {
      const res = await getNoticeDetail(id)
      this.setData({ notice: res.data || {} })
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    }
  }
})