// ⚠️ 直接用 utils/request，不引 api/station-mgmt.js 与 config/api.js：
// 那两个文件正被另一个工作流（商品图片库）改动，共用会让两边未提交的改动纠缠在一起。
// 路径常量写在本文件里，理由同上。
const { get, post, put, del } = require('../../../utils/request')

const PIECE_RATE = '/api/manager/piece-rate'
const EARNINGS = '/api/manager/earnings'
const PAYROLL = '/api/manager/payroll'
const STAFF = '/api/manager/staff'
const PRODUCTS = '/api/products/sale-by-station'
// v44：站长自定义工资条目（加项 / 扣项字典）。
const EARNING_ITEMS = '/api/manager/earning-items'
const ITEM_DIRECTIONS = EARNING_ITEMS + '/directions'

// POST /earning-items/{id}/status 的入参取值：1 启用 / 0 停用。
// 页面里判状态一律用服务端下发的布尔 enabled，不要拿 status 数字去做判断。
const ITEM_ENABLED = 1
const ITEM_DISABLED = 0

// 「默认单价」在服务端用 product_id = 0 表示（该站所有商品的兜底价），
// 商品专属价优先。这不是前端自己发明的编号，是后端 StaffPieceRate 的约定。
const DEFAULT_PRODUCT_ID = 0

/**
 * 站长端「计件工资」：单价配置 + 结算单 + 自定义工资条目。规格见 docs/design/18。
 *
 * 三条口径（改这个页面时必须守住）：
 *   1. **发钱是线下动作**（微信转账/现金）。系统只做两件事：算清楚、留痕迹。
 *      所以这里**没有**打款/提现/钱包入口 —— 只有「标记已发放」，它落的是发放时间与操作人。
 *   2. **计件单位是桶不是单**，且按商品分别计价：商品专属价优先，否则回落默认价（product_id=0）。
 *   3. **收益只在订单「完成配送」之后产生**（钉在状态 CAS 成功之后），
 *      所以结算单里的明细一定是"能取消的单还没产生收益"的那批，不存在回滚问题。
 *
 * ⚠️ 状态文案（草稿/已确认/已发放）用服务端下发的 `statusText`，前端不自带 1/2/3 映射表。
 *
 * v44 新增「自定义工资条目」（加项 / 扣项字典）。三条口径：
 *   1. **条目只是人工调整流水上的标签** —— 不参与自动计件、不进对账；
 *      `itemSummary` 也只汇总带条目的流水，所以它**不等于**未结合计，别把差额当错误。
 *   2. **方向由条目决定** —— 所以「记一笔」只收**正数**金额（负数由后端拒），
 *      只有不带条目的自由录入（`onAdjust`）才允许自带符号。
 *   3. **方向文案一律用服务端下发的 `directionText`**（含 /directions 的选项），
 *      页面里不写 1/2 映射表 —— 两端各写一套枚举文案是本仓出过事故的形状（见 constant/PayMethod）。
 */
