// ⚠️ 直接用 utils/request，不引 api/station-mgmt.js 与 config/api.js：
// 那两个文件正被另一个工作流（商品图片库）改动，共用会让两边未提交的改动纠缠在一起。
// 路径常量写在本文件里，理由同上。
const { get, post, put, del } = require('../../../utils/request')
const { itemUnit } = require('../../../utils/order-item-view')

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
 *   2. 按商品数量计件，不按订单数；展示单位跟随品类，专属价优先，否则回落默认价（product_id=0）。
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
    isManager: false,
    loaded: false,
    rateProducts: [],
    selectedRateName: '默认单价',
    selectedRateUnit: '件',
    defaultCurrentText: '待加载',
    payrollBusyId: null,
    itemBusyId: null,
    itemMenuId: null,
    itemMenuName: '',
    itemMenuEnabled: false,
    summaryError: '',
    detailError: '',
    /**
     * 「?」里的长解释（[2026-09-26] 视觉重排）。
     *
     * 按 HelpTip 组件的约定：**多段文本必须在 js 里拼好再传** —— 在 wxml 属性里写
     * `\n` 拼接（或 `&#10;`）会让 WXML 表达式解析器**直接编译报错**（Bad attr），
     * 而本地任何门禁都查不出来（要等微信开发者工具编译才炸，见 AGENTS §6）。
     *
     * 三条边界（组件文件头有全文）：藏的是**概念与解释**；当前状态、报错、
     * **与钱相关的短提示**、数据行一律不许藏 —— 所以「这笔会进结算单，扣钱填负数」
     * 「系统不会自己算这类钱」这类句子留在屏幕上，这里只放"为什么"。
     */
    tips: {
      rate: '工资只有两项：送水计件 + 楼层补贴。商品专属价优先，没配的商品用「默认单价」兜底。\n\n'
        + '留空等于不补（与填 0 等价，但不是同一件事：留空表示没配过，会被默认价接管）。\n\n'
        + '回收空桶与少收空桶都不折算成钱 —— 少收空桶有订单差异与异常单留痕，只提醒不扣款。',
      period: '结算期间包含结束日当天：比如选了 1 号到 10 号，'
        + '10 号当天的收益也会结进去。',
      adjust: '补一笔或扣一笔，自由填写原因（不需要先建条目）。\n\n'
        + '它和计件收益一样会算进结算单：生成结算单时，按「发生时间」落在期间内的就会被结进去。',
      item: '条目只是一张「名目表」：系统不认识「高温补贴」「迟到扣款」这些名目，'
        + '也不会自己去算它 —— 每次录钱都要你手填金额，条目只决定这笔是加还是扣。\n\n'
        + '它不参与自动计件；录进去的金额与计件工钱一样会算进结算单。',
      itemSummary: '这里的有符号合计只统计带条目的流水：加项为正、扣项为负。\n\n'
        + '自由文本的人工调整没有条目，不计入这个合计，所以它和下面的未结合计不是一个数 —— '
        + '两个都对，不是一个算错了。'
    },
    staffList: [],
    products: [],
    // ---- 计件单价 ----
    // ⚠️ 2026-09-18（v42）起工资只有两项：每桶计件价 + 楼层补贴（免费层数）。
    // 原来的「每回收 1 个空桶 / 每单固定补贴 / 少收 1 桶扣」已随库列删除；
    // 后端 DTO 也删了这三个字段，前端若还传会被 Jackson **静默忽略**（不报错，见 AGENTS §8.15）。
    rates: [],
    // 「这套单价配置会不会算出工钱」—— 见 decorateRates 的注释。
    // rateReady=false 时页面必须说清后果：完成配送既没有计件工资、也没有楼层补贴。
    rateReady: false,
    // 站级默认价那一行的金额（空 = 没配过）。仅用于提示文案，不参与任何计算。
    defaultRateText: '',
    // 有商品的**专属价**被配成 0/空 ⇒ 那几款水完成配送是 0 元（专属价优先，默认价兜不住）。见 decorateRates。
    hasProductZero: false,
    // 未配单价时的空态文案。**在 js 里拼好再下发** —— wxml 的属性里做带 \n 的拼接会
    // 直接编译报错（Bad attr），而本地门禁查不出来（AGENTS §6）。
    // ⚠️ 纯文本，不要写 markdown 的 ** 粗体 —— <text> 会原样渲染成星号。
    rateEmptyText: '还没有配过单价。\n\n'
      + '这种情况下，配送员完成配送不会产生计件工资，也没有楼层补贴 —— 收入台账会是空的。\n\n'
      + '要发工钱：先点「默认单价（所有商品兜底）」填每件多少钱并保存；'
      + '只想给某一款水单独定价，就点那款商品再填。只填默认价也能覆盖所有商品。',
    // 有商品专属价为 0 时的说明。判据：专属价优先，默认价兜不住它（见 decorateRates）。
    productZeroText: '有商品的单价是 0 —— 那几款水完成配送算 0 元。\n\n'
      + '商品单独配过价就按那个价算，不会再回落到「默认单价」。'
      + '要给它们算钱，点那款商品把计件金额填上。',
    rateForm: {
      productId: String(DEFAULT_PRODUCT_ID),
      perBucketAmount: '',
      floorBonusPerLevel: '',
      floorFreeLevel: ''
    },
    savingRate: false,
    // ---- 结算单 ----
    payrolls: [], filteredPayrolls: [], payrollKeyword: '', payrollStart: '', payrollEnd: '', payrollListError: '', payrollReadError: '', payrollScope: '', payrollLimit: 100,
    payrollBeforeId: null, payrollHasMore: false, payrollLoadingMore: false, payrollMoreError: '',
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
    this._payrollGone = false
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      this.invalidatePayrollReads()
      this.clearPayrollHistory()
      app.routeByRole(true)
      return
    }
    const info = app.globalData.userInfo || {}
    // 2026-10-07：业务访问包含配送员，工资管理实际只准站长，不能仅靠通用业务闸门。
    if (info.role !== 'STATION_MANAGER') {
      this.invalidatePayrollReads()
      this.clearPayrollHistory()
      this.setData({ isManager: false })
      app.routeByRole(true)
      return
    }
    const stationId = info.stationId
    if (!stationId) {
      this.invalidatePayrollReads()
      this.clearPayrollHistory()
      wx.showToast({ title: '未识别到所属水站', icon: 'none' })
      return
    }
    this.setData({ stationId, isManager: true })
    this.loadAll()
  },

  onHide() { this._payrollGone = true; this.invalidatePayrollReads() },
  onUnload() { this._payrollGone = true; this.invalidatePayrollReads() },

  payrollContext() {
    const app = getApp(), user = app.globalData.userInfo || {}
    return { app, stationId: this.data.stationId,
      identity: JSON.stringify([app._loginGeneration || 0, !!app.globalData.isLogin,
        user.staffId || user.id || '', user.role || '', user.stationId || '', user.bindStatus || '']) }
  },
  payrollContextCurrent(context) {
    if (!context || this._payrollGone) return false
    const current = this.payrollContext()
    return context.app === current.app && context.stationId === current.stationId && context.identity === current.identity
  },
  invalidatePayrollReads() {
    this._payrollLoadSeq = (this._payrollLoadSeq || 0) + 1
    this._payrollMoreSeq = (this._payrollMoreSeq || 0) + 1
    this._summarySeq = (this._summarySeq || 0) + 1
    this._detailSeq = (this._detailSeq || 0) + 1
    this.setData({ loading: false, payrollLoadingMore: false, summaryLoading: false, detailLoading: false })
  },
  clearPayrollHistory() {
    this._summarySeq = (this._summarySeq || 0) + 1
    this._detailSeq = (this._detailSeq || 0) + 1
    this._payrollReadContext = null
    // 换站/重登录不能把旧工资、员工选项或明细当作新身份的失败缓存继续显示。
    this.setData({ loaded: false, payrolls: [], filteredPayrolls: [], staffList: [], products: [],
      rateProducts: [], rates: [], items: [], directions: [], summaryStaffId: '', summaryEarnings: [],
      itemSummary: [], unsettledTotal: null, detail: null, record: null, itemMenuId: null,
      'genForm.staffId': '', 'adjustForm.staffId': '', payrollBeforeId: null, payrollHasMore: false,
      payrollLoadingMore: false, payrollMoreError: '', payrollReadError: '', payrollListError: '',
      payrollScope: '', loading: false, summaryLoading: false, detailLoading: false,
      summaryError: '', detailError: '', detailName: '', itemMenuName: '', itemMenuEnabled: false,
      rateReady: false, defaultCurrentText: '待加载', defaultRateText: '', hasProductZero: false,
      selectedRateName: '默认单价', selectedRateUnit: '件',
      rateForm: { productId: String(DEFAULT_PRODUCT_ID), perBucketAmount: '', floorBonusPerLevel: '', floorFreeLevel: '' } })
  },

  onPayrollSearch(e) {
    this.setData({ payrollKeyword: e.detail.value })
    this.applyPayrollFilters()
  },
  onPayrollFilterDate(e) {
    const field = e.currentTarget.dataset.field
    if (!['payrollStart', 'payrollEnd'].includes(field)) return
    this.setData({ [field]: e.detail.value })
    this.applyPayrollFilters()
  },
  onClearPayrollFilter() {
    this.setData({ payrollKeyword: '', payrollStart: '', payrollEnd: '' })
    this.applyPayrollFilters()
  },
  /**
   * [F-80 / 2026-10-07] 原来把 limit 扩到 500 仍会截断全部更早历史。
   * 失败保留原边界，按 id 追加去重；全量刷新必须使旧追加失效，否则旧页会混进新首屏。
   */
  async onExpandPayrollHistory() {
    if (!this.canManage() || this._payrollGone || this.data.loading || this.data.payrollLoadingMore
        || this.data.payrollReadError || !this.data.loaded || !this.data.payrollHasMore) return
    if (!this.payrollContextCurrent(this._payrollReadContext)) { this.clearPayrollHistory(); return }
    const context = this.payrollContext(), beforeId = this.data.payrollBeforeId
    const seq = this._payrollMoreSeq = (this._payrollMoreSeq || 0) + 1
    this.setData({ payrollLoadingMore: true, payrollMoreError: '' })
    try {
      const res = await get(PAYROLL + '?limit=' + this.data.payrollLimit + '&beforeId=' + beforeId)
      if (seq !== this._payrollMoreSeq || !this.payrollContextCurrent(context)) return
      const page = this.payrollPage(res.data, beforeId)
      const seen = new Set(this.data.payrolls.map(p => String(p.id)))
      const added = page.rows.filter(p => !seen.has(String(p.id)))
      this.setData({ payrolls: this.data.payrolls.concat(this.decoratePayrolls(added, this.data.staffList)),
        payrollBeforeId: page.beforeId === null ? beforeId : page.beforeId,
        payrollHasMore: page.hasMore, payrollMoreError: '' })
      this.applyPayrollFilters()
    } catch (err) {
      if (seq !== this._payrollMoreSeq || !this.payrollContextCurrent(context)) return
      this.setData({ payrollMoreError: err.message || '更早记录加载失败，请重试' })
    } finally {
      if (seq === this._payrollMoreSeq) {
        if (this.payrollContextCurrent(context)) this.setData({ payrollLoadingMore: false })
        else if (!this._payrollGone) this.clearPayrollHistory()
      }
    }
  },

  payrollPage(rows, beforeId = null) {
    if (!Array.isArray(rows) || rows.length > this.data.payrollLimit) throw new Error('结算单记录无法核对，请重试')
    const seen = new Set(), valid = []
    rows.forEach(row => {
      const id = row && Number(row.id)
      if (!Number.isSafeInteger(id) || id <= 0) throw new Error('结算单编号无法核对，请重试')
      if ((beforeId === null || id < beforeId) && !seen.has(String(id))) { seen.add(String(id)); valid.push(row) }
    })
    if (beforeId !== null && rows.length && !valid.length) throw new Error('未取回更早记录，请重试')
    return { rows: valid, beforeId: valid.length ? Math.min(...valid.map(row => Number(row.id))) : null,
      hasMore: rows.length === this.data.payrollLimit }
  },
  decoratePayrolls(rows, staff) {
    return rows.map(p => Object.assign({}, p, { staffName: this.staffName(staff, p.staffId),
      periodText: (p.periodStart || '') + ' 至 ' + (p.periodEnd || ''), statusClass: this.statusPillClass(p.status) }))
  },
  applyPayrollFilters() {
    const readError = this.data.payrollReadError
    const scope = (readError ? '重新核对失败；以下为上次取回资料。' : this.data.loading ? '正在重新核对；以下为上次取回资料。' : '')
      + '仅在已加载的' + this.data.payrolls.length + '张结算单中筛选。'
      + (this.data.payrollHasMore ? '可能还有更早记录，可继续加载。' : this.data.loaded ? '已读到历史末尾。' : '')
    if (this.data.payrollStart && this.data.payrollEnd && this.data.payrollStart > this.data.payrollEnd) {
      this.setData({ filteredPayrolls: [], payrollScope: scope,
        payrollListError: [readError, '开始日期不能晚于结束日期'].filter(Boolean).join('；') }); return
    }
    const keyword = String(this.data.payrollKeyword || '').trim().toLowerCase()
    const rows = this.data.payrolls.filter(p => {
      const nameMatches = !keyword || [p.staffName, p.staffId].join(' ').toLowerCase().includes(keyword)
      // 结算期间与所选日期相交，包含首尾日；这里只筛已加载记录，不改变结算期间。
      const periodMatches = (!this.data.payrollStart || p.periodEnd && p.periodEnd >= this.data.payrollStart)
        && (!this.data.payrollEnd || p.periodStart && p.periodStart <= this.data.payrollEnd)
      return nameMatches && periodMatches
    })
    this.setData({ filteredPayrolls: rows, payrollListError: readError, payrollScope: scope })
  },

  async loadAll() {
    if (!this.canManage() || this._payrollGone) return
    if (this._payrollReadContext && !this.payrollContextCurrent(this._payrollReadContext)) this.clearPayrollHistory()
    const context = this.payrollContext()
    const seq = this._payrollLoadSeq = (this._payrollLoadSeq || 0) + 1
    this._payrollMoreSeq = (this._payrollMoreSeq || 0) + 1
    const readLimit = this.data.payrollLimit
    const firstLoad = !this.data.loaded
    this.setData({ loading: true, payrollLoadingMore: false })
    this.applyPayrollFilters()
    try {
      const [staffRes, prodRes, rateRes, payrollRes, itemRes, dirRes] = await Promise.all([
        get(STAFF),
        get(PRODUCTS + '?stationId=' + this.data.stationId),
        get(PIECE_RATE),
        get(PAYROLL + '?limit=' + readLimit),
        get(EARNING_ITEMS),
        get(ITEM_DIRECTIONS)
      ])
      if (seq !== this._payrollLoadSeq || !this.payrollContextCurrent(context)) return
      const page = this.payrollPage(payrollRes.data)
      const decorated = this.decorateRates((rateRes.data || {}).rates || [], prodRes.data || [])
      this.setData({
        staffList: staffRes.data || [],
        products: prodRes.data || [],
        rateProducts: decorated.products,
        defaultCurrentText: decorated.defaultCurrentText,
        rates: decorated.rates,
        rateReady: decorated.rateReady,
        defaultRateText: decorated.defaultRateText,
        hasProductZero: decorated.hasProductZero,
        payrolls: this.decoratePayrolls(page.rows, staffRes.data || []),
        payrollBeforeId: page.beforeId,
        payrollHasMore: page.hasMore,
        payrollMoreError: '',
        items: itemRes.data || [],
        directions: dirRes.data || [],
        payrollReadError: '',
        loaded: true
      })
      this._payrollReadContext = context
      if (firstLoad) this.onRateProduct({ currentTarget: { dataset: { id: this.data.rateForm.productId } } })
      this.applyPayrollFilters()
      await this.loadSummary()
    } catch (err) {
      if (seq !== this._payrollLoadSeq || !this.payrollContextCurrent(context)) return
      this.setData({ payrollReadError: err.message || '工资数据加载失败，请重试' })
      this.applyPayrollFilters()
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      if (seq === this._payrollLoadSeq) {
        if (this.payrollContextCurrent(context)) { this.setData({ loading: false }); this.applyPayrollFilters() }
        else if (!this._payrollGone) this.clearPayrollHistory()
      }
    }
  },

  /**
   * 某配送员的未结算明细 + 按条目汇总（GET /earnings 不带 payrollId 的那条分支）。
   *
   * ⚠️ `itemSummary` 只统计带条目的流水，**不等于** `unsettledTotal`（自由文本的人工调整
   * 没有条目、进不了汇总）。两个数是不同口径，页面不要拿差额去报警。
   */
  async loadSummary() {
    const seq = this._summarySeq = (this._summarySeq || 0) + 1
    const staffId = this.data.summaryStaffId
    if (!staffId) {
      this.setData({ itemSummary: [], summaryEarnings: [], unsettledTotal: null, summaryError: '', summaryLoading: false })
      return
    }
    this.setData({ summaryLoading: true, summaryError: '' })
    try {
      const res = await get(EARNINGS + '?staffId=' + staffId)
      if (seq !== this._summarySeq) return
      const d = res.data || {}
      this.setData({
        itemSummary: d.itemSummary || [],
        summaryEarnings: (d.earnings || []).map(x => Object.assign({}, x, {
          dateText: String(x.createTime || '').slice(0, 10)
        })),
        unsettledTotal: d.unsettledTotal
      })
    } catch (err) {
      if (seq !== this._summarySeq) return
      this.setData({ summaryError: err.message || '明细加载失败，请重试' })
      wx.showToast({ title: err.message || '明细加载失败', icon: 'none' })
    } finally {
      if (seq === this._summarySeq) this.setData({ summaryLoading: false })
    }
  },

  /** 服务端不在结算单里带员工姓名（那是 staff 表的事），在这里按 id 查一次，避免 wxml 里做查找。 */
  staffName(staffList, staffId) {
    const s = (staffList || []).find(x => x.id === staffId)
    return s ? s.name : ('员工#' + staffId)
  },

  /**
   * 商品 id → 名字。写法与 {@link #staffName}、`station-mgmt/products` 的 `nameById` 同形：
   * **后端下发的名字优先，仅当没给才兜底**，避免「商品 #5」这种半成品展示。
   *
   * ⚠️ `productId === 0` 是**站级默认价**（该站所有商品的兜底价），不是某个商品 —— 它有自己的文案。
   */
  productName(products, productId) {
    if (Number(productId) === 0) return '默认单价'
    const p = (products || []).find(x => String(x.id) === String(productId))
    return p && p.name ? p.name : ('商品 #' + productId)
  },

  /**
   * 给单价列表补上商品名，并算出「有没有任何一笔工钱算得出来」。
   *
   * 判据正本在服务端 `StaffEarningServiceImpl`：
   *   `rateOf(stationId, productId)`（**商品专属价优先，没配该商品才回落 `product_id=0` 的默认价**；
   *   两处都没有返回全 0 的 `defaults()`）⇒ `:69` 单桶价 ≤ 0 的**该商品不计件**；
   *   `:78-80` **楼层补贴也只读 `product_id=0` 那一行**，`<= 0` 就整块跳过。
   *
   * ⚠️ 所以「有没有工钱」**不能只看默认价**：默认价 > 0、但某个商品被专门配成 0 时，
   * 那款水照样不计件（专属价说了算）。而**楼层补贴只认默认价那一行**（专属价配了也不用于楼层）。
   * 本函数返回的两个标记要分开用：
   *   - `rateReady` = 默认价 > 0 **或** 有任一商品专属价 > 0 ⇒ 至少有一条计件路径会算出钱；
   *   - `hasProductZero` = 有商品专属价被配成 0/空 ⇒ 那几款水完成配送**是 0 元**，页面要单独说明。
   *
   * 为什么在前端算：只决定**要不要给站长显示说明**（呈现，不是账务口径），真正的钱仍由服务端算。
   * **别在这里替站长伪造一个默认价** —— 那会让「没配」与「配了 1 元」再也分不开。
   */
  decorateRates(rates, products) {
    const list = (rates || []).map(r => Object.assign({}, r, {
      productName: this.productName(products, r.productId),
      quantityUnit: Number(r.productId) === 0 ? '件' : itemUnit((products || []).find(p => String(p.id) === String(r.productId))),
      isDefault: Number(r.productId) === 0
    }))
    const def = list.find(r => Number(r.productId) === 0)
    const defAmount = def ? Number(def.perBucketAmount) : 0
    const productRates = list.filter(r => Number(r.productId) !== 0)
    const hasProductZero = productRates.some(r => !(Number(r.perBucketAmount) > 0))
    return {
      rates: list,
      // 2026-10-07：显式 0 是专属价，不能用 || 回落默认价；未配置也不冒充真实零值。
      products: (products || []).map(p => {
        const own = list.find(r => String(r.productId) === String(p.id))
        const effective = own || def
        const unit = itemUnit(p)
        return Object.assign({}, p, { quantityUnit: unit, imageFailed: false,
          currentRateText: effective ? '当前 ¥' + this.money(effective.perBucketAmount == null ? 0 : effective.perBucketAmount) + '/' + unit + (own ? '' : ' · 默认价') : '未配置 · 暂不计件' })
      }),
      defaultCurrentText: def ? '当前 ¥' + this.money(def.perBucketAmount == null ? 0 : def.perBucketAmount) + '/件' : '未配置',
      rateReady: defAmount > 0 || productRates.some(r => Number(r.perBucketAmount) > 0),
      defaultRateText: def ? String(def.perBucketAmount) : '',
      hasProductZero: hasProductZero
    }
  },

  /**
   * 结算单状态 → 颜色类（[2026-09-26] 视觉重排时加的）。
   *
   * ⚠️ **只挑颜色，不挑文案**：那条状态文字是服务端下发的 `statusText`（1 草稿 / 2 已确认 /
   * 3 已发放），前端自己写 1/2/3 → 中文的表正是本仓出过事故的形状（见 constant/PayMethod）。
   * 颜色是"呈现"不是"口径"（同待分配列表的 riskView），认不出的状态给中性灰 ——
   * 宁可素一点，也不要给将来新增的状态乱涂一个"已发放"的绿。
   */
  statusPillClass(status) {
    if (status === 1) return 'pill-draft'
    if (status === 2) return 'pill-confirmed'
    if (status === 3) return 'pill-paid'
    return 'pill-off'
  },

  onTab(e) {
    const tab = e.currentTarget.dataset.tab
    if (!['rate', 'settle', 'items'].includes(tab)) return
    this.onCloseDetail()
    this.onItemMenuClose()
    if (!this.data.recording) this.onRecordClose()
    this.setData({ tab })
  },

  money(value) {
    if (value === null || value === undefined || value === '') return '待核对'
    return Number.isFinite(Number(value)) ? Number(value).toFixed(2) : '待核对'
  },

  canManage() {
    return (getApp().globalData.userInfo || {}).role === 'STATION_MANAGER'
  },

  onProductImageError(e) {
    const index = this.data.rateProducts.findIndex(p => String(p.id) === String(e.currentTarget.dataset.id))
    if (index >= 0) this.setData({ ['rateProducts[' + index + '].imageFailed']: true })
  },

  /* ---------------- 计件单价 ---------------- */

  onRateProduct(e) {
    if (this.data.savingRate) return
    const productId = String(e.currentTarget.dataset.id)
    // 切商品时把该商品**已配**的值回填，没配过就清空（清空 = 用默认价兜底，不要伪造一个 0 让人以为配过）
    const existing = this.data.rates.find(r => String(r.productId) === productId)
    const product = this.data.products.find(p => String(p.id) === productId)
    this.setData({
      selectedRateName: this.productName(this.data.products, productId),
      selectedRateUnit: productId === '0' ? '件' : itemUnit(product),
      'rateForm': {
        productId,
        perBucketAmount: this.str(existing && existing.perBucketAmount),
        floorBonusPerLevel: this.str(existing && existing.floorBonusPerLevel),
        floorFreeLevel: this.str(existing && existing.floorFreeLevel)
      }
    })
  },

  onRateInput(e) {
    if (this.data.savingRate) return
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
    if (!this.canManage() || this.data.savingRate || this.data.loading || this.data.payrollReadError) return
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
      const d = this.decorateRates((res.data || {}).rates || [], this.data.products)
      this.setData({ rates: d.rates, rateProducts: d.products, defaultCurrentText: d.defaultCurrentText,
        rateReady: d.rateReady, hasProductZero: d.hasProductZero, defaultRateText: d.defaultRateText })
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
    if (!this.canManage() || this.data.generating || this.data.loading || this.data.payrollReadError) return
    const f = this.data.genForm
    if (!f.staffId) {
      wx.showToast({ title: '请先选择配送员', icon: 'none' })
      return
    }
    if (!f.periodStart || !f.periodEnd) {
      wx.showToast({ title: '请选择结算期间', icon: 'none' })
      return
    }
    if (f.periodStart > f.periodEnd) {
      wx.showToast({ title: '开始日期不能晚于结束日期', icon: 'none' })
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
    const seq = this._detailSeq = (this._detailSeq || 0) + 1
    this._detailId = id
    this.setData({ detailLoading: true, detailError: '', detail: null, detailName: p.staffName })
    try {
      const res = await get(EARNINGS + '?staffId=' + p.staffId + '&payrollId=' + id)
      if (seq !== this._detailSeq) return
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
      if (seq !== this._detailSeq) return
      this.setData({ detailError: err.message || '明细加载失败，请重试' })
      wx.showToast({ title: err.message || '明细加载失败', icon: 'none' })
    } finally {
      if (seq === this._detailSeq) this.setData({ detailLoading: false })
    }
  },

  onCloseDetail() {
    this._detailSeq = (this._detailSeq || 0) + 1
    this.setData({ detail: null, detailLoading: false, detailError: '' })
  },

  onRetryDetail() {
    return this.onOpenPayroll({ currentTarget: { dataset: { id: this._detailId } } })
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
    const slip = this.data.payrolls.find(p => p.id === id)
    if (!this.canManage() || this.data.payrollBusyId !== null || this.data.loading || this.data.payrollReadError || !slip) return
    if (action === 'confirm' ? slip.status !== 1 : action !== 'pay' || slip.status !== 2) return
    // 在弹确认之前锁住，原生回调也只消费一次，避免双击重复写。
    this.setData({ payrollBusyId: id })
    let consumed = false
    const that = this
    wx.showModal({
      title: '请确认',
      content: confirmText,
      async success(r) {
        if (consumed) return
        consumed = true
        if (!r.confirm || !that.canManage()) { that.setData({ payrollBusyId: null }); return }
        try {
          await post(PAYROLL + '/' + id + '/' + action, {})
          wx.showToast({ title: okText, icon: 'success' })
          await that.loadAll()
        } catch (err) {
          wx.showToast({ title: err.message || '操作失败', icon: 'none' })
        } finally {
          that.setData({ payrollBusyId: null })
        }
      },
      fail() { if (!consumed) { consumed = true; that.setData({ payrollBusyId: null }) } }
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
    if (!this.canManage() || this.data.adjusting || this.data.loading || this.data.payrollReadError) return
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
    const d = this.data.directions[e.currentTarget.dataset.index === undefined ? e.detail.value : e.currentTarget.dataset.index]
    if (!d) return
    this.setData({ 'itemForm.direction': d.value })
  },

  /** 点某条 → 回填进表单改名 / 改方向（sort 一并带上，否则后端会把它归零）。 */
  onItemEdit(e) {
    if (!this.canManage() || this.data.savingItem || this.data.itemBusyId !== null) return
    const id = Number(e.currentTarget.dataset.id)
    const it = this.data.items.find(x => x.id === id)
    if (!it) return
    this.setData({
      itemForm: { id: it.id, name: it.name, direction: it.direction, sort: this.str(it.sort) }
    })
  },

  onItemFormReset() {
    if (this.data.savingItem) return
    this.setData({ itemForm: { id: null, name: '', direction: '', sort: '' } })
  },

  /**
   * 新增 / 保存条目。
   *
   * 方向必须由站长显式选（不给默认值）—— 它决定这笔钱是加还是扣，猜错方向比多点一下更贵。
   * 同站重名、名称超 20 字都由后端拒，错误文案原样弹给站长（绝不静默）。
   */
  async onSaveItem() {
    if (!this.canManage() || this.data.savingItem || this.data.itemBusyId !== null || this.data.loading || this.data.payrollReadError) return
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
      this.setData({ itemForm: { id: null, name: '', direction: '', sort: '' } })
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
    if (!this.canManage() || !it || this.data.itemBusyId !== null || this.data.savingItem || this.data.recording || this.data.loading || this.data.payrollReadError) return
    this.setData({ itemBusyId: id })
    const nextEnabled = !it.enabled
    try {
      await post(EARNING_ITEMS + '/' + id + '/status', {
        status: nextEnabled ? ITEM_ENABLED : ITEM_DISABLED
      })
      wx.showToast({ title: nextEnabled ? '已启用' : '已停用', icon: 'success' })
      await this.reloadItems()
    } catch (err) {
      wx.showToast({ title: err.message || '操作失败', icon: 'none' })
    } finally {
      this.setData({ itemBusyId: null })
    }
  },

  /** 删除前二次确认；**被工资流水用过的条目后端会拒**（那就只能停用），文案原样显示。 */
  onDeleteItem(e) {
    const id = Number(e.currentTarget.dataset.id)
    const it = this.data.items.find(x => x.id === id)
    if (!this.canManage() || !it || this.data.itemBusyId !== null || this.data.savingItem || this.data.recording || this.data.loading || this.data.payrollReadError) return
    this.setData({ itemBusyId: id })
    let consumed = false
    const that = this
    wx.showModal({
      title: '删除条目',
      content: '删除「' + it.name + '」？已被工资流水用过的条目不能删，只能停用。',
      success(r) {
        if (consumed) return
        consumed = true
        if (!r.confirm || !that.canManage()) { that.setData({ itemBusyId: null }); return }
        that.doDeleteItem(id)
      },
      fail() { if (!consumed) { consumed = true; that.setData({ itemBusyId: null }) } }
    })
  },

  async doDeleteItem(id) {
    try {
      await del(EARNING_ITEMS + '/' + id)
      wx.showToast({ title: '已删除', icon: 'success' })
      await this.reloadItems()
    } catch (err) {
      wx.showToast({ title: err.message || '删除失败', icon: 'none' })
    } finally {
      this.setData({ itemBusyId: null })
    }
  },

  onItemMore(e) {
    if (!this.canManage() || this.data.itemMenuId !== null || this.data.itemBusyId !== null || this.data.savingItem || this.data.recording || this.data.loading || this.data.payrollReadError) return
    const it = this.data.items.find(x => String(x.id) === String(e.currentTarget.dataset.id))
    if (it) this.setData({ itemMenuId: it.id, itemMenuName: it.name, itemMenuEnabled: it.enabled })
  },

  onItemMenuClose() {
    this.setData({ itemMenuId: null, itemMenuName: '', itemMenuEnabled: false })
  },

  onItemMenuAction(e) {
    const id = this.data.itemMenuId
    if (id === null) return
    const action = e.currentTarget.dataset.action
    this.onItemMenuClose()
    const event = { currentTarget: { dataset: { id } } }
    if (action === 'edit') { this.onItemEdit(event); wx.pageScrollTo({ scrollTop: 0, duration: 200 }) }
    if (action === 'toggle') return this.onToggleItem(event)
    if (action === 'delete') this.onDeleteItem(event)
  },

  onStopTap() {},

  /** 条目行上的「记一笔」：带上 itemId，所以只填正数金额。 */
  onRecordOpen(e) {
    if (!this.canManage() || this.data.recording || this.data.itemBusyId !== null || this.data.loading || this.data.payrollReadError) return
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
    if (this.data.recording) return
    this.setData({ record: null })
  },

  onRecordStaff(e) {
    if (this.data.recording) return
    const s = this.data.staffList[e.detail.value]
    if (!s) return
    this.setData({ 'record.staffId': s.id })
  },

  onRecordInput(e) {
    if (this.data.recording || !this.data.record) return
    this.setData({ ['record.' + e.currentTarget.dataset.field]: e.detail.value })
  },

  /**
   * 提交这一笔：POST /payroll/adjust 带 itemId。
   *
   * ⚠️ 带了 itemId **金额必须为正**（加/扣由条目决定）—— 后端也会拒负数并给可读文案，
   * 这里先拦一次只是为了少一个来回，不替代后端校验。
   */
  async onRecordSubmit() {
    if (!this.canManage() || this.data.recording) return
    const f = this.data.record
    if (!f) return
    const item = this.data.items.find(it => it.id === f.itemId)
    if (!item || !item.enabled) { wx.showToast({ title: '该条目已停用，请重新选择', icon: 'none' }); return }
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
      content: '按商品数量计件，不按单：单位随商品显示，' +
        '楼层补贴按超过免费层数的层数算。商品专属价优先，没配的商品用「默认单价」。\n\n' +
        '收益只在订单「完成配送」之后产生 —— 所以能取消的订单一定还没产生收益，不存在回滚。\n\n' +
        '自定义条目（加项 / 扣项）是给人工调整贴的标签：定义一次，以后录钱选一下就行。' +
        '【系统不会自己算这类钱】—— 名目是你自己定的，每次的金额也要你手填；' +
        '方向由条目决定，所以按条目录入只填正数金额。条目不参与自动计件。\n\n' +
        '发钱在线下完成（微信/现金），系统只记录发放时间，不做打款。',
      showCancel: false
    })
  }
})
