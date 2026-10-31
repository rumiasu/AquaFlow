const { showBusinessModal } = require('../../utils/business-dialog')
const { wxLoginStaff, testLogin } = require('../../api/auth')
const { UserInfoKey } = require('../../utils/constant')
const { STORAGE_KEYS } = require('../../utils/storage-keys')
const { isTestLoginClientEnv } = require('../../config/api')
const agreementApi = require('../../api/agreements')
const { drafts, validCatalog, loginEvidence, find } = require('../../utils/agreements')

const app = getApp()

function testLoginMessage(error) {
  const message = error && typeof error.message === 'string' ? error.message : ''
  return /[\u3400-\u9fff]/.test(message) && !/(?:Exception|Error:|\bSQL\b|https?:\/\/|request:fail|errno|stack)/.test(message)
    ? message : '测试登录未完成，请重试'
}

Page({
  data: {
    loading: false,
    agreementCatalog: drafts,
    agreementNotice: drafts.notice,
    // 仅开发/体验版显示账号表单，是否能登录仍由服务端决定。
    showTestLogin: false,
    testAccount: '',
    testPassword: ''
  },

  onLoad() {
    this.setData({ showTestLogin: isTestLoginClientEnv() })
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

  onHide() { this._agreementHidden = true; this.clearTestPassword() },

  onUnload() {
    this._agreementHidden = true
    this._agreementUnloaded = true
    this.clearTestPassword()
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
        wx.showToast({ title: '微信登录未完成，请重试', icon: 'none' })
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
      showBusinessModal({ confirmColor: '#1264D6', cancelColor: '#637083',  confirmText: '知道了', title: '登录失败', content: msg, showCancel: false })
    } finally {
      this.setData({ loading: false })
    }
  },

  onTestAccountInput(e) { this.setData({ testAccount: e.detail.value }) },
  onTestPasswordInput(e) { this.setData({ testPassword: e.detail.value }) },
  clearTestPassword() {
    const pending = this._testLoginFlight
    this._testLoginFlight = null
    this.setData(pending ? { testPassword: '', loading: false } : { testPassword: '' })
  },

  // 不保存密码；离页后丢弃迟到结果，避免旧身份覆盖新的微信登录。
  async onTestLogin() {
    if (this.data.loading || this._loginComplete || !this.data.showTestLogin || !isTestLoginClientEnv()) return
    const account = (this.data.testAccount || '').trim(), password = this.data.testPassword || ''
    if (!account || !password) { wx.showToast({ title: '请输入测试账号和密码', icon: 'none' }); return }
    const flight = this._testLoginFlight = {}
    this.setData({ loading: true, testPassword: '' })
    try {
      const res = await testLogin(account, password)
      if (this._testLoginFlight !== flight || this._agreementHidden || this._agreementUnloaded) return
      if (!res || res.code !== 0 || !res.data || !res.data.accessToken) throw new Error(res && res.message || '测试登录失败，请重试')
      if (res.data.userType !== 'staff') throw new Error('请使用员工测试账号')
      this._loginComplete = true
      this.handleLoginSuccess(res.data)
    } catch (error) {
      if (this._testLoginFlight === flight && !this._agreementHidden && !this._agreementUnloaded)
        wx.showToast({ title: testLoginMessage(error), icon: 'none' })
    } finally {
      if (this._testLoginFlight === flight) { this._testLoginFlight = null; this.setData({ loading: false }) }
    }
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
