const { submitFeedback, getMyFeedbacks } = require('../../api/feedback')

Page({
  data: {
    category: 'bug',
    content: '',
    contact: '',
    historyList: [],
    historyLoading: true
  },

  onShow() {
    this.loadHistory()
  },

  async loadHistory() {
    this.setData({ historyLoading: true })
    try {
      const res = await getMyFeedbacks()
      this.setData({ historyList: res.data || [] })
    } catch (err) {
      // 静默失败会被当成「没有历史反馈」，用户以为反馈丢了。留痕 + 提示。
      console.error('[Report] 反馈历史加载失败:', err)
      wx.showToast({ title: '反馈历史加载失败', icon: 'none' })
    } finally {
      this.setData({ historyLoading: false })
    }
  },

  onSelectCategory(e) {
    this.setData({ category: e.currentTarget.dataset.category })
  },

  onContentInput(e) {
    this.setData({ content: e.detail.value })
  },

  onContactInput(e) {
    this.setData({ contact: e.detail.value })
  },

  async onSubmit() {
    const { category, content, contact } = this.data

    if (!content.trim()) {
      wx.showToast({ title: '请填写反馈内容', icon: 'none' })
      return
    }

    wx.showModal({
      title: '提交反馈',
      content: '感谢您的反馈，我们会尽快处理',
      confirmText: '确认提交',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '提交中...' })
          try {
            await submitFeedback({ category, content, contact })
            wx.hideLoading()
            wx.showToast({ title: '反馈已提交，感谢！', icon: 'success' })
            this.setData({ content: '', contact: '', category: 'bug' })
            this.loadHistory()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '提交失败', icon: 'none' })
          }
        }
      }
    })
  }
})
