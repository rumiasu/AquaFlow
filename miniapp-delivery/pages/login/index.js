const { wxLoginStaff, devLogin } = require('../../api/auth')
const { UserInfoKey } = require('../../utils/constant')
const { STORAGE_KEYS } = require('../../utils/storage-keys')
const { isReleaseEnv } = require('../../config/api')

const app = getApp()

Page({
  data: {
    loading: false,
    // [2026-09-20] 「开发者登录」是否显示。默认 false、在 onLoad 里再纠正 ——
    // 正式版永远不会闪一下这个按钮；开发版/体验版晚一帧出现，肉眼看不出来。
    //
    // 为什么正式版必须藏：它直连 /api/auth/dev-login，而该端点带 @Profile("!prod")，
    // 正式版**根本不存在**；露出来既是事故级观感，也等于告诉外人"这里有个后门"。
    // 为什么开发版/体验版必须**保留**：真机联调时它是微信登录不通时的唯一退路（AGENTS §9.4）；
    // 判断用 envVersion 而不是 platform === 'devtools' —— 真机预览的 platform 是
    // ios/android，按 devtools 判会把真机联调的退路一起藏掉。
    showDevLogin: false
  },

  onLoad() {
    this.setData({ showDevLogin: !isReleaseEnv() })
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

  // 微信一键登录：wx.login 静默拿 code → 后端换 openid → 已绑定员工直接登录
  //
  // [2026-09-24 产品要求]「以后点击登录就自动同意协议吧，别再费劲点了」——
  // **取消"必须先勾选才能登录"那道拦截**，改成按钮正下方一行可见的
  // 「登录即代表你已阅读并同意《用户协议》和《隐私政策》」，点按钮本身即表示同意。
  // ⚠️ 这是"点击即同意"（明示告知 + 用户主动点击），与"默认帮你勾上"不是一回事。
  // ⚠️ **那行提示不能删**：没有可见告知就只剩"沉默同意"。顾客端 pages/login 同口径。
  async onWxLogin() {
    if (this.data.loading) return

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

  // 开发者登录（跳过微信验证）。同样不再要求先勾协议 —— 见 onWxLogin 上面的说明。
  onDevLogin() {
    if (this.data.loading) return
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
