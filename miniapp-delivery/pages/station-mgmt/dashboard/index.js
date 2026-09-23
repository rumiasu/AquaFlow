// 站长数据看板（综合报表）
const { getDashboardReport } = require('../../../api/station-mgmt')

const RANGES = [
  { key: 'today', label: '今日' },
  { key: '7d', label: '近7天' },
  { key: '30d', label: '近30天' }
]

Page({
  data: {
    loading: true,
    error: '',
    ranges: RANGES,
    range: '7d',
    report: null,
    // ===== 图表数据（JS 预计算，模板只取字段） =====
    trendChart: null,          // {labels, values, max}
    trendMetric: 'orders',     // orders 单量 / amount 金额
    trendTitle: '订单趋势',
    metricSwitchable: true,
    payChart: null,            // 环形图数据
    hourChart: null,           // {bars:[{label,height,orders}]}
    recordsExpanded: false,
    recordsPreviewCount: 3
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

  onRangeChange(e) {
    const key = e.currentTarget.dataset.key
    if (!key || key === this.data.range) return
    this.setData({ range: key })
    this.loadData()
  },

  onMetricChange(e) {
    const key = e.currentTarget.dataset.key
    if (!key || key === this.data.trendMetric) return
    this.setData({ trendMetric: key }, () => this.drawTrend())
  },

  onToggleRecords() {
    this.setData({ recordsExpanded: !this.data.recordsExpanded })
  },

  async loadData() {
    this.setData({ loading: true, error: '' })
    try {
      const res = await getDashboardReport(this.data.range)
      if (!res || res.code !== 0 || !res.data) {
        this.setData({ error: (res && res.message) || '报表加载失败', report: null })
        return
      }
      const report = this.decorate(res.data)
      this.setData({ report }, () => this.prepareCharts(report))
    } catch (err) {
      this.setData({ error: err.message || '加载失败', report: null })
    } finally {
      this.setData({ loading: false })
    }
  },

  /** 文案/配色/百分比统一在 JS 算好，模板只取字段（wxml 不做计算，避免兼容问题） */
  decorate(r) {
    const money = (v) => Number(v || 0).toFixed(2)
    const s = r.summary || {}

    r.grossAmountText = money(s.grossAmount)
    r.paidAmountText = money(s.paidAmount)
    r.pendingAmountText = money(s.pendingAmount)
    r.avgOrderAmountText = money(s.avgOrderAmount)
    r.completeRate = Number(s.completeRate) || 0
    r.cancelRate = Number(s.cancelRate) || 0

    // 核心指标卡：环比方向/文案由后端给（deltaDir, good），这里只补展示用文本
    const c = r.compare || {}
    const mk = (key, label, valueText) => {
      const d = c[key] || {}
      return {
        key, label, valueText,
        deltaText: d.deltaText || '持平',
        deltaDir: d.deltaDir || 'flat',
        good: d.good,
        previousText: this.fmtValue(d.previous, d.unit)
      }
    }
    r.metricCards = [
      mk('orderCount', '订单数', String(Number(s.orderCount) || 0)),
      mk('grossAmount', '营业额', '¥' + r.grossAmountText),
      mk('paidAmount', '已收款', '¥' + r.paidAmountText),
      mk('newCustomers', '新客户', String(Number(s.newCustomers) || 0)),
      mk('cancelledOrders', '取消单', String(Number(s.cancelledOrders) || 0)),
      mk('barrelsIn', '新增桶', String(Number(s.barrelsIn) || 0))
    ]

    r.statusDistribution = (r.statusDistribution || []).map((x, i) => ({
      ...x, idx: i, width: Math.max(4, Number(x.share) || 0)
    }))

    r.topProducts = (r.topProducts || []).map((x, i) => ({
      ...x, idx: i, rank: i + 1,
      qty: Number(x.qty) || 0,
      width: Math.max(4, Number(x.share) || 0),
      revenueText: money(x.revenue)
    }))

    r.topCustomers = (r.topCustomers || []).map((x, i) => ({
      ...x, idx: i, rank: i + 1, amountText: money(x.amount)
    }))

    r.staffPerformance = (r.staffPerformance || []).map((x, i) => ({
      ...x, idx: i,
      totalOrders: Number(x.totalOrders) || 0,
      completedOrders: Number(x.completedOrders) || 0,
      width: Math.min(100, Number(x.completeRate) || 0)
    }))

    // [2026-09-19 IA 重组 C2] 原有两行派生字段（owedCustomers / hasPending）只为已删除的
    // 「欠桶客户 TOP」与待收款横幅服务，一并删除。后端响应里仍有这两个字段（不改后端），
    // 前端不再读它 —— 别为了"看起来没浪费"又把它们渲染出来。
    r.hasData = Number(s.orderCount) > 0
    return r
  },

  fmtValue(v, unit) {
    if (v === null || v === undefined) return '-'
    if (unit === '元') return '¥' + Number(v).toFixed(2)
    return String(v) + (unit ? ' ' + unit : '')
  },

  // ==================== 图表数据准备 ====================

  prepareCharts(report) {
    // 1) 趋势图：今日看 24 小时分布（按天只有 1 个点没意义），7d/30d 看按天趋势
    if (report.range === 'today') {
      const hours = report.hourDistribution || []
      const max = Math.max(1, ...hours.map(h => Number(h.orders) || 0))
      this.setData({
        trendTitle: '今日下单时段分布',
        trendChart: { labels: hours.map(h => h.label), values: hours.map(h => Number(h.orders) || 0), max },
        trendMetric: 'orders',
        metricSwitchable: false
      }, () => this.drawTrend())
    } else {
      const trend = report.trend || []
      const metric = this.data.trendMetric
      const values = trend.map(t => metric === 'amount' ? Number(t.amount) || 0 : Number(t.orders) || 0)
      const max = Math.max(1, ...values)
      this.setData({
        trendTitle: report.label + (metric === 'amount' ? '营业额趋势' : '订单量趋势'),
        trendChart: { labels: trend.map(t => t.label), values, max },
        metricSwitchable: true
      }, () => this.drawTrend())
    }

    // 2) 支付方式环形图
    const pay = report.payMethodDistribution || []
    const palette = ['#4f8ef7', '#e6a23c', '#67c23a', '#909399', '#f56c6c']
    const total = pay.reduce((sum, x) => sum + (Number(x.cnt) || 0), 0)
    let acc = 0
    const segs = pay.map((x, i) => {
      const pct = total > 0 ? (Number(x.cnt) || 0) / total : 0
      const seg = {
        methodText: x.methodText,
        cnt: Number(x.cnt) || 0,
        amountText: x.amountText,
        share: Number(x.share) || 0,
        color: palette[i % palette.length],
        start: acc * Math.PI * 2,
        sweep: pct * Math.PI * 2
      }
      acc += pct
      return seg
    })
    this.setData({ payChart: { segs, total, hasData: total > 0 } }, () => this.drawDonut())

    // 3) 时段分布条（任何范围都展示，供排班参考）
    const hours = report.hourDistribution || []
    const hmax = Math.max(1, ...hours.map(h => Number(h.orders) || 0))
    this.setData({
      hourChart: {
        bars: hours.map(h => ({
          label: h.label,
          orders: Number(h.orders) || 0,
          height: Math.round((Number(h.orders) || 0) / hmax * 100)
        })),
        maxCount: hmax
      }
    })
  },

  // ==================== Canvas 绘制 ====================

  getCanvas(id, cb) {
    wx.createSelectorQuery().in(this)
      .select(id)
      .fields({ node: true, size: true })
      .exec((res) => {
        if (!res || !res[0] || !res[0].node) return
        const { node, width, height } = res[0]
        const dpr = (wx.getSystemInfoSync().pixelRatio) || 2
        node.width = width * dpr
        node.height = height * dpr
        const ctx = node.getContext('2d')
        ctx.setTransform(1, 0, 0, 1, 0, 0)
        ctx.scale(dpr, dpr)
        cb(ctx, width, height)
      })
  },

  drawTrend() {
    const chart = this.data.trendChart
    if (!chart || !chart.values || !chart.values.length) return
    this.getCanvas('#trendCanvas', (ctx, w, h) => {
      ctx.clearRect(0, 0, w, h)
      const padL = 34, padR = 12, padT = 16, padB = 26
      const cw = w - padL - padR
      const ch = h - padT - padB
      const n = chart.values.length
      const max = Math.max(1, chart.max)

      // 网格 + 纵轴刻度
      ctx.strokeStyle = '#eef1f5'
      ctx.fillStyle = '#8a9099'
      ctx.lineWidth = 1
      ctx.font = '10px sans-serif'
      ctx.textAlign = 'right'
      ctx.textBaseline = 'middle'
      for (let i = 0; i <= 4; i++) {
        const y = padT + ch * i / 4
        ctx.beginPath(); ctx.moveTo(padL, y); ctx.lineTo(w - padR, y); ctx.stroke()
        ctx.fillText(String(Math.round(max * (4 - i) / 4)), padL - 6, y)
      }

      const asLine = this.data.trendMetric === 'amount' && n > 1
      if (!asLine) {
        // 柱状
        const slot = cw / n
        const barW = Math.max(2, Math.min(26, slot * 0.6))
        chart.values.forEach((v, i) => {
          const bh = max > 0 ? (v / max) * ch : 0
          const x = padL + slot * i + (slot - barW) / 2
          const y = padT + ch - bh
          ctx.fillStyle = v > 0 ? '#4f8ef7' : '#e3e8ef'
          this.roundRect(ctx, x, y, barW, Math.max(bh, 1), Math.min(3, barW / 2))
          ctx.fill()
        })
      } else {
        // 金额：折线 + 渐变填充
        const step = n > 1 ? cw / (n - 1) : cw
        const pts = chart.values.map((v, i) => ({
          x: padL + step * i,
          y: padT + ch - (max > 0 ? (v / max) * ch : 0)
        }))
        ctx.beginPath()
        pts.forEach((p, i) => i === 0 ? ctx.moveTo(p.x, p.y) : ctx.lineTo(p.x, p.y))
        ctx.strokeStyle = '#4f8ef7'
        ctx.lineWidth = 2
        ctx.lineJoin = 'round'
        ctx.stroke()
        ctx.lineTo(pts[pts.length - 1].x, padT + ch)
        ctx.lineTo(pts[0].x, padT + ch)
        ctx.closePath()
        const g = ctx.createLinearGradient(0, padT, 0, padT + ch)
        g.addColorStop(0, 'rgba(79,142,247,0.25)')
        g.addColorStop(1, 'rgba(79,142,247,0)')
        ctx.fillStyle = g
        ctx.fill()
        ctx.fillStyle = '#4f8ef7'
        pts.forEach(p => { ctx.beginPath(); ctx.arc(p.x, p.y, 2.5, 0, Math.PI * 2); ctx.fill() })
      }

      // 横轴标签（点太多时隔一个显示）
      ctx.fillStyle = '#8a9099'
      ctx.textAlign = 'center'
      ctx.textBaseline = 'top'
      const skip = n > 16 ? 3 : (n > 8 ? 2 : 1)
      const slot = cw / n
      chart.labels.forEach((lb, i) => {
        if (i % skip !== 0 && i !== n - 1) return
        ctx.fillText(lb, padL + slot * i + slot / 2, padT + ch + 6)
      })
    })
  },

  drawDonut() {
    const chart = this.data.payChart
    if (!chart || !chart.hasData) return
    this.getCanvas('#payCanvas', (ctx, w, h) => {
      ctx.clearRect(0, 0, w, h)
      const cx = w / 2, cy = h / 2
      const r = Math.min(w, h) / 2 - 6
      const ring = r * 0.38
      const rad = r - ring / 2
      ctx.lineWidth = ring
      chart.segs.forEach(s => {
        ctx.beginPath()
        ctx.strokeStyle = s.color
        ctx.arc(cx, cy, rad, s.start - Math.PI / 2, s.start + s.sweep - Math.PI / 2)
        ctx.stroke()
      })
      ctx.fillStyle = '#1f2329'
      ctx.font = 'bold 16px sans-serif'
      ctx.textAlign = 'center'
      ctx.textBaseline = 'middle'
      ctx.fillText(String(chart.total), cx, cy - 8)
      ctx.fillStyle = '#8a9099'
      ctx.font = '10px sans-serif'
      ctx.fillText('总单数', cx, cy + 10)
    })
  },

  roundRect(ctx, x, y, w, h, r) {
    r = Math.min(r, w / 2, h / 2)
    ctx.beginPath()
    ctx.moveTo(x + r, y)
    ctx.arcTo(x + w, y, x + w, y + h, r)
    ctx.arcTo(x + w, y + h, x, y + h, r)
    ctx.arcTo(x, y + h, x, y, r)
    ctx.arcTo(x, y, x + w, y, r)
    ctx.closePath()
  }
})
