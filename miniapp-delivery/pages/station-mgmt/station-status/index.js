const {
  getStationStatus,
  updateStationStatus,
  getMyStation,
  updateStationCoordinates,
  getNotices,
  createNotice,
  updateNotice,
  deleteNotice
} = require('../../../api/station-mgmt')

/**
 * 营业状态 + 公告（站长）。
 *
 * 产品口径（2026-09-17 与站长确认）：
 *   · 营业状态是**软状态** —— 顾客照常下单，只是会在商城/下单页看到横幅、下单响应里带提示；
 *     "真的不接单"用的是另一套（station.status=2 停业，下单会被拒），两者别混。
 *   · 留言是配合状态的一句话说明（≤100 字），会原样展示给顾客。
 *   · 公告是本站通知（顾客端只读已发布的），支持草稿。
 *
 * 状态文案（1 正常运营 / 2 休息中 / 3 配送延迟 / 4 暂停配送可预约）**由后端下发**，
 * 前端只用 options 里的 value 提交，不要自己写一套 1..4 的中文映射表。
 */

const STATUS_OPTIONS = [
  { value: 1, name: '正常运营', desc: '照常接单配送' },
  { value: 2, name: '休息中', desc: '打烊/午休，稍后恢复' },
  { value: 3, name: '配送延迟', desc: '照常接单，送达会晚' },
  { value: 4, name: '暂停配送，可预约', desc: '今天不送，订单明天统一处理' }
]

