const { wxLogin, devLogin } = require('../../api/auth')
const { storage } = require('../../utils/storage')
const { STORAGE_KEYS } = require('../../utils/storage-keys')
const { isReleaseEnv } = require('../../config/api')

Page({
  data: {
    loading: false,
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
    const accessToken = storage.get(STORAGE_KEYS.ACCESS_TOKEN)
    if (accessToken) {
      wx.switchTab({ url: '/pages/home/index' })
    }
  },

  // 微信一键登录：wx.login 静默拿 code → 后端换 openid 自动注册/登录
  //
  // [2026-09-24 产品要求]「以后点击登录就自动同意协议吧，别再费劲点了」——
  // **取消了"必须先勾选才能登录"那道拦截**（原来 `if (!agreed) { toast; return }`），
  // 改成按钮下方一行可见的「登录即代表你已阅读并同意《用户协议》和《隐私政策》」：
  // 点按钮这个动作本身即表示同意。
  // ⚠️ 这是"点击即同意"（明示告知 + 用户主动点击），与"默认帮你勾上"不是一回事 ——
  //    后者才是审核会挑的形态。**别把下面那行提示也删掉**：没有可见告知就只剩"沉默同意"了。
  // ⚠️ 已知缺口（本次没做）：两个协议链接目前是**纯文本、点了没反应**（没有对应页面，
  //    也没有 handler）。合规上应当能点开全文，需要产品先给协议正文与页面路径。
  async onWxLogin() {
    if (this.data.loading) return

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
      const res = await wxLogin(loginRes.code)

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
      console.error('微信登录失败:', error)
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
      console.error('开发登录失败:', error)
      wx.showToast({ title: '登录失败: ' + (error.message || '网络错误'), icon: 'none', duration: 3000 })
    } finally {
      this.setData({ loading: false })
    }
  }
})
