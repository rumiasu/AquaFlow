const { submitFeedback, getMyFeedbacks } = require('../../api/feedback')

Page({
  data: {
    category: 'bug',
    content: '',
    contact: '',
    submitting: false,
    historyList: [],
    historyError: '',
    historyLoading: true
  },

  onShow() {
    this.loadHistory()
  },

  onRetryHistory() { return this.loadHistory() },

  async loadHistory() {
    this.setData({ historyLoading: true, historyError: '' })
    try {
      const res = await getMyFeedbacks()
      this.setData({ historyList: res.data || [] })
    } catch (err) {
      // 静默失败会被当成「没有历史反馈」，用户以为反馈丢了。留痕 + 提示。
      this.setData({ historyError: '反馈记录尚未读取，请重试后核对' })
      console.error('[Report] 反馈历史加载失败:', err)
      wx.showToast({ title: '反馈历史加载失败', icon: 'none' })
    } finally {
      this.setData({ historyLoading: false })
    }
  },

  onSelectCategory(e) {
    if (this.data.submitting) return
    this.setData({ category: e.currentTarget.dataset.category })
  },

  onContentInput(e) {
    if (this.data.submitting) return
    this.setData({ content: e.detail.value })
  },

  onContactInput(e) {
    if (this.data.submitting) return
    this.setData({ contact: e.detail.value })
  },

  async onSubmit() {
    if (this.data.submitting || this._feedbackConfirmation) return
    const { category, content, contact } = this.data

    if (!content.trim()) {
      wx.showToast({ title: '请填写反馈内容', icon: 'none' })
      return
    }

    const confirmation = {}
    this._feedbackConfirmation = confirmation
    wx.showModal({
      title: '提交反馈',
      content: '感谢您的反馈，我们会尽快处理',
      confirmText: '确认提交',
      success: async (res) => {
        if (this._feedbackConfirmation !== confirmation) return
        this._feedbackConfirmation = null
        if (res.confirm) {
          if (this.data.submitting) return
          this.setData({ submitting: true })
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
          } finally {
            this.setData({ submitting: false })
          }
        }
      },
      fail: () => { if (this._feedbackConfirmation === confirmation) this._feedbackConfirmation = null }
    })
  }
})
