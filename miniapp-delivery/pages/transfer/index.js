// 转让记录页
const { getTransferRecords } = require('../../api/delivery')

Page({
  data: {
    orders: [],
    loading: false
  },

  onLoad() {
    this.loadData()
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this.loadData()
  },

  onPullDownRefresh() {
    this.loadData().then(() => {
      wx.stopPullDownRefresh()
    })
  },

  // 加载数据
  async loadData() {
    this.setData({ loading: true })

    try {
      const res = await getTransferRecords()
      const orders = (res.data || []).map(order => ({
        ...order,
        updateTime: this.formatTime(order.updateTime)
      }))
      this.setData({ orders, loading: false })
    } catch (err) {
      console.error('加载转让记录失败:', err)
      this.setData({ loading: false })
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    }
  },

  // 点击订单卡片
  onOrderTap(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: `/pages/order/detail?id=${id}` })
  },

  // 格式化时间
  formatTime(timeStr) {
    if (!timeStr) return ''
    const date = new Date(timeStr)
    const month = (date.getMonth() + 1).toString().padStart(2, '0')
    const day = date.getDate().toString().padStart(2, '0')
    const hour = date.getHours().toString().padStart(2, '0')
    const minute = date.getMinutes().toString().padStart(2, '0')
    return `${month}-${day} ${hour}:${minute}`
  }
})
