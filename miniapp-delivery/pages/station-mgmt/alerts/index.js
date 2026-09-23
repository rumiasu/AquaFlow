// 站长端「运营告警」：本站需要站长处理的运营故障（桶异常待处置、补偿已执行、异常被忽略）。
//
// 边界（很重要，别越界）：
//   · 后端 `GET /api/manager/alerts` **固定只返回 `alert_type='OPERATION'` 且本站的记录**。
//     **系统故障告警（对账不平、补偿失败、未预期 500）不会、也不应该出现在这里** ——
//     它带平台级细节（不平的表与金额），收件人是系统管理员（开发者），站长既看不懂也修不了。
//     所以前端不要试图"顺带把全部告警都拉出来"，那会踩越权知情。
//   · 本页**只读**。告警指向的处理动作都在各自的业务页面（如桶异常单去异常列表处置），
//     这里只负责"让站长看见"，不在这里复制一套操作，否则两处逻辑必然分叉。
//
// 投递状态（notifyStatus）的语义，展示时要如实说明：
//   LOGGED = 已落库。**外部渠道没配或站长侧推送尚未接入时就是这个值，属正常**，
//            不代表告警不存在 —— 本页面本身就是它的送达方式。
//   PUSHED = 已推送外部渠道；FAILED = 推送失败（同样已落库）。

const { getAlerts } = require('../../../api/station-mgmt')

// level → 展示文案与配色类。后端只有 WARN / INFO 会出现在运营侧；
// ERROR 留给系统告警，这里仍保留映射以免将来复用该页时漏样式。
const LEVEL_TEXT = { ERROR: '严重', WARN: '待处置', INFO: '留痕' }
const LEVEL_CLASS = { ERROR: 'level-error', WARN: 'level-warn', INFO: 'level-info' }

// 关联业务对象类型 → 中文。后端传的是常量字符串，前端只做展示映射，不做逻辑判断。
const RELATED_TEXT = { ORDER_BARREL_EXCEPTION: '桶异常单' }

Page({
  data: {
    list: [],
    loading: true,
    filter: 'ALL',
    filters: [
      { value: 'ALL', label: '全部' },
      { value: 'WARN', label: '待处置' },
      { value: 'INFO', label: '留痕' }
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
    const filter = e.currentTarget.dataset.value
    if (filter === this.data.filter) return
    this.setData({ filter })
    this.loadData()
  },

  async loadData() {
    this.setData({ loading: true })
    try {
      const res = await getAlerts(200)
      const all = res.data || []
      const filtered = this.data.filter === 'ALL'
        ? all
        : all.filter(a => a.level === this.data.filter)

      // wxml 不做计算与兜底文案（项目约定：wxml 禁调方法、禁复杂表达式），这里一次性算好。
      const list = filtered.map(a => ({
        id: a.id,
        title: a.title || '(无标题)',
        content: a.content || '',
        levelText: LEVEL_TEXT[a.level] || a.level || '未知',
        levelClass: LEVEL_CLASS[a.level] || 'level-info',
        sourceText: a.source ? ('来源 ' + a.source) : '',
        timeText: this.formatTime(a.createTime),
        relatedText: a.relatedType
          ? ((RELATED_TEXT[a.relatedType] || a.relatedType) + (a.relatedId ? ' #' + a.relatedId : ''))
          : '',
        notifyText: a.notifyStatus === 'PUSHED' ? '已推送'
          : (a.notifyStatus === 'FAILED' ? '推送失败（已落库）' : '已落库')
      }))

      this.setData({ list })
    } catch (err) {
      // 静默失败会让站长以为"本站没有异常"，从而漏掉处置 —— 必须出声
      console.error('[Alerts] 加载失败:', err)
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  formatTime(d) {
    if (!d) return ''
    // 后端下发 ISO 字符串（Web 层 Jackson3 未开 WRITE_DATES_AS_TIMESTAMPS）。
    // wxml 里不能 new Date / 调方法，所以统一在这里格式化；解析失败则退化为截断显示，
    // 绝不显示 "Invalid Date"。
    const t = new Date(String(d).replace(' ', 'T'))
    if (isNaN(t.getTime())) return String(d).replace('T', ' ').slice(0, 16)
    const pad = n => (n < 10 ? '0' + n : '' + n)
    return t.getFullYear() + '-' + pad(t.getMonth() + 1) + '-' + pad(t.getDate())
      + ' ' + pad(t.getHours()) + ':' + pad(t.getMinutes())
  },

  async onPullDownRefresh() {
    await this.loadData()
    wx.stopPullDownRefresh()
  }
})