Page({
  data: {
    tab: 'rate',
    stationId: null,
    loading: true,
    staffList: [],
    products: [],
    // ---- 计件单价 ----
    // ⚠️ 2026-09-18（v42）起工资只有两项：每桶计件价 + 楼层补贴（免费层数）。
    // 原来的「每回收 1 个空桶 / 每单固定补贴 / 少收 1 桶扣」已随库列删除；
    // 后端 DTO 也删了这三个字段，前端若还传会被 Jackson **静默忽略**（不报错，见 AGENTS §8.15）。
    rates: [],
    rateForm: {
      productId: String(DEFAULT_PRODUCT_ID),
      perBucketAmount: '',
      floorBonusPerLevel: '',
      floorFreeLevel: ''
    },
    savingRate: false,
    // ---- 结算单 ----
    payrolls: [],
    genForm: { staffId: '', periodStart: '', periodEnd: '', note: '' },
    generating: false,
    adjustForm: { staffId: '', amount: '', note: '' },
    adjusting: false,
    // ---- 自定义工资条目（v44）----
    items: [],
    // 方向选项 [{value,text}]：全部来自 /earning-items/directions，页面不硬编码
    directions: [],
    // id 为空 = 新增；有值 = 编辑（改名 / 改方向 / 改排序）
    itemForm: { id: null, name: '', direction: '', sort: '' },
    savingItem: false,
    // 「记一笔」弹窗：选中条目后只填正数金额，加还是扣由条目决定
    record: null,
    recording: false,
    // 未结算明细 + 按条目汇总（选中配送员后才请求）
    summaryStaffId: '',
    summaryLoading: false,
    itemSummary: [],
    summaryEarnings: [],
    unsettledTotal: null,
    // 明细（点某张结算单后加载）
    detail: null,
    detailName: '',
    detailLoading: false
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
    this.loadAll()
  },

  async loadAll() {
    this.setData({ loading: true })
    try {
      const [staffRes, prodRes, rateRes, payrollRes, itemRes, dirRes] = await Promise.all([
        get(STAFF),
        get(PRODUCTS + '?stationId=' + this.data.stationId),
        get(PIECE_RATE),
        get(PAYROLL + '?limit=100'),
        get(EARNING_ITEMS),
        get(ITEM_DIRECTIONS)
      ])
      this.setData({
        staffList: staffRes.data || [],
        products: prodRes.data || [],
        rates: (rateRes.data || {}).rates || [],
        payrolls: (payrollRes.data || []).map(p => Object.assign({}, p, {
          staffName: this.staffName((staffRes.data || []), p.staffId),
          periodText: (p.periodStart || '') + ' ~ ' + (p.periodEnd || '')
        })),
        items: itemRes.data || [],
        directions: dirRes.data || []
      })
      await this.loadSummary()
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  /**
   * 某配送员的未结算明细 + 按条目汇总（GET /earnings 不带 payrollId 的那条分支）。
   *
   * ⚠️ `itemSummary` 只统计带条目的流水，**不等于** `unsettledTotal`（自由文本的人工调整
   * 没有条目、进不了汇总）。两个数是不同口径，页面不要拿差额去报警。
   */
  async loadSummary() {
    const staffId = this.data.summaryStaffId
    if (!staffId) {
      this.setData({ itemSummary: [], summaryEarnings: [], unsettledTotal: null })
      return
    }
    this.setData({ summaryLoading: true })
    try {
      const res = await get(EARNINGS + '?staffId=' + staffId)
      const d = res.data || {}
      this.setData({
        itemSummary: d.itemSummary || [],
        summaryEarnings: (d.earnings || []).map(x => Object.assign({}, x, {
          dateText: String(x.createTime || '').slice(0, 10)
        })),
        unsettledTotal: d.unsettledTotal
      })
    } catch (err) {
      wx.showToast({ title: err.message || '明细加载失败', icon: 'none' })
    } finally {
      this.setData({ summaryLoading: false })
    }
  },

  /** 服务端不在结算单里带员工姓名（那是 staff 表的事），在这里按 id 查一次，避免 wxml 里做查找。 */
  staffName(staffList, staffId) {
    const s = (staffList || []).find(x => x.id === staffId)
    return s ? s.name : ('员工#' + staffId)
  },

  onTab(e) {
    this.setData({ tab: e.currentTarget.dataset.tab, detail: null })
  },

  /* ---------------- 计件单价 ---------------- */

  onRateProduct(e) {
    const productId = String(e.currentTarget.dataset.id)
    // 切商品时把该商品**已配**的值回填，没配过就清空（清空 = 用默认价兜底，不要伪造一个 0 让人以为配过）
    const existing = this.data.rates.find(r => String(r.productId) === productId)
    this.setData({
      'rateForm': {
        productId,
        perBucketAmount: this.str(existing && existing.perBucketAmount),
        floorBonusPerLevel: this.str(existing && existing.floorBonusPerLevel),
        floorFreeLevel: this.str(existing && existing.floorFreeLevel)
      }
    })
  },

  onRateInput(e) {
    this.setData({ ['rateForm.' + e.currentTarget.dataset.field]: e.detail.value })
  },

  str(v) {
    return v === null || v === undefined ? '' : String(v)
  },

  num(v) {
    // 空串 → null（后端把 null 归零，但"没填"与"填 0"在语义上仍是两件事），其余转数字
    if (v === '' || v === null || v === undefined) return null
    const n = Number(v)
    return isNaN(n) ? null : n
  },

  async onSaveRate() {
    if (this.data.savingRate) return
    const f = this.data.rateForm
    this.setData({ savingRate: true })
    try {
      await put(PIECE_RATE, {
        productId: Number(f.productId),
        perBucketAmount: this.num(f.perBucketAmount),
        floorBonusPerLevel: this.num(f.floorBonusPerLevel),
        floorFreeLevel: this.num(f.floorFreeLevel)
      })
      wx.showToast({ title: '已保存', icon: 'success' })
      const res = await get(PIECE_RATE)
      this.setData({ rates: (res.data || {}).rates || [] })
    } catch (err) {
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ savingRate: false })
    }
  },

  /* ---------------- 结算单 ---------------- */

  onGenStaff(e) {
    this.setData({ 'genForm.staffId': this.data.staffList[e.detail.value].id })
  },

  onGenDate(e) {
    this.setData({ ['genForm.' + e.currentTarget.dataset.field]: e.detail.value })
  },

  onGenNote(e) {
    this.setData({ 'genForm.note': e.detail.value })
  },

  async onGenerate() {
    if (this.data.generating) return
    const f = this.data.genForm
    if (!f.staffId) {
      wx.showToast({ title: '请先选择配送员', icon: 'none' })
      return
    }
    if (!f.periodStart || !f.periodEnd) {
      wx.showToast({ title: '请选择结算期间', icon: 'none' })
      return
    }
    this.setData({ generating: true })
    try {
      const res = await post(PAYROLL, {
        staffId: f.staffId,
        periodStart: f.periodStart,
        periodEnd: f.periodEnd,
        note: f.note || null
      })
      const p = (res.data || {}).payroll || {}
      wx.showModal({
        title: '结算单已生成',
        content: (p.payrollNo || '') + '\n合计 ¥' + p.totalAmount + '\n（状态：' + (p.statusText || '') + '）',
        showCancel: false
      })
      this.setData({ genForm: { staffId: f.staffId, periodStart: '', periodEnd: '', note: '' } })
      await this.loadAll()
    } catch (err) {
      wx.showToast({ title: err.message || '生成失败', icon: 'none' })
    } finally {
      this.setData({ generating: false })
    }
  },

  /** 点结算单 → 看明细。payrollId 走服务端校验归属（跨站查询会被拒）。 */
  async onOpenPayroll(e) {
    const id = Number(e.currentTarget.dataset.id)
    const p = this.data.payrolls.find(x => x.id === id)
    if (!p) return
    this.setData({ detailLoading: true, detail: null, detailName: p.staffName })
    try {
      const res = await get(EARNINGS + '?staffId=' + p.staffId + '&payrollId=' + id)
      const d = res.data || {}
      this.setData({
        detail: {
          payroll: p,
          // 明细行只认服务端算好的 displayText（带条目的显示条目名，其余回落 kindText），
          // 页面里不要再写"有 itemName 就用它"的判空 —— 回落逻辑两处各写一次迟早写错一处。
          itemSummary: d.itemSummary || [],
          earnings: (d.earnings || []).map(x => Object.assign({}, x, {
            dateText: String(x.createTime || '').slice(0, 10)
          }))
        }
      })
    } catch (err) {
      wx.showToast({ title: err.message || '明细加载失败', icon: 'none' })
    } finally {
      this.setData({ detailLoading: false })
    }
  },

  onCloseDetail() {
    this.setData({ detail: null })
  },

  async onConfirmPayroll(e) {
    await this.payrollAction(e, 'confirm', '确认这张结算单？确认后明细锁定，不能再改。', '已确认')
  },

  /**
   * 标记已发放。**这一步只留痕，不转账** —— 钱是站长线下给的（微信/现金）。
   * 没有这条痕迹，下个月站长说不清"这笔到底发没发过"。
   */
  async onPayPayroll(e) {
    await this.payrollAction(e, 'pay', '确认已经把钱给到配送员了？（线下转账/现金）系统只记录发放时间。', '已标记发放')
  },

  async payrollAction(e, action, confirmText, okText) {
    const id = Number(e.currentTarget.dataset.id)
    const that = this
    wx.showModal({
      title: '请确认',
      content: confirmText,
      async success(r) {
        if (!r.confirm) return
        try {
          await post(PAYROLL + '/' + id + '/' + action, {})
          wx.showToast({ title: okText, icon: 'success' })
          await that.loadAll()
        } catch (err) {
          wx.showToast({ title: err.message || '操作失败', icon: 'none' })
        }
      }
    })
  },

  onAdjustStaff(e) {
    this.setData({ 'adjustForm.staffId': this.data.staffList[e.detail.value].id })
  },

  onAdjustInput(e) {
    this.setData({ ['adjustForm.' + e.currentTarget.dataset.field]: e.detail.value })
  },

  /**
   * 人工调整：补一笔或扣一笔。
   *
   * ⚠️ 这是**唯一允许带负号**的入口（其余收益方向由 kind 决定、调用方一律传正数）。
   * 所以这里必须让站长明确填负数来扣钱，而不是给两个按钮替他决定符号。
   *
   * 要按自定义条目录入请走条目行上的「记一笔」（那条路径带 itemId，只能填正数）——
   * 两条路径都打到 POST /payroll/adjust，区别只在带不带 itemId。
   */
  async onAdjust() {
    if (this.data.adjusting) return
    const f = this.data.adjustForm
    if (!f.staffId) {
      wx.showToast({ title: '请先选择配送员', icon: 'none' })
      return
    }
    const amount = this.num(f.amount)
    if (amount === null || amount === 0) {
      wx.showToast({ title: '请填金额（扣钱填负数）', icon: 'none' })
      return
    }
    this.setData({ adjusting: true })
    try {
      await post(PAYROLL + '/adjust', { staffId: f.staffId, amount, note: f.note || null })
      wx.showToast({ title: '已调整', icon: 'success' })
      this.setData({ 'adjustForm.amount': '', 'adjustForm.note': '' })
      await this.loadAll()
    } catch (err) {
      wx.showToast({ title: err.message || '调整失败', icon: 'none' })
    } finally {
      this.setData({ adjusting: false })
    }
  },

  /* ---------------- 自定义工资条目（v44） ---------------- */

  /** 条目列表（含停用；停用只挡新录入，历史流水照旧显示当时的条目名快照）。 */
  async reloadItems() {
    const res = await get(EARNING_ITEMS)
    this.setData({ items: res.data || [] })
  },

  onItemName(e) {
    this.setData({ 'itemForm.name': e.detail.value })
  },

  onItemSort(e) {
    this.setData({ 'itemForm.sort': e.detail.value })
  },

  /**
   * 选方向：选项来自 /earning-items/directions，这里只把它的 value 存下来，
   * 页面里任何地方都不出现 "1 就是加项" 这种映射。
   */
  onItemDirection(e) {
    const d = this.data.directions[e.detail.value]
    if (!d) return
    this.setData({ 'itemForm.direction': d.value })
  },

  /** 点某条 → 回填进表单改名 / 改方向（sort 一并带上，否则后端会把它归零）。 */
  onItemEdit(e) {
    const id = Number(e.currentTarget.dataset.id)
    const it = this.data.items.find(x => x.id === id)
    if (!it) return
    this.setData({
      itemForm: { id: it.id, name: it.name, direction: it.direction, sort: this.str(it.sort) }
    })
  },

  onItemFormReset() {
    this.setData({ itemForm: { id: null, name: '', direction: '', sort: '' } })
  },

  /**
   * 新增 / 保存条目。
   *
   * 方向必须由站长显式选（不给默认值）—— 它决定这笔钱是加还是扣，猜错方向比多点一下更贵。
   * 同站重名、名称超 20 字都由后端拒，错误文案原样弹给站长（绝不静默）。
   */
  async onSaveItem() {
    if (this.data.savingItem) return
    const f = this.data.itemForm
    const name = (f.name || '').trim()
    if (!name) {
      wx.showToast({ title: '请填条目名称', icon: 'none' })
      return
    }
    if (!f.direction) {
      wx.showToast({ title: '请选择方向', icon: 'none' })
      return
    }
    const body = { name, direction: f.direction, sort: this.num(f.sort) }
    this.setData({ savingItem: true })
    try {
      if (f.id) {
        await put(EARNING_ITEMS + '/' + f.id, body)
        wx.showToast({ title: '已保存', icon: 'success' })
      } else {
        await post(EARNING_ITEMS, body)
        wx.showToast({ title: '已新增', icon: 'success' })
      }
      this.onItemFormReset()
      await this.reloadItems()
    } catch (err) {
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ savingItem: false })
    }
  },

  /** 启用 / 停用。停用只挡新录入，历史流水不受影响，所以不需要二次确认。 */
  async onToggleItem(e) {
    const id = Number(e.currentTarget.dataset.id)
    const it = this.data.items.find(x => x.id === id)
    if (!it) return
    const nextEnabled = !it.enabled
    try {
      await post(EARNING_ITEMS + '/' + id + '/status', {
        status: nextEnabled ? ITEM_ENABLED : ITEM_DISABLED
      })
      wx.showToast({ title: nextEnabled ? '已启用' : '已停用', icon: 'success' })
      await this.reloadItems()
    } catch (err) {
      wx.showToast({ title: err.message || '操作失败', icon: 'none' })
    }
  },

  /** 删除前二次确认；**被工资流水用过的条目后端会拒**（那就只能停用），文案原样显示。 */
  onDeleteItem(e) {
    const id = Number(e.currentTarget.dataset.id)
    const it = this.data.items.find(x => x.id === id)
    if (!it) return
    const that = this
    wx.showModal({
      title: '删除条目',
      content: '删除「' + it.name + '」？已被工资流水用过的条目不能删，只能停用。',
      success(r) {
        if (!r.confirm) return
        that.doDeleteItem(id)
      }
    })
  },

  async doDeleteItem(id) {
    try {
      await del(EARNING_ITEMS + '/' + id)
      wx.showToast({ title: '已删除', icon: 'success' })
      await this.reloadItems()
    } catch (err) {
      wx.showToast({ title: err.message || '删除失败', icon: 'none' })
    }
  },

  /** 条目行上的「记一笔」：带上 itemId，所以只填正数金额。 */
  onRecordOpen(e) {
    const id = Number(e.currentTarget.dataset.id)
    const it = this.data.items.find(x => x.id === id)
    if (!it) return
    if (!it.enabled) {
      wx.showToast({ title: '该条目已停用，先启用再记', icon: 'none' })
      return
    }
    this.setData({
      record: {
        itemId: it.id,
        itemName: it.name,
        directionText: it.directionText,
        staffId: '',
        amount: '',
        note: ''
      }
    })
  },

  onRecordClose() {
    this.setData({ record: null })
  },

  onRecordStaff(e) {
    const s = this.data.staffList[e.detail.value]
    if (!s) return
    this.setData({ 'record.staffId': s.id })
  },

  onRecordInput(e) {
    this.setData({ ['record.' + e.currentTarget.dataset.field]: e.detail.value })
  },

  /**
   * 提交这一笔：POST /payroll/adjust 带 itemId。
   *
   * ⚠️ 带了 itemId **金额必须为正**（加/扣由条目决定）—— 后端也会拒负数并给可读文案，
   * 这里先拦一次只是为了少一个来回，不替代后端校验。
   */
  async onRecordSubmit() {
    if (this.data.recording) return
    const f = this.data.record
    if (!f) return
    if (!f.staffId) {
      wx.showToast({ title: '请先选择配送员', icon: 'none' })
      return
    }
    const amount = this.num(f.amount)
    if (amount === null || amount <= 0) {
      wx.showToast({ title: '金额填正数（加还是扣由条目决定）', icon: 'none' })
      return
    }
    this.setData({ recording: true })
    try {
      await post(PAYROLL + '/adjust', {
        staffId: f.staffId,
        itemId: f.itemId,
        amount,
        note: f.note || null
      })
      wx.showToast({ title: '已记录', icon: 'success' })
      // 顺手把下面的明细切到刚记账的这个人，站长不用再选一次
      this.setData({ record: null, summaryStaffId: f.staffId })
      await this.loadSummary()
    } catch (err) {
      wx.showToast({ title: err.message || '记账失败', icon: 'none' })
    } finally {
      this.setData({ recording: false })
    }
  },

  onSummaryStaff(e) {
    const s = this.data.staffList[e.detail.value]
    if (!s) return
    this.setData({ summaryStaffId: s.id })
    this.loadSummary()
  },

  onShowHelp() {
    wx.showModal({
      title: '计件工资怎么算',
      content: '按【桶】计价，不按单：送出多少桶算多少钱，' +
        '楼层补贴按超过免费层数的层数算。商品专属价优先，没配的商品用「默认单价」。\n\n' +
        '收益只在订单「完成配送」之后产生 —— 所以能取消的订单一定还没产生收益，不存在回滚。\n\n' +
        '自定义条目（加项 / 扣项）是给人工调整贴的标签：定义一次，以后录钱选一下就行，' +
        '方向由条目决定，所以按条目录入只填正数金额。条目不参与自动计件，也不进对账。\n\n' +
        '发钱在线下完成（微信/现金），系统只记录发放时间，不做打款。',
      showCancel: false
    })
  }
})
