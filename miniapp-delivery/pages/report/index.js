const { showBusinessModal } = require('../../utils/business-dialog')
const { submitFeedback, getMyFeedbacks } = require('../../api/feedback')
const { createDraftGuard } = require('../../utils/draft-guard')

// 类型是固定选项，其他自由文字及联系方式不进入草稿存储。
// WeakMap 仅跨页面实例保留当前登录内的类型，不写入设备存储。
const categorySlots = new WeakMap()
const categories = ['bug', 'feature', 'ui', 'other']
function reportScope() {
  const app = getApp(), g = app.globalData || {}, u = g.userInfo || {}
  if (!g.isLogin || !u.staffId) return null
  return { app: 'miniapp-delivery', actor: String(u.staffId), station: u.stationId == null ? 'unbound' : String(u.stationId), route: 'pages/report/index', entity: 'new', generation: app._loginGeneration || 0 }
}
function categorySlot(app, identity) {
  let slot = categorySlots.get(app)
  if (slot && slot.identity !== identity) { slot.values.clear(); categorySlots.delete(app); slot = null }
  if (!slot) {
    const values = new Map()
    slot = { identity, values, version: 0 }
    slot.storage = { getStorageSync: key => values.get(key), setStorageSync: (key, value) => { slot.version++; values.set(key, value) }, removeStorageSync: key => { if (values.has(key)) slot.version++; values.delete(key) } }
    categorySlots.set(app, slot)
  }
  return slot
}

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
    historyLoading: true, historyExpanded: false, submitNotice: '', categoryDraftAvailable: false, categoryDraftBlocked: false, categoryDraftNotice: ''
  },

  onLoad() {
    this._syncFeedbackOwner()
    this._initCategoryDraft()
  },

  onShow() {
    if (this._unloaded) return
    this._hidden = false
    this._pageGeneration = (this._pageGeneration || 0) + 1
    this._feedbackConfirmation = null
    this._syncFeedbackOwner()
    if (!this._categoryGuard) this._initCategoryDraft()
    this._applyFeedbackSuccess()
    this.setData({ submitting: !!this._feedbackFlight })
    return this.loadHistory()
  },

  onHide() { this._leaveFeedbackPage() },
  onUnload() {
    this._unloaded = true
    this._leaveFeedbackPage()
    if (this._categorySlot && !sameOwner(this._feedbackOwner, feedbackOwner())) { this._categorySlot.values.clear(); if (this._categoryApp && categorySlots.get(this._categoryApp) === this._categorySlot) categorySlots.delete(this._categoryApp) }
    if (typeof wx.disableAlertBeforeUnload === 'function') wx.disableAlertBeforeUnload()
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
      if (this._categorySlot) this._categorySlot.values.clear()
      if (this._categoryApp && categorySlots.get(this._categoryApp) === this._categorySlot) categorySlots.delete(this._categoryApp)
      this._categoryGuard = null; this._categoryToken = null; this._categorySlot = null; this._categoryApp = null
      this._feedbackConfirmation = null
      this._historyVersion = (this._historyVersion || 0) + 1
      this._feedbackFlight = null
      this._feedbackSuccess = null
      if (this._feedbackLoading) { wx.hideLoading(); this._feedbackLoading = false }
      this.setData({ category: 'bug', content: '', contact: '', submitting: false,
        historyList: [], historyError: '当前身份的反馈记录尚未读取，请重新读取', historyLoading: false, submitNotice: '', categoryDraftAvailable: false, categoryDraftBlocked: false, categoryDraftNotice: '' })
    }
    this._feedbackOwner = owner
    return owner
  },

  _initCategoryDraft() {
    const scope = reportScope(), app = getApp(), user = (app.globalData || {}).userInfo
    if (!scope || !user || typeof user !== 'object') { this.setData({ categoryDraftNotice: '当前身份未核实，反馈类型不能暂存。' }); return }
    const identity = JSON.stringify([scope.actor, scope.station, scope.generation])
    this._categoryApp = app; this._categorySlot = categorySlot(app, identity)
    this._categoryGuard = createDraftGuard(reportScope, this._categorySlot.storage)
    this._categoryToken = this._categoryGuard.begin()
    const result = this._categoryGuard.read(this._categoryToken)
    if (result.state === 'saved' && result.draft && categories.includes(result.draft.category)) this.setData({ categoryDraftAvailable: true, categoryDraftBlocked: false, categoryDraftNotice: '本次登录有可恢复的反馈类型。' })
    else if (result.state !== 'empty') this.setData({ categoryDraftAvailable: false, categoryDraftBlocked: true, categoryDraftNotice: '反馈类型草稿尚未核实，请先处理后再暂存。' })
  },

  _saveCategoryDraft() {
    if (!this._categoryGuard || !this._categoryToken) { this.setData({ categoryDraftNotice: '反馈类型尚未暂存。' }); return }
    if (this.data.categoryDraftAvailable || this.data.categoryDraftBlocked) { this.setData({ categoryDraftNotice: '已有类型草稿，请先恢复或放弃后再暂存新选择。' }); return }
    const result = this._categoryGuard.save(this._categoryToken, { category: this.data.category })
    this.setData({ categoryDraftNotice: result.state === 'saved' ? '反馈类型已在本次登录中暂存。' : '反馈类型尚未确认保存。', categoryDraftBlocked: result.state !== 'saved' })
  },

  onRestoreCategoryDraft() {
    if (this.data.submitting || !this._categoryGuard) return
    const result = this._categoryGuard.read(this._categoryToken)
    if (result.state !== 'saved' || !result.draft || !categories.includes(result.draft.category)) { this.setData({ categoryDraftAvailable: false, categoryDraftBlocked: true, categoryDraftNotice: '类型草稿尚未核实，未覆盖当前内容。' }); return }
    this.setData({ category: result.draft.category, categoryDraftAvailable: false, categoryDraftBlocked: false, categoryDraftNotice: '已恢复反馈类型；内容和联系方式未保存。' })
  },

  onDiscardCategoryDraft() {
    if (this.data.submitting || !this._categoryGuard) return
    const result = this._categoryGuard.clear(this._categoryToken)
    this.setData({ categoryDraftAvailable: false, categoryDraftBlocked: result.state !== 'empty', categoryDraftNotice: result.state === 'empty' ? '已放弃类型草稿。' : '类型草稿尚未确认清除。' })
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
      this.setData({ content: '', contact: '', category: 'bug', submitNotice: '反馈已提交。' })
      if (this._categoryGuard && this._categorySlot && this._categorySlot.version === completed.categoryDraftVersion) { const result = this._categoryGuard.clear(this._categoryToken); this.setData({ categoryDraftAvailable: false, categoryDraftBlocked: result.state !== 'empty', categoryDraftNotice: result.state === 'empty' ? '' : '类型草稿尚未确认清除。' }) }
      if (typeof wx.disableAlertBeforeUnload === 'function') wx.disableAlertBeforeUnload()
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
    this.setData(Object.assign({ submitNotice: '' }, patch))
    const dirty = this.data.content || this.data.contact
    if (dirty && typeof wx.enableAlertBeforeUnload === 'function') wx.enableAlertBeforeUnload({ message: '反馈尚未提交，确定离开吗？' })
    else if (!dirty && typeof wx.disableAlertBeforeUnload === 'function') wx.disableAlertBeforeUnload()
  },

  onToggleHistory() { this.setData({ historyExpanded: !this.data.historyExpanded }) },

  onSelectCategory(e) {
    const category = e.currentTarget.dataset.category
    if (!categories.includes(category) || this._unloaded || this._hidden || this.data.submitting || (this._feedbackOwner && !sameOwner(this._feedbackOwner, feedbackOwner()))) return
    this._updateFeedbackDraft({ category }); this._saveCategoryDraft()
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
    if (this.data.submitting || this._feedbackFlight) return
    const { category, content, contact } = this.data
    if (!content.trim()) { wx.showToast({ title: '请填写反馈内容', icon: 'none' }); return }
    const draft = { category, content, contact }
    const flight = this._feedbackFlight = { owner, generation: this._pageGeneration || 0, draft, categoryDraftVersion: this._categorySlot && this._categorySlot.version }
    this.setData({ submitting: true, submitNotice: '' })
    try {
      await submitFeedback(draft)
      if (this._feedbackFlight !== flight || !sameOwner(flight.owner, feedbackOwner())) return
      if (this._unloaded) { if (this._categoryGuard && this._categorySlot && this._categorySlot.version === flight.categoryDraftVersion) this._categoryGuard.clear(this._categoryToken); return }
      this._feedbackSuccess = flight
      this._applyFeedbackSuccess()
      if (!this._hidden) this.loadHistory()
    } catch (err) {
      if (this._feedbackFlight === flight && this._feedbackCurrent(flight.owner, flight.generation)) this.setData({ submitNotice: err && (err.businessRejected || err.requestMayHaveOccurred === false) ? (err.message || '提交未完成，请重试。') : '提交结果尚未确认，请先查看反馈记录再决定是否重试。' })
    } finally { this._releaseFeedbackFlight(flight) }
  }
})
