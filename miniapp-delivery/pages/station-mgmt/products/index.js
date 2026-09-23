const {
  getCatalog,
  getPresetImages,
  selectCatalogProduct,
  updateCatalogSetting,
  removeCatalogProduct,
  setCatalogStock,
  getMyProducts,
  createMyProduct,
  updateMyProduct,
  deleteMyProduct,
  submitMyProduct,
  getMySubmissions,
  inboundProducts,
  getInventoryRecords
} = require('../../../api/station-mgmt')
const { upload } = require('../../../utils/upload')
const { API } = require('../../../config/api')
// ⚠️ 水票档位这两个路径直接写常量、不往 api/station-mgmt.js 里加：那个文件正被另一个
// 工作流（商品图片库）改动，加函数会让两边未提交的改动纠缠在一起。
const { get, post, del } = require('../../../utils/request')
const PKG = '/api/ticket-packages'
const PKG_MANAGE = '/api/ticket-packages/manage'

// 表单里的分类下拉：只是"给站长选"的输入控件，**展示文案一律用后端下发的 categoryText**
// （本仓历史事故：前端自带 1/2/3 映射表导致下单必失败，所以映射表不再放前端）。
const CATEGORY_OPTIONS = [
  { value: 1, name: '桶装水' },
  { value: 2, name: '瓶装水' },
  { value: 3, name: '饮水器' }
]
const CATEGORY_NAMES = CATEGORY_OPTIONS.map(c => c.name)

/** 上报处理状态文案（后端 product_submission.status：0 待处理 / 1 已纳入 / 2 已驳回） */
const SUBMISSION_STATUS_TEXT = { 0: '待处理', 1: '已纳入通用库', 2: '已驳回' }

/**
 * 商品与库存（站长）。
 *
 * 产品口径见 docs/design/12-商品与库存重构.md：
 *   · 顶部是**选品目录**（通用库 + 本站自定义）——"选什么水"，点行进"本站设置"；
 *   · 站长能改的只有本站的：上架 / 库存 / 本站售价 / 本站押金 / 水票 / 优先展示；
 *   · 通用库商品的名称规格图片**锁死**（后端 DTO 里根本没有这些字段）；
 *   · 通用库没有的品，走**底部独立入口**「自己定义商品」（不进通用库，仅本站可见，可上报给开发者）。
 */
