// 我的 · Step6 重构（2026-09-12）
// 结构：用户行 → 钱包与桶四格卡 → 账单 / 我的信息 / 服务 三组菜单。
// 数字全部读后端派生口径（余额/押金/水票张数/水桶权益），前端不做业务加减。
const { getBarrelSummary } = require('../../api/barrel')
const { getCustomerStats } = require('../../api/customer')
const { getTicketAccounts } = require('../../api/ticket')
const { getCompanyInfo } = require('../../api/company')
const { stationStorage } = require('../../utils/storage')
const app = getApp()

const fmtMoney = (n) => {
  const v = Number(n) || 0
  return (Math.round(v * 100) / 100).toFixed(v % 1 === 0 ? 0 : 2)
}

Page({
  data: {
    isLogin: false,
    userInfo: null,
    statsText: '',       // 累计 N 单
    balanceText: '0',    // 余额
    depositText: '0',    // 押金（可退口径 = 押金账户余额）
    ticketCount: 0,      // 水票张数 = Σ remainQuantity
    barrelCount: 0,      // 水桶权益
    stationName: '',
    isCompany: false
  },

  onShow() {
    const { isLogin, userInfo } = app.globalData
    const station = stationStorage.get()
    this.setData({
      isLogin,
      userInfo,
      stationName: (station && station.name) || ''
    })
    if (isLogin) {
      this.loadAssets()
    }
  },

  async loadAssets() {
    const stationId = stationStorage.getId()
    // [2026-09-20 真机联调] 四个请求原来各自 `.catch(() => null)` —— 失败被吞成 null，
    // 页面照常显示「余额 ¥0 / 押金 ¥0 / 水票 0 张」，与"这个客户确实没有资产"完全无法区分
    // （AGENTS §8.22）。降级保留（部分成功仍然显示），但失败**必须出声**。
    let failedCount = 0
    const softCatch = () => { failedCount++; return null }
    const [statsRes, summaryRes, ticketsRes, companyRes] = await Promise.all([
      getCustomerStats().catch(softCatch),
      getBarrelSummary(stationId).catch(softCatch),
      getTicketAccounts(stationId).catch(softCatch),
      getCompanyInfo().catch(softCatch)
    ])
    if (failedCount > 0) {
      // 不阻断渲染：没失败的那几项照常显示。文案里带数量，便于区分"全挂了"与"只挂一项"。
      wx.showToast({ title: '有 ' + failedCount + ' 项资产没加载出来，显示的可能是 0', icon: 'none' })
    }

    const patch = {}

    if (statsRes && statsRes.data) {
      patch.balanceText = fmtMoney(statsRes.data.balance)
      patch.statsText = statsRes.data.totalOrders > 0 ? `累计 ${statsRes.data.totalOrders} 单` : ''
    }

    if (summaryRes && summaryRes.data) {
      patch.depositText = fmtMoney(summaryRes.data.depositBalance)
      patch.barrelCount = summaryRes.data.heldBuckets || 0
    }

    if (ticketsRes && ticketsRes.code === 0 && ticketsRes.data) {
      // 水票张数 = Σ 各商品账户 remainQuantity（接口已显式起驼峰别名，直接读）
      patch.ticketCount = ticketsRes.data.reduce((sum, t) => sum + (Number(t.remainQuantity) || 0), 0)
    }

    // 企业资料：仅月结/企业客户显示这行，个人用户不占行
    patch.isCompany = !!(companyRes && companyRes.code === 0 && companyRes.data
      && (companyRes.data.id || companyRes.data.companyName))

    this.setData(patch)
  },

  onLogin() {
    wx.navigateTo({ url: '/pages/login/index' })
  },

  onEditProfile() {
    wx.navigateTo({ url: '/pages/mine/edit' })
  },

  onMenuTap(e) {
    const url = e.currentTarget.dataset.url
    // tabBar 页面必须用 switchTab 跳转，否则会被微信拦截
    const tabPages = ['/pages/home/index', '/pages/order/list', '/pages/mine/index']
    if (tabPages.indexOf(url) >= 0) {
      wx.switchTab({ url })
    } else {
      wx.navigateTo({ url })
    }
  },

  onLogout() {
    wx.showModal({
      title: '提示',
      content: '确定退出登录吗？',
      success: (res) => {
        if (res.confirm) {
          app.clearLoginInfo()
          this.setData({
            isLogin: false,
            userInfo: null,
            statsText: '',
            balanceText: '0',
            depositText: '0',
            ticketCount: 0,
            barrelCount: 0,
            isCompany: false
          })
          wx.showToast({ title: '已退出', icon: 'success' })
        }
      }
    })
  }
})
