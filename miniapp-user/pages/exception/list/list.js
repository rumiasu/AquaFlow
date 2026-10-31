const { captureSession, isCurrentSession } = require('../../../utils/token')
const { get } = require('../../../utils/request')
const { API } = require('../../../config/api')

function formatTime(time) {
  if (!time) return '记录时间待核实'
  let value = String(time).trim()
  if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}/.test(value)) value = value.replace(' ', 'T')
  const date = new Date(value)
  if (isNaN(date.getTime())) return '记录时间待核实'
  const pad = n => String(n).padStart(2, '0')
  return pad(date.getMonth() + 1) + '-' + pad(date.getDate()) + ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes())
}
function numeric(value) {
  return (typeof value === 'number' || (typeof value === 'string' && /^-?\d+(\.\d+)?$/.test(value.trim()))) && Number.isFinite(Number(value))
}
function chineseLabel(value, fallback) {
  // 后端是状态/类别文案的唯一来源；未知内部代号不回退给顾客。
  return typeof value === 'string' && /[\u3400-\u9fff]/.test(value) && !/[A-Za-z_]/.test(value) ? value : fallback
}
function compensationOf(item) {
  const actual = []
  const suggested = []
  // REFUND_CASH 的现行执行路径增加客户押金账户；字段名不能解释成现金到账。
  if (numeric(item.refundCashAmount) && Number(item.refundCashAmount) > 0) actual.push('补偿金额 ¥' + Number(item.refundCashAmount).toFixed(2) + '（去向：客户押金账户）')
  if (numeric(item.refundTicketQty) && Number(item.refundTicketQty) > 0) actual.push(item.refundTicketQty + ' 张水票')
  if (numeric(item.suggestedCashAmount) && Number(item.suggestedCashAmount) > 0) suggested.push('补偿金额 ¥' + Number(item.suggestedCashAmount).toFixed(2) + '（拟计入客户押金账户）')
  if (numeric(item.suggestedTicketQty) && Number(item.suggestedTicketQty) > 0) suggested.push(item.suggestedTicketQty + ' 张水票')
  if (actual.length) return (item.status === 'EXECUTED' ? '已执行方案：' : '登记方案（执行状态待核实）：') + actual.join('、')
  if (suggested.length) return '建议方案：' + suggested.join('、') + '（以水站处理结果为准）'
  if (item.managerAction === 'IGNORE') return '水站未执行补偿，请查看处理说明'
  return '补偿结果待核实'
}
Page({
  onContactStation() { wx.navigateTo({ url: '/pages/service/index' }) },
  data: { loading: true, loaded: false, loadError: '', accessDenied: false,
    loadingMore: false, moreError: '', noMore: false, page: 1, pageSize: 20, exceptions: [],
    detailVisible: false, selectedException: null },
  onLoad() {
    if (!getApp().globalData.isLogin) {
      this._loginRequired = true
      wx.redirectTo({ url: '/pages/login/index' })
    }
  },
  onShow() {
    this._disposed = false
    if (!this._loginRequired) this.loadExceptions()
  },
  onHide() { this._readId = (this._readId || 0) + 1; this.setData({ loading: false, loadingMore: false, detailVisible: false }) },
  onUnload() { this._disposed = true; this._readId = (this._readId || 0) + 1 },
  onPullDownRefresh() { return this.loadExceptions().finally(() => wx.stopPullDownRefresh()) },
  onRetry() { return this.loadExceptions() },
  onLogin() { wx.navigateTo({ url: '/pages/login/index' }) },
  onReachBottom() { return this.loadMore() },
  async loadExceptions() { return this.readPage(1, false) },
  async loadMore() {
    if (this.data.loading || this.data.loadingMore || this.data.noMore || this.data.loadError || !this.data.loaded) return
    return this.readPage(this.data.page + 1, true)
  },
  ensurePageSession() {
    if (!this._pageSession) { this._pageSession = captureSession(); return true }
    if (isCurrentSession(this._pageSession)) return true
    this._readId = (this._readId || 0) + 1
    const patch = { loading: false, accessDenied: true, loadError: '登录身份已变化，请退出本页后重新进入。' }

    this.setData(patch)
    return false
  },
  async readPage(page, append) {
    if (!this.ensurePageSession()) return
    const readId = this._readId = (this._readId || 0) + 1
    this.setData(append ? { loadingMore: true, moreError: '' }
      : { loading: true, loadingMore: false, loadError: '', moreError: '' })
    try {
      const res = await get(API.CUSTOMER_EXCEPTIONS, { page, size: this.data.pageSize })
      if (readId !== this._readId || this._disposed || !this.ensurePageSession()) return
      if (!res || !res.data || !Array.isArray(res.data.records)) throw new Error('异常记录数据不完整')
      const list = res.data.records.map(item => this.decorateException(item))
      const exceptions = append ? this.data.exceptions.concat(list) : list
      this.setData({ exceptions, page, loaded: true, accessDenied: false, noMore: list.length < this.data.pageSize,
        selectedException: this.data.selectedException ? exceptions.find(x => String(x.id) === String(this.data.selectedException.id)) || null : null })
    } catch (error) {
      if (readId !== this._readId || this._disposed || !this.ensurePageSession()) return
      console.error('Load exceptions error:', error)
      const code = error && (error.code || error.statusCode)
      const denied = String(code) === '401' || String(code) === '403' || /登录|权限|无权|客户账号|HTTP\s*(401|403)|UNAUTHORIZED|FORBIDDEN/i.test(String(error && error.message || ''))
      this.setData({ accessDenied: denied, [append ? 'moreError' : 'loadError']: denied
        ? '当前账号无法查看异常记录，请使用客户账号重新登录。'
        : append ? '后续记录未能读取，已加载记录仍保留，请重试。' : '异常记录未能读取，请检查网络后重试。' })
    } finally {
      if (readId === this._readId && !this._disposed) this.setData({ loading: false, loadingMore: false })
    }
  },
  decorateException(item) {
    const colors = { STAFF_RECORDED: 'warning', MANAGER_PENDING: 'primary', MANAGER_APPROVED: 'info', EXECUTING: 'primary', EXECUTED: 'success', IGNORED: 'default' }
    const steps = { STAFF_RECORDED: 0, MANAGER_PENDING: 1, MANAGER_APPROVED: 1, EXECUTING: 2, EXECUTED: 3 }
    const discrepancy = numeric(item.discrepancy) ? Number(item.discrepancy) : null
    return { ...item,
      statusLabel: chineseLabel(item.statusText, '状态待核实'),
      categoryLabel: chineseLabel(item.categoryText, '类型待核实'),
      statusColor: colors[item.status] || 'default',
      timeText: formatTime(item.createdAt),
      orderLabel: item.orderNo || (item.orderId != null ? '#' + item.orderId : '订单待核实'),
      discrepancyText: discrepancy === null ? '回桶差异待核实' : discrepancy === 0 ? '无回桶差异' : (discrepancy > 0 ? '少回 ' : '多回 ') + Math.abs(discrepancy) + ' 桶',
      compensationText: compensationOf(item),
      stepIndex: steps[item.status] == null ? -1 : steps[item.status]
    }
  },
  onExceptionTap(e) {
    if (this.data.accessDenied) return
    const item = this.data.exceptions.find(x => String(x.id) === String(e.currentTarget.dataset.id))
    if (item) this.setData({ selectedException: item, detailVisible: true })
  },
  onCloseDetail() { this.setData({ detailVisible: false }) },
  onDetailPanelTap() {}
})
