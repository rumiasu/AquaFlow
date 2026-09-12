const { get, post } = require('../../../utils/request')
const { API } = require('../../../config/api')

Page({
  data: {
    loading: false,
    loadingMore: false,
    noMore: false,
    page: 1,
    pageSize: 20,
    exceptions: [],
    filters: {
      status: '',
      category: '',
      staffId: ''
    },
    statusOptions: [
      { value: '', label: '全部状态' },
      { value: 'STAFF_RECORDED', label: '待处理' },
      { value: 'MANAGER_PENDING', label: '处理中' },
      { value: 'MANAGER_APPROVED', label: '已审批' },
      { value: 'EXECUTED', label: '已执行' },
      { value: 'IGNORED', label: '已忽略' }
    ],
    categoryOptions: [
      { value: '', label: '全部类型' },
      { value: 'RETURN_SHORT', label: '少回桶' },
      { value: 'RETURN_OVER', label: '多回桶' },
      { value: 'RETURN_REFUSE', label: '拒收' },
      { value: 'RETURN_DAMAGE', label: '损坏' },
      { value: 'STATION_SHORTAGE', label: '站内缺水' },
      { value: 'CUSTOMER_REFUSE', label: '客户拒收' },
      { value: 'OTHER', label: '其他' }
    ],
    showFilter: false
  },

  onLoad() {
    if (!getApp().globalData.isLogin) {
      wx.redirectTo({ url: '/pages/login/index' })
      return
    }
    this.loadExceptions()
  },

  onShow() {
    // 刷新列表
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
      const { filters, page, pageSize } = this.data
      const params = {
        page: page - 1,
        size: pageSize
      }
      if (filters.status) params.status = filters.status
      if (filters.category) params.category = filters.category
      if (filters.staffId) params.staffId = filters.staffId

      const res = await get(API.MANAGER_EXCEPTIONS, params)
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
      console.error('加载异常列表失败:', error)
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

  onFilterTap() {
    this.setData({ showFilter: !this.data.showFilter })
  },

  onStatusChange(e) {
    const value = e.currentTarget.dataset.value
    this.setData({ 'filters.status': value, showFilter: false, page: 1, exceptions: [], noMore: false })
    this.loadExceptions()
  },

  onCategoryChange(e) {
    const value = e.currentTarget.dataset.value
    this.setData({ 'filters.category': value, showFilter: false, page: 1, exceptions: [], noMore: false })
    this.loadExceptions()
  },

  onResetFilter() {
    this.setData({ filters: { status: '', category: '', staffId: '' }, showFilter: false, page: 1, exceptions: [], noMore: false })
    this.loadExceptions()
  },

  onExceptionTap(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: `/pages/exception/detail/detail?id=${id}` })
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