Page({
  data: {
    loading: true,
    statusOptions: STATUS_OPTIONS,
    current: { operatingStatus: 1, statusText: '正常运营', note: '', customerHint: '' },
    picked: 1,
    noteInput: '',
    saving: false,

    // 水站坐标（配送范围判定用，2026-09-17 / v34）。
    // lat 为 null = 还没选点，此时配送范围校验会跳过（不是拒单）。
    coord: { lat: null, lng: null },
    coordSaving: false,

    notices: [],
    noticesLoading: false,
    showEdit: false,
    isAdd: true,
    editId: null,
    editForm: { title: '', content: '', published: true }
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this.loadStatus()
    this.loadCoordinates()
    this.loadNotices()
  },

  onPullDownRefresh() {
    Promise.all([this.loadStatus(), this.loadNotices()]).then(() => wx.stopPullDownRefresh())
  },

  async loadStatus() {
    this.setData({ loading: true })
    try {
      const res = await getStationStatus()
      const d = res.data || {}
      this.setData({
        current: {
          operatingStatus: d.operatingStatus || 1,
          statusText: d.statusText || '正常运营',
          note: d.note || '',
          customerHint: d.customerHint || '',
          statusUpdateTime: d.statusUpdateTime || ''
        },
        picked: d.operatingStatus || 1,
        noteInput: d.note || ''
      })
    } catch (err) {
      wx.showToast({ title: err.message || '营业状态加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  onPickStatus(e) {
    this.setData({ picked: Number(e.currentTarget.dataset.value) })
  },

  onNoteInput(e) {
    this.setData({ noteInput: e.detail.value })
  },

  async onSaveStatus() {
    // 文案与状态都由后端算，前端只负责提示"顾客会看到什么"
    this.setData({ saving: true })
    try {
      const res = await updateStationStatus(this.data.picked, this.data.noteInput)
      const d = res.data || {}
      this.setData({
        current: {
          operatingStatus: d.operatingStatus || 1,
          statusText: d.statusText || '正常运营',
          note: d.note || '',
          customerHint: d.customerHint || '',
          statusUpdateTime: d.statusUpdateTime || ''
        }
      })
      wx.showToast({ title: '已保存', icon: 'success' })
    } catch (err) {
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ saving: false })
    }
  },

  /* ==================== 水站坐标（配送范围用） ==================== */

  /**
   * 读本站坐标。
   *
   * ⚠️ 读失败**不弹错**：坐标只是配送范围判定的输入，读不到不该阻塞营业状态页。
   * 站长点「地图选点」时会再次暴露问题，那时再报错更有指向性。
   */
  async loadCoordinates() {
    try {
      const res = await getMyStation()
      const d = res.data || {}
      this.setData({
        coord: {
          // 三态别压扁：null 表示未设置，不能被 `|| null` 之外的写法顺手变成 0
          lat: (d.lat === null || d.lat === undefined) ? null : d.lat,
          lng: (d.lng === null || d.lng === undefined) ? null : d.lng
        }
      })
    } catch (err) {
      console.warn('load station coordinates failed:', err)
    }
  },

  /**
   * 地图选点并保存。
   *
   * 选完**立即保存**而不是"改了再点保存"：坐标这一项没有别的可改字段，
   * 多一步确认只会让站长以为还需要按别处的按钮。
   */
  onPickCoordinates() {
    wx.chooseLocation({
      success: async (res) => {
        this.setData({ coordSaving: true })
        try {
          await updateStationCoordinates(res.latitude, res.longitude)
          this.setData({ 'coord.lat': res.latitude, 'coord.lng': res.longitude })
          wx.showToast({ title: '坐标已保存', icon: 'success' })
        } catch (err) {
          wx.showToast({ title: err.message || '坐标保存失败', icon: 'none' })
        } finally {
          this.setData({ coordSaving: false })
        }
      },
      fail: (err) => {
        const msg = (err && err.errMsg) || ''
        if (msg.indexOf('auth') >= 0 || msg.indexOf('deny') >= 0) {
          wx.showToast({ title: '需要位置权限，请在设置中开启', icon: 'none' })
        }
      }
    })
  },

  /** 清除坐标：清除后范围校验跳过（不会拒单），所以文案要说清后果 */
  onClearCoordinates() {
    wx.showModal({
      title: '清除水站坐标',
      content: '清除后系统无法判断订单是否超出配送范围，会跳过范围校验（不会拒单）。确认清除？',
      success: async (res) => {
        if (!res.confirm) return
        try {
          await updateStationCoordinates(null, null)
          this.setData({ 'coord.lat': null, 'coord.lng': null })
          wx.showToast({ title: '已清除', icon: 'success' })
        } catch (err) {
          wx.showToast({ title: err.message || '清除失败', icon: 'none' })
        }
      }
    })
  },

  /* ==================== 公告 ==================== */

  async loadNotices() {
    this.setData({ noticesLoading: true })
    try {
      const res = await getNotices()
      const notices = (res.data || []).map(n => ({
        ...n,
        // [2026-09-18] statusText 一律用后端下发的（Notice.getStatusText，真相源 constant/NoticeStatus.java）。
        // 这里原来写的是 `n.status === 1 ? '已发布' : '草稿'` —— 正是本仓禁止的"前端自带映射表"：
        // 后端一旦改状态口径，前端不会跟随、也不会报错，只会一直显示错的那句。
        timeText: (n.createTime || '').replace('T', ' ').slice(0, 16)
      }))
      this.setData({ notices })
    } catch (err) {
      wx.showToast({ title: err.message || '公告加载失败', icon: 'none' })
    } finally {
      this.setData({ noticesLoading: false })
    }
  },

  openAddNotice() {
    this.setData({
      showEdit: true,
      isAdd: true,
      editId: null,
      editForm: { title: '', content: '', published: true }
    })
  },

  openEditNotice(e) {
    const item = e.currentTarget.dataset.item
    this.setData({
      showEdit: true,
      isAdd: false,
      editId: item.id,
      editForm: { title: item.title || '', content: item.content || '', published: item.status === 1 }
    })
  },

  closeEditNotice() {
    this.setData({ showEdit: false })
  },

  stopPropagation() {},

  /** 弹窗遮罩上吞掉 touchmove，防止滚动穿透到页面（wxml 用 catchtouchmove） */
  preventMove() {},

  onNoticeFieldInput(e) {
    const field = e.currentTarget.dataset.field
    this.setData({ ['editForm.' + field]: e.detail.value })
  },

  onNoticePublishChange(e) {
    this.setData({ 'editForm.published': e.detail.value })
  },

  async onSaveNotice() {
    const { isAdd, editId, editForm } = this.data
    if (!editForm.title || !editForm.title.trim()) {
      wx.showToast({ title: '请填写公告标题', icon: 'none' })
      return
    }
    if (!editForm.content || !editForm.content.trim()) {
      wx.showToast({ title: '请填写公告内容', icon: 'none' })
      return
    }
    // type=2 水站通知（站长发的是站点通知，不是系统公告/活动）
    const payload = {
      title: editForm.title.trim(),
      content: editForm.content.trim(),
      type: 2,
      status: editForm.published ? 1 : 0
    }
    this.setData({ saving: true })
    try {
      if (isAdd) {
        await createNotice(payload)
        wx.showToast({ title: '已发布', icon: 'success' })
      } else {
        await updateNotice(editId, payload)
        wx.showToast({ title: '已保存', icon: 'success' })
      }
      this.setData({ showEdit: false })
      this.loadNotices()
    } catch (err) {
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ saving: false })
    }
  },

  onToggleNoticeStatus(e) {
    const item = e.currentTarget.dataset.item
    const next = item.status === 1 ? 0 : 1
    updateNotice(item.id, {
      title: item.title,
      content: item.content,
      type: item.type || 2,
      status: next
    })
      .then(() => {
        wx.showToast({ title: next === 1 ? '已发布' : '已下架', icon: 'success' })
        this.loadNotices()
      })
      .catch(err => wx.showToast({ title: err.message || '操作失败', icon: 'none' }))
  },

  onDeleteNotice(e) {
    const item = e.currentTarget.dataset.item
    wx.showModal({
      title: '删除公告',
      content: '删除后客户立刻看不到该公告，确认删除？',
      confirmText: '删除',
      confirmColor: '#f44336',
      success: (res) => {
        if (!res.confirm) return
        deleteNotice(item.id)
          .then(() => {
            wx.showToast({ title: '已删除', icon: 'success' })
            this.loadNotices()
          })
          .catch(err => wx.showToast({ title: err.message || '删除失败', icon: 'none' }))
      }
    })
  }
})
