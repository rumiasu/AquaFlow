const { getPendingOrders, getDeliveringOrders, getCompletedToday, getTodayStats, acceptOrder, getDeliveredUnpaid, confirmCollection, transferOrder, returnToStation, getStaffList, respondTransfer, getTransferList, getAssignedToMe } = require('../../api/delivery')
const { post } = require('../../utils/request')
const { API } = require('../../config/api')

Page({
  data: {
    activeTab: 'assigned',
    isManager: false,
    stats: {},
    assignedOrders: [],
    deliveringOrders: [],
    completedOrders: [],
    deliveredUnpaidOrders: [],
    incomingTransfers: [],
    staffList: [],
    loading: false,
    showMediateModal: false,
    currentOrderId: null
  },

  onLoad() {},

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    const userInfo = app.globalData.userInfo || {}
    const role = userInfo.role || ''
    // #46: 匹配normalized后的角色值
    const isManager = role === 'STATION_MANAGER' || role === 'manager' || role === 'MANAGER'
    this.setData({ isManager })
    this.loadData()
  },

  onPullDownRefresh() {
    this.loadData().then(() => { wx.stopPullDownRefresh() })
  },

  async loadData() {
    this.setData({ loading: true })
    try {
      const results = await Promise.allSettled([
        getTodayStats(),
        getAssignedToMe(),
        getDeliveringOrders(),
        getCompletedToday(),
        getDeliveredUnpaid(),
        getPendingOrders()
      ])

      const unwrap = (r) => r.status === 'fulfilled' ? r.value : { data: [] }
      const statsRes = unwrap(results[0])
      const assignedRes = unwrap(results[1])
      const deliveringRes = unwrap(results[2])
      const completedRes = unwrap(results[3])
      const unpaidRes = unwrap(results[4])
      const pendingRes = unwrap(results[5])

      // 金额一律取后端 totalAmount。此前按 quantity * (waterTypePrice || productPrice)
      // 前端自算，而这两个单价字段后端从不返回，导致金额恒为 ¥0.00。
      const enrichOrder = (o) => ({
        ...o,
        amountText: `¥${Number(o.totalAmount || 0).toFixed(2)}`,
        // 是否需现场收款、是否已收款：均由后端按 payment_status / payment_method 判定，
        // 前端不再各写一套（此前三处 isOffline 口径互不一致）。
        isOffline: !!o.needCollect,
        isUnpaid: o.payState !== 'PAID'
      })

      const unpaidOrders = (unpaidRes.data || []).map(enrichOrder)

      // 待接单 = 站长已分配给我(未接单) + 本站待分配(用户刚下的单)，去重合并
      const assignedSet = new Set()
      const mergedAssigned = [
        ...(assignedRes.data || []),
        ...(pendingRes.data || [])
      ].filter(o => {
        if (assignedSet.has(o.id)) return false
        assignedSet.add(o.id)
        return true
      }).map(enrichOrder)

      this.setData({
        stats: (statsRes && statsRes.data) || {},
        assignedOrders: mergedAssigned,
        deliveringOrders: (deliveringRes.data || []).map(enrichOrder),
        completedOrders: (completedRes.data || []).map(enrichOrder),
        deliveredUnpaidOrders: unpaidOrders,
        loading: false
      })
    } catch (err) {
      console.error('加载数据失败:', err)
      this.setData({ loading: false })
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    }
  },

  switchTab(e) {
    this.setData({ activeTab: e.currentTarget.dataset.tab })
  },

  onOrderTap(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: `/pages/order/detail?id=${id}` })
  },

  async onAcceptOrder(e) {
    const id = e.currentTarget.dataset.id
    // #47: 防重复点击
    if (this._accepting) return
    this._accepting = true
    wx.showModal({
      title: '确认接单',
      content: '确定接受此配送任务？',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '接单中...' })
          try {
            await acceptOrder(id)
            wx.hideLoading()
            wx.showToast({ title: '接单成功', icon: 'success' })
            this.loadData()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '接单失败', icon: 'none' })
          }
        }
        this._accepting = false
      },
      fail: () => { this._accepting = false }
    })
  },

  onCompleteOrder(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: `/pages/order/complete?id=${id}&from=home` })
  },

  async onConfirmCollection(e) {
    const id = e.currentTarget.dataset.id
    wx.showModal({
      title: '确认收款',
      content: '确认已收到此订单款项？',
      confirmText: '确认收款',
      confirmColor: '#34C759',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '收款确认中...' })
          try {
            await confirmCollection(id)
            wx.hideLoading()
            wx.showToast({ title: '收款成功', icon: 'success' })
            this.loadData()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '收款失败', icon: 'none' })
          }
        }
      }
    })
  },

  onMediateOrder(e) {
    const id = e.currentTarget.dataset.id
    this.setData({ showMediateModal: true, currentOrderId: id })
  },

  onCloseMediateModal() {
    this.setData({ showMediateModal: false, currentOrderId: null })
  },

  async onMediateToColleague() {
    const id = this.data.currentOrderId
    this.setData({ showMediateModal: false })
    const app = getApp()
    const myId = (app.globalData.userInfo || {}).staffId
    let staffList = []
    try {
      const staffRes = await getStaffList((app.globalData.userInfo || {}).stationId)
      staffList = (staffRes.data || []).filter(s => String(s.id) !== String(myId))
    } catch (e) {
      console.error('加载配送员失败:', e)
    }
    if (staffList.length === 0) {
      wx.showModal({ title: '暂无同事', content: '本站暂无其他在职配送员可转单', showCancel: false })
      return
    }
    const itemList = staffList.map(s => s.name || ('配送员' + s.id))
    wx.showActionSheet({
      itemList: itemList,
      success: async (res) => {
        const target = staffList[res.tapIndex]
        wx.showModal({
          title: '转单确认',
          content: `确认将订单转给 ${target.name || '同事'}？需对方确认后生效。`,
          confirmText: '申请转单',
          success: async (modalRes) => {
            if (modalRes.confirm) {
              wx.showLoading({ title: '转单中...' })
              try {
                await transferOrder(id, { deliveryStaffId: target.id, reason: '配送员调解转单' })
                wx.hideLoading()
                wx.showToast({ title: '已申请转单，等待对方确认', icon: 'success' })
                this.loadData()
              } catch (err) {
                wx.hideLoading()
                wx.showToast({ title: err.message || '转单失败', icon: 'none' })
              }
            }
          }
        })
      }
    })
  },

  async onMediateToStation() {
    const id = this.data.currentOrderId
    this.setData({ showMediateModal: false })
    wx.showModal({
      title: '退回站长',
      content: '确定申请退回站长吗？退回后需站长确认，订单将重新分配。',
      confirmText: '申请退回',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '退回中...' })
          try {
            await returnToStation(id, { reason: '配送员调解退回' })
            wx.hideLoading()
            wx.showToast({ title: '已申请退回，等待站长确认', icon: 'success' })
            this.loadData()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '退回失败', icon: 'none' })
          }
        }
      }
    })
  },

  stopPropagation() {}
})
