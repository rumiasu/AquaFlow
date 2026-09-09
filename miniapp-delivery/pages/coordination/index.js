const { get, post } = require('../../utils/request')
const { API } = require('../../config/api')
const { getStaffList, getPoolOrders, claimPoolOrder, getDispatchTracking, cancelDispatch, approveDirectedReturn, rejectDirectedReturn, getDirectedIncoming, approveStaffReturn, rejectStaffReturn } = require('../../api/delivery')

// 转单中标记：站间转单 vs 配送员转单（退回站长/转让/重分配）
const DIRECTED_MARK = '[指定退回待确认]'
const STAFF_MARKS = ['[退回站长]', '[转让]', '[重分配]']

Page({
  data: {
    isManager: false,
    activeTab: 'pending',
    tabs: [
      { key: 'pending', label: '待分配', count: 0 },
      { key: 'pool', label: '抢单池', count: 0 },
      { key: 'dispatch', label: '外派', count: 0 },
      { key: 'incoming', label: '他站外派', count: 0 }
    ],
    lists: {
      pending: [],
      pool: [],
      dispatch: [],
      incoming: []
    },
    staffList: [],
    showAssignModal: false,
    currentOrderId: null
  },

  checkRole() {
    const app = getApp()
    const userInfo = app.globalData.userInfo || {}
    const role = userInfo.role || ''
    const isManager = role === 'STATION_MANAGER' || role === 'manager'
    this.setData({ isManager })
    return isManager
  },

  onLoad() {
    if (!this.checkRole()) {
      wx.switchTab({ url: '/pages/home/index' })
      return
    }
    wx.setNavigationBarTitle({ title: '首页' })
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    if (this.checkRole()) {
      this.loadAllData()
    }
  },

  onPullDownRefresh() {
    if (this.checkRole()) {
      this.loadAllData().then(() => { wx.stopPullDownRefresh() })
    } else {
      wx.stopPullDownRefresh()
    }
  },

  async loadAllData() {
    wx.showLoading({ title: '加载中...' })
    try {
      const app = getApp()
      const userInfo = app.globalData.userInfo || {}
      const stationId = userInfo.stationId

      // 并行加载3个tab数据
      // 待分配 = station-pending（未分配的）+ station-transfer（转单请求，合并进来）
      const [pendingRes, transferRes, poolRes, dispatchRes, incomingRes] = await Promise.all([
        get(API.DELIVERY_ORDERS + '/station-pending'),
        get(API.DELIVERY_ORDERS + '/station-transfer'),
        getPoolOrders(),
        getDispatchTracking(),
        getDirectedIncoming()
      ])

      let staffList = []
      if (stationId) {
        try {
          const staffRes = await getStaffList(stationId)
          staffList = staffRes.data || []
        } catch (e) { console.error('加载配送员失败:', e) }
      }

      // 待分配：合并未分配 + 转单请求（含「转单中」订单）
      // 状态检测：special_note 带 [指定退回待确认] => 转单中，前端渲染「同意/拒绝」而非「分配/外派」
      const pendingList = [
        ...(pendingRes.data || []),
        ...(transferRes.data || [])
      ].map(o => {
        const note = o.specialNote || o.special_note || ''
        let transferKind = ''
        if (note.indexOf(DIRECTED_MARK) >= 0) transferKind = 'directed'
        else if (STAFF_MARKS.some(m => note.indexOf(m) >= 0)) transferKind = 'staff'
        return { ...o, transferPending: transferKind !== '', transferKind }
      })

      const lists = {
        pending: pendingList,
        pool: poolRes.data || [],
        dispatch: dispatchRes.data || [],
        incoming: incomingRes.data || []
      }

      const tabs = this.data.tabs.map(t => ({
        ...t,
        count: (lists[t.key] || []).length
      }))

      this.setData({ lists, tabs, staffList })
      wx.hideLoading()
    } catch (err) {
      console.error('加载数据失败:', err)
      wx.hideLoading()
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

  onShowAssign(e) {
    this.setData({
      showAssignModal: true,
      currentOrderId: e.currentTarget.dataset.id
    })
  },

  async onConfirmAssign(e) {
    const staffId = e.currentTarget.dataset.id
    const name = e.currentTarget.dataset.name
    const orderId = this.data.currentOrderId

    wx.showLoading({ title: '分配中...' })
    try {
      await post(`${API.DELIVERY_ASSIGN}/${orderId}`, { deliveryStaffId: staffId })
      wx.hideLoading()
      wx.showToast({ title: `已分配给 ${name}`, icon: 'success' })
      this.setData({ showAssignModal: false, currentOrderId: null })
      this.loadAllData()
    } catch (err) {
      wx.hideLoading()
      wx.showToast({ title: err.message || '分配失败', icon: 'none' })
    }
  },

  // 外派：从待分配tab触发，可选「放入抢单池」或「指定水站外派」
  onOutsource(e) {
    const id = e.currentTarget.dataset.id
    wx.showActionSheet({
      itemList: ['放入抢单池', '指定水站外派'],
      success: async (res) => {
        if (res.tapIndex === 0) {
          wx.showModal({
            title: '外派抢单池',
            content: '将此订单放入抢单池，附近水站可抢单配送。是否继续？',
            confirmText: '确认外派',
            success: async (m) => { if (m.confirm) await this._doOutsource(id, null) }
          })
        } else if (res.tapIndex === 1) {
          this._pickStationAndOutsource(id)
        }
      }
    })
  },

  // 指定水站外派：拉取其他营业中的水站并选择
  async _pickStationAndOutsource(id) {
    const app = getApp()
    const myStationId = (app.globalData.userInfo || {}).stationId
    wx.showLoading({ title: '加载水站...' })
    try {
      const res = await get(API.STATION_SEARCH, {})
      wx.hideLoading()
      const stationList = (res.data || []).filter(s => s.id !== myStationId && s.status === 1)
      if (stationList.length === 0) {
        wx.showModal({ title: '无可外派水站', content: '暂无其他营业中的水站可外派', showCancel: false })
        return
      }
      const itemList = stationList.map(s => s.name || ('水站' + s.id))
      wx.showActionSheet({
        itemList: itemList,
        success: async (r) => {
          const target = stationList[r.tapIndex]
          wx.showModal({
            title: '外派确认',
            content: `将订单外派给「${target.name}」配送。确定吗？`,
            confirmText: '确定外派',
            confirmColor: '#34C759',
            success: async (m) => { if (m.confirm) await this._doOutsource(id, target.id) }
          })
        }
      })
    } catch (err) {
      wx.hideLoading()
      wx.showToast({ title: err.message || '加载水站失败', icon: 'none' })
    }
  },

  async _doOutsource(id, targetStationId) {
    wx.showLoading({ title: '外派中...' })
    try {
      const body = targetStationId != null ? { targetStationId } : {}
      await post(`${API.DELIVERY_TRANSFER}/${id}/outsource`, body)
      wx.hideLoading()
      wx.showToast({ title: targetStationId != null ? '已指定水站外派' : '已放入抢单池', icon: 'success' })
      this.loadAllData()
    } catch (err) {
      wx.hideLoading()
      wx.showToast({ title: err.message || '外派失败', icon: 'none' })
    }
  },

  // 抢单池 - 跳过
  onSkipPool(e) {
    const id = e.currentTarget.dataset.id
    const pool = this.data.lists.pool.filter(item => item.id !== id)
    this.setData({ 'lists.pool': pool })
  },

  // 抢单池 - 抢单
  onClaimPool(e) {
    const id = e.currentTarget.dataset.id
    const colleagues = this.data.staffList || []
    if (colleagues.length === 0) {
      wx.showModal({ title: '暂无配送员', content: '请先添加配送员', showCancel: false })
      return
    }
    const itemList = colleagues.map(s => s.name || ('配送员' + s.id))
    wx.showActionSheet({
      itemList: itemList,
      success: async (res) => {
        const target = colleagues[res.tapIndex]
        wx.showModal({
          title: '抢单确认',
          content: `确认抢单并分配给 ${target.name || '配送员'}？`,
          confirmText: '确认抢单',
          success: async (modalRes) => {
            if (modalRes.confirm) {
              wx.showLoading({ title: '抢单中...' })
              try {
                await claimPoolOrder(id, { deliveryStaffId: target.id })
                wx.hideLoading()
                wx.showToast({ title: '抢单成功', icon: 'success' })
                this.loadAllData()
              } catch (err) {
                wx.hideLoading()
                wx.showToast({ title: err.message || '抢单失败', icon: 'none' })
              }
            }
          }
        })
      }
    })
  },

  // 外派追踪 - 取消外派
  async onCancelDispatch(e) {
    const id = e.currentTarget.dataset.id
    wx.showModal({
      title: '取消外派',
      content: '确定取消此订单的外派？订单将恢复为本站待分配。',
      confirmText: '确认取消',
      confirmColor: '#FF3B30',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '取消中...' })
          try {
            await cancelDispatch(id)
            wx.hideLoading()
            wx.showToast({ title: '已取消外派', icon: 'success' })
            this.loadAllData()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '取消失败', icon: 'none' })
          }
        }
      }
    })
  },

  // 外派追踪 - 重新外派
  onReDispatch(e) {
    const id = e.currentTarget.dataset.id
    const app = getApp()
    const myStationId = (app.globalData.userInfo || {}).stationId

    let stationList = []
    get(API.STATION_SEARCH, {}).then(res => {
      stationList = (res.data || []).filter(s => s.id !== myStationId && s.status === 1)
      if (stationList.length === 0) {
        wx.showModal({ title: '无可外派水站', content: '暂无其他营业中的水站可外派', showCancel: false })
        return
      }
      const itemList = stationList.map(s => s.name || ('水站' + s.id))
      wx.showActionSheet({
        itemList: itemList,
        success: async (res) => {
          const targetStation = stationList[res.tapIndex]
          wx.showModal({
            title: '外派确认',
            content: `将订单外派给「${targetStation.name}」配送。确定吗？`,
            confirmText: '确定外派',
            confirmColor: '#34C759',
            success: async (modalRes) => {
              if (modalRes.confirm) {
                wx.showLoading({ title: '外派中...' })
                try {
                  await post(`${API.DELIVERY_ORDERS}/${id}/dispatch`, {
                    targetStationId: targetStation.id,
                    reason: '站长重新外派'
                  })
                  wx.hideLoading()
                  wx.showToast({ title: '外派成功', icon: 'success' })
                  this.loadAllData()
                } catch (err) {
                  wx.hideLoading()
                  wx.showToast({ title: err.message || '外派失败', icon: 'none' })
                }
              }
            }
          })
        }
      })
    })
  },

  // 转单中 - 站长「同意」=> 变回普通待分配（可分配配送员/外派）
  async onApproveReturn(e) {
    const id = e.currentTarget.dataset.id
    const api = e.currentTarget.dataset.kind === 'directed' ? approveDirectedReturn : approveStaffReturn
    wx.showModal({
      title: '同意转单',
      content: '同意后订单将变为普通待分配状态，届时可分配配送员或外派。',
      confirmText: '同意',
      confirmColor: '#34C759',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' })
          try {
            await api(id)
            wx.hideLoading()
            wx.showToast({ title: '已同意，订单已退回待分配', icon: 'success' })
            this.loadAllData()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '操作失败', icon: 'none' })
          }
        }
      }
    })
  },

  // 转单中 - 站长「拒绝」=> 回到配送中，由原配送员继续完成配送
  async onRejectReturn(e) {
    const id = e.currentTarget.dataset.id
    const api = e.currentTarget.dataset.kind === 'directed' ? rejectDirectedReturn : rejectStaffReturn
    wx.showModal({
      title: '拒绝转单',
      content: '拒绝后订单将回到配送中，由原配送员继续完成配送。',
      confirmText: '拒绝',
      confirmColor: '#FF3B30',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' })
          try {
            await api(id)
            wx.hideLoading()
            wx.showToast({ title: '已拒绝，订单回到配送中', icon: 'none' })
            this.loadAllData()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '操作失败', icon: 'none' })
          }
        }
      }
    })
  },

  // 他站外派给我：作为目标水站，将订单「调解退回」原归属站，等待原站长同意
  async onMediateReturn(e) {
    const id = e.currentTarget.dataset.id
    wx.showModal({
      title: '调解退回原站',
      content: '将此订单退回原归属水站，由其站长决定是否重新分配。',
      confirmText: '退回原站',
      confirmColor: '#FF9500',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '退回中...' })
          try {
            await directedReturn(id)
            wx.hideLoading()
            wx.showToast({ title: '已退回原站，等待对方确认', icon: 'success' })
            this.loadAllData()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '退回失败', icon: 'none' })
          }
        }
      }
    })
  },

  onCloseModal() {
    this.setData({ showAssignModal: false, currentOrderId: null })
  },

  stopPropagation() {}
})
