// 站长资产调整单详情（含快照、证据图、执行 / 撤销）
const { getAdjustment, executeAdjustment, reverseAdjustment } = require('../../../../../api/station-mgmt')

/**
 * 快照字段的展示标签。
 * 后端 before/after 快照是 JSON 串：{"right":..,"over":..,"occupied":..,"ticket":..,"deposit":..}，
 * 这里只把键翻成人话（字段名，不是「类型→文案」映射），键名一律照抄后端。
 */
const SNAPSHOT_FIELDS = [
  { key: 'right', label: '桶权益(个)' },
  { key: 'over', label: '欠桶(个)' },
  { key: 'occupied', label: '占用(个)' },
  { key: 'ticket', label: '水票(张)' },
  { key: 'deposit', label: '押金(元)' }
]

function parseSnapshot(raw) {
  if (!raw) return null
  try {
    const obj = typeof raw === 'string' ? JSON.parse(raw) : raw
    return (obj && typeof obj === 'object') ? obj : null
  } catch (e) {
    // 解析失败不吞掉：页面上退回展示原始串（raw），保证站长能看到真实数据
    return null
  }
}

/**
 * 把 before/after 两个快照串合并成一张对照表（在 JS 里拼好，wxml 只做取值）。
 * 任一侧缺失时该侧显示「—」，不拿 0 冒充真实值。
 */
function buildSnapshotRows(beforeRaw, afterRaw) {
  const before = parseSnapshot(beforeRaw)
  const after = parseSnapshot(afterRaw)
  if (!before && !after) return []
  const val = (obj, key) => {
    if (!obj) return '—'
    const v = obj[key]
    return (v === null || v === undefined) ? '—' : String(v)
  }
  return SNAPSHOT_FIELDS.map(f => ({
    label: f.label,
    before: val(before, f.key),
    after: val(after, f.key)
  }))
}

/**
 * 展示字段全部来自后端下发的文案（adjustTypeText / statusText），
 * 前端不做任何「类型→文案」「状态→文案」映射，只做数值格式化与布尔派生。
 */
function decorate(a) {
  const qty = a.qty
  const amount = a.amount
  const unitPrice = a.unitPrice
  const hasQty = qty !== null && qty !== undefined
  const hasAmount = amount !== null && amount !== undefined
  const hasUnitPrice = unitPrice !== null && unitPrice !== undefined
  // evidence 是逗号分隔串；上传接口返回的是可直接给 <image> 用的 URL
  const evidenceList = a.evidence
    ? String(a.evidence).split(',').map(s => s.trim()).filter(Boolean)
    : []
  return Object.assign({}, a, {
    hasQty: hasQty,
    // 带符号展示：正数补 + 号。仅 OVER_ADJUST 可能为负（负=核销欠桶）
    qtyText: hasQty ? (qty > 0 ? '+' + qty : String(qty)) : '',
    hasAmount: hasAmount,
    amountText: hasAmount ? Number(amount).toFixed(2) : '',
    hasUnitPrice: hasUnitPrice,
    unitPriceText: hasUnitPrice ? Number(unitPrice).toFixed(2) : '',
    isMigratedText: a.isMigrated === 1 ? '是（按商品当前押金推断，退款时需二次确认）' : '否',
    evidenceList: evidenceList,
    snapshotRows: buildSnapshotRows(a.beforeSnapshot, a.afterSnapshot),
    beforeRaw: a.beforeSnapshot || '',
    afterRaw: a.afterSnapshot || '',
    createTimeText: a.createTime || '',
    executeTimeText: a.executeTime || '',
    isPending: a.status === 'PENDING',
    isEffective: a.status === 'EFFECTIVE',
    // 仅用于配色，文案仍用后端下发的 statusText
    statusClass: a.status === 'PENDING' ? 'pending' : (a.status === 'EFFECTIVE' ? 'effective' : 'muted')
  })
}

