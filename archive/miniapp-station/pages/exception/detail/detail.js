const { get, post } = require('../../../utils/request')
const { API } = require('../../../config/api')

Page({
  data: {
    loading: true,
    exceptionId: null,
    exception: null,
    order: null,
    showHandleModal: false,
    handleAction: 'APPROVE',
    handleOptions: [
      { value: 'APPROVE', label: '同意建议补偿', desc: '按系统建议执行补偿' },
      { value: 'MODIFY', label: '修改补偿方案', desc: '自定义水票/现金/减免/调资产' },
      { value: 'IGNORE', label: '标记忽略', desc: '不处理，仅归档' },
      { value: 'ESCALATE', label: '转人工处理', desc: '挂起，后续人工跟进' }
    ],
    refundTicketQty: 0,
    refundCashAmount: '',
    adjustAssetQty: 0,
    adjustProductId: null,
    managerNote: '',
    submitting: false
  },

  onLoad(options) {
    if (!getApp().globalData.isLogin) {
      wx.redirectTo({ url: '/pages/login/index' })
      return
    }
    const exceptionId = options.id
    if (!exceptionId) {
      wx.showToast({ title: '异常ID缺失', icon: 'none' })
      setTimeout(() => wx.navigateBack(), 1500)
      return
    }
    this.setData({ exceptionId })
    this.loadDetail(exceptionId)
  },

  async loadDetail(exceptionId) {
    try {
      const res = await get(`${API.MANAGER_EXCEPTIONS}/${exceptionId}`)
      if (res.data && res.data.code === 0) {
        const exception = res.data.data
        // 获取订单详情
        let order = null
        if (exception.orderId) {
          try {
            const orderRes = await get(`${API.ORDERS}/${exception.orderId}`)
            if (orderRes.data && orderRes.data.code === 0) {
              order = orderRes.data.data
            }
          } catch (e) {
            console.warn('获取订单详情失败:', e)
          }
        }
        this.setData({ exception, order })
      } else {
        throw new Error(res.data?.message || '加载失败')
      }
    } catch (error) {
      console.error('加载异常详情失败:', error)
      wx.showToast({ title: error.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  onHandleTap() {
    const { exception } = this.data
    if (exception.status === 'EXECUTED' || exception.status === 'IGNORED') {
      wx.showToast({ title: '该异常已处理完成', icon: 'none' })
      return
    }
    // 预填建议补偿
    this.setData({
      showHandleModal: true,
      handleAction: 'APPROVE',
      refundTicketQty: exception.suggestedTicketQty || 0,
      refundCashAmount: exception.suggestedCashAmount ? String(exception.suggestedCashAmount) : '',
      adjustAssetQty: 0,
      adjustProductId: null,
      managerNote: ''
    })
  },

  onActionChange(e) {
    this.setData({ handleAction: e.currentTarget.dataset.value })
  },

  onTicketQtyChange(e) {
    this.setData({ refundTicketQty: parseInt(e.detail.value) || 0 })
  },

  onCashAmountChange(e) {
    this.setData({ refundCashAmount: e.detail.value })
  },

  onAssetQtyChange(e) {
    this.setData({ adjustAssetQty: parseInt(e.detail.value) || 0 })
  },

  onProductIdChange(e) {
    this.setData({ adjustProductId: e.detail.value })
  },

  onNoteInput(e) {
    this.setData({ managerNote: e.detail.value })
  },

  onCloseModal() {
    this.setData({ showHandleModal: false })
  },

  async onConfirmHandle() {
    const { exceptionId, handleAction, refundTicketQty, refundCashAmount, adjustAssetQty, adjustProductId, managerNote } = this.data
    
    if (!managerNote.trim()) {
      wx.showToast({ title: '请填写处理备注', icon: 'none' })
      return
    }

    if (handleAction === 'MODIFY') {
      if (refundTicketQty < 0) {
        wx.showToast({ title: '水票数量不能为负', icon: 'none' })
        return
      }
      if (refundCashAmount && parseFloat(refundCashAmount) < 0) {
        wx.showToast({ title: '现金金额不能为负', icon: 'none' })
        return
      }
    }

    this.setData({ submitting: true })

    try {
      const res = await post(`${API.MANAGER_EXCEPTIONS}/${this.data.exceptionId}/handle`, {
        action: handleAction,
        refundTicketQty,
        refundCashAmount: refundCashAmount ? parseFloat(refundCashAmount) : 0,
        adjustAssetQty,
        adjustProductId,
        managerNote
      })

      if (res.data && res.data.code === 0) {
        wx.showToast({ title: '处理成功', icon: 'success' })
        this.setData({ showHandleModal: false })
        this.loadDetail(this.data.exceptionId)
      } else {
        throw new Error(res.data?.message || '处理失败')
      }
    } catch (error) {
      console.error('处理异常失败:', error)
      wx.showToast({ title: error.message || '处理失败', icon: 'none' })
    } finally {
      this.setData({ submitting: false })
    }
  },

  getStatusName(status) {
    const map = {
      'STAFF_RECORDED': '待处理',
      'MANAGER_PENDING': '处理中',
      'MANAGER_APPROVED': '已审批',
      'EXECUTING': '执行中',
      'EXECUTED': '已执行',
      'IGNORED': '已忽略'
    }
    return map[status] || status
  },

  getCategoryName(category) {
    const map = {
      'RETURN_SHORT': '少回桶',
      'RETURN_OVER': '多回桶',
      'RETURN_REFUSE': '拒收',
      'RETURN_DAMAGE': '损坏',
      'STATION_SHORTAGE': '站内缺水',
      'CUSTOMER_REFUSE': '客户拒收',
      'OTHER': '其他'
    }
    return map[category] || category
  },

  getStaffActionName(action) {
    const map = {
      'FULL': '全部给水',
      'PARTIAL': '部分给水',
      'REFUSE': '拒绝给水',
      'OWE': '欠水给桶'
    }
    return map[action] || action
  },

  getManagerActionName(action) {
    const map = {
      'REFUND_TICKET': '退水票',
      'REFUND_CASH': '退现金',
      'WAIVE_DEPOSIT': '减免押金',
      'ADJUST_ASSET': '调整桶资产',
      'RESCHEDULE': '重新安排',
      'IGNORE': '忽略'
    }
    return map[action] || action
  },

  getStatusColor(status) {
    const map = {
      'STAFF_RECORDED': 'warning',
      'MANAGER_PENDING': 'primary',
      'MANAGER_APPROVED': 'info',
      'EXECUTING': 'primary',
      'EXECUTED': 'success',
      'IGNORED': 'default'
    }
    return map[status] || 'default'
  },

  formatTime(time) {
    if (!time) return ''
    // [AQ-045] 时间统一按 ISO-8601 解析。旧写法 .replace(/-/g,'/') 会把 ISO 的 'T' 破坏成 '2026/09/10T12:00:00'，
    // iOS Safari 对此返回 Invalid Date。这里直解 ISO，并兼容历史空格分隔格式。
    let s = String(time).trim()
    if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}/.test(s)) s = s.replace(' ', 'T')
    const date = new Date(s)
    if (isNaN(date.getTime())) return String(time)
    const month = String(date.getMonth() + 1).padStart(2, '0')
    const day = String(date.getDate()).padStart(2, '0')
    const hour = String(date.getHours()).padStart(2, '0')
    const minute = String(date.getMinutes()).padStart(2, '0')
    return `${month}-${day} ${hour}:${minute}`
  }
})