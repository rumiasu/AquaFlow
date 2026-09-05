const { wxLoginStaff, devLogin } = require('../../api/auth')
const { UserInfoKey } = require('../../utils/constant')
const { STORAGE_KEYS } = require('../../utils/storage-keys')

const app = getApp()

Page({
  data: {
    loading: false,
    agreed: false
  },

  onLoad() {
    this.checkAutoLogin()
  },

  checkAutoLogin() {
    const token = wx.getStorageSync(STORAGE_KEYS.ACCESS_TOKEN)
    if (!token) return
    // V1 关键：绝不能直接跳首页！必须先经过 routeByRole 做身份+绑定状态判定
    //   例如旧 token 对应的角色是 UNSELECTED → 必须回到角色选择
    //   站长未建站 → 去 create-station
    //   配送员未绑定 → 去 apply-bind / bind-wait
    try {
      const raw = wx.getStorageSync(STORAGE_KEYS.USER_INFO) || {}
      app.setLoginState({
        accessToken: token,
        refreshToken: wx.getStorageSync(STORAGE_KEYS.REFRESH_TOKEN),
        ...raw
      })
    } catch (e) { /* ignore */ }
    app.routeByRole(false)
  },

  // 协议勾选切换
  onToggleAgreement() {
    this.setData({ agreed: !this.data.agreed })
  },

  // 微信一键登录：wx.login 静默拿 code → 后端换 openid → 已绑定员工直接登录
  async onWxLogin() {
    if (this.data.loading) return

    if (!this.data.agreed) {
      wx.showToast({ title: '请先阅读并同意用户协议和隐私政策', icon: 'none', duration: 2500 })
      return
    }

    this.setData({ loading: true })

    try {
      const loginRes = await new Promise((resolve, reject) => {
        wx.login({ success: resolve, fail: reject })
      })

      console.log('[wx-login] wx.login result:', loginRes)

      if (!loginRes.code) {
        wx.showToast({ title: '微信登录失败：未获取code', icon: 'none' })
        return
      }

      console.log('[wx-login] code获取成功, 发送请求...')
      const res = await wxLoginStaff(loginRes.code)
      console.log('[wx-login] 后端响应:', JSON.stringify(res))

      if (res && res.data) {
        console.log('[wx-login] 登录成功, 开始跳转, data:', JSON.stringify(res.data))
        this.handleLoginSuccess(res.data)
      } else {
        console.warn('[wx-login] 响应异常:', res)
        wx.showToast({ title: (res && res.message) || '登录失败', icon: 'none' })
      }
    } catch (error) {
      console.error('[wx-login] 微信登录异常:', error)
      const msg = (error && error.message) || '登录失败'
      console.error('[wx-login] 错误消息:', msg)
      wx.showModal({ title: '登录失败', content: msg, showCancel: false })
    } finally {
      this.setData({ loading: false })
    }
  },

  onDevLogin() {
    if (this.data.loading) return
    if (!this.data.agreed) {
      wx.showToast({ title: '请先阅读并同意用户协议和隐私政策', icon: 'none', duration: 2500 })
      return
    }
    this.setData({ loading: true })

    devLogin('DELIVERY').then(result => {
      if (result.code === 200 || result.code === 0) {
        this.handleLoginSuccess(result.data)
      } else {
        wx.showToast({ title: result.message || '登录失败', icon: 'error' })
      }
    }).catch(err => {
      wx.showToast({ title: err.message || '登录失败', icon: 'error' })
    }).finally(() => {
      this.setData({ loading: false })
    })
  },

  handleLoginSuccess(data) {
    const payload = data || {}
    console.log('[login] handleLoginSuccess payload:', JSON.stringify(payload))
    const u = app.setLoginState(payload)
    console.log('[login] setLoginState 完成, userInfo:', JSON.stringify(u))

    try {
      wx.setStorageSync(UserInfoKey.ID, u.staffId || '')
      wx.setStorageSync(UserInfoKey.NAME, u.nickname || '')
      wx.setStorageSync(UserInfoKey.ROLE, u.role || '')
      wx.setStorageSync(UserInfoKey.STATION_ID, u.stationId || '')
    } catch (e) { /* ignore */ }

    console.log('[login] 即将 routeByRole, role=' + u.role + ', needSelectRole=' + u.needSelectRole + ', stationId=' + u.stationId)
    app.routeByRole(false)
    console.log('[login] routeByRole 已调用')
  }
})
