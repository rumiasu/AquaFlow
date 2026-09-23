const { get } = require('../../../utils/request')
const { API } = require('../../../config/api')

/** 纯展示用：时间格式化（MM-DD HH:mm），不涉及业务判定 */
function formatTime(time) {
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

Page({
  data: {
    // 初始必须为 false：loadExceptions() 开头有 `if (this.data.loading) return` 的并发保护，
    // 旧实现初始值为 true，导致首次进入时直接 return、列表永远为空。
    loading: false,
    loadingMore: false,
    noMore: false,
    page: 1,
    pageSize: 20,
    exceptions: []
  },

  onLoad() {
    if (!getApp().globalData.isLogin) {
      wx.redirectTo({ url: '/pages/login/index' })
      return
    }
    this.loadExceptions()
  },

  onShow() {
    this.setData({ page: 1, exceptions: [], noMore: false })
    this.loadExceptions()
  },

  onPullDownRefresh() {
    this.setData({ page: 1, exceptions: [], noMore: false })
    this.loadExceptions().then(() => wx.stopPullDownRefresh())
  },

  onReachBottom() {
    if (!this.data.loadingMore && !this.data.noMore) {
      this.loadMore()
    }
  },

  async loadExceptions() {
    if (this.data.loading) return
    this.setData({ loading: true })

    try {
      const { page, pageSize } = this.data
      // 后端页码从 1 开始；utils/request 已把 HTTP body 剥成 Result，故这里是 res.code / res.data
      // （旧实现按 wx.request 原始响应处理 res.data.code，恒为 undefined → 每次都抛"加载失败"）
      const res = await get(API.CUSTOMER_EXCEPTIONS, { page, size: pageSize })
      const pageData = res && res.data ? res.data : {}
      const newList = (pageData.records || []).map(item => this.decorateException(item))
      this.setData({
        exceptions: this.data.page === 1 ? newList : [...this.data.exceptions, ...newList],
        noMore: newList.length < this.data.pageSize
      })
    } catch (error) {
      console.error('加载异常历史失败:', error)
      wx.showToast({ title: error.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  async loadMore() {
    if (this.data.loadingMore || this.data.noMore) return
    this.setData({ loadingMore: true, page: this.data.page + 1 })
    await this.loadExceptions()
    this.setData({ loadingMore: false })
  },

  /**
   * 补齐展示字段。
   * 注意：WXML 无法调用 Page 方法，此前列表直接写 {{getStatusName(item.status)}}
   * 且不存在的 wxs，状态/类别/时间全部渲染为空。改为此处预处理后由模板直接取字段。
   * 其中状态文案与类别文案由后端下发（OrderBarrelException.statusText / categoryText），
   * 时间与配色属纯展示，保留前端处理。
   */
  decorateException(item) {
    const statusColorMap = {
      STAFF_RECORDED: 'warning',
      MANAGER_PENDING: 'primary',
      MANAGER_APPROVED: 'info',
      EXECUTING: 'primary',
      EXECUTED: 'success',
      IGNORED: 'default'
    }
    // 进度步骤：0 提交 1 审批 2 执行 3 完成
    const stepMap = {
      STAFF_RECORDED: 0, MANAGER_PENDING: 1,
      MANAGER_APPROVED: 1, EXECUTING: 2, EXECUTED: 3
    }
    return {
      ...item,
      statusText: item.statusText || item.status || '未知',
      categoryText: item.categoryText || item.category || '其他',
      statusColor: statusColorMap[item.status] || 'default',
      timeText: formatTime(item.createdAt),
      discrepancyAbs: Math.abs(item.discrepancy != null ? item.discrepancy : 0),
      // 补偿文案：纯展示，依据后端下发的退款/水票字段生成
      compensationText: (() => {
        const cash = item.refundCashAmount != null ? Number(item.refundCashAmount)
          : (item.suggestedCashAmount != null ? Number(item.suggestedCashAmount) : 0)
        const tickets = item.refundTicketQty != null ? item.refundTicketQty
          : (item.suggestedTicketQty != null ? item.suggestedTicketQty : 0)
        if (cash > 0) return `补偿 ¥${cash}`
        if (tickets > 0) return `补偿 ${tickets} 张水票`
        return '无需补偿'
      })(),
      stepIndex: item.status === 'IGNORED' ? -1 : (stepMap[item.status] != null ? stepMap[item.status] : 0)
    }
  },

  // 卡片已展示类型/状态/差异/补偿/进度，点击再补充说明与备注。
  // 旧实现跳转 /pages/exception/customer-detail/customer-detail —— 该页面不存在，点了就报 navigateTo:fail。
  onExceptionTap(e) {
    const { id } = e.currentTarget.dataset
    const item = this.data.exceptions.find(x => String(x.id) === String(id))
    if (!item) return
    const lines = [
      `订单：${item.orderNo ? item.orderNo : '#' + item.orderId}`,
      `类型：${item.categoryText}`,
      `状态：${item.statusText}`,
      `差异：${item.discrepancy ? '少回 ' + Math.abs(item.discrepancy) + ' 桶' : '无差异'}`,
      `补偿：${item.compensationText}`
    ]
    if (item.staffNote) lines.push(`配送员说明：${item.staffNote}`)
    if (item.managerNote) lines.push(`水站说明：${item.managerNote}`)
    wx.showModal({
      title: '异常处理详情',
      content: lines.join('\n'),
      showCancel: false,
      confirmText: '知道了'
    })
  },

})