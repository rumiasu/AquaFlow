const { getPendingOrders, getDeliveringOrders, getCompletedToday, acceptOrder, getDeliveredUnpaid, confirmCollection, transferOrder, returnToStation, getStaffList, respondTransfer, getTransferList, getAssignedToMe } = require('../../api/delivery')
// 楼层/电梯文案与订单详情页共用同一份实现（口径只有一处）
const { buildFloorText } = require('../../utils/address')
// 自绘导航栏 + 水站营业状态胶囊（本页 navigationStyle=custom）：与「首页」共用一份实现
// —— 结构与样式见 templates/station-navbar.wxml、styles/station-navbar.wxss
const stationNavbar = require('../../behaviors/stationNavbar')

Page({
  behaviors: [stationNavbar],

  data: {
    activeTab: 'assigned',
    isManager: false,
    assignedOrders: [],
    deliveringOrders: [],
    completedOrders: [],
    deliveredUnpaidOrders: [],
    incomingTransfers: [],
    staffList: [],
    loading: false,
    // 部分列表接口失败时的提示文案（空串 = 全部正常）。见 loadData 里的说明。
    loadError: '',
    showMediateModal: false,
    currentOrderId: null
  },

  onLoad() {
    // 自绘导航栏尺寸先算好再渲染，避免状态胶囊闪一下（实现来自 behaviors/stationNavbar.js）
    this.initNavMetrics()
  },

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
    // 营业状态跟着首页刷新：站长刚改成"休息中"，配送员回到这页就该看到
    // （软状态 v32：只提示不阻断；实现与「首页」共用，见 behaviors/stationNavbar.js）
    this.loadStationStatus(userInfo.stationId)
    this.loadData()
  },

  onPullDownRefresh() {
    this.loadData().then(() => { wx.stopPullDownRefresh() })
  },

  async loadData() {
    this.setData({ loading: true })
    try {
      // ⚠️ [2026-09-19 删除] 这里原先还调 `getTodayStats()`（/api/delivery/stats/today）并把结果写进
      // `data.stats` —— 而本页 wxml **从来没有读过 `stats`**（上面看板用的是三个列表的 .length）。
      // 也就是说每次进「配送」页都白发一次请求。删掉它，页面上的数字一个都不会变。
      // 证据见 docs/audit/2026-09-16-死端点评估.md「删除登记表」#10。
      const results = await Promise.allSettled([
        getAssignedToMe(),
        getDeliveringOrders(),
        getCompletedToday(),
        getDeliveredUnpaid(),
        getPendingOrders()
      ])

      const unwrap = (r) => r.status === 'fulfilled' ? r.value : { data: [] }
      // [2026-09-20 真机联调] 原来 unwrap 把「失败」静默折成「空列表」，于是外层 catch
      // **永远不会触发** —— 后端没起 / 手机换了网时，配送员看到的是 4 个空白列表，
      // 与"今天确实没有单"完全无法区分（正是 AGENTS §8.22 描述的形状）。
      // 现在把失败项数记下来，由 wxml 显式提示；列表照常渲染（部分成功仍然有用）。
      const failedCount = results.filter(r => r.status === 'rejected').length
      if (failedCount) {
        console.error('[home] 有 ' + failedCount + ' 个列表接口失败：',
          results.filter(r => r.status === 'rejected').map(r => r.reason))
      }
      const assignedRes = unwrap(results[0])
      const deliveringRes = unwrap(results[1])
      const completedRes = unwrap(results[2])
      const unpaidRes = unwrap(results[3])
      const pendingRes = unwrap(results[4])

      // 金额一律取后端 totalAmount。此前按 quantity * (waterTypePrice || productPrice)
      // 前端自算，而这两个单价字段后端从不返回，导致金额恒为 ¥0.00。
      const enrichOrder = (o) => ({
        ...o,
        amountText: `¥${Number(o.totalAmount || 0).toFixed(2)}`,
        // 是否需现场收款、是否已收款：均由后端按 payment_status / payment_method 判定，
        // 前端不再各写一套（此前三处 isOffline 口径互不一致）。
        isOffline: !!o.needCollect,
        isUnpaid: o.payState !== 'PAID',
        // 楼层/电梯：配送员出车前要知道这一单要不要上楼。
        // 文案口径与订单详情页共用 utils/address.buildFloorText（只有一处实现）；
        // 后端只有「我的配送中」这条查询带了这两个字段，没有时它是空串、整行不显示。
        floorText: buildFloorText(o)
      })

      const unpaidOrders = (unpaidRes.data || []).map(enrichOrder)

      // 「待配送」页签 = status 1（后端状态名就叫待配送）：
      //   ① 站长已分配给我、我还没接单的；② 本站还没派出去的单（用户刚下的）。
      // 两支去重合并 —— 站长自己也会接单，所以这两支对站长来说是同一件事。
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
        assignedOrders: mergedAssigned,
        deliveringOrders: (deliveringRes.data || []).map(enrichOrder),
        completedOrders: (completedRes.data || []).map(enrichOrder),
        deliveredUnpaidOrders: unpaidOrders,
        loadError: failedCount
          ? '有 ' + failedCount + ' 项没加载出来（网络或后端异常），下面列表可能不完整'
          : '',
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
    // [2026-09-20] 原来失败只 console.error，随后照旧拿空列表往下走 —— 于是"接口挂了/断网"
    // 被显示成「本站暂无其他在职配送员可转单」，把人往错误方向带（AGENTS §8.17 的判据：
    // 「用户以为做成了、账上没动」与「用户以为没数据、其实没查到」都算缺陷，宁可失败出声）。
    let loadError = ''
    try {
      const staffRes = await getStaffList((app.globalData.userInfo || {}).stationId)
      staffList = (staffRes.data || []).filter(s => String(s.id) !== String(myId))
    } catch (e) {
      loadError = (e && e.message) || '网络异常'
      console.error('加载配送员失败:', e)
    }
    if (loadError) {
      wx.showModal({
        title: '加载失败',
        content: '没能取到同事名单（' + loadError + '），请稍后重试',
        showCancel: false
      })
      return
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
