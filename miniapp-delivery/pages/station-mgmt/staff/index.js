// 站长端「员工」——员工列表 + 员工画像入口 + 绑定/解绑申请审核。
//
// [2026-09-19 IA 重组] 这里合并了原先散在「我的」tab 里的第二份员工管理
// （pages/mine：配送员管理 + 绑定申请两个页签）。两份打的是同一份数据
// （getStaffList / detachStaff / MANAGER_BIND_RELEASE），属重复实现，
// 「我的」那份已降级为一句跳转。定稿见 docs/design/24-站长端IA重组-定稿.md。
//
// ⚠️ 从「我的」搬过来时**不能省掉的两处**（都是踩过的坑，注释随实现一起搬）：
//   1. 绑定申请(type=1)与解绑申请(type=2)共用同一张待审批列表，但**后端是两个端点**：
//      /approve 对 type!=1 直接报「这不是绑定申请」→ 解绑会永久悬挂。审批/拒绝都必须按 type 分流。
//   2. GET /api/staff 返回的是 Staff 实体，**没有 bindStatus 字段**，判 s.bindStatus 恒为
//      undefined → 每个配送员都显示「已绑定」，有解绑申请的看不出来。要用已加载的待审批
//      列表里 type=2 的 staffId 反推（syncUnbindFlags）。
const { getStaffList } = require('../../../api/delivery')
const { createStaff, detachStaff } = require('../../../api/station-mgmt')
const { get, post } = require('../../../utils/request')
const { API } = require('../../../config/api')
const { STORAGE_KEYS } = require('../../../utils/storage-keys')

const AVATAR_COLORS = ['#409EFF', '#67C23A', '#E6A23C', '#F56C6B', '#909399', '#9254DE']

const ROLE_TEXT = { STATION_MANAGER: '站长', DELIVERY: '配送员', ADMIN: '管理员' }

function firstChar(str) {
  return str ? String(str).charAt(0) : ''
}

function decorate(item) {
  const name = item.name || '?'
  item.avatarText = firstChar(name)
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
    activeTab: 'staff',   // staff 员工 | apply 绑定申请
    list: [],
    keyword: '',
    filterStatus: 'all', // all | 1 在职 | 0 离职
    showModal: false,
    formName: '',
    formPhone: '',
    // 待审批申请（绑定 type=1 与解绑 type=2 混在一张列表里）
    applications: []
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this.loadData()
  },

  onTabSwitch(e) {
    this.setData({ activeTab: e.currentTarget.dataset.tab })
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
      // 冷启动时 globalData 可能尚未水合（userInfo 为 null），只读它会让列表一直空白。
      // 依次兜底：globalData.userInfo -> globalData -> 本地存储
      const stationId = (app.globalData.userInfo && app.globalData.userInfo.stationId)
        || app.globalData.stationId
        || wx.getStorageSync(STORAGE_KEYS.STATION_ID)
        || null
      if (!stationId) {
        this.setData({ list: [] })
        wx.showToast({ title: '未获取到水站信息，请重新登录', icon: 'none' })
        return
      }
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
      // 员工列表先出，再补申请（申请只用来打「解绑申请中」标记 + 申请页签，失败不该拖垮员工列表）
      this.loadApplications()
    } catch (err) {
      // 原实现只 console.error，请求失败时页面无任何反馈，表现为"空白"
      console.error(err)
      this.setData({ list: [] })
      wx.showToast({ title: (err && err.message) || '加载失败，请重试', icon: 'none' })
    }
  },

  async loadApplications() {
    try {
      const r = await get(API.MANAGER_BIND_APPLICATIONS)
      // 后端 applicationToMap 下发的是 staffName / staffPhone / createTime / applyNote，
      // 此前前端读 name / phone / applyTime / remark，字段全不匹配 → 所有申请人都显示成
      // 「申请人 · 暂无电话 · 今天」，无法分辨。这里统一归一到视图字段名（真实字段优先）。
      const applications = (r.data || []).map(a => ({
        ...a,
        name: a.staffName || a.nickname || a.name || '',
        phone: a.staffPhone || a.phone || '',
        applyTime: a.createTime || a.applyTime || '',
        applyNote: a.applyNote || a.remark || a.skill || '',
        isUnbind: Number(a.type) === 2,
        typeText: Number(a.type) === 2 ? '申请解绑' : '申请绑定',
        _loading: false,
        _firstChar: firstChar(a.staffName || a.nickname || a.name || a.nickName || '申')
      }))
      this.setData({ applications })
      this.syncUnbindFlags()
    } catch (e) {
      // 静默失败会让站长看不到待审申请（以为没人申请）。出声。
      console.error('[Staff] 绑定申请加载失败:', e)
      this.setData({ applications: [] })
      this.syncUnbindFlags()
      wx.showToast({ title: '绑定申请加载失败', icon: 'none' })
    }
  },

  /** 用待审批里 type=2（解绑）的 staffId 反推员工行的「解绑申请中」标记 */
  syncUnbindFlags() {
    const pendingUnbind = new Set(
      (this.data.applications || []).filter(a => a.isUnbind).map(a => a.staffId)
    )
    this.setData({
      list: (this.data.list || []).map(s => ({ ...s, _unbindPending: pendingUnbind.has(s.id) }))
    })
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

  /** 同意申请：按 type 分流端点（解绑必须走 /unbind-confirm，否则永久悬挂） */
  async onApproveApply(e) {
    const { id } = e.currentTarget.dataset
    const apps = this.data.applications
    const idx = apps.findIndex(a => a.id === id)
    const isUnbind = idx >= 0 && apps[idx].isUnbind
    if (idx >= 0) { apps[idx]._loading = true; this.setData({ applications: [...apps] }) }
    try {
      await post(isUnbind ? API.MANAGER_BIND_UNBIND_CONFIRM : API.MANAGER_BIND_APPROVE, { applicationId: id })
      wx.showToast({ title: isUnbind ? '已同意解绑' : '已同意绑定', icon: 'success' })
      this.loadData()
    } catch (err) {
      if (idx >= 0) { apps[idx]._loading = false; this.setData({ applications: [...apps] }) }
      wx.showToast({ title: err.message || '操作失败', icon: 'none' })
    }
  },

  async onRejectApply(e) {
    const { id } = e.currentTarget.dataset
    const apps = this.data.applications
    const idx = apps.findIndex(a => a.id === id)
    const isUnbind = idx >= 0 && apps[idx].isUnbind
    if (idx >= 0) { apps[idx]._loading = true; this.setData({ applications: [...apps] }) }
    try {
      await post(isUnbind ? API.MANAGER_BIND_UNBIND_REJECT : API.MANAGER_BIND_REJECT, { applicationId: id })
      wx.showToast({ title: isUnbind ? '已拒绝解绑' : '已拒绝绑定', icon: 'success' })
      this.loadData()
    } catch (err) {
      if (idx >= 0) { apps[idx]._loading = false; this.setData({ applications: [...apps] }) }
      wx.showToast({ title: err.message || '操作失败', icon: 'none' })
    }
  },

  onCall(e) {
    const { phone } = e.currentTarget.dataset
    if (phone) wx.makePhoneCall({ phoneNumber: phone })
  },

  onCloseModal() { this.setData({ showModal: false }) },
  onNameInput(e) { this.setData({ formName: e.detail.value }) },
  onPhoneInput(e) { this.setData({ formPhone: e.detail.value }) },
  stopPropagation() {}
})
