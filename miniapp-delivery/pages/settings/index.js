// 设置页
const app = getApp()
const { STORAGE_KEYS } = require('../../utils/storage-keys')
const {
  isReminderEnabled,
  setReminderEnabled,
  fetchPendingSummary,
  applyRedDot
} = require('../../utils/pending-reminder')

Page({
  data: {
    staffId: '',
    stationName: '',
    // 待办提醒（应用内）。**这个开关是真的** —— 关掉后首页 tab 的红点不再亮。
    //
    // [2026-09-19] 原先这里是「新订单提醒」开关，只 `wx.setStorageSync('notifyNewOrder')`，
    // **全项目没有任何代码读它** → 一个纯粹的假开关（站长以为关掉了通知，其实什么都没发生）。
    // 现在接上 utils/pending-reminder：开关状态被 app.onShow 与首页 onShow 真正消费。
    reminderEnabled: true
  },

  onLoad() {
    this.loadSettings()
  },

  onShow() {
    this.loadSettings()
  },

  loadSettings() {
    const userInfo = app.globalData.userInfo || {}
    this.setData({
      staffId: userInfo.staffId || wx.getStorageSync(STORAGE_KEYS.STAFF_ID) || '',
      stationName: userInfo.stationName || wx.getStorageSync('stationName') || '未分配',
      reminderEnabled: isReminderEnabled()
    })
  },

  /**
   * 开关待办提醒。
   *
   * 关掉只是**不亮红点**，待办卡照常显示 —— 静默吞掉待办比不提醒更糟（站长会以为没事）。
   * 打开时立刻拉一次汇总，免得站长要等下次进首页才看到红点。
   */
  onToggleReminder(e) {
    const value = !!e.detail.value
    this.setData({ reminderEnabled: value })
    setReminderEnabled(value)
    if (value) {
      // 立刻亮起来，让"打开了"这件事当场可见
      fetchPendingSummary().then(data => { if (data) applyRedDot(data) })
    }
    wx.showToast({
      title: value ? '已开启待办提醒' : '已关闭待办提醒（待办仍可查看）',
      icon: 'none'
    })
  },

  onClearCache() {
    wx.showModal({
      title: '清除缓存',
      content: '清除本地缓存，不影响登录状态。确定继续吗？',
      confirmColor: '#FF9500',
      success: (res) => {
        if (res.confirm) {
          const accessToken = wx.getStorageSync(STORAGE_KEYS.ACCESS_TOKEN)
          const refreshToken = wx.getStorageSync(STORAGE_KEYS.REFRESH_TOKEN)
          const userInfo = wx.getStorageSync(STORAGE_KEYS.USER_INFO)
          wx.clearStorageSync()
          if (accessToken) wx.setStorageSync(STORAGE_KEYS.ACCESS_TOKEN, accessToken)
          if (refreshToken) wx.setStorageSync(STORAGE_KEYS.REFRESH_TOKEN, refreshToken)
          if (userInfo) wx.setStorageSync(STORAGE_KEYS.USER_INFO, userInfo)
          wx.showToast({ title: '缓存已清除', icon: 'success' })
        }
      }
    })
  },

  onLogout() {
    wx.showModal({
      title: '退出登录',
      content: '确定要退出登录吗？',
      confirmColor: '#FF3B30',
      success: (res) => {
        if (res.confirm) {
          app.logout()
        }
      }
    })
  }
})
