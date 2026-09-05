const { get } = require('../../../utils/request')
const { API } = require('../../../config/api')

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
        const newList = res.data.data.records || []
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

  onExceptionTap(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: `/pages/exception/customer-detail/customer-detail?id=${id}` })
  },

  getStatusName(status) {
    const map = {
      'STAFF_RECORDED': '待处理',
      'MANAGER_PENDING': '处理中',
      'MANAGER_APPROVED': '已审批',
      'EXECUTING': '执行中',
      'EXECUTED': '已完成',
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
    const date = new Date(time.replace(/-/g, '/'))
    const month = String(date.getMonth() + 1).padStart(2, '0')
    const day = String(date.getDate()).padStart(2, '0')
    const hour = String(date.getHours()).padStart(2, '0')
    const minute = String(date.getMinutes()).padStart(2, '0')
    return `${month}-${day} ${hour}:${minute}`
  }
})