// 订单详情页
const { getOrderDetail, completeOrder, transferOrder, returnToStation, reportOrder, getStaffList, dispatchOrder, resolveOrder } = require('../../api/delivery')

Page({
  data: {
    orderId: null,
    order: {},
    loading: true
  },

  onLoad(options) {
    if (options.id) {
      this.setData({ orderId: options.id })
      this.loadOrderDetail(options.id)
    }
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    // 从完成配送页返回时刷新
    if (this.data.orderId) {
      this.loadOrderDetail(this.data.orderId)
    }
  },

  // 加载订单详情
  async loadOrderDetail(id) {
    this.setData({ loading: true })

    try {
      const res = await getOrderDetail(id)
      const order = res.data

      const statusMap = {
        1: { text: '待配送', class: 'pending' },
        3: { text: '配送中', class: 'delivering' },
        4: { text: '已送达', class: 'warning' },
        5: { text: '已完成', class: 'completed' },
        6: { text: '已取消', class: 'cancelled' }
      }
      const statusInfo = statusMap[order.status] || { text: '待处理', class: 'default' }

      let notes = []
      if (order.specialNote) {
        notes = order.specialNote.split('\n').filter(n => n.trim())
      }

      const paymentMethodMap = {
        1: '微信',
        2: '现金',
        3: '水票'
      }

      const isOffline = order.paymentMethod === 2 || order.paymentMethod === 4
        || (order.paymentMethod === 1 && order.paymentStatus !== 2)
      const isTransfer = !!order.transferStatus && order.transferStatus !== 'NONE'
      const isReturnReq = !!order.returnStatus && order.returnStatus === 'REQUESTED'
      const isTransferTarget = !!order.isTransferTarget

      const labels = []
      if (isOffline) labels.push({ type: 'offline', text: '线下' })
      if (isTransfer) labels.push({ type: 'transfer', text: '转单中' })
      if (isReturnReq) labels.push({ type: 'return', text: '退回申请' })
      if (isTransferTarget) labels.push({ type: 'target', text: '待你确认' })

      this.setData({
        order: {
          ...order,
          statusText: statusInfo.text,
          statusClass: statusInfo.class,
          notes,
          paymentMethodText: paymentMethodMap[order.paymentMethod] || '现金',
          isOffline,
          labels,
          isTransfer,
          isReturnReq,
          isTransferTarget
        },
        loading: false
      })
    } catch (err) {
      console.error('加载订单详情失败:', err)
      this.setData({ loading: false })
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    }
  },

  // 拨打电话
  onCallPhone() {
    const phone = this.data.order.receiverPhone || this.data.order.customerPhone
    if (phone) {
      wx.makePhoneCall({ phoneNumber: phone })
    }
  },

  // 复制地址
  onCopyAddress() {
    const address = this.data.order.addressSnapshot || this.data.order.addressDetail
    if (address) {
      wx.setClipboardData({
        data: address,
        success: () => {
          wx.showToast({ title: '地址已复制', icon: 'success' })
        }
      })
    }
  },

  // 导航（优先使用下单时地址快照，避免客户改地址后导错）
  onNavigate() {
    const order = this.data.order
    const lat = Number(order.addressSnapshotLat)
    const lng = Number(order.addressSnapshotLng)
    const addressText = order.addressSnapshot || order.addressDetail || ''
    if (lat && lng) {
      wx.openLocation({
        latitude: lat,
        longitude: lng,
        name: addressText,
        address: addressText,
        scale: 18
      })
    } else {
      // 如果没有坐标，使用地址搜索
      wx.chooseLocation({
        success: (res) => {
          wx.openLocation({
            latitude: res.latitude,
            longitude: res.longitude,
            name: res.name,
            address: res.address,
            scale: 18
          })
        }
      })
    }
  },

  // 完成配送
  onComplete() {
    wx.navigateTo({
      url: `/pages/order/complete?id=${this.data.orderId}`
    })
  },

  // 异常反馈
  onReport() {
    wx.showActionSheet({
      itemList: ['客户不接电话', '地址找不到', '客户拒收', '水桶破损', '其他'],
      success: (res) => {
        const reasons = ['客户不接电话', '地址找不到', '客户拒收', '水桶破损', '其他']
        const reason = reasons[res.tapIndex]

        wx.showModal({
          title: '异常反馈',
          content: `反馈原因：${reason}`,
          confirmText: '确认反馈',
          success: async (modalRes) => {
            if (modalRes.confirm) {
              try {
                await reportOrder(this.data.orderId, { reason })
                wx.showToast({ title: '反馈已提交', icon: 'success' })
              } catch (err) {
                wx.showToast({ title: err.message || '反馈失败', icon: 'none' })
              }
            }
          }
        })
      }
    })
  },

  async onAcceptTransfer() {
    const id = this.data.orderId
    wx.showModal({
      title: '同意转单',
      content: '确定接手此转单？接手后你将成为该订单配送员。',
      confirmText: '同意',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' })
          try {
            const { post } = require('../../utils/request')
            const { API } = require('../../config/api')
            await post(`${API.DELIVERY_TRANSFER}/${id}/claim`)
            wx.hideLoading()
            wx.showToast({ title: '已接手', icon: 'success' })
            setTimeout(() => { wx.navigateBack() }, 1500)
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '操作失败', icon: 'none' })
          }
        }
      }
    })
  },

  async onRejectTransfer() {
    const id = this.data.orderId
    wx.showModal({
      title: '拒绝转单',
      content: '确定拒绝此转单申请？',
      confirmColor: '#FF3B30',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' })
          try {
            const { post } = require('../../utils/request')
            const { API } = require('../../config/api')
            await post(`${API.DELIVERY_TRANSFER}/${id}/reject`)
            wx.hideLoading()
            wx.showToast({ title: '已拒绝', icon: 'success' })
            setTimeout(() => { wx.navigateBack() }, 1500)
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '操作失败', icon: 'none' })
          }
        }
      }
    })
  },

  // 转给同事（直转本站配送员）
  onTransfer() {
    const app = getApp()
    const myId = (app.globalData.userInfo || {}).staffId
    const stationId = (app.globalData.userInfo || {}).stationId
    const that = this
    wx.showLoading({ title: '加载中...' })
    getStaffList(stationId).then(res => {
      wx.hideLoading()
      const staffs = res.data || []
      const colleagues = staffs.filter(s => String(s.id) !== String(myId))
      if (colleagues.length === 0) {
        wx.showModal({ title: '暂无同事', content: '本站暂无其他在职配送员可转让', showCancel: false })
        return
      }
      const itemList = colleagues.map(s => s.name || ('配送员' + s.id))
      wx.showActionSheet({
        itemList: itemList,
        success: (res2) => {
          const target = colleagues[res2.tapIndex]
          wx.showModal({
            title: '转给同事',
            content: `确认将订单转给 ${target.name}？`,
            confirmText: '确认',
            success: async (modalRes) => {
              if (modalRes.confirm) {
                wx.showLoading({ title: '转单中...' })
                try {
                  await transferOrder(that.data.orderId, { deliveryStaffId: target.id, reason: '配送员转让' })
                  wx.hideLoading()
                  wx.showToast({ title: '已转给 ' + target.name, icon: 'success' })
                  setTimeout(() => { wx.navigateBack() }, 1500)
                } catch (err) {
                  wx.hideLoading()
                  wx.showToast({ title: err.message || '转让失败', icon: 'none' })
                }
              }
            }
          })
        }
      })
    }).catch(() => {
      wx.hideLoading()
      wx.showToast({ title: '加载配送员失败', icon: 'none' })
    })
  },

  // 退回站长
  onReturnToStation() {
    const that = this
    wx.showModal({
      title: '退回站长',
      content: '确定要退回站长吗？退回后站长将重新分配此订单。',
      confirmText: '确认退回',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '退回中...' })
          try {
            await returnToStation(that.data.orderId, { reason: '配送员退回站长' })
            wx.hideLoading()
            wx.showToast({ title: '已退回站长', icon: 'success' })
            setTimeout(() => { wx.navigateBack() }, 1500)
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '退回失败', icon: 'none' })
          }
        }
      }
    })
  },

  // 外派订单：临时指派给其他水站配送
  async onDispatchOrder() {
    const id = this.data.orderId
    const app = getApp()
    const myStationId = (app.globalData.userInfo || {}).stationId

    // 获取其他水站列表
    const { get } = require('../../utils/request')
    const { API } = require('../../config/api')
    let stationList = []
    try {
      const res = await get(API.STATION_SEARCH, {})
      stationList = (res.data || []).filter(s => s.id !== myStationId && s.status === 1)
    } catch (e) {
      console.error('加载水站列表失败:', e)
    }

    if (stationList.length === 0) {
      wx.showModal({ title: '无可外派水站', content: '暂无其他营业中的水站可外派', showCancel: false })
      return
    }

    const itemList = stationList.map(s => s.name || ('水站' + s.id))
    wx.showActionSheet({
      itemList: itemList,
      success: async (res) => {
        const targetStation = stationList[res.tapIndex]
        // 二次确认：显示风险提醒
        const confirm = await new Promise(resolve => {
          wx.showModal({
            title: '⚠️ 外派确认',
            content: `将订单外派给「${targetStation.name}」配送。\n\n` +
              '重要提醒：\n' +
              '1. 本单归属仍在本站，客户资产（桶/水票/押金）不转移\n' +
              '2. 客户下次下单仍在本站，需手动切站才会用外派站资产\n' +
              '3. 外派站仅负责本次配送，不建立客户归属关系\n\n' +
              '确定外派吗？',
            confirmText: '确定外派',
            confirmColor: '#34C759',
            cancelText: '取消',
            success: (r) => resolve(r.confirm)
          })
        })
        if (!confirm) return

        wx.showLoading({ title: '外派中...' })
        try {
          await dispatchOrder(id, { targetStationId: targetStation.id, reason: '外派配送' })
          wx.hideLoading()
          wx.showToast({ title: '外派成功', icon: 'success' })
          setTimeout(() => { wx.navigateBack() }, 1500)
        } catch (err) {
          wx.hideLoading()
          wx.showToast({ title: err.message || '外派失败', icon: 'none' })
        }
      }
    })
  },

  // 解决订单：拒单并取消，触发退款
  async onResolveOrder() {
    const id = this.data.orderId
    // 严重警告：拒单会导致订单取消、退款、客户可能流失
    const confirm = await new Promise(resolve => {
      wx.showModal({
        title: '⚠️ 严重警告：解决/拒单',
        content: '此操作将：\n\n' +
          '1. 取消订单，状态变为「已取消」\n' +
          '2. 触发退款，款项原路退回（微信支付需几分钟到账）\n' +
          '3. 客户需重新下单，体验极差，极大概率导致客户流失\n\n' +
          '建议优先考虑：「外派」给其他水站，或内部协调配送。\n\n' +
          '确定要解决（拒单）吗？',
        confirmText: '确定解决',
        confirmColor: '#FF3B30',
        cancelText: '取消，去外派',
        success: (r) => resolve(r.confirm)
      })
    })
    if (!confirm) return

    // 必填拒单原因
    const reason = await new Promise(resolve => {
      wx.showModal({
        title: '拒单原因 (必填)',
        content: '请输入拒单原因，将记录在订单备注中：',
        editable: true,
        placeholderText: '如：地址偏远无法配送、暂时缺货',
        success: (r) => resolve(r.confirm ? r.content : ''),
        fail: () => resolve('')
      })
    })
    if (!reason || !reason.trim()) {
      wx.showToast({ title: '拒单原因不能为空', icon: 'none' })
      return
    }

    wx.showLoading({ title: '处理中...' })
    try {
      await resolveOrder(id, { reason: reason.trim() })
      wx.hideLoading()
      wx.showToast({ title: '已解决，订单取消并触发退款', icon: 'success' })
      setTimeout(() => { wx.navigateBack() }, 1500)
    } catch (err) {
      wx.hideLoading()
      wx.showToast({ title: err.message || '解决失败', icon: 'none' })
    }
  }
})
