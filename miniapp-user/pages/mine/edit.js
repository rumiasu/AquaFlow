const { createDraftGuard } = require('../../utils/draft-guard')
const { showBusinessModal } = require('../../utils/business-dialog')
const { updateProfile } = require('../../api/auth')
const { STORAGE_KEYS } = require('../../utils/storage-keys')
const { captureSession, isCurrentSession } = require('../../utils/token')

Page({
  data: {
    loading: false,
    submitting: false,
    saveError: '',
    nickname: '',
    phone: '', draftState: 'empty', draftMessage: ''
  },

  onLoad() {
    this._session = captureSession()
    const needLogin = !this._session.loggedIn || !this._session.customerId
    this.setData({ needLogin })
    const app = getApp()
    const userInfo = needLogin ? {} : app.globalData.userInfo || {}
    this._original = { nickname: userInfo.nickname || userInfo.nickName || '', phone: userInfo.phone || '' }
    this.setData({
      nickname: userInfo.nickname || userInfo.nickName || '',
      phone: userInfo.phone || ''
    })
    this.initDraft()
  },
  onShow() { if (!isCurrentSession(this._session)) this.onLoad() },
  onLogin() { wx.navigateTo({ url: '/pages/login/index' }) },

  draftScope() {
    const session = captureSession()
    return session.loggedIn && session.customerId ? { app: 'miniapp-user', actor: session.customerId, station: 'personal', route: 'pages/mine/edit', entity: 'profile', generation: session.epoch } : {}
  },
  initDraft() {
    this._draftGuard = createDraftGuard(() => this.draftScope()); this._draftToken = this._draftGuard.begin()
    this._draftRevision = 0; this._draftOwned = false; this._saveCompleted = false; this.readDraftOffer()
  },
  readDraftOffer() {
    const result = this._draftGuard.read(this._draftToken)
    const draft = result.draft
    const valid = draft && typeof draft.nickname === 'string' && typeof draft.phone === 'string'
    this._draftOffered = result.state === 'saved' && !!valid
    this.setData({ draftState: this._draftOffered ? 'saved' : result.state === 'saved' ? 'unknown' : result.state,
      draftMessage: result.state === 'unknown' || result.state === 'saved' && !valid ? '草稿暂无法读取，请重试。' : '' })
  },
  onRetryDraft() { if (this._saveCompleted && this._draftOwned) { if (this.clearSavedDraft() && this._original && (this.data.nickname !== this._original.nickname || this.data.phone !== this._original.phone)) { this._saveCompleted = false; this.saveEditableDraft() } return } this.readDraftOffer(); if (!this._draftOffered && this.data.draftState === 'empty' && this._original && (this.data.nickname !== this._original.nickname || this.data.phone !== this._original.phone)) this.saveEditableDraft() },
  saveEditableDraft() {
    if (!this._draftGuard || !this._draftToken || this._draftOffered || this.data.draftState === 'unknown') {
      this.setData({ draftMessage: '草稿尚未确认保存，请先处理已有草稿或重试。' }); return
    }
    this._draftOwned = true; this._draftSnapshot = JSON.stringify({ nickname: this.data.nickname, phone: this.data.phone })
    const result = this._draftGuard.save(this._draftToken, { nickname: this.data.nickname, phone: this.data.phone })
    this.setData({ draftState: result.state === 'saved' ? 'active' : result.state,
      draftMessage: result.state === 'saved' ? '' : '草稿尚未确认保存，请稍后重试。' })
  },
  onRestoreDraft() {
    const token = this._draftToken, revision = this._draftRevision, result = this._draftGuard.read(token)
    if (result.state !== 'saved' || !result.draft || typeof result.draft.nickname !== 'string' || typeof result.draft.phone !== 'string') { this.readDraftOffer(); return }
    const restore = () => {
      const current = this._draftGuard.read(token)
      if (this._unloaded || revision !== this._draftRevision || current.state !== 'saved' || JSON.stringify(current.draft) !== JSON.stringify(result.draft)) return
      this._draftOffered = false; this._draftOwned = true; this._draftSnapshot = JSON.stringify(result.draft); this._draftRevision++
      this.setData({ nickname: result.draft.nickname, phone: result.draft.phone, draftState: 'active', draftMessage: '' }, () => this.updateLeaveWarning())
    }
    const changed = this.data.nickname !== this._original.nickname || this.data.phone !== this._original.phone
    if (changed) return showBusinessModal({ title: '恢复之前的输入？', content: '当前未保存的输入会被替换。', confirmText: '恢复', cancelText: '继续填写', success: res => { if (res.confirm) restore() } })
    restore()
  },
  onDiscardSavedDraft() {
    const token = this._draftToken, revision = this._draftRevision; const offered = this._draftGuard.read(token)
    showBusinessModal({ title: '放弃之前的输入？', content: '确认后将清除已保存的草稿。', confirmText: '放弃', cancelText: '保留', success: res => {
      if (!res.confirm || this._unloaded || revision !== this._draftRevision) return
      const current = this._draftGuard.read(token)
      if (offered.state !== 'saved' || current.state !== 'saved' || JSON.stringify(current.draft) !== JSON.stringify(offered.draft)) { this.setData({ draftState: 'unknown', draftMessage: '草稿已有变化或暂无法读取，请重试。' }); return }
      const result = this._draftGuard.clear(token)
      if (result.state !== 'empty') { this.setData({ draftState: result.state, draftMessage: '草稿尚未确认清除，请重试。' }); return }
      this._draftOffered = false; this._draftOwned = false; this.setData({ draftState: 'empty', draftMessage: '' }); this.updateLeaveWarning()
    } })
  },
  clearSavedDraft() {
    if (!this._draftGuard || !this._draftOwned) return true
    const current = this._draftGuard.read(this._draftToken)
    if (current.state === 'empty') { this._draftOwned = false; this.setData({ draftState: 'empty', draftMessage: '' }); return true }
    if (current.state !== 'saved' || JSON.stringify(current.draft) !== this._draftSnapshot) { this.setData({ draftState: 'unknown', draftMessage: '草稿已有变化或暂无法读取，请重试。' }); return false }
    const result = this._draftGuard.clear(this._draftToken)
    if (result.state !== 'empty') { this.setData({ draftState: result.state, draftMessage: '资料已保存，但草稿尚未确认清除，请重试。' }); return false }
    this._draftOwned = false; this.setData({ draftState: 'empty', draftMessage: '' }); return true
  },
  updateLeaveWarning() {
    const dirty = this._original && (this.data.nickname !== this._original.nickname || this.data.phone !== this._original.phone)
    if (dirty || (this._draftOwned && !this._saveCompleted)) this.saveEditableDraft()
    if (dirty && wx.enableAlertBeforeUnload) wx.enableAlertBeforeUnload({ message: '资料尚未保存，确定离开吗？' })
    else if (!dirty && wx.disableAlertBeforeUnload) wx.disableAlertBeforeUnload()
  },
  onNicknameInput(e) {
    this._draftRevision = (this._draftRevision || 0) + 1; this.setData({ nickname: e.detail.value }, () => this.updateLeaveWarning())
  },

  onPhoneInput(e) {
    this._draftRevision = (this._draftRevision || 0) + 1; this.setData({ phone: e.detail.value }, () => this.updateLeaveWarning())
  },

  async onSubmit() {
    if (this.data.submitting || this._unloaded) return
    if (this.data.needLogin) { this.setData({ saveError: '请先登录后修改本人资料。' }); return }
    if (!isCurrentSession(this._session)) { this.setData({ saveError: '登录状态已变化，请返回后重新进入个人资料。' }); return }
    const { nickname, phone } = this.data
    if (!nickname.trim()) {
      wx.showToast({ title: '请输入昵称', icon: 'none' })
      return
    }
    if (phone && !/^1\d{10}$/.test(phone)) {
      wx.showToast({ title: '手机号格式不正确', icon: 'none' })
      return
    }

    this.setData({ submitting: true, saveError: '' })
    try {
      await updateProfile({ nickname: nickname.trim(), phone: phone.trim() })
      if (this._unloaded || !isCurrentSession(this._session)) return
      // 更新本地存储
      const app = getApp()
      const userInfo = app.globalData.userInfo || {}
      userInfo.nickname = nickname.trim()
      userInfo.phone = phone.trim()
      app.globalData.userInfo = userInfo
      wx.setStorageSync(STORAGE_KEYS.USER_INFO, userInfo)

      this._original = { nickname: nickname.trim(), phone: phone.trim() }; this._saveCompleted = true; const cleared = this.clearSavedDraft(); this.updateLeaveWarning()
      wx.showToast({ title: '保存成功', icon: 'success' })
      if (cleared) setTimeout(() => { if (!this._unloaded && isCurrentSession(this._session)) wx.navigateBack() }, 1500)
    } catch (error) {
      if (!this._unloaded && isCurrentSession(this._session)) this.setData({ saveError: '资料未能保存，输入已保留，请重试。' })
    } finally {
      if (!this._unloaded) this.setData({ submitting: false })
    }
  },
  onUnload() { this._unloaded = true }
})
