const { getAddresses, deleteAddress, setDefaultAddress } = require('../../api/address')
const { storage } = require('../../utils/storage')
const { formatAddress } = require('../../utils/address')

Page({
  data: {
    loading: true,
    addresses: [],
    from: ''
  },

  onLoad(options) {
    if (options.from) {
      this.setData({ from: options.from })
    }
    this.loadAddresses()
  },

  onShow() {
    this.loadAddresses()
  },

  async loadAddresses() {
    this.setData({ loading: true })
    try {
      const res = await getAddresses()
      if (res.data) {
        // 统一展示粒度：「区 + 街道门牌」，与首页一致
        const addresses = res.data.map(function (a) {
          return Object.assign({}, a, { addressText: formatAddress(a) })
        })
        this.setData({ addresses })
      }
    } catch (error) {
      console.error('Load addresses error:', error)
    } finally {
      this.setData({ loading: false })
    }
  },

  onSelectAddress(e) {
    const { item } = e.currentTarget.dataset
    // 首页/下单页统一通过 selectedAddress 回写
    if (this.data.from === 'home' || this.data.from === 'order') {
      storage.set('selectedAddress', item)
      wx.navigateBack()
    }
  },

  onAddAddress() {
    wx.navigateTo({ url: '/pages/address/edit' })
  },

  onEditAddress(e) {
    const { id } = e.currentTarget.dataset
    wx.navigateTo({ url: `/pages/address/edit?id=${id}` })
  },

  onDeleteAddress(e) {
    const { id } = e.currentTarget.dataset
    wx.showModal({
      title: '确认删除',
      content: '确定要删除这个地址吗？',
      success: async (res) => {
        if (res.confirm) {
          try {
            await deleteAddress(id)
            wx.showToast({ title: '删除成功', icon: 'success' })
            this.loadAddresses()
          } catch (error) {
            console.error('Delete address error:', error)
            wx.showToast({ title: '删除失败', icon: 'none' })
          }
        }
      }
    })
  },

  async onSetDefault(e) {
    const { id } = e.currentTarget.dataset
    try {
      await setDefaultAddress(id)
      wx.showToast({ title: '设置成功', icon: 'success' })
      this.loadAddresses()
    } catch (error) {
      console.error('Set default error:', error)
      wx.showToast({ title: '设置失败', icon: 'none' })
    }
  }
})
