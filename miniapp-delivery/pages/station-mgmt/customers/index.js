// 站长客户查询
const { getCustomers, getOfflinePaymentSummary, updateOfflinePayment } = require('../../../api/station-mgmt')
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
    filterType: 'all', // all | 1 个人 | 2 企业
    // 货到付款开通弹窗（v48）：站长在"设置是否允许货到付款"的那一刻就要看到该客户欠了多少、
    // 为什么现在用不了（原因文案来自后端唯一判据，前端不自己编）
    codModal: { visible: false, customerId: null, customerName: '', orderCount: 0, overdueCount: 0, overdueAmount: '0.00', blockReason: '' },
    codForm: { enabled: false, allowFirstOrder: false, singleLimit: '' }
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
      // 关键字交给服务端：站长认人靠地址，而「阳光81301」这种缩写与「八栋/8栋」的
      // 数字混用只有归一化之后才匹配得上（本地 includes 一定漏）。
      // 因此这里**不再**本地 filter name/phone —— 服务端已经把结果筛好了。
      const keyword = this.data.keyword.trim()
      const res = await getCustomers(stationId, keyword)
      const filterType = this.data.filterType
      let list = (res.data || []).map(decorate)
      if (filterType !== 'all') {
        const t = Number(filterType)
        list = list.filter(c => c.customerType === t)
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
  },

  /* ==================== 货到付款开通与约束（v48） ==================== */

  /**
   * 打开「货到付款」设置弹窗。
   *
   * 先把后端算好的依据拉下来（欠款/逾期/历史订单数/当前能不能用 + 原因），再回填当前配置 ——
   * 站长是**在设置的那一刻**看到"这个客户欠着多少、为什么现在用不了"，
   * 而不是开通完再被下单拒绝。
   */
  async onCod(e) {
    const { id, name } = e.currentTarget.dataset
    try {
      const res = await getOfflinePaymentSummary(id)
      const d = res.data || {}
      this.setData({
        codModal: {
          visible: true,
          customerId: id,
          customerName: name || '',
          orderCount: d.orderCount || 0,
          overdueCount: d.overdueCount || 0,
          overdueAmount: d.overdueAmount != null ? d.overdueAmount : '0.00',
          blockReason: d.blockReason || ''
        },
        codForm: {
          enabled: Number(d.offlinePaymentEnabled) === 1,
          allowFirstOrder: Number(d.allowFirstOrder) === 1,
          // 上限为 null = 不限 → 输入框留空（不要填 0，那会被当成"上限 0 元"）
          singleLimit: d.singleLimit == null ? '' : String(d.singleLimit)
        }
      })
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    }
  },

  onCodClose() {
    this.setData({ 'codModal.visible': false })
  },

  /** 弹窗内容区的空处理器：阻止点击穿透到遮罩（否则点输入框就把弹窗关了）。 */
  onCodNoop() {},

  onCodToggle(e) {
    this.setData({ 'codForm.enabled': e.detail.value })
  },

  onCodFirstToggle(e) {
    this.setData({ 'codForm.allowFirstOrder': e.detail.value })
  },

  onCodLimitInput(e) {
    this.setData({ 'codForm.singleLimit': e.detail.value })
  },

  async onCodSave() {
    const { customerId } = this.data.codModal
    const { enabled, allowFirstOrder, singleLimit } = this.data.codForm
    const limitText = (singleLimit || '').trim()
    // 留空 = 不限（null）；填了就必须是非负数，服务端也会再挡一道
    const payload = {
      offlinePaymentEnabled: enabled ? 1 : 0,
      allowFirstOrder: allowFirstOrder ? 1 : 0,
      singleLimit: limitText === '' ? null : limitText
    }
    try {
      const res = await updateOfflinePayment(customerId, payload)
      if (res.code !== 0) {
        wx.showToast({ title: res.message || '保存失败', icon: 'none' })
        return
      }
      wx.showToast({ title: '已保存', icon: 'success' })
      this.setData({ 'codModal.visible': false })
      this.loadData()
    } catch (err) {
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    }
  }
})
