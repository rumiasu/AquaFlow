// 站长客户查询
const { getCustomers } = require('../../../api/station-mgmt')
const { STORAGE_KEYS } = require('../../../utils/storage-keys')

const AVATAR_COLORS = ['#409EFF', '#67C23A', '#E6A23C', '#F56C6B', '#909399', '#9254DE']

// 列表项前端派生展示字段（头像/脱敏等纯展示，不依赖后端）
function decorate(item) {
  const name = item.name || '?'
  item.avatarText = name.charAt(0)
  let h = 0
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0
  item.avatarColor = AVATAR_COLORS[h % AVATAR_COLORS.length]
  const phone = item.phone || ''
  item.phoneMasked = phone.length === 11 ? phone.replace(/(\d{3})\d{4}(\d{4})/, '$1****$2') : (phone || '未填写')
  item.totalOrders = item.totalOrders || 0
  item.totalConsumptionText = Number(item.totalConsumption || 0).toFixed(2)
  item.depositBalanceText = Number(item.depositBalance || 0).toFixed(2)
  item.customerLevel = item.customerLevel || '普通客户'
  item.customerLevelColor = item.customerLevelColor || '#909399'
  item.activityStatus = item.activityStatus || 'new'
  item.activityText = item.activityText || '新客'
  item.tagsList = item.tags ? String(item.tags).split(',').map(s => s.trim()).filter(Boolean) : []
  return item
}

Page({
  data: {
    loading: true,
    list: [],
    keyword: '',
    filterType: 'all' // all | 1 个人 | 2 企业
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

  onKeywordInput(e) {
    this.setData({ keyword: e.detail.value })
  },

  onSearch() {
    this.loadData()
  },

  onFilterType(e) {
    this.setData({ filterType: e.currentTarget.dataset.type })
    this.loadData()
  },

  async loadData() {
    const app = getApp()
    // 冷启动时 globalData 可能尚未水合，需回退本地存储，否则列表一直空白
    const stationId = (app.globalData.userInfo && app.globalData.userInfo.stationId)
      || app.globalData.stationId
      || wx.getStorageSync(STORAGE_KEYS.STATION_ID)
      || null

    this.setData({ loading: true })
    try {
      const res = await getCustomers(stationId)
      const keyword = this.data.keyword.trim()
      const filterType = this.data.filterType
      let list = (res.data || []).map(decorate)
      if (filterType !== 'all') {
        const t = Number(filterType)
        list = list.filter(c => c.customerType === t)
      }
      if (keyword) {
        list = list.filter(c =>
          (c.name || '').includes(keyword) || (c.phone || '').includes(keyword)
        )
      }
      this.setData({ list })
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  onCall(e) {
    const { phone } = e.currentTarget.dataset
    if (phone) wx.makePhoneCall({ phoneNumber: phone })
  },

  // 跳转客户画像/权限管理
  onManage(e) {
    const { id } = e.currentTarget.dataset
    wx.navigateTo({ url: `/pages/station-mgmt/customers/detail/index?id=${id}` })
  }
})
