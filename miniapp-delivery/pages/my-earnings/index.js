// ⚠️ 直接用 utils/request，不引 api/station-mgmt.js 与 config/api.js：
// 那两个文件正被另一个工作流（商品图片库）改动，共用会让两边未提交的改动纠缠在一起。
// 路径常量写在本文件里，理由同上。
const { get } = require('../../utils/request')

const EARNINGS = '/api/delivery/earnings'

/**
 * 员工端「我的工资」：配送员（以及自己也在送水的站长）看自己的计件收益与结算单。
 * 规格见 docs/design/18，接口是员工自助端点（身份取自登录态）。
 *
 * 三条口径（改这个页面时必须守住）：
 *   1. **这是自助页**：服务端只认登录态里的身份，路径里没有 staffId。别"顺手"加一个 ——
 *      加了服务端也只会返回你自己那份（`DeliveryMyEarningsIntegrationTest` 锁着这条）。
 *   2. **前端不算钱**：合计、未结合计、结算单金额一律用服务端下发的值；
 *      类型文案用 `kindText`、单据状态用 `statusText`（本仓前端禁止自带映射表）。
 *   3. **发钱在线下**：这里没有「提现 / 打款」按钮，只有站长登记过的发放状态。
 *
 * 金额与时间在本文件里格式化：wxml 不能调用 Page 方法，也不能用 Math / Date。
 */
Page({
  data: {
    loading: true,
    range: 'month',        // month（本月至今） | today
    from: '',
    to: '',
    periodTotalText: '0.00',
    unsettledTotalText: '0.00',
    items: [],
    payrolls: [],
    note: ''
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

  onRange(e) {
    const range = e.currentTarget.dataset.range
    if (range === this.data.range) return
    this.setData({ range })
    this.load()
  },

  async load() {
    const today = dayText(new Date())
    const from = this.data.range === 'today' ? today : monthStartText(new Date())
    try {
      const res = await get(EARNINGS, { from, to: today })
      const d = res.data || {}
      const items = (d.items || []).map(it => ({
        id: it.id,
        kindText: it.kindText,
        amountText: money(it.amount),
        // 只有计件类收益才有「数量 × 单价」，人工调整没有
        detailText: it.qty ? it.qty + ' × ' + money(it.unitAmount) : '',
        orderId: it.orderId,
        note: it.note || '',
        timeText: dateTimeText(it.createTime)
      }))
      const payrolls = (d.payrolls || []).map(p => ({
        id: p.id,
        payrollNo: p.payrollNo,
        periodText: text(p.periodStart) + ' ~ ' + text(p.periodEnd),
        totalText: money(p.totalAmount),
        // ⚠️ 状态文案由服务端下发，前端不写 1/2/3 映射表
        statusText: p.statusText,
        paidText: p.paidTime ? dateTimeText(p.paidTime) : ''
      }))
      this.setData({
        from: d.from || from,
        to: d.to || today,
        periodTotalText: money(d.periodTotal),
        unsettledTotalText: money(d.unsettledTotal),
        items,
        payrolls,
        note: d.note || ''
      })
    } catch (err) {
      // 静默失败会让配送员以为"这个月没挣到钱"，必须出声
      wx.showToast({ title: err.message || '工资加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  }
})

function money(v) {
  const n = Number(v)
  return isNaN(n) ? '0.00' : n.toFixed(2)
}

function text(v) {
  return v === null || v === undefined ? '' : String(v)
}

function pad(n) {
  return n < 10 ? '0' + n : '' + n
}

/** 本地日期串（后端按 LocalDate 解析，不能用 toISOString —— 那是 UTC，会差一天） */
function dayText(d) {
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
}

function monthStartText(d) {
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-01'
}

/** ISO-8601 串直接 new Date()，禁止 replace(/-/g,'/')（本仓有明文约定） */
function dateTimeText(s) {
  if (!s) return ''
  const d = new Date(s)
  if (isNaN(d.getTime())) return ''
  return dayText(d) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes())
}
