const { getPendingPayments, confirmPayment } = require('../../../api/station-mgmt')

// 支付方式/支付状态文案一律由后端下发（PaymentRecord.getMethodText / getStatusText，
// 真相源为 PayMethod.java / PaymentStatus.java）。前端不再自建 1/2/3 映射表。

Page({
  data: { list: [], loading: false },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this.loadData()
  },

  onPullDownRefresh() {
    this.loadData().then(() => wx.stopPullDownRefresh())
  },

  async loadData() {
    this.setData({ loading: true })
    try {
      const res = await getPendingPayments()
      // wxml 里不能做函数调用与三元嵌套，所有展示字段在 JS 里预计算好。
      const list = (res.data || []).map(item => Object.assign({}, item, {
        methodText: item.methodText || '—',
        statusText: item.statusText || '—',
        amountText: Number(item.amount || 0).toFixed(2),
        // 无订单号 = 线上买水票这类"不挂在订单上"的收款
        isTicketPurchase: !item.orderId,
        // 备注后端带的是「线上购买水票」等中文，直接展示；为空时给个兜底
        noteText: item.note || (item.orderId ? '订单待收款' : '线上购票待确认')
      }))
      this.setData({ list })
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  /**
   * 确认某笔收款已到账。
   * 两种语义，由后端按是否有 order_id 分派：
   *   · 订单类 → 订单支付状态置已付 + 入账预收桶押金（幂等）
   *   · 购票类 → 水票入账（幂等，乐观锁保证只入一次）
   * 因此重复点击是安全的，但仍要提示清楚"确认的是钱已到手"，避免误把未到账的钱点成已确认。
   */
  onConfirm(e) {
    const id = e.currentTarget.dataset.id
    const item = this.data.list.find(x => x.id === id)
    if (!item) return
    const what = item.isTicketPurchase
      ? `确认已收到该客户购买 ${item.ticketQty || 0} 张水票的款项 ¥${item.amountText}？确认后水票立即入账。`
      : `确认已收到该客户支付 ¥${item.amountText}？确认后订单将标记为已付款。`

    wx.showModal({
      title: '确认收款',
      content: what,
      confirmText: '确认到账',
      confirmColor: '#34C759',
      success: async (res) => {
        if (!res.confirm) return
        wx.showLoading({ title: '确认中...' })
        try {
          await confirmPayment(id)
          wx.hideLoading()
          wx.showToast({ title: '已确认到账', icon: 'success' })
          this.loadData()
        } catch (err) {
          wx.hideLoading()
          wx.showToast({ title: err.message || '确认失败', icon: 'none', duration: 3000 })
          // 失败常见原因是状态已被改（重复确认）→ 重新拉一次让列表自愈
          this.loadData()
        }
      }
    })
  }
})
