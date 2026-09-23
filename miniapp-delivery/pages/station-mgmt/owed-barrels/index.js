// 站长端「欠桶台账」：本站**当前仍欠桶**的客户，按欠得最久排前面。
//
// 口径（与后端 OwedBarrelVO 一致，**前端不重算**）：
//   · overQty      = customer_barrel_over.over_qty 的**当前净额**（只取 > 0；已被回收的部分不在这里）
//   · owedDaysText = 由 owed_since 算出的自然日；历史存量行未回填时为「天数未知」
//   · urgent       = 后端判定的「已达催收线」（≥7 天），仅用于标红
//
// 明细（哪一单欠的、差几个、处理到哪一步）走现成的异常单（order_barrel_exception，
// discrepancy > 0），与这里的「当前净额」是**两回事，不可相加**，故本页不合并展示。
//
// 本页**只读、只预警**：下单是否放行与欠桶无关（原「欠桶 ≥5 拒绝下单」硬拦已于
// 2026-09-15 按产品决定移除），所以这里没有任何"拦截/放行"按钮。

const { getOwedBarrels } = require('../../../api/station-mgmt')

Page({
  data: {
    list: [],
    loading: true,
    minDays: 0,
    filters: [
      { value: 0, label: '全部' },
      { value: 7, label: '欠 ≥ 7 天' }
    ]
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this.loadData()
  },

  onFilter(e) {
    const minDays = Number(e.currentTarget.dataset.value) || 0
    if (minDays === this.data.minDays) return
    this.setData({ minDays })
    this.loadData()
  },

  async loadData() {
    this.setData({ loading: true })
    try {
      const res = await getOwedBarrels(this.data.minDays)
      // wxml 不做计算与兜底文案（项目约定：wxml 禁调方法、禁复杂表达式），
      // 这里一次性算好展示字段；_key 用于 wx:key —— 同一客户可能因不同桶型出现多行。
      const list = (res.data || []).map(r => Object.assign({}, r, {
        _key: `${r.customerId}-${r.productId}`,
        _name: r.customerName || ('客户 ID:' + r.customerId),
        _product: (r.productName || '未知商品') + (r.productSpec ? ' · ' + r.productSpec : ''),
        _daysText: r.owedDaysText || '天数未知',
        _sinceText: r.owedSinceText || '',
        _urgent: !!r.urgent
      }))
      this.setData({ list })
    } catch (err) {
      // 静默失败会让站长误以为「本站没人欠桶」，从而漏掉催收 —— 必须出声
      console.error('[OwedBarrels] 加载失败:', err)
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  /** 下钻到客户详情：那里有完整桶账（权益/持有/占用/暂存）与押金明细 */
  onOpenCustomer(e) {
    const id = e.currentTarget.dataset.id
    if (!id) return
    wx.navigateTo({ url: `/pages/station-mgmt/customers/detail/index?id=${id}` })
  },

  async onPullDownRefresh() {
    await this.loadData()
    wx.stopPullDownRefresh()
  }
})