Page({
  data: {
    loading: true,
    tab: 'all',          // all=全部目录 | selected=已选用 | mine=我的商品
    keyword: '',
    category: 0,         // 0=全部
    quick: '',           // ''=不限 | offShelf 未上架 | lowStock 低库存 | ticket 已开水票
    categoryNames: CATEGORY_NAMES,
    list: [],
    viewList: [],
    stationId: null,

    // 本站设置弹窗
    showSetting: false,
    setting: {},
    pkgRows: [],         // 水票档位（快捷定义）：{id,qty,price,onShelf,deleted}
    pkgLoading: false,
    stockMode: 'in',     // in=入库(在现有基础上加) | check=盘点(把库存设成输入值)
    stockInput: '',
    stockAfterText: '',
    warningsInline: [],
    saving: false,

    // 自定义商品弹窗
    showEdit: false,
    isAdd: false,
    editId: null,
    editForm: {},
    editCategoryIndex: 0,
    uploading: false,

    // 预设图选择面板（平台统一图库）：站长不必自己拍照，直接从平台图里挑
    showPresetPicker: false,
    presetImages: [],      // [{ key, path }] 由后端下发
    presetLoading: false,

    // 库存流水
    showRecords: false,
    records: [],
    recordsLoading: false,
    recordsLimit: 50,       // 「加载更多」按 50 递增（后端上限 1000）
    recordsProductId: null, // 按商品过滤
    recordsFilterName: '',

    // 上报记录（自定义商品上报通用库的处理进度）
    showSubmissions: false,
    submissions: []
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this.setData({ stationId: (app.globalData.userInfo || {}).stationId || null })
    this.loadData()
  },

  onPullDownRefresh() {
    this.loadData().then(() => wx.stopPullDownRefresh())
  },

  async loadData() {
    this.setData({ loading: true })
    try {
      const res = await getCatalog()
      const raw = res.data || []
      // 上报状态：一次拉取，按商品挂到列表行上（站长最关心"我上报的那个品处理没处理"）
      let submissionsByProduct = {}
      try {
        const subRes = await getMySubmissions()
        ;(subRes.data || []).forEach(s => {
          if (!s.productId) return
          // 同一商品可能有多条（驳回后可再上报）：取最新的一条（列表按 id desc）
          if (!submissionsByProduct[s.productId]) {
            submissionsByProduct[s.productId] = {
              status: s.status,
              statusText: SUBMISSION_STATUS_TEXT[s.status] || '已上报',
              handleNote: s.handleNote || ''
            }
          }
        })
      } catch (e) {
        // 上报记录拿不到不影响选品主流程
        submissionsByProduct = {}
      }
      const list = raw.map(i => ({
        ...i,
        // 后端下发文案优先；仅当后端没给才兜底，避免"商品 #12"这种半成品展示
        displayName: i.name || ('商品 #' + i.id),
        categoryLabel: i.categoryText || '',
        refPriceText: this.money(i.price),
        refDepositText: this.money(i.deposit),
        effectivePriceText: this.money(i.effectivePrice),
        effectiveDepositText: this.money(i.effectiveDeposit),
        stockText: i.selected ? String(i.quantity || 0) : '—',
        priceDiffText: this.priceDiffText(i),
        lowStock: i.selected && i.enabled === 1 && (i.quantity || 0) < 20,
        submission: submissionsByProduct[i.id] || null
      }))
      this.setData({ list }, () => this.applyFilter())
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  money(v) {
    if (v === null || v === undefined || v === '') return '0.00'
    const n = parseFloat(v)
    return isNaN(n) ? '0.00' : n.toFixed(2)
  },

  /**
   * 本站价与平台参考价的差：一眼看出"这个品我调过价没有、调了多少"。
   * 只在两者都存在且不相等时返回文案（未覆盖时返回空串，避免列表噪音）。
   */
  priceDiffText(i) {
    if (i.salePrice === null || i.salePrice === undefined) return ''
    const ref = parseFloat(i.price)
    const station = parseFloat(i.salePrice)
    if (isNaN(ref) || isNaN(station) || ref === station) return ''
    const diff = station - ref
    const sign = diff > 0 ? '+' : ''
    return sign + diff.toFixed(2)
  },

  /** 分段 + 关键字 + 分类 + 快捷筛选（前端过滤：一个站的目录量级很小） */
  applyFilter() {
    const { list, tab, keyword, category, quick } = this.data
    const kw = (keyword || '').trim().toLowerCase()
    const viewList = list.filter(i => {
      if (tab === 'selected' && !i.selected) return false
      if (tab === 'mine' && !i.ownerStationId) return false
      if (category && i.category !== category) return false
      if (quick === 'offShelf' && !(i.selected && i.enabled !== 1)) return false
      if (quick === 'lowStock' && !i.lowStock) return false
      if (quick === 'ticket' && !(i.selected && i.ticketEnabled === 1)) return false
      if (quick === 'priceOverridden' && !i.salePrice) return false
      if (kw) {
        const hay = ((i.name || '') + (i.brand || '') + (i.spec || '')).toLowerCase()
        if (hay.indexOf(kw) < 0) return false
      }
      return true
    })
    this.setData({ viewList })
  },

  onQuickFilter(e) {
    const value = e.currentTarget.dataset.value || ''
    // 再点一次同一个 chip = 取消筛选
    this.setData({ quick: this.data.quick === value ? '' : value }, () => this.applyFilter())
  },

  onTabChange(e) {
    this.setData({ tab: e.currentTarget.dataset.tab }, () => this.applyFilter())
  },

  onKeywordInput(e) {
    this.setData({ keyword: e.detail.value }, () => this.applyFilter())
  },

  onCategoryFilter(e) {
    this.setData({ category: Number(e.currentTarget.dataset.value) || 0 }, () => this.applyFilter())
  },

  /* ==================== 选用 / 本站设置 ==================== */

  async onSelectProduct(e) {
    const id = Number(e.currentTarget.dataset.id)
    try {
      const res = await selectCatalogProduct(id, { enabled: 0 })
      this.toastWarnings(res)
      wx.showToast({ title: '已加入本站，请设置库存与价格', icon: 'none', duration: 2000 })
      this.loadData()
    } catch (err) {
      wx.showToast({ title: err.message || '选用失败', icon: 'none' })
    }
  },

  onOpenSetting(e) {
    const item = e.currentTarget.dataset.item
    if (!item) return
    if (!item.selected) {
      this.onSelectProduct(e)
      return
    }
    this.setData({
      showSetting: true,
      setting: {
        id: item.id,
        name: item.displayName,
        category: item.category || 1,
        categoryLabel: item.categoryLabel,
        spec: item.spec || '',
        brand: item.brand || '',
        refPriceText: item.refPriceText,
        refDepositText: item.refDepositText,
        enabled: item.enabled === 1,
        salePrice: item.salePrice === null || item.salePrice === undefined ? '' : String(item.salePrice),
        depositPrice: item.depositPrice === null || item.depositPrice === undefined ? '' : String(item.depositPrice),
        ticketEnabled: item.ticketEnabled === 1,
        ticketPrice: item.ticketPrice === null || item.ticketPrice === undefined ? '' : String(item.ticketPrice),
        priorityDisplay: item.priorityDisplay === 1,
        quantity: item.quantity || 0,
        ownerStationId: item.ownerStationId || null
      },
      stockMode: 'in',
      stockInput: '',
      stockAfterText: '',
      warningsInline: [],
      pkgRows: []
    })
    // 档位跟着商品走：打开设置就把该商品已挂的档位拉出来（没挂 = 空表，客户只能散买）
    this.loadPkgRows(item.id)
  },

  closeSetting() {
    this.setData({ showSetting: false })
  },

  onSettingShelfChange(e) {
    this.setData({ 'setting.enabled': e.detail.value })
  },

  onSettingTicketChange(e) {
    this.setData({ 'setting.ticketEnabled': e.detail.value })
  },

  /* ==================== 水票档位（商品上架时的「基本」定义）====================
   * 口径：上架一个商品时，水票这块的**基本**内容就应该在这里配完 ——
   *   ① 推出水票开关（上面已有）② 散买单张价（ticketPrice）③ 档位（10 张 / 20 张 / 100 张各多少钱）。
   * 更丰富的设置（档位标题、排序、单独上下架某档）在独立模块「水票档位」里，这里只放基本档位 + 一个入口。
   *
   * ⚠️ **没挂档位的商品，客户只能按单张价散买** —— 这就是"水票还在按张卖"的直接原因，
   *    所以这块必须有一句明示，而不是让站长以为打开开关就自动有了档位。
   * ⚠️ 金额一律发服务端算：unitPrice 由后端用 price/qty 推导，前端不传、也不显示自己算的均价
   *    （档位均价是水票批次的快照值，前端算会与快照不一致）。
   */
  async loadPkgRows(productId) {
    if (!productId) return
    this.setData({ pkgLoading: true })
    try {
      const res = await get(PKG_MANAGE + '?productId=' + productId)
      const rows = (res.data || []).map(p => ({
        key: 'p' + p.id,
        id: p.id,
        qty: p.qty,
        price: p.price === null || p.price === undefined ? '' : String(p.price),
        onShelf: p.status === 1,
        deleted: false
      }))
      this.setData({ pkgRows: rows })
    } catch (err) {
      // 档位拉不到不该挡住上架设置：给一句提示 + 空表，站长仍可新增
      console.warn('[ticket-packages] load failed:', err && err.message)
      this.setData({ pkgRows: [] })
    } finally {
      this.setData({ pkgLoading: false })
    }
  },

  onPkgPriceInput(e) {
    const idx = e.currentTarget.dataset.index
    this.setData({ ['pkgRows[' + idx + '].price']: e.detail.value })
  },

  onPkgQtyInput(e) {
    const idx = e.currentTarget.dataset.index
    this.setData({ ['pkgRows[' + idx + '].qty']: e.detail.value })
  },

  /** 加一档：默认按常见档位递推（10 / 20 / 100），已存在的就不重复加 */
  onAddPkgRow() {
    const rows = this.data.pkgRows.slice()
    const used = rows.filter(r => !r.deleted).map(r => String(r.qty))
    const preset = [10, 20, 100].find(q => !used.includes(String(q))) || ''
    rows.push({ key: 'n' + Date.now() + rows.length, id: null, qty: preset, price: '', deleted: false })
    this.setData({ pkgRows: rows })
  },

  /** 已存在的档位只标记删除（保存时才真删，再点一次可撤销）；没保存过的新行直接移除。 */
  onRemovePkgRow(e) {
    const idx = e.currentTarget.dataset.index
    const rows = this.data.pkgRows.slice()
    const row = rows[idx]
    if (!row) return
    if (row.id) {
      row.deleted = !row.deleted
    } else {
      rows.splice(idx, 1)
    }
    this.setData({ pkgRows: rows })
  },

  onOpenTicketPackages() {
    wx.navigateTo({ url: '/pages/station-mgmt/ticket-packages/index' })
  },

  /** 把档位行落库：有价的新增/改价走 upsert；被清空价格或被标记删除的走 DELETE。 */
  async savePkgRows(productId) {
    const rows = this.data.pkgRows || []
    for (const r of rows) {
      const price = parseFloat(r.price)
      const qty = parseInt(r.qty, 10)
      if (r.id && (r.deleted || !r.price || isNaN(price) || price <= 0)) {
        await del(PKG + '/' + r.id)
        continue
      }
      if (r.deleted) continue
      if (!qty || qty <= 0 || isNaN(price) || price <= 0) continue   // 没填完的行不提交，也不报错
      await post(PKG, { productId, qty, price })
    }
  },

  onSettingPriorityChange(e) {
    this.setData({ 'setting.priorityDisplay': e.detail.value })
  },

  onSettingFieldInput(e) {
    const field = e.currentTarget.dataset.field
    this.setData({ ['setting.' + field]: e.detail.value })
  },

  /* ==================== 库存：入库 / 盘点（P0 合并交互）====================
   * 原实现是两个按钮共用一个输入框，语义全靠按钮区分：点错"盘点为"就把库存**覆盖**成输入值。
   * 现在改成显式模式切换 + 实时预览"变更后库存 N"，盘点在提交前还要二次确认。
   */

  onStockModeChange(e) {
    this.setData({ stockMode: e.currentTarget.dataset.mode }, () => this.refreshStockPreview())
  },

  onStockInput(e) {
    this.setData({ stockInput: e.detail.value }, () => this.refreshStockPreview())
  },

  /** 实时算出"提交后库存会变成几"，让站长在点按钮之前就看到结果 */
  refreshStockPreview() {
    const qty = parseInt(this.data.stockInput, 10)
    const current = Number(this.data.setting.quantity || 0)
    if (isNaN(qty) || qty < 0) {
      this.setData({ stockAfterText: '' })
      return
    }
    const after = this.data.stockMode === 'in' ? current + qty : qty
    const delta = after - current
    const sign = delta > 0 ? '+' : ''
    this.setData({ stockAfterText: '变更后库存 ' + after + '（' + sign + delta + '）' })
  },

  /** 一个按钮走两种模式：入库直接提交，盘点先确认再提交 */
  onSubmitStock() {
    const { stockMode, stockInput, setting } = this.data
    const qty = parseInt(stockInput, 10)
    if (isNaN(qty) || qty < 0 || (stockMode === 'in' && qty <= 0)) {
      wx.showToast({ title: stockMode === 'in' ? '请输入入库数量' : '请输入盘点数量', icon: 'none' })
      return
    }
    if (stockMode === 'in') {
      this.doInbound(qty)
      return
    }
    const after = qty
    wx.showModal({
      title: '确认盘点',
      content: '「' + setting.name + '」库存将直接设为 ' + after + '（当前 ' + (setting.quantity || 0) + '）。盘点会写一条调整流水，确认？',
      confirmText: '确认盘点',
      success: (res) => {
        if (res.confirm) this.doStockCheck(after)
      }
    })
  },

  /** 入库：在现有库存上加（写 INBOUND 流水） */
  async doInbound(qty) {
    const s = this.data.setting
    if (!this.data.stationId) {
      wx.showToast({ title: '登录态缺少水站信息', icon: 'none' })
      return
    }
    try {
      await inboundProducts(this.data.stationId, [{ productId: s.id, quantity: qty }])
      wx.showToast({ title: '入库成功', icon: 'success' })
      this.setData({ stockInput: '', stockAfterText: '', 'setting.quantity': (s.quantity || 0) + qty })
      this.loadData()
    } catch (err) {
      wx.showToast({ title: err.message || '入库失败', icon: 'none' })
    }
  },

  /** 盘点：把库存设成目标值（写 ADJUST 流水） */
  async doStockCheck(target) {
    const s = this.data.setting
    try {
      await setCatalogStock(s.id, target, '商品页盘点')
      wx.showToast({ title: '已盘点', icon: 'success' })
      this.setData({ stockInput: '', stockAfterText: '', 'setting.quantity': target })
      this.loadData()
    } catch (err) {
      wx.showToast({ title: err.message || '盘点失败', icon: 'none' })
    }
  },

  /** 价格输入失焦时统一成两位小数（真机上少打一个小数点很常见） */
  onPriceBlur(e) {
    const field = e.currentTarget.dataset.field
    const raw = this.data.setting[field]
    if (raw === '' || raw === null || raw === undefined) return
    const n = parseFloat(raw)
    if (isNaN(n)) {
      this.setData({ ['setting.' + field]: '' })
      return
    }
    this.setData({ ['setting.' + field]: n.toFixed(2) })
  },

  /** 保存本站设置：价格留空 = 用平台参考价；填 0 = 清除覆盖（后端归一化为 NULL） */
  async onSaveSetting() {
    const s = this.data.setting
    if (!s || !s.id) return
    const payload = {
      enabled: s.enabled ? 1 : 0,
      ticketEnabled: s.ticketEnabled ? 1 : 0,
      priorityDisplay: s.priorityDisplay ? 1 : 0
    }
    // 空字符串不传（= 保持原值）；填了数字才传，0 会被后端解释为"清除覆盖"
    if (s.salePrice !== '') payload.salePrice = parseFloat(s.salePrice)
    // 押金只对桶装水(category=1)提交：非桶装不显示这个输入框，若把表单里的残留值一并提交，
    // 后端会按"只有桶装水能设押金"拒绝整次保存 —— 那就变成"改个售价也被拒"的怪事。
    // 判据与后端 util/BarrelScope 一致（品类值正本：1 桶装水 / 2 瓶装水 / 3 饮水器）。
    if (s.category === 1 && s.depositPrice !== '') payload.depositPrice = parseFloat(s.depositPrice)
    if (s.ticketPrice !== '') payload.ticketPrice = parseFloat(s.ticketPrice)

    this.setData({ saving: true })
    try {
      const res = await updateCatalogSetting(s.id, payload)
      this.toastWarnings(res)
      // 档位跟在同一次"保存"里落库（站长不必再跑一趟水票档位页）——
      // 关掉水票开关时不动档位：档位是价目表，下架商品不该顺手把它删了。
      if (s.ticketEnabled) await this.savePkgRows(s.id)
      wx.showToast({ title: '已保存', icon: 'success' })
      this.setData({ showSetting: false })
      this.loadData()
    } catch (err) {
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ saving: false })
    }
  },

  /** 盘点：把库存设成输入值（差额会落 ADJUST 流水）—— 已被 onSubmitStock 取代，见下 */
  onRemoveProduct() {
    const s = this.data.setting
    wx.showModal({
      title: '移除本站配置',
      content: '移除后顾客在本站看不到该商品；库存必须先盘点为 0。确认移除？',
      confirmText: '移除',
      confirmColor: '#f44336',
      success: (res) => {
        if (!res.confirm) return
        removeCatalogProduct(s.id)
          .then(() => {
            wx.showToast({ title: '已移除', icon: 'success' })
            this.setData({ showSetting: false })
            this.loadData()
          })
          .catch(err => wx.showToast({ title: err.message || '移除失败', icon: 'none' }))
      }
    })
  },

  /* ==================== 库存流水 ==================== */

  /** 打开流水：默认本站全部商品；可按商品过滤 + 加载更多（每次 +50，后端上限 1000） */
  async onOpenRecords() {
    this.setData({ showRecords: true, recordsLimit: 50, recordsProductId: null, recordsFilterName: '' })
    await this.loadRecords()
  },

  async loadRecords() {
    this.setData({ recordsLoading: true })
    try {
      const res = await getInventoryRecords(this.data.recordsLimit, this.data.recordsProductId)
      const records = (res.data || []).map(r => ({
        ...r,
        deltaText: (r.delta > 0 ? '+' : '') + r.delta,
        timeText: (r.createTime || '').replace('T', ' ').slice(0, 16),
        productText: r.productName || ('商品 #' + (r.productId || '')),
        refText: r.refId ? '单据 #' + r.refId : ''
      }))
      this.setData({ records })
    } catch (err) {
      wx.showToast({ title: err.message || '流水加载失败', icon: 'none' })
    } finally {
      this.setData({ recordsLoading: false })
    }
  },

  /** 把流水弹窗过滤到当前设置里的这个商品 */
  onFilterRecordsByCurrent() {
    const s = this.data.setting
    if (!s || !s.id) return
    this.setData({ recordsProductId: s.id, recordsFilterName: s.name, recordsLimit: 50, showRecords: true })
    this.loadRecords()
  },

  onClearRecordsFilter() {
    this.setData({ recordsProductId: null, recordsFilterName: '', recordsLimit: 50 })
    this.loadRecords()
  },

  onLoadMoreRecords() {
    this.setData({ recordsLimit: this.data.recordsLimit + 50 }, () => this.loadRecords())
  },

  closeRecords() {
    this.setData({ showRecords: false })
  },

  /* ==================== 上报记录 ==================== */

  async onOpenSubmissions() {
    this.setData({ showSubmissions: true })
    try {
      const res = await getMySubmissions()
      const nameById = {}
      this.data.list.forEach(i => { nameById[i.id] = i.displayName })
      const submissions = (res.data || []).map(s => ({
        ...s,
        productName: nameById[s.productId] || ('商品 #' + s.productId),
        statusText: SUBMISSION_STATUS_TEXT[s.status] || '已上报',
        timeText: (s.createTime || '').replace('T', ' ').slice(0, 16)
      }))
      this.setData({ submissions })
    } catch (err) {
      wx.showToast({ title: err.message || '上报记录加载失败', icon: 'none' })
    }
  },

  closeSubmissions() {
    this.setData({ showSubmissions: false })
  },

  /* ==================== 自己定义商品 ==================== */

  openAdd() {
    this.setData({
      showEdit: true,
      isAdd: true,
      editId: null,
      editCategoryIndex: 0,
      pkgRows: [],   // 新商品还没有档位
      editForm: {
        name: '', brand: '', spec: '', category: 1,
        price: '', deposit: '', quantity: '', imageUrl: '',
        enabled: false, ticketEnabled: false, ticketPrice: ''
      }
    })
  },

  openEdit(e) {
    const item = e.currentTarget.dataset.item
    const idx = CATEGORY_OPTIONS.findIndex(c => c.value === item.category)
    this.setData({
      showEdit: true,
      isAdd: false,
      editId: item.id,
      editCategoryIndex: idx >= 0 ? idx : 0,
      editForm: {
        name: item.name || '',
        brand: item.brand || '',
        spec: item.spec || '',
        category: item.category || 1,
        price: item.price === null || item.price === undefined ? '' : String(item.price),
        deposit: item.deposit === null || item.deposit === undefined ? '' : String(item.deposit),
        quantity: item.quantity === null || item.quantity === undefined ? '' : String(item.quantity),
        imageUrl: item.imageUrl || '',
        enabled: item.enabled === 1,
        ticketEnabled: item.ticketEnabled === 1,
        ticketPrice: item.ticketPrice === null || item.ticketPrice === undefined ? '' : String(item.ticketPrice)
      }
    })
    // 编辑自定义商品时同样把档位带出来（与「本站设置」共用同一份行数据与保存逻辑）
    this.loadPkgRows(item.id)
  },

  closeEdit() {
    this.setData({ showEdit: false })
  },

  stopPropagation() {},

  /** 弹窗遮罩上吞掉 touchmove，防止滚动穿透到页面（wxml 用 catchtouchmove） */
  preventMove() {},

  onEditFieldInput(e) {
    const field = e.currentTarget.dataset.field
    this.setData({ ['editForm.' + field]: e.detail.value })
  },

  onEditCategoryChange(e) {
    const idx = Number(e.detail.value)
    const cat = CATEGORY_OPTIONS[idx].value
    const updates = { editCategoryIndex: idx, 'editForm.category': cat }
    if (cat !== 1) updates['editForm.deposit'] = ''
    if (cat === 3) {
      updates['editForm.ticketEnabled'] = false
      updates['editForm.ticketPrice'] = ''
    }
    this.setData(updates)
  },

  onEditSwitchChange(e) {
    const field = e.currentTarget.dataset.field
    this.setData({ ['editForm.' + field]: e.detail.value })
  },

  async onSaveEdit() {
    const { isAdd, editId, editForm } = this.data
    if (!editForm.name || !editForm.name.trim()) {
      wx.showToast({ title: '商品名称不能为空', icon: 'none' })
      return
    }
    if (!editForm.price || isNaN(parseFloat(editForm.price))) {
      wx.showToast({ title: '请输入正确的售价', icon: 'none' })
      return
    }
    const payload = {
      name: editForm.name.trim(),
      brand: editForm.brand || '',
      spec: editForm.spec || '',
      category: editForm.category || 1,
      price: parseFloat(editForm.price) || 0,
      deposit: parseFloat(editForm.deposit) || 0,
      enabled: editForm.enabled ? 1 : 0,
      ticketEnabled: editForm.ticketEnabled ? 1 : 0,
      imageUrl: editForm.imageUrl || ''
    }
    if (editForm.ticketEnabled && editForm.ticketPrice !== '') {
      payload.ticketPrice = parseFloat(editForm.ticketPrice)
    }
    if (isAdd && editForm.quantity !== '') {
      payload.quantity = parseInt(editForm.quantity, 10) || 0
    }
    if (!isAdd && editForm.quantity !== '') {
      payload.quantity = parseInt(editForm.quantity, 10) || 0
    }

    this.setData({ saving: true })
    try {
      if (isAdd) {
        // createMyProduct 返回的是新建商品的 id（Result<Long>）—— 拿到它才能把档位挂上去，
        // 否则「新建商品」这一步永远配不出档位，站长还得回头去水票档位页补一次
        const created = await createMyProduct(payload)
        const newId = created && created.data
        if (editForm.ticketEnabled && newId) await this.savePkgRows(newId)
        wx.showToast({ title: '已创建', icon: 'success' })
      } else {
        const res = await updateMyProduct(editId, payload)
        this.toastWarnings(res)
        if (editForm.ticketEnabled) await this.savePkgRows(editId)
        wx.showToast({ title: '已保存', icon: 'success' })
      }
      this.setData({ showEdit: false })
      this.loadData()
    } catch (err) {
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ saving: false })
    }
  },

  /** 停用本站自定义商品（软删：历史订单/桶账仍能引用它） */
  onDeactivate(e) {
    const item = e.currentTarget.dataset.item
    wx.showModal({
      title: '停用商品',
      content: '停用后本站顾客看不到它，历史订单不受影响。确认停用？',
      confirmText: '停用',
      confirmColor: '#f44336',
      success: (res) => {
        if (!res.confirm) return
        deleteMyProduct(item.id)
          .then(() => {
            wx.showToast({ title: '已停用', icon: 'success' })
            this.loadData()
          })
          .catch(err => wx.showToast({ title: err.message || '操作失败', icon: 'none' }))
      }
    })
  },

  /** 上报给开发者，请其考虑补进通用库 */
  onSubmitToPlatform(e) {
    const item = e.currentTarget.dataset.item
    wx.showModal({
      title: '上报给开发者',
      editable: true,
      placeholderText: '补充说明（规格/品牌/进货渠道等，可留空）',
      confirmText: '上报',
      success: (res) => {
        if (!res.confirm) return
        submitMyProduct(item.id, res.content || '')
          .then(() => wx.showToast({ title: '已上报，等待开发者处理', icon: 'success', duration: 2000 }))
          .catch(err => wx.showToast({ title: err.message || '上报失败', icon: 'none' }))
      }
    })
  },

  /**
   * 打开平台预设图选择面板。
   *
   * 产品口径（docs/design/14）：平台提供一套**统一商品图**，站长不必自己拍照上传 ——
   * 这样顾客端看到的是同一套视觉，不会出现各站五花八门的糊图。
   * 图在**小程序包内**（不依赖 COS），由后端下发路径，前端零硬编码。
   */
  async openPresetPicker() {
    this.setData({ showPresetPicker: true })
    if (this.data.presetImages.length) return   // 已拉过就不重复请求
    this.setData({ presetLoading: true })
    try {
      const res = await getPresetImages()
      this.setData({ presetImages: res.data || [] })
    } catch (err) {
      wx.showToast({ title: err.message || '预设图加载失败', icon: 'none' })
    } finally {
      this.setData({ presetLoading: false })
    }
  },

  closePresetPicker() {
    this.setData({ showPresetPicker: false })
  },

  /**
   * 选中某张预设图。
   *
   * ⚠️ 值必须写进 `imageUrl` 且是**以 / 开头的路径** —— 后端
   * `CatalogServiceImpl.pickImageValue` 只认这种形式的 imageUrl
   * （http 开头的一律视为上传回填的过期预签名 URL 而丢弃，对象键已丢失无法反推）。
   */
  onPickPresetImage(e) {
    const path = e.currentTarget.dataset.path
    if (!path) return
    this.setData({
      'editForm.imageUrl': path,
      showPresetPicker: false
    })
  },

  async uploadImage() {
    wx.chooseImage({
      count: 1,
      success: async (res) => {
        this.setData({ uploading: true })
        try {
          const tempFile = res.tempFilePaths[0]
          const uploadRes = await upload({
            filePath: tempFile,
            url: API.GENERAL_UPLOAD,
            name: 'file'
          })
          if (uploadRes.code === 0) {
            this.setData({ 'editForm.imageUrl': uploadRes.data })
            wx.showToast({ title: '图片上传成功', icon: 'success' })
          } else {
            wx.showToast({ title: uploadRes.message || '上传失败', icon: 'none' })
          }
        } catch (err) {
          wx.showToast({ title: err.message || '上传失败', icon: 'none' })
        } finally {
          this.setData({ uploading: false })
        }
      }
    })
  },

  /**
   * 站级价提醒（非阻断）：后端返回 warnings 时只提示，不当失败处理。
   * 口径见 docs/design/12 §4.4：站间允许差价，这里只防手滑填错。
   *
   * [P0 优化] 原实现一律弹**阻断式** showModal，把"保存成功"这件事也变成了要先点确认。
   * 现在改成：行内展示（`warningsInline`，弹窗里一直看得到）+ toast 一句；
   * 只有偏离到 **10 倍以上**（明显的录入事故）才弹一次 modal。
   */
  toastWarnings(res) {
    const warnings = (res && res.data && res.data.warnings) || []
    if (!warnings.length) {
      this.setData({ warningsInline: [] })
      return
    }
    this.setData({ warningsInline: warnings })
    const times = warnings.map(w => {
      const m = String(w).match(/参考价的\s*([\d.]+)\s*倍/)
      return m ? parseFloat(m[1]) : 0
    })
    if (times.some(t => t >= 10)) {
      wx.showModal({
        title: '价格请再核对一次',
        content: warnings.join('\n'),
        showCancel: false,
        confirmText: '我知道了'
      })
      return
    }
    wx.showToast({ title: '已保存（有价格提醒）', icon: 'none', duration: 2500 })
  }
})
