const { get } = require('../../../utils/request')
const { API } = require('../../../config/api')

/** 纯展示用：时间格式化（MM-DD HH:mm），不涉及业务判定 */
function formatTime(time) {
  if (!time) return ''
  const date = new Date(String(time).replace(/-/g, '/'))
  if (isNaN(date.getTime())) return String(time)
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  const hour = String(date.getHours()).padStart(2, '0')
  const minute = String(date.getMinutes()).padStart(2, '0')
  return `${month}-${day} ${hour}:${minute}`
}

Page({
  data: {
    loading: true,
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
      const res = await get(`${API.CUSTOMER_EXCEPTIONS}`, { 
        page: page - 1, 
        size: pageSize 
      })
      if (res.data && res.data.code === 0) {
        const newList = (res.data.data.records || []).map(this.decorateException)
        this.setData({
          exceptions: this.data.page === 1 ? newList : [...this.data.exceptions, ...newList],
          noMore: newList.length < this.data.pageSize
        })
      } else {
        throw new Error(res.data?.message || '加载失败')
      }
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

  onExceptionTap(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: `/pages/exception/customer-detail/customer-detail?id=${id}` })
  },

})