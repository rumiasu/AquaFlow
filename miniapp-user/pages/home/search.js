const { searchStorage } = require('../../utils/storage')
const { getProducts } = require('../../api/product')

Page({
  data: {
    keyword: '',
    searchHistory: [],
    searchResults: [],
    loading: false
  },

  onLoad() {
    this.setData({
      searchHistory: searchStorage.get()
    })
  },

  onInput(e) {
    this.setData({ keyword: e.detail.value })
  },

  onSearch() {
    const { keyword } = this.data
    if (keyword.trim()) {
      searchStorage.add(keyword)
      this.setData({ searchHistory: searchStorage.get() })
      this.doSearch(keyword)
    }
  },

  onClearInput() {
    this.setData({
      keyword: '',
      searchResults: []
    })
  },

  onHistoryTap(e) {
    const { keyword } = e.currentTarget.dataset
    this.setData({ keyword })
    this.doSearch(keyword)
  },

  onClearHistory() {
    searchStorage.clear()
    this.setData({ searchHistory: [] })
  },

  async doSearch(keyword) {
    this.setData({ loading: true })
    try {
      // keyword 传给后端做名称/品牌/规格匹配；只展示在售商品，避免把下架商品搜出来
      const res = await getProducts({ keyword })
      const list = Array.isArray(res.data) ? res.data : []
      this.setData({ searchResults: list.filter(p => p.status === 1) })
    } catch (error) {
      console.error('Search error:', error)
      wx.showToast({ title: error.message || '搜索失败', icon: 'none' })
      this.setData({ searchResults: [] })
    } finally {
      this.setData({ loading: false })
    }
  },

  onProductTap(e) {
    const { id } = e.currentTarget.dataset
    wx.navigateTo({
      url: `/pages/product/detail?id=${id}`
    })
  }
})
