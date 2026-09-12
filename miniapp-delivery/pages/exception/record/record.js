const { getOrderDetail } = require('../../../api/order')
const { getStationById } = require('../../../api/station')
const app = getApp()

Page({
  /** 弹窗内容区吞掉点击，避免冒泡到 overlay 触发「取消提交」
   *  （wxml 侧已改为 catchtap，这里保留空实现以消除未定义方法告警） */
  stopPropagation() {},

  data: {
    loading: true,
    orderId: null,
    order: null,
    stationName: '',
    stationPhone: '',
    deliveryQty: 0,
    actualReturn: 0,
    staffAction: 'FULL',
    staffActionOptions: [
      { value: 'FULL', label: '全部给水', desc: '按订单数量全部送达，实收等于应送' },
      { value: 'PARTIAL', label: '部分给水', desc: '仅送达部分，客户少回桶' },
      { value: 'REFUSE', label: '拒绝给水', desc: '客户拒收/不在家，全单不送' },
      { value: 'OWE', label: '欠水给桶', desc: '少给水但记欠桶，下单抵扣' }
    ],
    waterGiven: 0,
    waterOwed: 0,
    staffNote: '',
    discrepancy: 0,
    discrepancyDesc: '',
    submitting: false,
    showConfirm: false
  },

  onLoad(options) {
    if (!app.globalData.isLogin) {
      wx.redirectTo({ url: '/pages/login/index' })
      return
    }
    const orderId = options.id
    if (!orderId) {
      wx.showToast({ title: '订单ID缺失', icon: 'none' })
      setTimeout(() => wx.navigateBack(), 1500)
      return
    }
    this.setData({ orderId })
    this.loadOrderDetail(orderId)
  },

  async loadOrderDetail(orderId) {
    try {
      const res = await getOrderDetail(orderId)
      const order = res.data
      if (!order) {
        wx.showToast({ title: '订单不存在', icon: 'none' })
        setTimeout(() => wx.navigateBack(), 1500)
        return
      }

      // 计算应送桶数
      const deliveryQty = order.deliveryBucketQty || 0

      this.setData({
        order,
        deliveryQty,
        actualReturn: deliveryQty, // 默认按全收
        discrepancy: 0,
        discrepancyAbs: 0,
        discrepancyDesc: '实收等于应送，无差异'
      })

      // 获取水站信息
      if (order.stationId) {
        try {
          const stationRes = await getStationById(order.stationId)
          if (stationRes.data) {
            this.setData({
              stationName: stationRes.data.name || '',
              stationPhone: stationRes.data.phone || ''
            })
          }
        } catch (e) {
          console.warn('获取水站信息失败:', e)
        }
      }

      this.updateDiscrepancyDesc()
    } catch (error) {
      console.error('加载订单详情失败:', error)
      wx.showToast({ title: '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  onActualReturnChange(e) {
    const actualReturn = Math.max(0, parseInt(e.detail.value) || 0)
    const { deliveryQty, staffAction } = this.data
    const discrepancy = deliveryQty - actualReturn
    
    let waterOwed = 0
    if (staffAction === 'OWE' && discrepancy > 0) {
      waterOwed = discrepancy
    }

    this.setData({ actualReturn, discrepancy, waterOwed, discrepancyAbs: Math.abs(discrepancy) })
    this.updateDiscrepancyDesc()
  },

  onStaffActionChange(e) {
    const staffAction = e.currentTarget.dataset.value
    const { actualReturn, deliveryQty } = this.data
    const discrepancy = deliveryQty - this.data.actualReturn
    
    let waterGiven = actualReturn
    let waterOwed = 0
    
    if (staffAction === 'OWE' && discrepancy > 0) {
      waterOwed = discrepancy
      waterGiven = actualReturn
    } else if (staffAction === 'PARTIAL') {
      waterGiven = actualReturn
    } else if (staffAction === 'REFUSE') {
      waterGiven = 0
      waterOwed = 0
    } else {
      // FULL
      waterGiven = actualReturn
    }

    this.setData({ staffAction, waterGiven, waterOwed })
    this.updateDiscrepancyDesc()
  },

  onWaterGivenChange(e) {
    const waterGiven = Math.max(0, parseInt(e.detail.value) || 0)
    this.setData({ waterGiven })
  },

  onWaterOwedChange(e) {
    const waterOwed = Math.max(0, parseInt(e.detail.value) || 0)
    this.setData({ waterOwed })
  },

  onNoteInput(e) {
    this.setData({ staffNote: e.detail.value })
  },

  updateDiscrepancyDesc() {
    const { discrepancy, staffAction, actualReturn, deliveryQty } = this.data
    let desc = ''
    if (discrepancy === 0) {
      desc = '实收等于应送，无差异'
    } else if (discrepancy > 0) {
      desc = `少回 ${discrepancy} 桶`
    } else {
      desc = `多回 ${Math.abs(discrepancy)} 桶`
    }
    this.setData({ discrepancyDesc: desc })
  },

  onPreviewSubmit() {
    const { actualReturn, staffAction, discrepancy, waterGiven, waterOwed } = this.data
    
    if (actualReturn < 0) {
      wx.showToast({ title: '实收桶数不能为负', icon: 'none' })
      return
    }

    let confirmMsg = ''
    if (discrepancy === 0) {
      confirmMsg = '实收等于应送，无差异，确认提交？'
    } else if (discrepancy > 0) {
      confirmMsg = `少回 ${discrepancy} 桶，将记录异常并通知站长处理，确认提交？`
    } else {
      confirmMsg = `多回 ${Math.abs(this.data.discrepancy)} 桶，将记录异常，确认提交？`
    }

    if (this.data.staffAction === 'OWE' && this.data.waterOwed > 0) {
      confirmMsg += `\n记录欠水 ${this.data.waterOwed} 桶，下单自动抵扣`
    }

    this.setData({ showConfirm: true, confirmMsg })
  },

  onConfirmSubmit() {
    this.setData({ showConfirm: false, submitting: true })
    this.submitException()
  },

  onCancelSubmit() {
    this.setData({ showConfirm: false })
  },

  async submitException() {
    const { orderId, actualReturn, staffAction, waterGiven, waterOwed, staffNote, discrepancy } = this.data
    
    try {
      const res = await wx.request({
        url: `${getApp().globalData.baseUrl}/api/delivery/orders/${this.data.orderId}/exception/return`,
        method: 'POST',
        header: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${wx.getStorageSync('accessToken')}`
        },
        data: {
          actualReturn,
          staffAction,
          waterGiven,
          waterOwed,
          staffNote: this.data.staffNote
        }
      })

      if (res.data && res.data.code === 0) {
        wx.showToast({ title: '录入成功，已通知站长', icon: 'success' })
        setTimeout(() => {
          wx.navigateBack()
        }, 1500)
      } else {
        throw new Error(res.data?.message || '提交失败')
      }
    } catch (error) {
      console.error('提交异常失败:', error)
      wx.showToast({ title: error.message || '提交失败', icon: 'none' })
    } finally {
      this.setData({ submitting: false })
    }
  }
})