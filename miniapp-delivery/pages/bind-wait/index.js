const { get, post } = require('../../utils/request')
const { API, BINDING_STATUS } = require('../../config/api')
const { STORAGE_KEYS } = require('../../utils/storage-keys')

// V1: 配送员"等待审批状态"展示页。用于:
  //   PENDING: 绑定申请中等待站长批准(可撤回)
  //   PENDING_UNBIND: 主动申请解绑,等待站长确认(不可撤回,仅等待)
  // 如果被拒绝 → 跳回 apply-bind 重新申请
  // 如果绑定成功 → 跳首页
Page({
  data: {
    mode: 'PENDING',          // PENDING / PENDING_UNBIND
    stationName: '',
    applyStationName: '',
    hint: '', refreshLoading: false, refreshError: '', statusReady: false
  },

  onLoad() {
    this.refresh(true)
  },

  onRefresh() {
    this.refresh(false)
  },

  onShow() { if (this._bindingHidden) { this._bindingHidden = false; this.refresh(true) } },
  onHide() { this._bindingHidden = true; this._bindingSeq = (this._bindingSeq || 0) + 1 },
  onUnload() { this.onHide() },

  bindingSnapshot() {
    const app = getApp(), g = app.globalData || {}, u = g.userInfo || {}
    return { app, generation: app._loginGeneration || 0, identity: JSON.stringify([!!g.isLogin, u.staffId, u.stationId, u.role, u.bindStatus]), seq: this._bindingSeq }
  },
  bindingCurrent(snapshot) {
    const now = this.bindingSnapshot()
    return !this._bindingHidden && snapshot.app === now.app && snapshot.generation === now.generation && snapshot.identity === now.identity && snapshot.seq === now.seq
  },

  async refresh(silent) {
    this._bindingSeq = (this._bindingSeq || 0) + 1
    let active = this.bindingSnapshot()
    const app = active.app
    const userInfo = { ...((app.globalData && app.globalData.userInfo) || wx.getStorageSync(STORAGE_KEYS.USER_INFO) || {}) }
    this.setData({ refreshLoading: true, refreshError: '', statusReady: false, applyStationName: '' })
    try {
      // 优先从服务器拿最新 bind 状态
      const optionalRead = error => { if (error && error.sessionChanged) throw error; return null }
      const [bindRes, meRes] = await Promise.all([
        get(API.BIND_STATUS).catch(optionalRead),
        get(API.ME).catch(optionalRead)
      ])

      if (!this.bindingCurrent(active)) return
      const validState = value => value && typeof value === 'object' && !Array.isArray(value) && Object.values(BINDING_STATUS).includes(value.bindingStatus || value.bindStatus)
      const bindData = bindRes && validState(bindRes.data) ? bindRes.data : null
      const meData = meRes && validState(meRes.data) ? meRes.data : null
      if (!bindData && !meData) throw new Error('申请状态尚未读取')
      let bindStatus = userInfo.bindStatus || BINDING_STATUS.UNBOUND
      let stationId = userInfo.stationId || null
      let applyStationId = userInfo.applyStationId || null

      if (bindData) {
        bindStatus = bindData.bindingStatus || bindData.bindStatus
        if (Object.prototype.hasOwnProperty.call(bindData, 'stationId')) stationId = bindData.stationId
        if (Object.prototype.hasOwnProperty.call(bindData, 'applyStationId')) applyStationId = bindData.applyStationId
      }
      if (meData) {
        const m = meData
        if (m.bindingStatus) bindStatus = m.bindingStatus
        if (Object.prototype.hasOwnProperty.call(m, 'stationId')) stationId = m.stationId
        if (m.bindStatus) bindStatus = m.bindStatus
      }

      // 落盘最新状态
      const fresh = app.setLoginState({
        accessToken: app.globalData.accessToken,
        refreshToken: app.globalData.refreshToken,
        staffId: userInfo.staffId,
        nickname: (meData && meData.nickname) || userInfo.nickname,
        phone: (meData && meData.phone) || userInfo.phone,
        role: (meData && (meData.staffRole || meData.role)) || userInfo.role,
        stationId,
        bindingStatus: bindStatus,
        applyStationId
      })
      active = this.bindingSnapshot()
      this.setData({ statusReady: true })

      // 判定跳转
      if (bindStatus === BINDING_STATUS.BOUND && stationId) {
        if (!silent) wx.showToast({ title: '绑定成功', icon: 'success' })
        setTimeout(() => {
          if (this.bindingCurrent(active)) wx.switchTab({ url: '/pages/home/index' })
        }, silent ? 0 : 1500)
        return
      }

      if (bindStatus === BINDING_STATUS.REJECTED || bindStatus === BINDING_STATUS.UNBOUND) {
        if (!silent && bindStatus === BINDING_STATUS.REJECTED) {
          wx.showModal({ confirmText: '知道了',
            title: '申请被拒绝',
            content: '站长拒绝了您的绑定申请，请重新申请。',
            showCancel: false,
            success: () => {
              if (this.bindingCurrent(active)) wx.redirectTo({ url: '/pages/station-mgmt/apply-bind/index' })
            }
          })
          return
        }
        wx.redirectTo({ url: '/pages/station-mgmt/apply-bind/index' })
        return
      }

      // PENDING 或 PENDING_UNBIND → 继续显示
      const mode = bindStatus === BINDING_STATUS.PENDING_UNBIND ? 'PENDING_UNBIND' : 'PENDING'
      this.setData({
        mode,
        stationName: '',
        applyStationName: userInfo.applyStationName || ''
      })
    } catch (err) {
      if (!this.bindingCurrent(active)) return
      this.setData({ refreshError: '申请最新状态尚未核实，请刷新后查看' })
      if (!silent) wx.showToast({ title: '刷新失败', icon: 'none' })
    } finally { if (this.bindingCurrent(active)) this.setData({ refreshLoading: false }) }
  },

  /**
   * 退出出口。本页是 redirectTo 进来的（页面栈无上一页）：
   * PENDING 时还能靠「撤回申请」自己走出来，**PENDING_UNBIND 撤回被禁用**，
   * 没有这个按钮就只能干等站长，属于用户报的"卡死"的一部分。
   */
  onLogout() {
    wx.showModal({
      title: '退出登录',
      content: '退出后可用其他微信账号登录。',
      confirmText: '退出',
      success: (res) => {
        if (res.confirm) getApp().logout()
      }
    })
  },

  onWithdraw() {
    if (this.data.mode === 'PENDING_UNBIND') {
      wx.showToast({ title: '解绑审批中，无法撤回', icon: 'none' })
      return
    }
    wx.showModal({ confirmText: '确认撤回',
      title: '撤回申请',
      content: '确定要撤回当前绑定申请吗？',
      success: async (res) => {
        if (!res.confirm) return
        wx.showLoading({ title: '撤回中...' })
        try {
          await post(API.BIND_CANCEL)
          const app = getApp()
          const userInfo = (app.globalData && app.globalData.userInfo) || wx.getStorageSync(STORAGE_KEYS.USER_INFO) || {}
          app.setLoginState({
            accessToken: app.globalData.accessToken,
            refreshToken: app.globalData.refreshToken,
            ...userInfo,
            bindStatus: BINDING_STATUS.UNBOUND,
            applyStationId: null,
            applyStationName: ''
          })
          wx.hideLoading()
          wx.showToast({ title: '已撤回', icon: 'success' })
          setTimeout(() => {
            wx.redirectTo({ url: '/pages/station-mgmt/apply-bind/index' })
          }, 1200)
        } catch (err) {
          wx.hideLoading()
          wx.showToast({ title: err.message || '撤回失败', icon: 'none' })
        }
      }
    })
  }
})
