const { getOrders, createPayment } = require('../../api/order')
const { getCustomerId, captureSession, isCurrentSession } = require('../../utils/token')
const { notifyPayResult } = require('../../utils/pay')
const app = getApp()

Page({
  data: {
    currentTab: 0,
    tabs: [
      { name: '全部', status: 0 },
      { name: '待配送', status: 1 },
      { name: '配送中', status: 2 },
      { name: '已送达', status: 3 },
      { name: '已完成', status: 4 },
      // P6: 抢单池tab已冻结 — 客户选站后不再有"待认领"状态
      // { name: '待认领', status: 7 },
      { name: '已取消', status: 5 }
    ],
    orders: [],
    // ===== [2026-09-26] 「没有订单」与「没加载出来」必须不同（设计 29 §2）=====
    // 此前只有 wx.showToast：toast 一消失，屏幕上是空列表 + empty「暂无订单」，
    // 与"确实没有订单"完全无法区分（AGENTS §8.22 的同一类坑）。
    loading: true,        // 页面还没有数据时的加载态（首屏 / 上次失败后的重试）
    loadError: '',        // 非空 = 这次没取到，渲染成页面顶部的 .load-error 提示条
    page: 0,
    pageSize: 20,
    hasMore: false,
    loadingMore: false
  },

  onLoad() {
    if (!app.globalData.isLogin) {
      // #41: tabBar页面用reLaunch而非redirectTo
      wx.reLaunch({ url: '/pages/login/index' })
      return
    }
    this.loadOrders()
  },
  onShow() { this.loadOrders() },

  async onPullDownRefresh() {
    try {
      await this.loadOrders()
    } finally {
      wx.stopPullDownRefresh()
    }
  },

  onReachBottom() { return this.loadOrders(true) },

  onUnload() {
    this._destroyed = true
    this._readVersion = (this._readVersion || 0) + 1
  },

  loadOrders(append = false) {
    if (this._destroyed) return Promise.resolve()
    const { currentTab, tabs } = this.data
    const status = currentTab > 0 && tabs[currentTab] ? tabs[currentTab].status : undefined
    const session = captureSession()
    const sameQuery = this._listSession && isCurrentSession(this._listSession) && this._listStatus === status
    if (!session.loggedIn || !session.customerId) {
      this._readVersion = (this._readVersion || 0) + 1
      this.setData({ orders: [], page: 0, hasMore: false, loading: false, loadingMore: false, loadError: '' })
      return Promise.resolve()
    }
    if (append && (!sameQuery || !this.data.hasMore || !this.data.page || this.data.loading || this.data.loadingMore)) return Promise.resolve()
    const page = append ? this.data.page + 1 : 1
    const params = { page, pageSize: this.data.pageSize }
    if (status !== undefined) params.status = status
    const version = this._readVersion = (this._readVersion || 0) + 1
    this._listSession = session
    this._listStatus = status
    if (append) {
      this.setData({ loadingMore: true, loadError: '' })
    } else {
      // 跨筛选/登录不能把上一份列表留在新查询下；同查询刷新可保留已读内容。
      const orders = sameQuery ? this.data.orders : []
      this.setData({ orders, page: 0, hasMore: false, loading: orders.length === 0, loadingMore: false, loadError: '' })
    }
    const current = () => !this._destroyed && version === this._readVersion && isCurrentSession(session)
      && this.data.currentTab === currentTab
    return getOrders(params).then(res => {
      if (!current()) return
      const rows = Array.isArray(res) ? res : res && res.data
      if (!Array.isArray(rows)) throw new Error('收到的数据不完整，请重试')
      const orders = append ? this.data.orders.slice() : []
      const ids = new Set(orders.map(order => order.id))
      rows.forEach(order => {
        if (!ids.has(order.id)) { orders.push(order); ids.add(order.id) }
      })
      this.setData({ orders, page, hasMore: rows.length === params.pageSize, loading: false, loadingMore: false })
    }).catch(err => {
      if (!current()) return
      const msg = (err && err.message) || '网络异常'
      if (append) {
        // 下一页失败保留已经读到的页码；重试仍请求同一页。
        this.setData({ loadingMore: false, loadError: '更多订单没加载出来（' + msg + '），继续上滑可重试' })
      } else {
        this.setData({ orders: [], page: 0, hasMore: false, loading: false, loadingMore: false,
          loadError: '订单没加载出来（' + msg + '），下拉可重试' })
      }
    }).finally(() => {
      // 身份/筛选直接改变而未开始下一次查询时，也要收尾旧加载态并清除旧资料。
      if (!this._destroyed && version === this._readVersion && !current()) {
        this.setData({ orders: [], page: 0, hasMore: false, loading: false, loadingMore: false, loadError: '' })
      }
    })
  },

  onTabChange(e) {
    const idx = e.currentTarget.dataset.index
    this.setData({ currentTab: idx })
    return this.loadOrders()
  },

  onReorder(e) {
    const { order } = e.detail
    wx.navigateTo({ url: `/pages/order/create?reorderId=${order.id}` })
  },

  onCancel(e) {
    // OrderCard 已处理取消逻辑，这里刷新列表
    this.loadOrders()
  },

  // 订单列表中的"去支付/重新支付"入口
  async onPay(e) {
    const { order } = e.detail
    if (!order) return
    try {
      const res = await createPayment({
        orderId: order.id,
        customerId: getCustomerId(),
        amount: order.totalAmount || order.amount,
        paymentMethod: order.paymentMethod || 1,
        waterAmount: 0,
        barrelDeposit: 0,
        extraDepositBuckets: 0,
        extraDepositAmount: 0,
        ticketProductId: null,
        ticketQty: null
      })
      // 按真实支付状态提示，不再无条件报"支付成功"
      notifyPayResult(res && res.data)
      this.loadOrders()
    } catch (err) {
      wx.showToast({ title: err.message || '支付失败', icon: 'none' })
    }
  }
})
