const { wxLoginStaff, devLogin } = require('../../api/auth')
const { UserInfoKey } = require('../../utils/constant')
const { STORAGE_KEYS } = require('../../utils/storage-keys')
const { isReleaseEnv } = require('../../config/api')
const agreementApi = require('../../api/agreements')
const { drafts, validCatalog, loginEvidence, find } = require('../../utils/agreements')

const app = getApp()

Page({
  data: {
    loading: false,
    agreementCatalog: drafts,
    agreementNotice: drafts.notice,
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
    this.loadAgreements()
    this.checkAutoLogin()
  },

  async loadAgreements() {
    if (this._catalogFlight || this._agreementUnloaded) return
    this._catalogFlight = true
    try {
      const res = await agreementApi.current()
      if (!validCatalog(res && res.data)) throw Error('协议目录无法核实')
      if (!this._agreementUnloaded && !this._agreementHidden)
        this.setData({ agreementCatalog: res.data, agreementNotice: res.data.notice })
    } catch (error) {
      if (!this._agreementUnloaded && !this._agreementHidden)
        this.setData({ agreementCatalog: drafts, agreementNotice: '协议草稿尚未启用；当前无法核实最新正文，本次登录不会自动记录正式接受。' })
    } finally { this._catalogFlight = false }
  },

  // 2026-10-07：原协议名没有处理器；公开阅读仅导航，不触发登录或记录同意。
  // 原生请求未完成时保留锁，打开后待返回 onShow 再释放，避免连续 tap 堆叠页面。
  onShow() {
    if (this._agreementUnloaded) return
    this._agreementHidden = false
    this.loadAgreements()
    if (this._agreementFlight && this._agreementFlight.finished) this._agreementFlight = null
  },

  onHide() { this._agreementHidden = true },

  onUnload() {
    this._agreementHidden = true
    this._agreementUnloaded = true
    this._agreementFlight = null
  },

  onOpenAgreement(e) {
    if (this.data.loading || this._agreementHidden || this._agreementUnloaded || this._agreementFlight) return
    const value = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.type
    const type = value === 'privacy' ? 'privacy' : 'user'
    const doc = find(this.data.agreementCatalog, type)
    const version = this.data.agreementCatalog.enabled && doc ? '&versionId=' + encodeURIComponent(doc.versionId) : ''
    const flight = this._agreementFlight = { finished: false }
    const success = () => { if (this._agreementFlight === flight) flight.finished = true }
    const fail = () => {
      if (this._agreementFlight !== flight) return
      flight.finished = true
      this._agreementFlight = null
      if (!this._agreementHidden && !this._agreementUnloaded) {
        wx.showToast({ title: '协议暂时无法打开，请重试', icon: 'none' })
      }
    }
    try {
      wx.navigateTo({ url: '/pages/mine/agreement/index?type=' + type + version, success, fail,
        complete: result => { if (result && /:ok$/.test(result.errMsg || '')) success(); else fail() } })
    } catch (err) { fail() }
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
  // 保留2026-09-24取消强制复选框的裁定。草稿未启用时不声称已接受正式文本。
  // 在主动点击时固定实际展示版本，条款接受/隐私告知分别记录，微信授权独立处理。
  async onWxLogin() {
    if (this.data.loading) return
    const agreement = loginEvidence(this.data.agreementCatalog)

    this.setData({ loading: true })

    try {
      const loginRes = await new Promise((resolve, reject) => {
        wx.login({ success: resolve, fail: reject })
      })

      console.log('[wx-login] credential received:', !!loginRes.code)

      if (!loginRes.code) {
        wx.showToast({ title: '微信登录失败：未获取code', icon: 'none' })
        return
      }

      console.log('[wx-login] code获取成功, 发送请求...')
      const res = await wxLoginStaff(loginRes.code, agreement)
      console.log('[wx-login] response accepted:', !!(res && res.data))

      if (res && res.data) {
        this.handleLoginSuccess(res.data)
      } else {
        console.warn('[wx-login] response rejected')
        wx.showToast({ title: (res && res.message) || '登录失败', icon: 'none' })
      }
    } catch (error) {
      this.loadAgreements()
      console.error('[wx-login] login failed')
      const msg = (error && error.message) || '登录失败'
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
    const u = app.setLoginState(payload)
    console.log('[login] state:', { needsRole: !!u.needSelectRole, hasStation: !!u.stationId })

    try {
      wx.setStorageSync(UserInfoKey.ID, u.staffId || '')
      wx.setStorageSync(UserInfoKey.NAME, u.nickname || '')
      wx.setStorageSync(UserInfoKey.ROLE, u.role || '')
      wx.setStorageSync(UserInfoKey.STATION_ID, u.stationId || '')
    } catch (e) { /* ignore */ }

    app.routeByRole(false)
    console.log('[login] routeByRole 已调用')
  }
})
