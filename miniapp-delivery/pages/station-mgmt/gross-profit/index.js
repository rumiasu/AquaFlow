// ⚠️ 直接用 utils/request，不引 api/station-mgmt.js 与 config/api.js：
// 那两个文件正被另一个工作流（商品图片库）改动，共用会让两边未提交的改动纠缠在一起。
// 路径常量写在本文件里，理由同上。
const { get, put } = require('../../../utils/request')

const GP = '/api/manager/gross-profit'
const MISSING_COST = GP + '/missing-cost'
const COST = GP + '/cost'

/**
 * 站长端「毛利」：进货成本 + 期间毛利报表。规格见 docs/design/20 §2。
 *
 * 三条口径（改这个页面时必须守住）：
 *   1. **成本价是站长的商业机密**，只走站长端接口（后端全部带 @RequireRole("STATION_MANAGER")）
 *      —— 进价泄露等于竞争对手知道你的底价。本页任何数据都不要往顾客端带。
 *   2. **缺成本的商品不给毛利数字**。后端在那种行上下发 profit=null、
 *      profitText="未填成本，无法计算"，合计也返回 null。页面必须**原样展示**，
 *      绝不能把 null 当 0 相减 —— 那会让站长以为这一单赚了整整一个售价。
 *   3. **成本改了历史毛利会跟着变**（本版不做批次成本核算）。后端下发的 costBasisNote
 *      必须原样展示，不能让站长以为这是"当时的真实毛利"。
 *
 * ⚠️ 所有金额与文案都用服务端下发的值（含 profitRateText / costPriceText / missingCostHint），
 * 页面里不做任何毛利算术。
 */
Page({
  data: {
    stationId: null,
    loading: true,
    from: '',
    to: '',
    report: null,
    missingCost: [],
    // 成本编辑弹层
    costVisible: false,
    costProduct: null,
    costInput: '',
    costSaving: false
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    const stationId = (app.globalData.userInfo || {}).stationId
    if (!stationId) {
      wx.showToast({ title: '未识别到所属水站', icon: 'none' })
      return
    }
    this.setData({ stationId })
    this.load()
  },

  async load() {
    this.setData({ loading: true })
    try {
      // from/to 留空 = 让服务端用默认区间（本月 1 号 ~ 今天），避免前端自己算日期口径
      const qs = []
      if (this.data.from) qs.push('from=' + this.data.from)
      if (this.data.to) qs.push('to=' + this.data.to)
      const [reportRes, missingRes] = await Promise.all([
        get(GP + (qs.length ? '?' + qs.join('&') : '')),
        get(MISSING_COST)
      ])
      const d = reportRes.data || {}
      this.setData({
        report: d,
        missingCost: missingRes.data || [],
        // 服务端把生效区间回传，界面据此显示（不要自己拼日期）
        from: d.from || this.data.from,
        to: d.to || this.data.to
      })
    } catch (err) {
      wx.showToast({ title: err.message || '报表加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  onDate(e) {
    this.setData({ [e.currentTarget.dataset.field]: e.detail.value }, () => this.load())
  },

  /** 快捷区间：只改 from/to 再拉一次，日期口径仍由服务端解释。 */
  onQuickRange(e) {
    const days = Number(e.currentTarget.dataset.days)
    const fmt = (d) => d.getFullYear() + '-' +
      String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0')
    const now = new Date()
    if (days === 0) {
      // 本月：交给服务端默认（清空 from/to）
      this.setData({ from: '', to: '' }, () => this.load())
      return
    }
    const start = new Date(now.getTime() - (days - 1) * 24 * 3600 * 1000)
    this.setData({ from: fmt(start), to: fmt(now) }, () => this.load())
  },

  /** 点报表行 → 给该商品设/改成本价。 */
  onOpenCost(e) {
    const id = Number(e.currentTarget.dataset.id)
    const item = (this.data.report.items || []).find(x => Number(x.productId) === id)
    if (!item) return
    this.setData({
      costVisible: true,
      costProduct: item,
      costInput: item.missingCost === 1 || item.costPrice == null ? '' : String(item.costPrice)
    })
  },

  /** 从「未填成本」列表点进来——那里只有 productId/productName，没有价格。 */
  onOpenCostFromMissing(e) {
    const id = Number(e.currentTarget.dataset.id)
    const p = this.data.missingCost.find(x => Number(x.productId) === id)
    if (!p) return
    this.setData({
      costVisible: true,
      costProduct: { productId: p.productId, productName: p.productName, missingCost: 1 },
      costInput: ''
    })
  },

  onCostInput(e) {
    this.setData({ costInput: e.detail.value })
  },

  onCloseCost() {
    this.setData({ costVisible: false, costProduct: null })
  },

  async onSaveCost() {
    if (this.data.costSaving) return
    const p = this.data.costProduct
    const raw = String(this.data.costInput || '').trim()
    const n = Number(raw)
    if (raw === '' || isNaN(n) || n < 0) {
      wx.showToast({ title: '请填非负的成本价', icon: 'none' })
      return
    }
    this.setData({ costSaving: true })
    try {
      await put(COST, { productId: p.productId, costPrice: n })
      wx.showToast({ title: '已保存', icon: 'success' })
      this.setData({ costVisible: false, costProduct: null })
      await this.load()
    } catch (err) {
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ costSaving: false })
    }
  },

  /**
   * 清除成本价。**必须显式传 clear: true** ——
   * 后端把"缺 costPrice 且没声明 clear"当成错误，正是为了不让拼错的键变成一次静默清空。
   */
  async onClearCost() {
    if (this.data.costSaving) return
    const p = this.data.costProduct
    this.setData({ costSaving: true })
    try {
      await put(COST, { productId: p.productId, clear: true })
      wx.showToast({ title: '已清除', icon: 'success' })
      this.setData({ costVisible: false, costProduct: null })
      await this.load()
    } catch (err) {
      wx.showToast({ title: err.message || '清除失败', icon: 'none' })
    } finally {
      this.setData({ costSaving: false })
    }
  },

  onShowHelp() {
    wx.showModal({
      title: '毛利怎么算',
      content: '毛利 = 该期间的销售收入 − 卖出数量 × 当前进货成本价。\n\n' +
        '没填成本价的商品不给毛利数字（只列收入）—— 把成本当 0 相减会让你以为这一单\n' +
        '赚了整整一个售价，那是最坏的一种"看起来正确"。\n\n' +
        '本版不做批次成本核算：改了成本价，历史期间的毛利也会跟着重算。',
      showCancel: false
    })
  }
})
