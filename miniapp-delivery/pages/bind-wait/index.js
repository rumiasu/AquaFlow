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
    hint: ''
  },

  onLoad() {
    this.refresh(true)
  },

  onRefresh() {
    this.refresh(false)
  },

  async refresh(silent) {
    try {
      const app = getApp()
      // 优先从服务器拿最新 bind 状态
      const [bindRes, meRes] = await Promise.all([
        get(API.BIND_STATUS).catch(() => null),
        get(API.ME).catch(() => null)
      ])

      const userInfo = (app.globalData && app.globalData.userInfo) || wx.getStorageSync(STORAGE_KEYS.USER_INFO) || {}
      let bindStatus = userInfo.bindStatus || BINDING_STATUS.UNBOUND
      let stationId = userInfo.stationId || null
      let applyStationId = userInfo.applyStationId || null

      if (bindRes && bindRes.data) {
        bindStatus = bindRes.data.bindingStatus || bindStatus
        stationId = bindRes.data.stationId != null ? bindRes.data.stationId : stationId
        applyStationId = bindRes.data.applyStationId != null ? bindRes.data.applyStationId : applyStationId
      }
      if (meRes && meRes.data) {
        const m = meRes.data
        if (m.bindingStatus) bindStatus = m.bindingStatus
        if (m.stationId) stationId = m.stationId
        if (m.bindStatus) bindStatus = m.bindStatus
      }

      // 落盘最新状态
      const fresh = app.setLoginState({
        accessToken: app.globalData.accessToken,
        refreshToken: app.globalData.refreshToken,
        staffId: userInfo.staffId,
        nickname: (meRes && meRes.data && meRes.data.nickname) || userInfo.nickname,
        phone: (meRes && meRes.data && meRes.data.phone) || userInfo.phone,
        role: (meRes && meRes.data && (meRes.data.staffRole || meRes.data.role)) || userInfo.role,
        stationId,
        bindingStatus: bindStatus,
        applyStationId
      })

      // 判定跳转
      if (bindStatus === BINDING_STATUS.BOUND && stationId) {
        if (!silent) wx.showToast({ title: '绑定成功', icon: 'success' })
        setTimeout(() => {
          wx.switchTab({ url: '/pages/home/index' })
        }, silent ? 0 : 1500)
        return
      }

      if (bindStatus === BINDING_STATUS.REJECTED || bindStatus === BINDING_STATUS.UNBOUND) {
        if (!silent && bindStatus === BINDING_STATUS.REJECTED) {
          wx.showModal({
            title: '申请被拒绝',
            content: '站长拒绝了您的绑定申请，请重新申请。',
            showCancel: false,
            success: () => {
              wx.redirectTo({ url: '/pages/station-mgmt/apply-bind/index' })
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
      if (!silent) wx.showToast({ title: '刷新失败', icon: 'none' })
    }
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
    wx.showModal({
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
