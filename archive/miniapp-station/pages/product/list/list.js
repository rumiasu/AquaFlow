const { get, request } = require('../../../utils/request')
const { API } = require('../../../config/api')

Page({
  data: {
    loading: false,
    loadingMore: false,
    noMore: false,
    page: 1,
    pageSize: 20,
    products: [],
    filter: {
      status: '',
      keyword: ''
    }
  },

  onLoad() {
    if (!getApp().globalData.isLogin) {
      wx.redirectTo({ url: '/pages/login/index' })
      return
    }
    this.loadProducts()
  },

  onShow() {
    this.setData({ page: 1, products: [], noMore: false })
    this.loadProducts()
  },

  onPullDownRefresh() {
    this.setData({ page: 1, products: [], noMore: false })
    this.loadProducts().then(() => wx.stopPullDownRefresh())
  },

  onReachBottom() {
    if (!this.data.loadingMore && !this.data.noMore) {
      this.loadMore()
    }
  },

  async loadProducts() {
    if (this.data.loading) return
    this.setData({ loading: true })
    
    try {
      const { page, pageSize, filter } = this.data
      const params = { page: page - 1, size: pageSize }
      if (filter.status !== '') params.status = filter.status
      if (filter.keyword) params.keyword = filter.keyword

      const res = await get(API.MANAGER_PRODUCTS_WITH_STOCK, params)
      if (res.data && res.data.code === 0) {
        const newList = res.data.data || []
        this.setData({
          products: this.data.page === 1 ? newList : [...this.data.products, ...newList],
          noMore: newList.length < this.data.pageSize
        })
      } else {
        throw new Error(res.data?.message || '加载失败')
      }
    } catch (error) {
      console.error('加载商品列表失败:', error)
      wx.showToast({ title: error.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  async loadMore() {
    if (this.data.loadingMore || this.data.noMore) return
    this.setData({ loadingMore: true, page: this.data.page + 1 })
    await this.loadProducts()
    this.setData({ loadingMore: false })
  },

  onSearchInput(e) {
    this.setData({ 'filter.keyword': e.detail.value })
  },

  onSearchConfirm() {
    this.setData({ page: 1, products: [], noMore: false })
    this.loadProducts()
  },

  async onTogglePriority(e) {
    const { id, priorityDisplay } = e.currentTarget.dataset
    const newPriority = priorityDisplay === 1 ? 0 : 1
    
    // 前端优先展示数量上限检查
    if (newPriority === 1) {
      const currentCount = this.data.products.filter(p => p.priorityDisplay === 1).length
      if (currentCount >= 3) {
        wx.showToast({ title: '优先展示最多3个商品', icon: 'none' })
        return
      }
    }

    try {
      await request({
        url: `${API.MANAGER_PRODUCTS}/${id}/priority`,
        method: 'POST',
        data: { enabled: newPriority }
      })
      wx.showToast({ title: newPriority === 1 ? '已设为优先展示' : '已取消优先展示', icon: 'success' })
      
      // 更新本地数据
      const products = this.data.products.map(p => 
        p.id === id ? { ...p, priorityDisplay: newPriority } : p
      )
      this.setData({ products })
    } catch (error) {
      console.error('切换优先展示失败:', error)
      wx.showToast({ title: error.message || '操作失败', icon: 'none' })
    }
  },

  async onToggleShelf(e) {
    const { id, enabled } = e.currentTarget.dataset
    const newEnabled = enabled === 1 ? 0 : 1
    
    try {
      await request({
        url: `${API.MANAGER_PRODUCTS}/${id}/shelf`,
        method: 'POST',
        data: { enabled: newEnabled }
      })
      wx.showToast({ title: newEnabled === 1 ? '已上架' : '已下架', icon: 'success' })
      
      const products = this.data.products.map(p => 
        p.id === id ? { ...p, enabled: newEnabled } : p
      )
      this.setData({ products })
    } catch (error) {
      console.error('切换上下架失败:', error)
      wx.showToast({ title: error.message || '操作失败', icon: 'none' })
    }
  },

  onProductTap(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: `/pages/product/detail/detail?id=${id}` })
  }
})