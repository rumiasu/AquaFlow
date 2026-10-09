const { wxLogin, devLogin } = require('../../api/auth')
const { storage } = require('../../utils/storage')
const { STORAGE_KEYS } = require('../../utils/storage-keys')
const { isReleaseEnv } = require('../../config/api')
const agreementApi = require('../../api/agreements')
const { drafts, validCatalog, loginEvidence, find } = require('../../utils/agreements')

Page({
  data: {
    loading: false,
    agreementCatalog: drafts,
    agreementNotice: drafts.notice,
    // [2026-09-20] 「测试账号直接登录」是否显示。**默认 false、在 onLoad 里再纠正** ——
    // 这样正式版永远不会闪一下这个按钮；开发版/体验版晚一帧出现，肉眼看不出来。
    //
    // 为什么正式版必须藏：它直连 /api/auth/dev-login，而那个端点带 @Profile("!prod")，
    // 正式版**根本不存在** —— 露出来纯属事故级观感（顾客看到"测试账号直接登录"）。
    // 为什么开发版/体验版必须**保留**：真机联调时它是微信登录不通时的唯一退路（AGENTS §9.4）；
    // 判断用 envVersion 而不是 platform === 'devtools'，因为真机预览的 platform 是
    // ios/android —— 按 devtools 判会把真机联调的退路一起藏掉。
    showDevLogin: false
  },

  onLoad() {
    this.setData({ showDevLogin: !isReleaseEnv() })
    this.loadAgreements()
    const accessToken = storage.get(STORAGE_KEYS.ACCESS_TOKEN)
    if (accessToken) {
      wx.switchTab({ url: '/pages/home/index' })
    }
  },

  onShow() { this._agreementHidden = false; this.loadAgreements() },
  onHide() { this._agreementHidden = true },
  onUnload() { this._agreementUnloaded = true },
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
    if (this.data.loading) return
    const agreement = loginEvidence(this.data.agreementCatalog)

    this.setData({ loading: true })

    try {
      // 1. wx.login 拿临时登录凭证 code
      const loginRes = await new Promise((resolve, reject) => {
        wx.login({ success: resolve, fail: reject })
      })

      if (!loginRes.code) {
        wx.showToast({ title: '微信登录失败', icon: 'none' })
        return
      }

      // 2. 把 code 发给后端，后端用 code 换 openid 并查/建用户
      const res = await wxLogin(loginRes.code, agreement)

      if (res && res.data && res.data.accessToken) {
        const data = res.data
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
        wx.showToast({ title: (res && res.message) || '登录失败', icon: 'none' })
      }
    } catch (error) {
      this.loadAgreements()
      console.error('微信登录失败')
      wx.showToast({ title: (error && error.message) || '微信登录失败', icon: 'none', duration: 2500 })
    } finally {
      this.setData({ loading: false })
    }
  },

  // 开发模式登录（跳过微信验证）。同样不再要求先勾协议 —— 见 onWxLogin 上面的说明。
  async onDevLogin() {
    if (this.data.loading) return
    this.setData({ loading: true })

    try {
      const res = await devLogin('测试用户')

      if (res.data && res.data.accessToken) {
        const app = getApp()
        app.setLoginInfo(res.data.accessToken, res.data.refreshToken, {
          nickname: res.data.nickname || '测试用户',
          phone: res.data.phone || ''
        })
        if (res.data.customerId) {
          wx.setStorageSync(STORAGE_KEYS.CUSTOMER_ID, res.data.customerId)
        }

        wx.showToast({ title: '登录成功', icon: 'success' })
        setTimeout(() => {
          wx.switchTab({ url: '/pages/home/index' })
        }, 500)
      } else {
        wx.showToast({ title: res.message || '登录失败', icon: 'none' })
      }
    } catch (error) {
      console.error('开发登录失败')
      wx.showToast({ title: '登录失败: ' + (error.message || '网络错误'), icon: 'none', duration: 3000 })
    } finally {
      this.setData({ loading: false })
    }
  }
})
