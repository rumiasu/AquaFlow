// ⚠️ 直接用 utils/request，不引 api/station-mgmt.js 与 config/api.js：
// 那两个文件正被另一个工作流（商品图片库）改动，共用会让两边未提交的改动纠缠在一起。
// 路径常量写在本文件里，理由同上。
const { get } = require('../../../utils/request')

const EXCEPTIONS = '/api/manager/exceptions'
const BARREL_LOSS = '/api/manager/barrel-loss'

/**
 * 站长端「桶异常」：异常单列表 + 近 30 天统计 + 桶损耗读数。规格见 docs/design/20 §3.5 / §7。
 *
 * 三条口径（改这个页面时必须守住）：
 *   1. **本页只读**。后端确实有处置入口（handle / execute：同意、改判、忽略、退水票、退现金、
 *      调资产），但那些动作会**真动钱和桶账**，要不要在界面上开放是产品决定 —— 所以这里
 *      一个按钮都没有。别顺手加"一键补偿"：§8.17 那次事故就是"界面显示已补偿、账上一分没动"。
 *   2. **前端不做任何判定**：状态/类别的中文用服务端下发的 `statusText` / `categoryText`，
 *      "还等着处理"用服务端下发的 `pending` 布尔（不自带状态码表，也不比对中文文案）。
 *   3. **桶损耗本站不做**（2026-09-18 决定：桶是跟水厂换的，破损丢失归水厂）：
 *      所以那块正常情况下永远是 0，页面只在"真有登记"时才显示数字，没登记就只显示
 *      服务端下发的 note —— 一个 0 被读成"桶没丢过"就是假数据。
 *
 * 运营告警页（站点告警）指向的"桶异常单处置"就是本页；处置动作开放前，这里只能看。
 */
Page({
  data: {
    loading: true,
    onlyPending: false,
    items: [],
    total: 0,
    loaded: 0,
    stats: null,
    loss: null
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this.load()
  },

  /** json 里开了 enablePullDownRefresh，就必须有对应的处理函数，否则下拉只转圈不停。 */
  async onPullDownRefresh() {
    try {
      await this.load()
    } finally {
      wx.stopPullDownRefresh()
    }
  },

  onTogglePending() {
    this.setData({ onlyPending: !this.data.onlyPending })
  },

  async load() {
    const today = new Date()
    const from = dayText(new Date(today.getTime() - 29 * 24 * 3600 * 1000))
    const to = dayText(today)
    try {
      // 一次拿 50 条：水站的异常单量级很小（真实库跑了一个多月只有个位数），
      // 超过时页面会明确提示"只显示了最近 N 条"，不做静默截断。
      const [listRes, statsRes, lossRes] = await Promise.all([
        get(EXCEPTIONS, { page: 1, size: 50 }),
        get(EXCEPTIONS + '/stats', { startDate: from, endDate: to }),
        get(BARREL_LOSS, { from, to })
      ])

      const raw = (listRes.data && listRes.data.records) || []
      const items = raw.map(r => ({
        id: r.id,
        orderId: r.orderId,
        customerId: r.customerId,
        pending: !!r.pending,
        categoryText: r.categoryText,
        statusText: r.statusText,
        // 差异：交付 N 桶、收回 M 桶 —— 少收的那部分就是这张单要处理的
        qtyText: '送 ' + (r.deliveryQty === null || r.deliveryQty === undefined ? '-' : r.deliveryQty)
          + ' / 回 ' + (r.returnQty === null || r.returnQty === undefined ? '-' : r.returnQty)
          + ' / 差 ' + (r.discrepancy === null || r.discrepancy === undefined ? '-' : r.discrepancy),
        staffNote: r.staffNote || '',
        managerNote: r.managerNote || '',
        suggestText: suggestText(r),
        timeText: dateTimeText(r.createdAt)
      }))

      this.setData({
        items,
        total: (listRes.data && listRes.data.total) || raw.length,
        loaded: raw.length,
        stats: statsRes.data || null,
        loss: normalizeLoss(lossRes.data)
      })
    } catch (err) {
      // 静默失败会让站长以为"本站没有异常"，从而漏掉处置 —— 必须出声
      wx.showToast({ title: err.message || '异常列表加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  }
})

/** 服务端给的"建议补偿"（它自己不判断要不要补，只把当初算出来的数摆出来） */
function suggestText(r) {
  const parts = []
  if (r.suggestedTicketQty) parts.push('水票 ' + r.suggestedTicketQty + ' 张')
  if (r.suggestedCashAmount && Number(r.suggestedCashAmount) !== 0) {
    parts.push('现金 ￥' + Number(r.suggestedCashAmount).toFixed(2))
  }
  return parts.length ? parts.join(' / ') : ''
}

function normalizeLoss(d) {
  if (!d) return null
  const items = (d.items || []).map(it => ({
    productName: it.productName || ('商品 #' + it.productId),
    lostQty: it.lostQty,
    damagedQty: it.damagedQty,
    totalQty: it.totalQty,
    lostText: it.lostText,
    damagedText: it.damagedText
  }))
  return {
    from: d.from,
    to: d.to,
    items,
    totalLost: d.totalLost,
    totalDamaged: d.totalDamaged,
    totalLoss: d.totalLoss,
    hasWriteEntry: !!d.hasWriteEntry,
    note: d.note || ''
  }
}

function pad(n) {
  return n < 10 ? '0' + n : '' + n
}

/** 本地日期串（后端按 LocalDate 解析；不能用 toISOString —— 那是 UTC，会差一天） */
function dayText(d) {
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
}

/** ISO-8601 串直接 new Date()，禁止 replace(/-/g,'/')（本仓有明文约定） */
function dateTimeText(s) {
  if (!s) return ''
  const d = new Date(s)
  if (isNaN(d.getTime())) return ''
  return dayText(d) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes())
}
