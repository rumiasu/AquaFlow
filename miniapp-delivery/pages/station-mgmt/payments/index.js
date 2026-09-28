const { getPendingPayments, confirmPayment } = require('../../../api/station-mgmt')

// 支付方式/支付状态文案一律由后端下发（PaymentRecord.getMethodText / getStatusText，
// 真相源为 PayMethod.java / PaymentStatus.java）。前端不再自建 1/2/3 映射表。

Page({
  data: {
    list: [],
    loading: false,
    /**
     * [2026-09-27 走查 C05/U01 同族修] 加载失败的原因（空串 = 成功）。
     *
     * 原来失败只有一句 `wx.showToast`：toast 两三秒后消失，页面剩一句「暂无待确认收款」——
     * 与"本站真的没有待收款"一模一样（AGENTS §8.22 的老形状），而且这里**没有下拉刷新之外的重试路**。
     * 现在把失败原因常驻在页面上，并给一个「重新加载」。
     */
    loadError: ''
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
    this.loadData().then(() => wx.stopPullDownRefresh())
  },

  async loadData() {
    this.setData({ loading: true })
    try {
      const res = await getPendingPayments()
      // wxml 里不能做函数调用与三元嵌套，所有展示字段在 JS 里预计算好。
      const list = (res.data || []).map(item => Object.assign({}, item, {
        methodText: item.methodText || '—',
        // [2026-09-27 走查 M01 修] 兜底从 `'—'` 改成 `'待核实到账'`：
        // 本列表按 `status = 1`（待收款）过滤，缺文案时写"待核实到账"是**照实说**，
        // 与页面顶部说明、按钮（确认已到账）同口径；写 `'—'` 则等于把这一格的信息丢掉，
        // 而写"已收到"会与事实相反。⚠️ 这**不是**前端自带映射表 ——
        // 文案正本仍是后端 PaymentStatus.textOf（有值就用它），这里只是缺值时的兜底。
        statusText: item.statusText || '待核实到账',
        amountText: Number(item.amount || 0).toFixed(2),
        // 无订单号 = 线上买水票这类"不挂在订单上"的收款
        isTicketPurchase: !item.orderId,
        // 备注后端带的是「线上购买水票」等中文，直接展示；为空时给个兜底
        noteText: item.note || (item.orderId ? '订单待收款' : '线上购票待确认')
      }))
      this.setData({ list, loadError: '' })
    } catch (err) {
      // 失败必须**常驻可见**（不是只弹一次 toast）：见 data.loadError 的注释
      this.setData({ loadError: (err && err.message) || '网络异常' })
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  /** 失败提示条上的「重新加载」（wxml 绑定，不存在会导致点击静默无反应） */
  onRetryLoad() {
    this.loadData()
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
      confirmColor: '#2E9E6B',
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
