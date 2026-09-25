const { get, request } = require('../../../utils/request')
const { API } = require('../../../config/api')

Page({
  data: {
    loading: true,
    productId: null,
    product: null,
    inventory: null,
    saving: false
  },

  onLoad(options) {
    if (!getApp().globalData.isLogin) {
      wx.redirectTo({ url: '/pages/login/index' })
      return
    }
    const productId = options.id
    if (!productId) {
      wx.showToast({ title: '商品ID缺失', icon: 'none' })
      setTimeout(() => wx.navigateBack(), 1500)
      return
    }
    this.setData({ productId })
    this.loadDetail(productId)
  },

  async loadDetail(productId) {
    try {
      const res = await get(`${API.MANAGER_PRODUCTS}/${productId}`)
      if (res.data && res.data.code === 0) {
        this.setData({ 
          product: res.data.data.product,
          inventory: res.data.data.inventory
        })
      } else {
        throw new Error(res.data?.message || '加载失败')
      }
    } catch (error) {
      console.error('加载商品详情失败:', error)
      wx.showToast({ title: error.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  onPriorityChange(e) {
    const priorityDisplay = e.detail.value ? 1 : 0
    this.setData({ 'inventory.priorityDisplay': priorityDisplay })
  },

  onShelfChange(e) {
    const enabled = e.detail.value ? 1 : 0
    this.setData({ 'inventory.enabled': enabled })
  },

  onInputChange(e) {
    const { field } = e.currentTarget.dataset
    const value = e.detail.value
    this.setData({ [`inventory.${field}`]: value })
  },

  async onSave() {
    const { inventory } = this.data
    if (!inventory || !inventory.id) {
      wx.showToast({ title: '库存信息不存在', icon: 'none' })
      return
    }

    // 前端优先展示数量上限检查
    if (inventory.priorityDisplay === 1) {
      // 需要从列表获取当前优先展示数量（详情页暂不检查，由后端校验）
    }

    this.setData({ saving: true })

    try {
      await request({
        url: `${API.MANAGER_PRODUCTS}/${inventory.id}/shelf`,
        method: 'POST',
        data: { enabled: inventory.enabled }
      })
      await request({
        url: `${API.MANAGER_PRODUCTS}/${inventory.id}/priority`,
        method: 'POST',
        data: { enabled: inventory.priorityDisplay }
      })
      wx.showToast({ title: '保存成功', icon: 'success' })
    } catch (error) {
      console.error('保存失败:', error)
      wx.showToast({ title: error.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ saving: false })
    }
  }
})