Page({
  data: {
    id: null,
    loading: true,
    detail: null,
    denied: false,
    deniedText: '',
    executing: false,
    reversing: false,
    reverseClientToken: ''
  },

  onLoad(options) {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      this.setData({ denied: true, deniedText: '当前账号没有水站业务权限' })
      app.routeByRole(true)
      return
    }
    if (!app.isStationManager()) {
      this.setData({ denied: true, deniedText: '资产调整仅站长可操作，请联系站长处理' })
      return
    }
    const id = options && options.id ? Number(options.id) : null
    this.setData({
      id: id,
      // 撤销的幂等键：与新建同规则（时间戳+随机数），前缀区分以免与创建单的 token 撞车；
      // 同一页面复用同一个 token，重复点「确认撤销」不会生成第二张反向单。
      reverseClientToken: 'ADJR-' + Date.now() + '-' + Math.floor(Math.random() * 1000)
    })
    if (!id) {
      this.setData({ loading: false })
      wx.showToast({ title: '缺少调整单 ID', icon: 'none' })
      return
    }
    this.loadData()
  },

  onPullDownRefresh() {
    if (this.data.denied || !this.data.id) {
      wx.stopPullDownRefresh()
      return
    }
    this.loadData().then(() => wx.stopPullDownRefresh())
  },

  async loadData() {
    if (!this.data.id) return
    this.setData({ loading: true })
    try {
      const res = await getAdjustment(this.data.id)
      if (res.code === 0 && res.data) {
        this.setData({ detail: decorate(res.data) })
      } else {
        this.setData({ detail: null })
        wx.showToast({ title: res.message || '调整单不存在', icon: 'none' })
      }
    } catch (err) {
      this.setData({ detail: null })
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  /** 撤销的单据 / 反向单互跳。用 redirectTo 而不是 navigateTo：两单互相引用，层层压栈迟早爆栈 */
  onOpenRelated(e) {
    const id = e.currentTarget.dataset.id
    if (!id) return
    wx.redirectTo({ url: `/pages/station-mgmt/customers/adjust/detail/index?id=${id}` })
  },

  onPreviewEvidence(e) {
    const index = Number(e.currentTarget.dataset.index)
    wx.previewImage({ current: this.data.detail.evidenceList[index], urls: this.data.detail.evidenceList })
  },

  /** 执行（PENDING → EFFECTIVE）：回调式 wx.showModal 二次确认 */
  onExecute() {
    const detail = this.data.detail
    if (!detail || this.data.executing) return
    const what = detail.hasQty ? `数量 ${detail.qtyText}` : `金额 ¥${detail.amountText}`
    wx.showModal({
      title: '执行调整单',
      content: `确定执行 ${detail.adjustNo}？\n类型：${detail.adjustTypeText}，${what}。\n执行后立即改写该客户在本站的资产，事后只能通过「撤销」生成反向单。`,
      confirmText: '执行',
      confirmColor: '#4F8EF7',
      success: async (res) => {
        if (!res.confirm) return
        this.setData({ executing: true })
        wx.showLoading({ title: '执行中...' })
        try {
          await executeAdjustment(detail.id)
          wx.hideLoading()
          wx.showToast({ title: '已执行生效', icon: 'success' })
          this.loadData()
        } catch (err) {
          wx.hideLoading()
          wx.showToast({ title: err.message || '执行失败', icon: 'none', duration: 3000 })
          this.loadData()
        } finally {
          this.setData({ executing: false })
        }
      }
    })
  },

  /**
   * 撤销（EFFECTIVE → 反向单）。
   * 第一步 editable 弹窗填原因（必填），第二步二次确认把类型/数量与原因再念一遍，
   * 两步都是回调式 API。
   */
  onReverse() {
    const detail = this.data.detail
    if (!detail || this.data.reversing) return
    wx.showModal({
      title: '撤销原因（必填）',
      content: '撤销会生成一张反向单并立即执行，原单置「已撤销」。请输入撤销原因：',
      editable: true,
      placeholderText: '如：补录数量填错、重复补录',
      success: (r) => {
        if (!r.confirm) return
        const reason = String(r.content || '').trim()
        if (!reason) {
          wx.showToast({ title: '撤销原因不能为空', icon: 'none' })
          return
        }
        const what = detail.hasQty ? `数量 ${detail.qtyText}` : `金额 ¥${detail.amountText}`
        wx.showModal({
          title: '确认撤销',
          content: `将撤销 ${detail.adjustNo}（${detail.adjustTypeText}，${what}）并生成反向单立即生效。\n原因：${reason}`,
          confirmText: '确认撤销',
          confirmColor: '#FF3B30',
          success: (r2) => {
            if (r2.confirm) this.doReverse(reason)
          }
        })
      }
    })
  },

  async doReverse(reason) {
    const detail = this.data.detail
    if (!detail) return
    this.setData({ reversing: true })
    wx.showLoading({ title: '撤销中...' })
    try {
      const res = await reverseAdjustment(detail.id, reason, this.data.reverseClientToken)
      wx.hideLoading()
      const no = res && res.data && res.data.adjustNo ? res.data.adjustNo : ''
      wx.showToast({ title: no ? '已撤销，反向单 ' + no : '已撤销', icon: 'success' })
      this.loadData()
    } catch (err) {
      wx.hideLoading()
      wx.showToast({ title: err.message || '撤销失败', icon: 'none', duration: 3000 })
      // 失败常见原因是原单状态已被并发改变 → 重新拉一次让页面自愈
      this.loadData()
    } finally {
      this.setData({ reversing: false })
    }
  }
})
