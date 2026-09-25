// 站长资产调整单列表（本站；带 customerId 时只看该客户）
const { listAdjustments, executeAdjustment } = require('../../../../api/station-mgmt')

const PAGE_SIZE = 20

/**
 * 列表项展示字段。
 * adjustTypeText / statusText 一律直接渲染后端下发的文案，前端**不维护**任何
 * 「类型→文案」「状态→文案」映射表（历史上两端各写一套映射，导致新客下单 100% 失败）。
 * 这里只做数值格式化：qty 为带符号原值（唯一可能为负的是 OVER_ADJUST，负=核销欠桶）。
 */
function decorate(item) {
  const qty = item.qty
  const amount = item.amount
  const hasQty = qty !== null && qty !== undefined
  const hasAmount = amount !== null && amount !== undefined
  return Object.assign({}, item, {
    hasQty: hasQty,
    hasAmount: hasAmount,
    qtyText: hasQty ? (qty > 0 ? '+' + qty : String(qty)) : '',
    amountText: hasAmount ? Number(amount).toFixed(2) : '',
    createTimeText: item.createTime || '',
    isPending: item.status === 'PENDING',
    // 仅用于配色，文案仍用后端下发的 statusText
    statusClass: item.status === 'PENDING' ? 'pending' : (item.status === 'EFFECTIVE' ? 'effective' : 'muted')
  })
}

Page({
  data: {
    customerId: null,
    list: [],
    total: 0,
    page: 1,
    size: PAGE_SIZE,
    hasMore: false,
    loading: false,
    executing: false,
    denied: false,
    deniedText: ''
  },

  onLoad(options) {
    const customerId = options && options.customerId ? Number(options.customerId) : null
    this.setData({ customerId })

    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      this.setData({ denied: true, deniedText: '当前账号没有水站业务权限' })
      app.routeByRole(true)
      return
    }
    // 调整单是站长专属能力（后端 @RequireRole("STATION_MANAGER")）：配送员进来只会收到
    // 一条与本页无关的权限错误，这里前置拦掉并把原因写在页面上。
    if (!app.isStationManager()) {
      this.setData({ denied: true, deniedText: '资产调整仅站长可操作，请联系站长处理' })
    }
  },

  onShow() {
    if (this.data.denied) return
    this.loadData()
  },

  onPullDownRefresh() {
    if (this.data.denied) {
      wx.stopPullDownRefresh()
      return
    }
    this.loadData().then(() => wx.stopPullDownRefresh())
  },

  onReachBottom() {
    this.loadMore()
  },

  buildQuery(page) {
    const query = { page: page, size: this.data.size }
    if (this.data.customerId) query.customerId = this.data.customerId
    return query
  },

  async loadData() {
    if (this.data.denied) return
    this.setData({ loading: true })
    try {
      const res = await listAdjustments(this.buildQuery(1))
      const data = res.data || {}
      const list = (data.list || []).map(decorate)
      const total = data.total || 0
      this.setData({ list: list, total: total, page: 1, hasMore: list.length < total })
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  async loadMore() {
    if (this.data.denied || this.data.loading || !this.data.hasMore) return
    const next = this.data.page + 1
    this.setData({ loading: true })
    try {
      const res = await listAdjustments(this.buildQuery(next))
      const data = res.data || {}
      const list = this.data.list.concat((data.list || []).map(decorate))
      const total = data.total || 0
      this.setData({ list: list, total: total, page: next, hasMore: list.length < total })
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  // 新建必须绑定客户（后端 create 的 customerId 为必填），所以没有 customerId 时不给进
  onCreate() {
    if (!this.data.customerId) {
      wx.showToast({ title: '请从客户详情页进入后再新建调整', icon: 'none', duration: 3000 })
      return
    }
    wx.navigateTo({
      url: `/pages/station-mgmt/customers/adjust/edit/index?customerId=${this.data.customerId}`
    })
  },

  onDetail(e) {
    const { id } = e.currentTarget.dataset
    wx.navigateTo({ url: `/pages/station-mgmt/customers/adjust/detail/index?id=${id}` })
  },

  /**
   * 执行（PENDING → EFFECTIVE）：会真正改写客户资产（建权益批次 / 动押金 / 动水票）。
   * 二次确认用**回调式** wx.showModal（本端统一写法，见 barrel-return/index.js）。
   */
  onExecute(e) {
    const { id } = e.currentTarget.dataset
    const item = this.data.list.find(x => x.id === id)
    if (!item) return
    if (this.data.executing) return
    const what = item.hasQty ? `数量 ${item.qtyText}` : `金额 ¥${item.amountText}`
    wx.showModal({
      title: '执行调整单',
      content: `确定执行 ${item.adjustNo}？\n类型：${item.adjustTypeText}，${what}。\n执行后立即改写该客户在本站的资产，事后只能通过「撤销」生成反向单，不能删除。`,
      confirmText: '执行',
      confirmColor: '#4F8EF7',
      success: async (res) => {
        if (!res.confirm) return
        this.setData({ executing: true })
        wx.showLoading({ title: '执行中...' })
        try {
          await executeAdjustment(id)
          wx.hideLoading()
          wx.showToast({ title: '已执行生效', icon: 'success' })
          this.loadData()
        } catch (err) {
          wx.hideLoading()
          wx.showToast({ title: err.message || '执行失败', icon: 'none', duration: 3000 })
          // 失败常见原因是状态已被并发处理（重复执行被 CAS 拒绝）→ 重新拉一次让列表自愈
          this.loadData()
        } finally {
          this.setData({ executing: false })
        }
      }
    })
  }
})
