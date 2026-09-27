const { getOrders, createPayment } = require('../../api/order')
const { getCustomerId } = require('../../utils/token')
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
    loadError: ''         // 非空 = 这次没取到，渲染成页面顶部的 .load-error 提示条
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

  onPullDownRefresh() {
    this.loadOrders().then(() => wx.stopPullDownRefresh())
  },

  loadOrders() {
    const { currentTab, tabs } = this.data
    const params = {}
    if (currentTab > 0) params.status = tabs[currentTab].status
    // ⚠️ 本函数只加了 loading / loadError 两个展示状态，**取数逻辑（参数、接口、返回结构解析）
    //    一行都没动** —— 这页只有 89 行，顺手重构取数是最容易出事的地方。
    // 加载态只在"页面上还没有东西"时占位（首屏、上一次失败）：本页是 tabBar 页，
    // onShow 每次回来都会重拉，无条件转圈会让已经看到的列表每次闪一下、下拉时还叠两个圈。
    const showLoading = this.data.orders.length === 0
    if (showLoading) this.setData({ loading: true })
    this.setData({ loadError: '' })
    return getOrders(params).then(res => {
      const orders = Array.isArray(res) ? res : (res.data || [])
      this.setData({ orders, loading: false })
    }).catch(err => {
      // 失败必须**留在页面上**（不是一条会消失的 toast）：清空旧数据避免拿上一次页签的
      // 订单冒充本次结果，同时保留可读原因与"怎么重试"。
      const msg = (err && err.message) || '网络异常'
      this.setData({
        orders: [],
        loading: false,
        loadError: '订单没加载出来（' + msg + '），下拉可重试'
      })
    })
  },

  onTabChange(e) {
    const idx = e.currentTarget.dataset.index
    this.setData({ currentTab: idx })
    this.loadOrders()
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
