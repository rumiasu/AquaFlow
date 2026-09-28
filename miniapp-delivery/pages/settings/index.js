// 设置页
const app = getApp()
const { STORAGE_KEYS } = require('../../utils/storage-keys')
// [2026-09-27 走查 D06 修] 「所属水站」缺名字时要能重试 —— 拿真实绑定数据去补，
// 而不是把"名字没加载出来"写成「未分配」（见 loadSettings 的注释）。
const { get } = require('../../utils/request')
const { API, BINDING_STATUS } = require('../../config/api')
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
    /** [2026-09-27 走查 D06] 真实的绑定状态文案（未绑定时说"未绑定水站"，缺名字时说"名称暂未加载"） */
    stationStateText: '',
    /** true = 已绑定但站名没拿到，行尾给一个「重试」 */
    stationNameRetry: false,
    stationNameLoading: false,
    /**
     * [2026-09-27 走查 D07] 待办提醒**只对站长有效**：
     * `/api/manager/pending-summary` 是 @RequireRole("STATION_MANAGER")，
     * 配送员点了这个开关什么都不会发生（组件里那次请求只会拿到权限错误），
     * 而原本文案还说"首页标签亮红点" —— 配送员的底栏根本没有「首页」这一项。
     * 所以：站长才给开关；配送员给一句事实说明，不提供无作用的开关。
     */
    isManager: false,
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
    const role = userInfo.role || ''
    const isManager = role === 'STATION_MANAGER' || role === 'manager' || role === 'MANAGER'
    const bindStatus = userInfo.bindStatus
    const cached = userInfo.stationName || wx.getStorageSync('stationName') || ''
    // [2026-09-27 走查 D06 修] 原来缺站名一律兜底成「未分配」—— 而「未分配」是**另一种事实**
    // （这个员工没有水站）。后果：「我的」页显示"已绑定"（那里兜底成「已绑定」），设置页却写
    // "所属水站 未分配"（截图 02 / 03），配送员会以为自己没绑上，跑去重新申请绑定。
    // 现在三个状态分开说：有名字 → 显示名字；已绑定但名字没拿到 → 「名称暂未加载」+ 可重试；
    // 确实没有水站 → 「未绑定水站」。
    const bound = !!(userInfo.stationId || bindStatus === BINDING_STATUS.BOUND
      || bindStatus === BINDING_STATUS.PENDING_UNBIND)
    const stationStateText = cached
      ? cached
      : (bound ? '名称暂未加载' : (bindStatus === BINDING_STATUS.PENDING ? '绑定审核中' : '未绑定水站'))
    this.setData({
      staffId: userInfo.staffId || wx.getStorageSync(STORAGE_KEYS.STAFF_ID) || '',
      stationName: cached,
      stationStateText,
      // 只对"已绑定 + 名字缺失"给重试：没绑定时重试一万次也拿不到名字
      stationNameRetry: !cached && bound,
      isManager,
      reminderEnabled: isReminderEnabled()
    })
  },

  /**
   * [2026-09-27 走查 D06 修] 拉一次真实的所属水站名（`GET /api/stations/mine`，站长与配送员都可调）。
   * 成功就补上名字并落一份本地缓存（其他页面也读 `stationName` 这个键）；
   * 失败**照实说**（保持「名称暂未加载」），不编一个站名、也不退回「未分配」。
   */
  async onRetryStationName() {
    if (this.data.stationNameLoading) return
    this.setData({ stationNameLoading: true })
    try {
      const res = await get(API.STATION_GET)
      const name = res && res.data ? (res.data.name || '') : ''
      if (name) {
        wx.setStorageSync('stationName', name)
        this.setData({ stationName: name, stationStateText: name, stationNameRetry: false })
        wx.showToast({ title: '已取到水站名称', icon: 'success' })
      } else {
        // code=0 但 data 为空 = 这个账号确实没有所属水站（如未选身份的员工会话）
        this.setData({ stationStateText: '未绑定水站', stationNameRetry: false })
        wx.showToast({ title: '这个账号还没有所属水站', icon: 'none' })
      }
    } catch (err) {
      wx.showToast({ title: (err && err.message) || '名称还是没取到，请稍后再试', icon: 'none' })
    } finally {
      this.setData({ stationNameLoading: false })
    }
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
      confirmColor: '#C9764B',
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
      confirmColor: '#B5442C',
      success: (res) => {
        if (res.confirm) {
          app.logout()
        }
      }
    })
  }
})
