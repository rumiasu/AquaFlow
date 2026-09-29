// 站长端「站间结算」台账（v67 / 2026-09-27 产品拍板 4.a + 4.b）。
//
// **这一页回答什么**：跨站单的钱收在**归属站**（票钱/微信款在它手上）、营收却算**接单站**
// （`coalesce(settle_station_id, delivery_station_id, station_id)`）——
// 以前系统里**没有任何一处**能回答"谁欠谁、欠多少、什么时候算办完"（实测：接单站名下 0 条支付流水，
// 看板却显示了一笔"已收款"）。
//
// 三条口径（正本 `docs/design/31` §8，**前端一个字都不自己算**）：
//   · 「欠多少」是**后端实时算**的（真相源＝订单与支付流水），页面上每个金额都来自响应；
//   · 「按什么价结」有两条：默认**折算实付**（票当初实付多少钱），卖票站可改成**按挂牌价**
//     （差价由卖票站承担）—— 后者是**站长点**，系统不会自动改；
//   · 「办完没有」只有两种事实：**登记结清**（付款方登记，钱线下走，系统只留痕、不假装打款）
//     与**冲销**（订单取消/退款后那笔应付不再成立）。
//
// ⚠️ 三个"不"（都是会真出错的形状）：
//   ① **不自己算金额**：`direction/basisText/statusText/amount` 全部用后端下发的值，
//      前端只做千分位与拼串；口径文案由服务端一处持有（同 statusText / payMethodText 的既定判据）。
//   ② **不判断谁有权**：能不能结清/改价由后端判（`canSettle` / `canChangePrice` 只是用来显示按钮，
//      真越权时后端会拒并把原因回给我们）。
//   ③ **不藏失败**：拉不到台账必须出声，静默失败会让站长以为"没人欠我钱"。

const {
  getInterStationSettlements,
  settleInterStation,
  reverseInterStation
} = require('../../../api/station-mgmt')
const { priceByListed } = require('../../../api/delivery')

