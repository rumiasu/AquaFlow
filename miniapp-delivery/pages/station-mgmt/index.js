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
    wx.navigateTo({ url })
  }
})
