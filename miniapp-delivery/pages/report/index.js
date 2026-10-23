const { submitFeedback, getMyFeedbacks } = require('../../api/feedback')

function feedbackOwner() {
  const app = getApp(), data = app.globalData || {}, user = data.userInfo || {}
  return { app, generation: app._loginGeneration || 0,
    identity: JSON.stringify([!!data.isLogin, user.staffId, user.id, user.role, user.stationId, user.bindStatus]) }
}

function sameOwner(a, b) {
  return a && b && a.app === b.app && a.generation === b.generation && a.identity === b.identity
}

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

  onLoad() {
    this._syncFeedbackOwner()
  },

  onShow() {
    if (this._unloaded) return
    this._hidden = false
    this._pageGeneration = (this._pageGeneration || 0) + 1
    this._feedbackConfirmation = null
    this._syncFeedbackOwner()
    this._applyFeedbackSuccess()
    this.setData({ submitting: !!this._feedbackFlight })
    return this.loadHistory()
  },

  onHide() { this._leaveFeedbackPage() },
  onUnload() {
    this._unloaded = true
    this._leaveFeedbackPage()
  },

  _leaveFeedbackPage() {
    this._hidden = true
    this._pageGeneration = (this._pageGeneration || 0) + 1
    this._historyVersion = (this._historyVersion || 0) + 1
    this._feedbackConfirmation = null
    // 请求本身可能已执行：离页只失效回调，不解除仍在途的同身份提交锁。
    if (this._feedbackLoading) { wx.hideLoading(); this._feedbackLoading = false }
  },

  _syncFeedbackOwner() {
    const owner = feedbackOwner()
    if (this._feedbackOwner && !sameOwner(this._feedbackOwner, owner)) {
      this._feedbackConfirmation = null
      this._historyVersion = (this._historyVersion || 0) + 1
      this._feedbackFlight = null
      this._feedbackSuccess = null
      if (this._feedbackLoading) { wx.hideLoading(); this._feedbackLoading = false }
      this.setData({ category: 'bug', content: '', contact: '', submitting: false,
        historyList: [], historyError: '当前身份的反馈记录尚未读取，请重新读取', historyLoading: false })
    }
    this._feedbackOwner = owner
    return owner
  },

  _feedbackCurrent(owner, generation) {
    return !this._unloaded && !this._hidden && (this._pageGeneration || 0) === generation
      && sameOwner(owner, feedbackOwner())
  },

  _applyFeedbackSuccess() {
    const completed = this._feedbackSuccess
    if (!completed || this._unloaded || this._hidden) return
    this._feedbackSuccess = null
    if (!sameOwner(completed.owner, feedbackOwner())) return
    // 明确成功属于原请求；返回时只处理其原稿，不能清掉后来恢复的新草稿。
    const draft = completed.draft
    if (this.data.category === draft.category && this.data.content === draft.content && this.data.contact === draft.contact) {
      this.setData({ content: '', contact: '', category: 'bug' })
    }
    wx.showToast({ title: '反馈已提交，感谢！', icon: 'success' })
  },

  _releaseFeedbackFlight(flight) {
    if (this._feedbackFlight !== flight) return
    this._feedbackFlight = null
    if (this._feedbackLoading) { wx.hideLoading(); this._feedbackLoading = false }
    if (!this._unloaded && !this._hidden) {
      this._syncFeedbackOwner()
      this.setData({ submitting: false })
    }
  },

  onRetryHistory() { return this.loadHistory() },

  async loadHistory() {
    if (this._unloaded || this._hidden) return
    const owner = this._syncFeedbackOwner(), generation = this._pageGeneration || 0
    const version = this._historyVersion = (this._historyVersion || 0) + 1
    const current = () => version === this._historyVersion && this._feedbackCurrent(owner, generation)
    this.setData({ historyLoading: true, historyError: '' })
    try {
      const res = await getMyFeedbacks()
      if (!current()) return
      if (!res || !Array.isArray(res.data)) throw new Error('反馈记录尚未核实')
      this.setData({ historyList: res.data })
    } catch (err) {
      if (!current()) return
      // 静默失败会被当成「没有历史反馈」，用户以为反馈丢了。留痕 + 提示。
      this.setData({ historyError: '反馈记录尚未读取，请重试后核对' })
      console.error('[Report] 反馈历史加载失败:', err)
      wx.showToast({ title: '反馈历史加载失败', icon: 'none' })
    } finally {
      if (version === this._historyVersion && !this._unloaded && !this._hidden) {
        if (!sameOwner(owner, feedbackOwner())) this._syncFeedbackOwner()
        else if (current()) this.setData({ historyLoading: false })
      }
    }
  },

  _updateFeedbackDraft(patch) {
    if (this._unloaded || this._hidden || this.data.submitting) return
    const changed = this._feedbackOwner && !sameOwner(this._feedbackOwner, feedbackOwner())
    this._syncFeedbackOwner()
    if (changed) return // 不接纳身份切换前已经排队的输入事件。
    this._feedbackConfirmation = null
    this.setData(patch)
  },

  onSelectCategory(e) {
    this._updateFeedbackDraft({ category: e.currentTarget.dataset.category })
  },

  onContentInput(e) {
    this._updateFeedbackDraft({ content: e.detail.value })
  },

  onContactInput(e) {
    this._updateFeedbackDraft({ contact: e.detail.value })
  },

  async onSubmit() {
    if (this._unloaded || this._hidden) return
    const owner = this._syncFeedbackOwner()
    if (this.data.submitting || this._feedbackFlight || this._feedbackConfirmation) return
    const { category, content, contact } = this.data

    if (!content.trim()) {
      wx.showToast({ title: '请填写反馈内容', icon: 'none' })
      return
    }

    const draft = { category, content, contact }
    const confirmation = { owner, generation: this._pageGeneration || 0, draft }
    this._feedbackConfirmation = confirmation
    wx.showModal({
      title: '提交反馈',
      content: '感谢您的反馈，我们会尽快处理',
      confirmText: '确认提交',
      success: async (res) => {
        if (this._feedbackConfirmation !== confirmation) return
        this._feedbackConfirmation = null
        if (res.confirm) {
          // request只能保护调用API之后的身份变化；原生确认返回前也必须复核。
          if (!this._feedbackCurrent(confirmation.owner, confirmation.generation)) {
            if (!this._unloaded && !this._hidden) this._syncFeedbackOwner()
            return
          }
          if (this.data.category !== draft.category || this.data.content !== draft.content || this.data.contact !== draft.contact) {
            wx.showToast({ title: '内容已变化，请重新确认', icon: 'none' })
            return
          }
          if (this.data.submitting || this._feedbackFlight) return
          const flight = this._feedbackFlight = confirmation
          this.setData({ submitting: true })
          this._feedbackLoading = true
          wx.showLoading({ title: '提交中...' })
          try {
            await submitFeedback(draft)
            if (this._feedbackFlight !== flight || this._unloaded || !sameOwner(flight.owner, feedbackOwner())) return
            // 已发出请求的明确成功不因hide丢失；页面再可见时消费一次结果。
            this._feedbackSuccess = flight
            this._applyFeedbackSuccess()
            if (!this._hidden) this.loadHistory()
          } catch (err) {
            if (this._feedbackFlight !== flight || !this._feedbackCurrent(flight.owner, flight.generation)) return
            wx.showToast({ title: err.message || '提交失败', icon: 'none' })
          } finally {
            this._releaseFeedbackFlight(flight)
          }
        }
      },
      fail: () => { if (this._feedbackConfirmation === confirmation) this._feedbackConfirmation = null }
    })
  }
})
