const { wxLogin, testLogin } = require('../../api/auth')
const { storage } = require('../../utils/storage')
const { STORAGE_KEYS } = require('../../utils/storage-keys')
const { isTestLoginClientEnv } = require('../../config/api')
const agreementApi = require('../../api/agreements')
const { drafts, validCatalog, loginEvidence, find } = require('../../utils/agreements')

// 只清理错误的展示文字；原对象的 code、resultUnknown、businessRejected 均不改动。
function viewError(error, fallback = '暂时无法完成，请稍后重试') {
  const text = error && typeof error.message === 'string' ? error.message.trim() : ''
  return /[\u3400-\u9fff]/.test(text)
    && !/(?:Exception|Error:|\bat\s+\S+\(|\bSQL\b|https?:\/\/|request:fail|errno|stack|[A-Z][A-Z_]{5,})/.test(text)
    ? text : fallback
}

Page({
  data: {
    loading: false,
    loginError: '',
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
    const accessToken = storage.get(STORAGE_KEYS.ACCESS_TOKEN)
    if (accessToken) {
      wx.switchTab({ url: '/pages/home/index' })
    }
  },

  onShow() { this._agreementHidden = false; this.loadAgreements() },
  onHide() { this._agreementHidden = true; this.clearTestPassword() },
  onUnload() { this._agreementUnloaded = true; this.clearTestPassword() },
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
        this.setData({ agreementCatalog: drafts, agreementNotice: '最新协议暂未加载，本次登录不会记录正式协议接受。' })
    } finally { this._catalogFlight = false }
  },

  // [2026-10-07 评审 #7] 协议名跳转承载页（占位草稿，页内有显著告示；
  // 正式正文与版本化同意流程见 docs/design/36 §5.5 的交接清单）。
  onOpenAgreement(e) {
    if (this.data.loading || this._agreementUnloaded || this._agreementHidden) return
    const value = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.type
    const type = value === 'privacy' ? 'privacy' : 'user'
    const doc = find(this.data.agreementCatalog, type)
    const version = this.data.agreementCatalog.enabled && doc ? '&versionId=' + encodeURIComponent(doc.versionId) : ''
    wx.navigateTo({ url: '/pages/mine/agreement/index?type=' + type + version })
  },

  // 微信一键登录：wx.login 静默拿 code → 后端换 openid 自动注册/登录
  //
  // 保留2026-09-24取消强制复选框的裁定。当前草稿不声称已被接受。
  // 只有服务端已启用的正文可在主动登录点击时携带固定版本；不代表所有处理或微信权限已获同意。
  async onWxLogin() {
    if (this.data.loading || this._loginComplete) return
    const agreement = loginEvidence(this.data.agreementCatalog)

    this.setData({ loading: true, loginError: '' })

    try {
      // 1. wx.login 拿临时登录凭证 code
      const loginRes = await new Promise((resolve, reject) => {
        wx.login({ success: resolve, fail: reject })
      })

      if (!loginRes.code) {
        this.setData({ loginError: '微信暂时未能完成登录，请稍后手动重试。' })
        return
      }

      // 2. 把 code 发给后端，后端用 code 换 openid 并查/建用户
      const res = await wxLogin(loginRes.code, agreement)

      if (res && res.data && res.data.accessToken) {
        const data = res.data
        this._loginComplete = true
        const app = getApp()
        app.setLoginInfo(data.accessToken, data.refreshToken, {
          nickname: data.nickname || '微信用户',
          phone: data.phone || ''
        })
        if (data.customerId) {
          wx.setStorageSync(STORAGE_KEYS.CUSTOMER_ID, data.customerId)
        }

        wx.showToast({ title: '登录成功', icon: 'success' })
        setTimeout(() => {
          wx.switchTab({ url: '/pages/home/index' })
        }, 800)
      } else {
        this.setData({ loginError: viewError(res, '暂时无法登录，请稍后重试。') })
      }
    } catch (error) {
      this.loadAgreements()
      console.error('微信登录失败')
      this.setData({ loginError: viewError(error, '微信登录未完成，请检查网络后手动重试。') })
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
      if (res.data.userType !== 'customer') throw new Error('请使用顾客测试账号')
      this._loginComplete = true
      const app = getApp()
      app.setLoginInfo(res.data.accessToken, res.data.refreshToken, { nickname: res.data.nickname || '', phone: res.data.phone || '' })
      wx.setStorageSync(STORAGE_KEYS.CUSTOMER_ID, res.data.customerId)
      wx.switchTab({ url: '/pages/home/index' })
    } catch (error) {
      if (this._testLoginFlight === flight && !this._agreementHidden && !this._agreementUnloaded)
        wx.showToast({ title: viewError(error, '测试登录未完成，请重试'), icon: 'none' })
    } finally {
      if (this._testLoginFlight === flight) { this._testLoginFlight = null; this.setData({ loading: false }) }
    }
  },

})
