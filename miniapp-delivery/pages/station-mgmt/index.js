// 水站管理宫格（站长）—— 只做路由，不做任何业务判断。
//
// [2026-09-19 IA 重组] 分区与条目清单见 docs/design/24-站长端IA重组-定稿.md。
//
// ⚠️ 本页有 tabBar 页做目标：「订单管理」→ pages/coordination/index
// （「水站资料」2026-09-19 起指向自己的 station-info 页，不再是 mine）。
// wx.navigateTo 对 tabBar 页会失败（fail 回调里只有一句 errMsg，界面**毫无反应**，
// 站长会以为"点了没反应=坏了"），必须换 wx.switchTab。
// 这里集中分流，不要在 wxml 里给某张卡单独绑另一个方法 —— 下一个加卡的人一定会漏。
const TAB_BAR_PAGES = ['/pages/coordination/index', '/pages/home/index', '/pages/mine/index']

Page({
  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
  },

  onNavigate(e) {
    const url = e.currentTarget.dataset.url
    if (!url) return
    if (TAB_BAR_PAGES.indexOf(url) >= 0) {
      wx.switchTab({
        url,
        // switchTab 失败时给一次出声的兜底，别让它静默（本仓多次踩过"点了没反应"）
        fail: () => wx.showToast({ title: '打开失败，请从底部标签进入', icon: 'none' })
      })
      return
    }
    // ⚠️ 页面栈去重：宫格是**常驻入口**，反复进出同一页（尤其"水站资料"这种办完就回头的页）
    // 会把栈堆到 10 层上限，之后 navigateTo 直接失败、界面**毫无反应**。
    // 栈里已有就回退到它 —— 注意 delta 要**算出来**，不能写死 1：
    // 栈可能是 [宫格, 资料页, 营业状态, 宫格]，此时资料页在 2 层之前，退 1 层会落到"营业状态"。
    const route = url.split('?')[0].replace(/^\//, '')
    const pages = getCurrentPages()
    for (let i = pages.length - 1; i >= 0; i--) {
      if (pages[i] && pages[i].route === route) {
        wx.navigateBack({ delta: pages.length - 1 - i })
        return
      }
    }
    wx.navigateTo({ url })
  }
})
