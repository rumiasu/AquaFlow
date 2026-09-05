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
      const res = await getProducts({ keyword })
      if (res.data) {
        this.setData({ searchResults: res.data })
      }
    } catch (error) {
      console.error('Search error:', error)
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
