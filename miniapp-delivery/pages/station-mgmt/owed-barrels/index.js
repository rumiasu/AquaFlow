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
const { STORAGE_KEYS } = require('../../../utils/storage-keys')
function validId(value) { return /^[1-9][0-9]*$/.test(String(value)) }

Page({
  data: { list: [], loading: true, loaded: false, error: '', minDays: 0, filters: [{ value: 0, label: '全部' }, { value: 7, label: '欠 ≥ 7 天' }], },

  onLoad() { this._unloaded = false },
  // 2026-10-02：首屏只在 onShow 读取，重试也走同一访问守卫。
  onShow() { return this.loadData() },
  onRetry() { return this.loadData() },
  _context() {
    const app = getApp(), u = app.globalData.userInfo || wx.getStorageSync(STORAGE_KEYS.USER_INFO) || {}
    return JSON.stringify([u.staffId || u.id || '', u.role || '', u.stationId || '', u.bindStatus || '', this.data.minDays])
  },
  _current(version, context) {
    return !this._unloaded && version === this._requestVersion && getApp().canAccessStationBusiness() && context === this._context()
  },
  onFilter(e) {
    const minDays = Number(e.currentTarget.dataset.value) || 0
    if (minDays === this.data.minDays && !this.data.error) return
    this.setData({ minDays })
    return this.loadData()
  },

  async loadData() {
    if (this._unloaded) return
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      this._requestVersion = (this._requestVersion || 0) + 1
      this._dataContext = null
      this.setData({ list: [], loaded: false, loading: false, error: '请先完成身份及水站绑定，再重新进入。' })
      app.routeByRole(true)
      return
    }
    const context = this._context(), version = this._requestVersion = (this._requestVersion || 0) + 1
    // 2026-10-02：旧筛选/身份响应曾覆盖新页；先清跨上下文数据，再守 success/catch/finally。
    if (this._dataContext !== context) this.setData({ list: [], loaded: false })
    this._dataContext = context
    this.setData({ loading: true, error: '' })
    try {
      const res = await getOwedBarrels(this.data.minDays)
      if (!this._current(version, context)) return
      if (!res || (res.code !== 0 && res.code !== 200) || !Array.isArray(res.data)
          || !res.data.every(r => r && typeof r === 'object' && !Array.isArray(r) && validId(r.customerId) && validId(r.productId))) {
        throw new Error('收到的数据不完整，请重试。')
      }
      const list = res.data.map(r => ({ ...r, _key: r.customerId + '-' + r.productId,
        _name: r.customerName || ('客户 ID:' + r.customerId),
        _product: (r.productName || '未知商品') + (r.productSpec ? ' · ' + r.productSpec : ''),
        _daysText: r.owedDaysText || '天数未知', _sinceText: r.owedSinceText || '', _urgent: !!r.urgent }))
      this.setData({ list, loaded: true })
    } catch (err) {
      if (!this._current(version, context)) return
      const reason = err && typeof err.message === 'string' && err.message.trim() ? err.message : '暂时无法加载，请重试。'
      this.setData({ error: (this.data.loaded ? '刷新失败，显示上次成功加载的结果。' : '欠桶台账加载失败：') + reason })
    } finally {
      // 2026-10-02：仅丢旧响应会卡住最后一份 flight；无新请求时明确收尾并提示重读。
      // 已有新版本或卸载时仍不能写页，否则旧 finally 会清掉新请求的加载状态。
      if (!this._unloaded && version === this._requestVersion) {
        if (this._current(version, context)) this.setData({ loading: false })
        else {
          this._dataContext = null
          this.setData({ list: [], loaded: false, loading: false, error: '身份或水站已变化，请重试或重新进入。' })
        }
      }
    }
  },
  async onPullDownRefresh() {
    const version = this._pullVersion = (this._pullVersion || 0) + 1
    this._pullRefreshing = true
    try { await this.loadData() } finally {
      if (version === this._pullVersion) { this._pullRefreshing = false; wx.stopPullDownRefresh() }
    }
  },
  onUnload() {
    this._unloaded = true
    this._requestVersion = (this._requestVersion || 0) + 1
    this._pullVersion = (this._pullVersion || 0) + 1
    if (this._pullRefreshing) { this._pullRefreshing = false; wx.stopPullDownRefresh() }
  },

  /** 下钻到客户详情：那里有完整桶账（权益/持有/占用/暂存）与押金明细 */
  onOpenCustomer(e) {
    const id = e.currentTarget.dataset.id
    if (!id) return
    wx.navigateTo({ url: `/pages/station-mgmt/customers/detail/index?id=${id}` })
  },

})