Page({
  data: {
    loading: true,
    loaded: false,
    // 三个数：别人欠本站 / 本站欠别人 / 净额（正 = 净收）
    summary: { receivableText: '0.00', payableText: '0.00', netText: '0.00', netPositive: true },
    scopeNote: '',
    items: [],
    dangling: [],
    unsettledCount: 0
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this.loadData()
  },

  async onPullDownRefresh() {
    await this.loadData()
    wx.stopPullDownRefresh()
  },

  async loadData() {
    this.setData({ loading: true })
    try {
      const res = await getInterStationSettlements()
      const d = res.data || {}
      this.setData({
        loaded: true,
        summary: this.decorateSummary(d),
        scopeNote: d.scopeNote || '',
        items: (d.items || []).map((it) => this.decorateItem(it)),
        dangling: (d.danglingSettled || []).map((it) => this.decorateItem(it)),
        unsettledCount: Number(d.unsettledCount) || 0
      })
    } catch (err) {
      // 静默失败 = 站长以为"本站没有站间欠款"，而这正是这一页要回答的问题
      console.error('[InterStation] 台账加载失败:', err)
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  /** 金额与方向全部来自后端；这里只做展示格式化（wxml 不做计算）。 */
  decorateSummary(d) {
    const money = (v) => Number(v || 0).toFixed(2)
    const net = Number(d.netAmount || 0)
    return {
      receivableText: money(d.receivableAmount),
      payableText: money(d.payableAmount),
      netText: money(Math.abs(net)),
      // 正 = 净收（别人欠我多）；负 = 净付。**方向由文案承担**，不显示负号（站长看数字更顺）
      netPositive: net >= 0,
      netLabel: net >= 0 ? '别人净欠本站' : '本站净欠别人'
    }
  },

  /**
   * 逐单展示字段。口径说明（`_ruleText`）把"这个数是怎么来的"写在行里 ——
   * 柜台与站长按同一句话对账，不用回去翻设计文档。
   */
  decorateItem(it) {
    const money = (v) => Number(v || 0).toFixed(2)
    const basis = Number(it.basis)
    let rule = it.basisText || ''
    if (basis === 2) {
      rule += `（${it.ticketQty} 张 × 约 ${money(it.unitPrice)}）`
    } else if (basis === 3) {
      rule += `（${it.ticketQty} 张 × 约 ${money(it.unitPrice)}，比折算实付多出的部分由本单卖票站承担）`
    }
    // [2026-09-29 拍板] 票覆盖的配送费/楼层费一起结进金额：费用 > 0 必须点破，
    // 否则站长拿「张数 × 单价」一乘对不上金额，差的正是这笔费用（谁也不解释）
    if ((basis === 2 || basis === 3) && Number(it.feeAmount || 0) > 0) {
      rule += `，另含配送/楼层费 ${money(it.feeAmount)}`
    }
    // 结清了就把"什么时候算办完、凭据是什么"显示出来 —— 那正是这一页的验收标准
    let settledText = ''
    if (Number(it.status) === 2 && it.settledTime) {
      settledText = '已结清 · ' + String(it.settledTime).replace('T', ' ').slice(0, 16)
      if (it.settleNote) settledText += ' · ' + it.settleNote
    }
    return Object.assign({}, it, {
      _key: String(it.orderId),
      _amountText: money(it.amount),
      _ruleText: rule,
      _routeText: (it.fromStationName || '？') + ' → ' + (it.toStationName || '？'),
      _settledText: settledText,
      _payMethodText: it.paymentMethodText || ''
    })
  },

  /** 登记结清：只有**付款方**（本站）能点；凭据说明可空 —— 钱是线下走的，系统只留痕。 */
  onSettle(e) {
    const id = e.currentTarget.dataset.id
    const amount = e.currentTarget.dataset.amount
    wx.showModal({
      title: '登记这笔已结清',
      // editable 的输入框用于凭据说明（转账流水号/经手人）：可留空，
      // 因为"什么时候算办完"靠的是时间与登记人，凭据说明是加分项而不是门槛。
      editable: true,
      placeholderText: '凭据说明：转账流水号 / 经手人（可留空）',
      content: '',
      confirmText: '登记结清',
      success: async (res) => {
        if (!res.confirm) return
        wx.showLoading({ title: '处理中...' })
        try {
          await settleInterStation(id, res.content || '')
          wx.hideLoading()
          wx.showToast({ title: `已登记 ¥${amount} 结清`, icon: 'none' })
          this.loadData()
        } catch (err) {
          wx.hideLoading()
          // 后端会给出可读原因（"这笔钱在 X 站手上，应由该站登记结清"等），原样透出
          wx.showToast({ title: err.message || '登记失败', icon: 'none' })
        }
      }
    })
  },

  /** 卖出站改按挂牌价结这一单（差价由本站承担）——**是站长点，系统不会自动改**。 */
  onPriceByListed(e) {
    const id = e.currentTarget.dataset.id
    wx.showModal({
      title: '按挂牌价结这一单',
      content: '改成按挂牌价结后，接单站能拿到更多，多出来的差价由本站承担。'
        + '系统不会自动改价，也不会改动客户已付的钱。确定要改吗？',
      confirmText: '按挂牌价',
      success: async (res) => {
        if (!res.confirm) return
        wx.showLoading({ title: '处理中...' })
        try {
          await priceByListed(id)
          wx.hideLoading()
          wx.showToast({ title: '已改为按挂牌价结', icon: 'success' })
          this.loadData()
        } catch (err) {
          wx.hideLoading()
          wx.showToast({ title: err.message || '改价失败', icon: 'none' })
        }
      }
    })
  },

  /** 冲销：订单取消/退款后那笔应付不再成立（那笔钱还没真付出去时才该点）。 */
  onReverse(e) {
    const id = e.currentTarget.dataset.id
    const amount = e.currentTarget.dataset.amount
    wx.showModal({
      title: '冲销这一笔',
      content: `这一单已经取消或退款，¥${amount} 这笔站间应付不再成立。`
        + '冲销只改状态、不删记录，事后仍查得到。若钱**已经真的付给对方**了，请先与对方协商，不要直接冲销。',
      confirmText: '冲销',
      success: async (res) => {
        if (!res.confirm) return
        wx.showLoading({ title: '处理中...' })
        try {
          await reverseInterStation(id)
          wx.hideLoading()
          wx.showToast({ title: '已冲销', icon: 'success' })
          this.loadData()
        } catch (err) {
          wx.hideLoading()
          wx.showToast({ title: err.message || '冲销失败', icon: 'none' })
        }
      }
    })
  },

  onOpenOrder(e) {
    const id = e.currentTarget.dataset.id
    if (!id) return
    wx.navigateTo({ url: `/pages/order/detail?id=${id}` })
  }
})
