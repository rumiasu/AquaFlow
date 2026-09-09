const { getStaffList } = require('../../../api/delivery')
const { createStaff, detachStaff } = require('../../../api/station-mgmt')

const AVATAR_COLORS = ['#409EFF', '#67C23A', '#E6A23C', '#F56C6B', '#909399', '#9254DE']

const ROLE_TEXT = { STATION_MANAGER: '站长', DELIVERY: '配送员', ADMIN: '管理员' }

function decorate(item) {
  const name = item.name || '?'
  item.avatarText = name.charAt(0)
  let h = 0
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0
  item.avatarColor = AVATAR_COLORS[h % AVATAR_COLORS.length]
  const phone = item.phone || ''
  item.phoneMasked = phone.length === 11 ? phone.replace(/(\d{3})\d{4}(\d{4})/, '$1****$2') : (phone || '未填写')
  item.roleText = item.roleText || ROLE_TEXT[item.role] || item.role || ''
  item.statusText = item.statusText || (item.status === 1 ? '在职' : '离职')
  return item
}

Page({
  data: {
    list: [],
    keyword: '',
    filterStatus: 'all', // all | 1 在职 | 0 离职
    showModal: false,
    formName: '',
    formPhone: ''
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this.loadData()
  },

  onKeywordInput(e) {
    this.setData({ keyword: e.detail.value })
  },

  onSearch() {
    this.loadData()
  },

  onFilterStatus(e) {
    this.setData({ filterStatus: e.currentTarget.dataset.status })
    this.loadData()
  },

  async loadData() {
    try {
      const app = getApp()
      const stationId = app.globalData.userInfo?.stationId
      if (!stationId) return
      const res = await getStaffList(stationId)
      const keyword = this.data.keyword.trim()
      const filterStatus = this.data.filterStatus
      let list = (res.data || []).map(decorate)
      if (filterStatus !== 'all') {
        const s = Number(filterStatus)
        list = list.filter(s2 => s2.status === s)
      }
      if (keyword) {
        list = list.filter(c =>
          (c.name || '').includes(keyword) || (c.phone || '').includes(keyword)
        )
      }
      this.setData({ list })
    } catch (err) {
      console.error(err)
    }
  },

  onShowAdd() {
    this.setData({ showModal: true, formName: '', formPhone: '' })
  },

  async onSubmitAdd() {
    const { formName, formPhone } = this.data
    if (!formName) return wx.showToast({ title: '请输入姓名', icon: 'none' })
    if (!formPhone) return wx.showToast({ title: '请输入电话', icon: 'none' })

    wx.showLoading({ title: '添加中...' })
    try {
      await createStaff({ name: formName, phone: formPhone, role: 'DELIVERY' })
      wx.hideLoading()
      wx.showToast({ title: '添加成功', icon: 'success' })
      this.setData({ showModal: false })
      this.loadData()
    } catch (err) {
      wx.hideLoading()
      wx.showToast({ title: err.message || '添加失败', icon: 'none' })
    }
  },

  onViewProfile(e) {
    const { id } = e.currentTarget.dataset
    wx.navigateTo({ url: `/pages/station-mgmt/staff/profile/index?id=${id}` })
  },

  onDetach(e) {
    const { id, name } = e.currentTarget.dataset
    wx.showModal({
      title: '解除所属',
      content: `确定解除配送员「${name}」的所属关系？`,
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' })
          try {
            await detachStaff(id)
            wx.hideLoading()
            wx.showToast({ title: '已解除', icon: 'success' })
            this.loadData()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '操作失败', icon: 'none' })
          }
        }
      }
    })
  },

  onCloseModal() { this.setData({ showModal: false }) },
  stopPropagation() {}
})
