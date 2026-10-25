const { initialModuleData, refreshModuleTasks } = require('../../../utils/setup-progress')
const { WATER_PRODUCT_TEMPLATES, TEMPLATE_IMAGE, IMAGE_NOTICE, templatesWithImages, matchingCatalogProducts, templateBusinessError } = require('../../../utils/water-product-templates')
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
const { upload, chooseImages, uploadDraft, isUploadSessionCurrent } = require('../../../utils/upload')
const businessRules = require('../../../api/business-rules')
const { API, getTransportConfig } = require('../../../config/api')
const inboundIntent = require('../../../utils/inbound-intent')
const { inventoryTypeText } = require('../../../utils/display-message')
const { uploadDiagnostic, diagnosticText } = require('../../../utils/upload-diagnostic')
// ⚠️ 水票档位这两个路径直接写常量、不往 api/station-mgmt.js 里加：那个文件正被另一个
// 工作流（商品图片库）改动，加函数会让两边未提交的改动纠缠在一起。
const { get, post, del, captureSession } = require('../../../utils/request')
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

const blankNumber = value => value == null || String(value).trim() === ''
/** 2026-10-21: parseFloat/parseInt accepted numeric prefixes and silently saved a different amount/stock. */
const exactNumber = value => {
  const text = String(value == null ? '' : value).trim()
  if (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/.test(text)) return null
  const number = Number(text)
  return Number.isFinite(number) ? number : null
}
// Existing tier rows are durable records: only the explicit deleted flag may delete one.
// An untouched new blank row is harmless; a partly filled or invalid row blocks the whole save.
function ticketPackageValidationError(rows, startIndex = 0) {
  for (let index = Math.max(0, startIndex); index < rows.length; index++) {
    const row = rows[index] || {}
    if (row.deleted) continue
    const qtyText = String(row.qty == null ? '' : row.qty).trim()
    const priceText = String(row.price == null ? '' : row.price).trim()
    if (!row.id && !qtyText && !priceText) continue
    const qty = exactNumber(qtyText), price = exactNumber(priceText)
    if (qty === null || !Number.isSafeInteger(qty) || qty <= 0) return `水票档位第 ${index + 1} 档张数无效，请填写正整数`
    if (price === null || price <= 0) return `水票档位第 ${index + 1} 档总价无效，请填写大于 0 的金额；如要移除，请点删除`
  }
  return ''
}
function productSession() {
  const app = getApp(), g = app.globalData || {}, u = g.userInfo || {}
  return { app, generation: app._loginGeneration || 0,
    key: JSON.stringify([!!g.isLogin, u.staffId, u.stationId, u.role, u.bindStatus]) }
}
function sameProductSession(proof) {
  const now = productSession()
  return proof && proof.app === now.app && proof.generation === now.generation && proof.key === now.key
}
const productCopy = value => JSON.parse(JSON.stringify(value))
const productIdOf = value => (typeof value === 'number' || typeof value === 'string')
  && /^[1-9]\d*$/.test(String(value)) && Number.isSafeInteger(Number(value)) ? Number(value) : null
const createSaveMemory = new Map()
const creationKey = () => {
  const g = getApp().globalData || {}, u = g.userInfo || {}, c = getTransportConfig()
  if (!g.isLogin || !productIdOf(u.staffId) || !productIdOf(u.stationId)) throw Error('请重新登录后核对原商品建档')
  // 持久意图按环境及员工/水站隔离；权限字段变化不能令同一人的原建档记录消失。
  const route = c.mode === 'cloudContainer' ? [c.mode, c.env, c.service] : [c.mode, c.baseUrl]
  return 'aq_product_creation_v1:' + JSON.stringify([route, u.staffId + ':' + u.stationId])
}
const saveScopeError = () => Object.assign(Error('原商品保存范围已变化，后续操作已暂停'), { productSaveScopeChanged: true })
const saveOutcomeUnknown = error => !!(error && (error.requestMayHaveOccurred === true || error.resultUnknown
  || error.malformedResponse || Number(error.statusCode) >= 500 || Number(error.backendCode) >= 500
  || error.businessRejected !== true && error.requestMayHaveOccurred !== false))

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
    ...initialModuleData(),
    loading: true,
    loadError: '',
    tab: 'all',          // all=全部目录 | selected=已选用 | mine=我的商品
    keyword: '',
    category: 0,         // 0=全部
    quick: '',           // ''=不限 | offShelf 未上架 | lowStock 低库存 | ticket 已开水票
    categoryNames: CATEGORY_NAMES,
    list: [],
    viewList: [], reportableProducts: [],
    stationId: null,

    // 本站设置弹窗
    productInactive: false,
    showSetting: false, showInventory: false, stationImageEditable: false, stationImageOverridden: false, platformImageObjectName: '', platformImageUrl: '', editOriginalImageObjectName: '', restorePlatformImage: false,
    productEditorSection: 'details', productScrollTarget: '',
    setting: {},
    pkgRows: [],         // 水票档位（快捷定义）：{id,qty,price,onShelf,deleted}
    pkgLoading: false, pkgError: '', pkgReady: false, pkgProductId: null, pkgValidationError: '',
    stockMode: 'in',     // in=入库(在现有基础上加) | check=盘点(把库存设成输入值)
    stockInput: '',
    stockAfterText: '', stockOperationBusy: false, stockConfirmPending: false,
    inventoryDraftAvailable: false, inventoryDraftName: '',
    warningsInline: [],
    saving: false,
    productSaveError: '', productCreationRecovery: null, productCreationPending: false,
    inboundBusy: false, inboundRecovery: null, inboundError: '',

    // 自定义商品弹窗
    showEdit: false,
    isAdd: false,
    editId: null,
    editForm: {},
    editCategoryIndex: 0,
    uploading: false,
    productImageState: 'empty', productImageHint: '尚未设置商品图片', productImageBusy: false,
    productImagePreviewUrl: '', productEditSheetStyle: '', productEditorResume: false,
    editHelpExpanded: false,

    // 预设图选择面板（平台统一图库）：站长不必自己拍照，直接从平台图里挑
    showPresetPicker: false,
    presetImages: [],      // [{ key, path }] 由后端下发
    presetLoading: false, presetError: '', presetReady: false,

    // 库存流水
    showRecords: false,
    records: [],
    recordsLoading: false, recordsError: '', recordsReady: false,
    recordsLimit: 50,       // 「加载更多」按 50 递增（后端上限 1000）
    recordsProductId: null, // 按商品过滤
    recordsFilterName: '',

    // 上报记录（自定义商品上报通用库的处理进度）
    showSubmissions: false, submissionsLoading: false, submissionsError: '', submissionsReady: false,
    submissions: [],
    waterTemplates: WATER_PRODUCT_TEMPLATES,
    showWaterTemplates: false,
    templateImage: TEMPLATE_IMAGE,
    imageNotice: IMAGE_NOTICE, brokenImages: {},
    editTemplateId: ''
  },

  refreshModuleSetup() { return refreshModuleTasks(this, '/pages/station-mgmt/products/index') },
  onModuleSetupTask(e) {
    const key = e.currentTarget.dataset.key
    if (!(this.data.moduleSetupTasks || []).some(it => it.key === key)) return
    if (typeof wx.pageScrollTo === 'function') wx.pageScrollTo({ selector: '#setup-products', duration: 180 })
  },

  onShow() {
    this._productHidden = false
    const visibleSession = productSession()
    if (this._productPageSession && !sameProductSession(this._productPageSession)) {
      this._catalogReadSeq = (this._catalogReadSeq || 0) + 1
      this._recordsSeq = (this._recordsSeq || 0) + 1
      this._submissionsSeq = (this._submissionsSeq || 0) + 1
      this._pkgSeq = (this._pkgSeq || 0) + 1
      this._productSettingEpoch = (this._productSettingEpoch || 0) + 1
      this._productEditEpoch = (this._productEditEpoch || 0) + 1
      this._closedProductEditor = null
      this._closedInventoryDraft = null
      this.setData({ list: [], viewList: [], reportableProducts: [], warningsInline: [], showRecords:false, records:[], showSubmissions:false, submissions:[], loadError: '', loading: true, showSetting: false, showEdit: false, showInventory: false, setting: {}, editForm: {}, pkgRows: [],
        pkgReady: false, productSaveError: '', inboundRecovery: null, inboundError: '', inboundBusy: false, stockOperationBusy: false, stockConfirmPending: false, inventoryDraftAvailable: false, inventoryDraftName: '', stockInput: '', stockMode: 'in', productEditorResume: false })
    }
    this._productPageSession = visibleSession
    this.captureProductEditorWindow()
    if (!this._productResizeHandler && typeof wx.onWindowResize === 'function') {
      this._productResizeHandler = event => {
        this.layoutProductEditor(event && event.size && event.size.windowHeight)
        if (this.data.showEdit) setTimeout(() => this.measureProductEditorSections(), 0)
      }
      wx.onWindowResize(this._productResizeHandler)
    }
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this.setData({ stationId: (app.globalData.userInfo || {}).stationId || null })
    try { this.syncProductCreationRecovery() } catch (_) { /* 风险常显，仍允许只读目录核对。 */ }
    this.loadData()
    this.loadWaterTemplateImages()
  },

  onUnload() {
    this._productDestroyed = true
    this._catalogReadSeq = (this._catalogReadSeq || 0) + 1
    if (this._productResizeHandler && typeof wx.offWindowResize === 'function') wx.offWindowResize(this._productResizeHandler)
    if (this._productKeyboardBlurTimer) clearTimeout(this._productKeyboardBlurTimer)
    this._productResizeHandler = null
  },

  onHide() {
    this._productHidden = true
    if (this._inboundFlight && this._inboundFlight.cancelConfirmation) this._inboundFlight.cancelConfirmation()
  },

  productWriteLocked() { return !!(this._productSaveFlight || this._inboundFlight || this._stockOperationFlight || this.data.saving || this.data.inboundBusy || this.data.stockOperationBusy) },

  productSaveProof(kind, targetId) {
    return { kind, targetId, session: (kind === 'setting' ? this._productSettingOwner : kind === 'edit' ? this._productEditOwner : null) || productSession(),
      epoch: kind === 'setting' ? this._productSettingEpoch || 0 : this._productEditEpoch || 0,
      draft: kind === 'recovery' ? '' : JSON.stringify([kind === 'setting' ? [this.data.setting, this.data.editForm] : this.data.editForm, this.data.pkgRows]) }
  },

  productSaveCurrent(proof) {
    if (!proof || this._productHidden || this._productDestroyed || !sameProductSession(proof.session)
      || !getApp().canAccessStationBusiness()) return false
    if (proof.kind === 'recovery') return true
    return proof.epoch === (proof.kind === 'setting' ? this._productSettingEpoch || 0 : this._productEditEpoch || 0)
      && proof.targetId === (proof.kind === 'setting' ? this.data.setting.id : this.data.isAdd ? 'new' : this.data.editId)
      && proof.draft === JSON.stringify([proof.kind === 'setting' ? [this.data.setting, this.data.editForm] : this.data.editForm, this.data.pkgRows])
  },

  readProductCreation() {
    const key = creationKey(), memory = createSaveMemory.get(key)
    // 已收到的商品编号先保留在本页内存；存储故障不能把已创建误判为可重新创建。
    const record = memory || wx.getStorageSync(key)
    if (!record) return null
    if (record.version !== 1 || record.key !== key || !['create-unknown', 'packages-pending'].includes(record.phase)
      || !Array.isArray(record.rows) || record.rows.some(row => !row || typeof row !== 'object' || Array.isArray(row)
        || row.id != null && !productIdOf(row.id))
      || !Number.isSafeInteger(record.nextRow) || record.nextRow < 0 || record.nextRow > record.rows.length
      || record.phase === 'packages-pending' && !productIdOf(record.productId)) throw Error('原商品建档记录不完整，请保留记录并联系负责人核实')
    createSaveMemory.set(key, record)
    return record
  },

  writeProductCreation(record) {
    createSaveMemory.set(record.key, record)
    wx.setStorageSync(record.key, productCopy(record))
    if (JSON.stringify(wx.getStorageSync(record.key)) !== JSON.stringify(record)) throw Error('原商品建档进度未能可靠保存，请保留本页并重试原进度')
  },

  clearProductCreation(record) {
    wx.removeStorageSync(record.key)
    if (wx.getStorageSync(record.key)) throw Error('原商品建档记录未能清理，请继续核对原商品，勿另建')
    createSaveMemory.delete(record.key)
  },

  syncProductCreationRecovery() {
    try {
      const record = this.readProductCreation()
      this.setData({ productCreationRecovery: record ? { productId: record.productId || null, name: record.name,
        phase: record.phase, message: record.productId
          ? '商品 #' + record.productId + ' 已创建，原水票档位尚未全部保存。继续只处理这个商品，不会再次创建。'
          : '上次新建商品结果待核实。重新读取目录不会自动确认成功，请保留本页并联系负责人核实；核实前不重复新建，已有商品仍可编辑。' } : null,
        productCreationPending: !!(record && (this.data.isAdd || Number(this.data.editId) === record.productId)) })
      return record
    } catch (error) {
      this.setData({ productCreationPending: true, productSaveError: error.message,
        productCreationRecovery: { phase: 'unreadable', message: error.message } })
      throw error
    }
  },

  async onVerifyProductCreation() { return this.loadData() },

  async onContinueCreatedProduct() {
    if (this.productWriteLocked()) return
    let record
    try { record = this.syncProductCreationRecovery() } catch (_) { return }
    if (!record || record.phase !== 'packages-pending') return
    const proof = this.productSaveProof('recovery', record.productId), flight = this._productSaveFlight = {}
    if (this.data.showEdit && this.data.isAdd && record.draftId && record.draftId === this._uploadDraftId) {
      this.setData({ isAdd: false, editId: record.productId, pkgProductId: record.productId })
    }
    this.setData({ saving: true, productSaveError: '' })
    try {
      await this.savePkgRows(record.productId, record.rows, () => this.productSaveCurrent(proof), index => {
        record.nextRow = index; this.writeProductCreation(record)
      }, record.nextRow)
      if (!this.productSaveCurrent(proof)) throw saveScopeError()
      this.clearProductCreation(record)
      this.syncProductCreationRecovery()
      wx.showToast({ title: '原商品档位已保存', icon: 'success' })
      this.loadData()
    } catch (error) {
      if (this.productSaveCurrent(proof)) {
        try { this.syncProductCreationRecovery() } catch (_) { /* 保留读取失败提示及已知编号。 */ }
        this.setData({ productSaveError: '商品 #' + record.productId + ' 已创建，原档位仍待完成。' + error.message })
      }
    } finally {
      if (this._productSaveFlight === flight) { this._productSaveFlight = null; if (!this._productDestroyed) this.setData({ saving: false }) }
    }
  },

  productWindowHeight() {
    try {
      const info = typeof wx.getWindowInfo === 'function' ? wx.getWindowInfo() : wx.getSystemInfoSync()
      return Number.isFinite(info.windowHeight) && info.windowHeight > 0 ? info.windowHeight : 0
    } catch (_) { return 0 }
  },

  captureProductEditorWindow(resetKeyboard = false) {
    if (resetKeyboard) {
      this._productKeyboardHeight = 0
      if (this._productKeyboardBlurTimer) { clearTimeout(this._productKeyboardBlurTimer); this._productKeyboardBlurTimer = null }
    }
    const height = this.productWindowHeight()
    if (resetKeyboard || !this._productKeyboardHeight || !this._productBaseWindowHeight) this._productBaseWindowHeight = height
    this.layoutProductEditor()
  },

  layoutProductEditor(resizedHeight) {
    const measured = Number(resizedHeight), currentHeight = measured > 0 ? measured : this.productWindowHeight()
    if (!currentHeight) { this.setData({ productEditSheetStyle: 'height:calc(100vh - 56px);max-height:calc(100vh - 56px);bottom:0px;' }); return }
    const baseline = this._productBaseWindowHeight || currentHeight, keyboard = this._productKeyboardHeight || 0
    // 2026-10-24：Android 可能已缩小窗口；只补键盘未被 resize 吃掉的部分，不能重复扣高度。
    const consumed = Math.max(0, baseline - currentHeight), bottom = Math.max(0, keyboard - consumed)
    // Reserve a full-width 56px dismissal area above the custom editor, including with the keyboard open.
    const height = Math.max(0, Math.floor(currentHeight - bottom - 56))
    this.setData({ productEditSheetStyle: 'height:' + height + 'px;max-height:' + height + 'px;bottom:' + bottom + 'px;' })
  },

  onProductKeyboardHeightChange(e) {
    const height = Number(e && e.detail && e.detail.height)
    if (!Number.isFinite(height) || height < 0) return
    if (this._productKeyboardBlurTimer) { clearTimeout(this._productKeyboardBlurTimer); this._productKeyboardBlurTimer = null }
    this._productKeyboardHeight = height
    const current = this.productWindowHeight()
    if (height === 0 && current) this._productBaseWindowHeight = current
    this.layoutProductEditor(current)
  },

  onProductKeyboardBlur() {
    if (this._productKeyboardBlurTimer) clearTimeout(this._productKeyboardBlurTimer)
    // Some native keyboard dismissals omit keyboardheightchange(height=0). A short grace
    // period lets a focus change to another field cancel this reset before the sheet moves.
    this._productKeyboardBlurTimer = setTimeout(() => {
      this._productKeyboardBlurTimer = null
      if (!this._productKeyboardHeight) return
      this._productKeyboardHeight = 0
      const current = this.productWindowHeight()
      if (current) this._productBaseWindowHeight = current
      this.layoutProductEditor(current)
    }, 180)
  },

  async loadWaterTemplateImages() {
    const seq = this._templateImagesSeq = (this._templateImagesSeq || 0) + 1
    try {
      const res = await getPresetImages()
      if (!res || res.code !== 0 || !Array.isArray(res.data)) return
      if (seq !== this._templateImagesSeq) return
      this.setData({ waterTemplates: templatesWithImages(res.data), presetImages: res.data, presetReady: true })
    } catch (_) {
      // 图片不可用不阻断建档；中性示意图有明确提示，不替换正在编辑的照片。
    }
  },

  onPullDownRefresh() {
    this.loadData().then(() => wx.stopPullDownRefresh())
  },

  onDisplayImageError(e) {
    const src = e.currentTarget.dataset.src
    if (!src) return
    this.setData({ brokenImages: { ...this.data.brokenImages, [src]: true } })
    if (src === this.data.productImagePreviewUrl || src === this.data.editForm.imageUrl) {
      console.warn('[product-image-preview]', { diagnosticCode: 'IMAGE_PREVIEW_UNAVAILABLE', confirmedObjectKey: !!this.data.editForm.imageObjectName })
    }
  },

  async loadData() {
    const owner = productSession(), seq = this._catalogReadSeq = (this._catalogReadSeq || 0) + 1
    const current = () => seq === this._catalogReadSeq && sameProductSession(owner) && !this._productDestroyed && !this._productHidden && getApp().canAccessStationBusiness()
    if (!current()) return
    this.refreshModuleSetup()
    this.setData({ loading: true, loadError: '' })
    try {
      const res = await getCatalog()
      if (!current()) return
      if (!res || res.code !== 0 || !Array.isArray(res.data)) throw new Error((res && res.message) || '商品资料未核实，请重试')
      const raw = res.data || []
      // 上报状态：一次拉取，按商品挂到列表行上（站长最关心"我上报的那个品处理没处理"）
      let submissionsByProduct = {}
      try {
        const subRes = await getMySubmissions()
        if (!current()) return
        ;(subRes.data || []).forEach(s => {
          if (!s.productId) return
          // 同一商品可能有多条（驳回后可再上报）：取最新的一条（列表按 id desc）
          if (!submissionsByProduct[s.productId]) {
            submissionsByProduct[s.productId] = {
              status: s.status,
              statusText: SUBMISSION_STATUS_TEXT[s.status] || '处理状态待核实',
              handleNote: s.handleNote || ''
            }
          }
        })
      } catch (e) {
        if (!current()) return
        // 上报记录拿不到不影响选品主流程
        submissionsByProduct = {}
      }
      if (!current()) return
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
        lowStock: i.status !== 0 && i.status !== 2 && i.selected && i.enabled === 1 && (i.quantity || 0) < 20,
        submission: submissionsByProduct[i.id] || null
      }))
      this.setData({ list, reportableProducts: list.filter(i => Number(i.ownerStationId) === Number((getApp().globalData.userInfo || {}).stationId) && i.status !== 0 && i.status !== 2) }, () => this.applyFilter())
    } catch (err) {
      if (!current()) return
      // 2026-10-22：读取失败不等于本站无商品，保留明确失败状态与重试入口。
      this.setData({ loadError: err.message || '商品加载失败，请重试' })
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      if (current()) this.setData({ loading: false })
    }
  },

  money(v) {
    if (v === null || v === undefined || v === '') return '待核实'
    const n = Number(v)
    return !Number.isFinite(n) ? '待核实' : n.toFixed(2)
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
      if (tab === 'selected' && (!i.selected || i.status === 0 || i.status === 2)) return false
      if (tab === 'mine' && !i.ownerStationId) return false
      if (category && i.category !== category) return false
      if (quick === 'offShelf' && !(i.selected && i.status !== 0 && i.status !== 2 && i.enabled !== 1)) return false
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

  async onSelectProduct(e, openSetting = false) {
    const id = Number(e.currentTarget.dataset.id)
    try {
      const res = await selectCatalogProduct(id, { enabled: 0 })
      this.toastWarnings(res)
      wx.showToast({ title: '已加入本站，请设置库存与价格', icon: 'none', duration: 2000 })
      await this.loadData()
      if (openSetting) {
        const item = this.data.list.find(row => row.id === id && row.selected)
        if (item) this.onOpenSetting({ currentTarget: { dataset: { item } } })
      }
    } catch (err) {
      wx.showToast({ title: err.message || '选用失败', icon: 'none' })
    }
  },

  // 2026-10-25：选用后统一进入一个编辑面板；公共资料限制逐字段说明，不能写回公共模板。
  onOpenSetting(e) {
    if (this.productWriteLocked()) return
    const item = e.currentTarget.dataset.item
    if (!item) return
    const stationId = (getApp().globalData.userInfo || {}).stationId
    if (item.ownerStationId && Number(item.ownerStationId) !== Number(stationId)) {
      wx.showToast({ title: '只能编辑本站商品', icon: 'none' }); return
    }
    if (!item.selected) return this.onSelectProduct(e)
    return this.openEdit(e, !item.ownerStationId)
  },

  onOpenInventory(e) {
    if (this.productWriteLocked()) return
    const item = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.item
    if (!item || !item.id) return
    const app = getApp(), stationId = (app.globalData.userInfo || {}).stationId
    if (!app.canAccessStationBusiness()) { app.routeByRole(true); return }
    if (item.ownerStationId && Number(item.ownerStationId) !== Number(stationId)) {
      wx.showToast({ title: '只能操作本站商品库存', icon: 'none' }); return
    }
    if (!item.selected && !item.ownerStationId) return
    const owner = productSession(), previous = this._closedInventoryDraft
    const resume = previous && sameProductSession(previous.session) && Number(previous.item.id) === Number(item.id)
      ? previous : null
    this._productSettingEpoch = (this._productSettingEpoch || 0) + 1
    this._productSettingOwner = owner
    this._productSettingClosed = false
    this._closedProductEditor = null
    this._productEditorClosed = false
    this.captureProductEditorWindow(true)
    this.setData({
      showEdit: false, showSetting: false, showInventory: true, productEditorResume: false,
      productInactive: item.status === 0 || item.status === 2,
      setting: { id: item.id, name: item.displayName || item.name, category: item.category || 1,
        quantity: item.quantity == null ? null : item.quantity, ownerStationId: item.ownerStationId,
        salePrice: item.salePrice == null ? '' : String(item.salePrice),
        depositPrice: item.depositPrice == null ? '' : String(item.depositPrice),
        enabled: item.enabled === 1, priorityDisplay: item.priorityDisplay === 1,
        ticketEnabled: item.ticketEnabled === 1, ticketPrice: item.ticketPrice == null ? '' : String(item.ticketPrice),
        refPriceText: item.refPriceText, refDepositText: item.refDepositText },
      stockMode: resume ? resume.stockMode : 'in', stockInput: resume ? resume.stockInput : '',
      stockAfterText: '', stockConfirmPending: false, inboundError: '', inboundRecovery: null,
      inventoryDraftAvailable: false, inventoryDraftName: '', productScrollTarget: ''
    }, () => this.refreshStockPreview())
    try { this.syncInboundRecovery() } catch (_) { /* 原入库仍按原编号恢复，不改用当前输入。 */ }
    this.layoutProductEditor()
    if (resume) this._closedInventoryDraft = null
  },

  closeInventory() {
    if (!this.data.showInventory) return
    const item = (this.data.list || []).find(row => Number(row.id) === Number(this.data.setting.id))
    const hasDraft = String(this.data.stockInput || '').trim() !== '' || this.data.stockMode !== 'in'
    if (hasDraft && item) {
      this._closedInventoryDraft = { session: this._productSettingOwner || productSession(), item: productCopy(item),
        stockMode: this.data.stockMode, stockInput: this.data.stockInput }
    } else this._closedInventoryDraft = null
    this._productSettingClosed = true
    if (!this.productWriteLocked()) this._productSettingEpoch = (this._productSettingEpoch || 0) + 1
    this._productKeyboardHeight = 0
    if (this._productKeyboardBlurTimer) { clearTimeout(this._productKeyboardBlurTimer); this._productKeyboardBlurTimer = null }
    if (typeof wx.hideKeyboard === 'function') wx.hideKeyboard()
    this.setData({ showInventory: false, inventoryDraftAvailable: !!this._closedInventoryDraft,
      inventoryDraftName: this._closedInventoryDraft ? (this._closedInventoryDraft.item.displayName || this._closedInventoryDraft.item.name || '商品') : '',
      stockConfirmPending: false, stockAfterText: '' })
  },

  onProductInventoryBeforeLeave() { this.closeInventory() },

  onResumeInventoryDraft() {
    const draft = this._closedInventoryDraft
    if (!draft || !sameProductSession(draft.session)) { this.onDiscardInventoryDraft(); return }
    return this.onOpenInventory({ currentTarget: { dataset: { item: draft.item } } })
  },

  onDiscardInventoryDraft() {
    this._closedInventoryDraft = null
    this.setData({ inventoryDraftAvailable: false, inventoryDraftName: '', stockInput: '', stockMode: 'in', stockAfterText: '' })
  },

  closeSetting() {
    if (this.productWriteLocked()) return
    if (this.data.showEdit) return this.closeEdit()
    this._productSettingEpoch = (this._productSettingEpoch || 0) + 1
    this._productSettingClosed = true
    this._productKeyboardHeight = 0
    if (typeof wx.hideKeyboard === 'function') wx.hideKeyboard()
    this.setData({ showSetting: false })
  },

  onInactiveProductRecords(e) {
    const item = e.currentTarget.dataset.item
    if (!item || Number(item.ownerStationId) !== Number((getApp().globalData.userInfo || {}).stationId)) return
    this.setData({recordsProductId:item.id,recordsFilterName:item.name || item.displayName,recordsLimit:50,showRecords:true})
    this.loadRecords()
  },
  onRestoreProduct(e) {
    if (this.productWriteLocked()) return
    const item=e.currentTarget.dataset.item, owner=productSession()
    if (!item || ![0,2].includes(item.status) || Number(item.ownerStationId)!==Number((getApp().globalData.userInfo||{}).stationId)) return
    wx.showModal({title:'恢复本站商品',content:'恢复后仍为未上架；库存和历史记录保留。请核对商品资料后再决定上架。',confirmText:'恢复',confirmColor:'#1264D6',success:async r=>{
      if (!r.confirm || !sameProductSession(owner) || this.productWriteLocked()) return
      const flight=this._productSaveFlight={};this.setData({saving:true})
      try { await updateMyProduct(item.id,{status:1,enabled:0})
        if (!sameProductSession(owner) || this._productDestroyed) return
        await this.loadData()
        if (!sameProductSession(owner) || this._productDestroyed) return
        const actual=!this.data.loadError && this.data.list.find(i=>Number(i.id)===Number(item.id))
        if(!actual || actual.status!==1 || actual.enabled!==0)throw Error('恢复结果尚未核实，请重新读取商品')
        wx.showToast({title:'已恢复，尚未上架',icon:'success'})
      } catch(error) {if(sameProductSession(owner))wx.showToast({title:error.message||'恢复结果待核实，请重新读取商品',icon:'none'})}
      finally {if(this._productSaveFlight===flight){this._productSaveFlight=null;if(!this._productDestroyed)this.setData({saving:false})}}
    }})
  },
  onChooseReportProduct() {
    if (this.productWriteLocked()) return
    const owner=productSession(), station=(getApp().globalData.userInfo||{}).stationId
    const rows=(this.data.list||[]).filter(i=>Number(i.ownerStationId)===Number(station)&&i.status!==0&&i.status!==2)
    if(!rows.length){wx.showToast({title:'暂无可上报的本站商品',icon:'none'});return}
    wx.showActionSheet({itemList:rows.map(i=>i.name||i.displayName||('商品 #'+i.id)),success:r=>{
      if(!sameProductSession(owner)||!rows[r.tapIndex])return
      this.onSubmitToPlatform({currentTarget:{dataset:{item:rows[r.tapIndex]}}})
    }})
  },
  onDismissPriceWarnings() { this.setData({warningsInline:[]}) },

  onProductMore(e) {
    if (this.productWriteLocked()) return
    const item = e.currentTarget.dataset.item, session = productSession()
    const stationId = (getApp().globalData.userInfo || {}).stationId
    if (!item || !item.selected || item.ownerStationId && [0,2].includes(item.status) || item.ownerStationId && Number(item.ownerStationId) !== Number(stationId)) return
    wx.showActionSheet({ itemList: ['移除商品'], itemColor: '#BD3328', success: result => {
      if (result.tapIndex !== 0 || !sameProductSession(session) || this.productWriteLocked()) return
      if (item.ownerStationId) this.onDeactivate(e)
      else this.onRemoveProduct(e)
    } })
  },

  onSettingShelfChange(e) {
    if (this.productWriteLocked()) return
    this.setData({ 'setting.enabled': e.detail.value })
  },

  onSettingTicketChange(e) {
    if (this.productWriteLocked()) return
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
    const seq = this._pkgSeq = (this._pkgSeq || 0) + 1
    this.setData({ pkgLoading: true, pkgReady: false, pkgError: '', pkgProductId: productId })
    try {
      const res = await get(PKG_MANAGE + '?productId=' + productId)
      if (seq !== this._pkgSeq) return
      if (!Array.isArray(res.data)) throw new Error('档位数据未读取')
      const rows = res.data.map(p => ({
        key: 'p' + p.id,
        id: p.id,
        qty: p.qty,
        price: p.price === null || p.price === undefined ? '' : String(p.price),
        onShelf: p.status === 1,
        deleted: false
      }))
      this.setData({ pkgRows: rows, pkgReady: true })
    } catch (err) {
      // 2026-10-22：原配置未核实时不能把空表当作原价目表；保留草稿，先重读再修改档位。
      if (seq !== this._pkgSeq) return
      console.warn('[ticket-packages] load failed:', err && err.message)
      this.setData({ pkgError: '水票档位尚未读取，请先重读原配置，草稿保持不变' })
    } finally {
      if (seq === this._pkgSeq) this.setData({ pkgLoading: false })
    }
  },

  onRetryPkgRows() { return this.loadPkgRows(this.data.pkgProductId) },

  onPkgPriceInput(e) {
    if (this.productWriteLocked() || this.data.showEdit && this.data.productCreationPending) return
    const idx = e.currentTarget.dataset.index
    this.setData({ ['pkgRows[' + idx + '].price']: e.detail.value, pkgValidationError: '' })
  },

  onPkgQtyInput(e) {
    if (this.productWriteLocked() || this.data.showEdit && this.data.productCreationPending) return
    const idx = e.currentTarget.dataset.index
    this.setData({ ['pkgRows[' + idx + '].qty']: e.detail.value, pkgValidationError: '' })
  },

  /** 加一档：默认按常见档位递推（10 / 20 / 100），已存在的就不重复加 */
  onAddPkgRow() {
    if (this.productWriteLocked() || this.data.showEdit && this.data.productCreationPending) return
    const rows = this.data.pkgRows.slice()
    const used = rows.filter(r => !r.deleted).map(r => String(r.qty))
    const preset = [10, 20, 100].find(q => !used.includes(String(q))) || ''
    rows.push({ key: 'n' + Date.now() + rows.length, id: null, qty: preset, price: '', deleted: false })
    this.setData({ pkgRows: rows, pkgValidationError: '' })
  },

  /** 已存在的档位只标记删除（保存时才真删，再点一次可撤销）；没保存过的新行直接移除。 */
  onRemovePkgRow(e) {
    if (this.productWriteLocked() || this.data.showEdit && this.data.productCreationPending) return
    const idx = e.currentTarget.dataset.index
    const rows = this.data.pkgRows.slice()
    const row = rows[idx]
    if (!row) return
    if (row.id) {
      row.deleted = !row.deleted
    } else {
      rows.splice(idx, 1)
    }
    this.setData({ pkgRows: rows, pkgValidationError: '' })
  },

  onOpenTicketPackages() {
    // 2026-10-06：从商品设置进入曾丢失当前商品，必须沿用这一份设置上下文。
    const id = this.data.setting && this.data.setting.id
    wx.navigateTo({ url: '/pages/station-mgmt/ticket-packages/index' + (id ? '?productId=' + encodeURIComponent(id) : '') })
  },

  /** 把档位行落库：合法行走 upsert；仅明确标记删除的已有档位走 DELETE。 */
  async savePkgRows(productId, snapshot, current = () => true, progress, startIndex = 0) {
    const rows = productCopy(snapshot || this.data.pkgRows || [])
    const validationError = ticketPackageValidationError(rows, startIndex)
    if (validationError) throw new Error(validationError)
    for (let index = startIndex; index < rows.length; index++) {
      if (!current()) throw saveScopeError()
      const r = rows[index]
      if (r.id && r.deleted) {
        await del(PKG + '/' + r.id)
        if (!current()) throw saveScopeError()
        if (progress) progress(index + 1)
        continue
      }
      if (!r.deleted && (r.id || String(r.qty == null ? '' : r.qty).trim() || String(r.price == null ? '' : r.price).trim())) {
        await post(PKG, { productId, qty: exactNumber(r.qty), price: exactNumber(r.price) })
        if (!current()) throw saveScopeError()
      }
      if (progress) progress(index + 1)
    }
  },

  onSettingPriorityChange(e) {
    if (this.productWriteLocked()) return
    this.setData({ 'setting.priorityDisplay': e.detail.value })
  },

  onSettingFieldInput(e) {
    if (this.productWriteLocked()) return
    const field = e.currentTarget.dataset.field
    this.setData({ ['setting.' + field]: e.detail.value })
  },

  /* ==================== 库存：入库 / 盘点（P0 合并交互）====================
   * 原实现是两个按钮共用一个输入框，语义全靠按钮区分：点错"盘点为"就把库存**覆盖**成输入值。
   * 现在改成显式模式切换 + 实时预览"变更后库存 N"，盘点在提交前还要二次确认。
   */

  onStockModeChange(e) {
    if (this.productWriteLocked() || !this.data.showInventory) return
    const mode = e.currentTarget.dataset.mode
    if (!['in', 'check', 'loss'].includes(mode)) return
    if (this.data.inboundRecovery && mode !== 'in') {
      wx.showToast({ title: '请先继续原入库核实', icon: 'none' }); return
    }
    this.setData({ stockMode: mode }, () => this.refreshStockPreview())
  },

  onStockInput(e) {
    if (this.productWriteLocked() || !this.data.showInventory) return
    this.setData({ stockInput: e.detail.value }, () => this.refreshStockPreview())
  },

  inventoryOperationProof() {
    return { session: this._productSettingOwner || productSession(), epoch: this._productSettingEpoch || 0,
      productId: Number(this.data.setting && this.data.setting.id) }
  },

  inventoryOperationCurrent(proof, requireOpen = true) {
    return !!proof && Number.isSafeInteger(proof.productId) && proof.productId > 0
      && sameProductSession(proof.session) && getApp().canAccessStationBusiness()
      && !this._productHidden && !this._productDestroyed
      && proof.epoch === (this._productSettingEpoch || 0)
      && Number(this.data.setting && this.data.setting.id) === proof.productId
      && (!requireOpen || this.data.showInventory && !this._productSettingClosed)
  },

  clearStockConfirmation() {
    this._stockConfirmPending = false
    if (!this._productDestroyed) this.setData({ stockConfirmPending: false })
  },

  /** 实时算出"提交后库存会变成几"，让站长在点按钮之前就看到结果 */
  refreshStockPreview() {
    if (this.data.stockMode === 'in' && this.data.inboundRecovery) {
      const intent = this.data.inboundRecovery
      this.setData({ stockAfterText: '继续原入库 ' + intent.quantity + ' 件；不会使用当前输入' })
      return
    }
    const qty = parseInt(this.data.stockInput, 10)
    const current = Number(this.data.setting.quantity || 0)
    if (isNaN(qty) || qty < 0) {
      this.setData({ stockAfterText: '' })
      return
    }
    const after = this.data.stockMode === 'in' ? current + qty : qty
    const delta = after - current
    const label = this.data.stockMode === 'in' ? '入库后 ' : this.data.stockMode === 'loss' ? '短缺登记后 ' : '核对后 '
    this.setData({ stockAfterText: label + after + ' 件（' + (delta < 0 ? '少 ' + (-delta) : delta > 0 ? '多 ' + delta : '无变化') + (delta === 0 ? '' : ' 件') + '）' })
  },

  /** 一个按钮走两种模式：入库直接提交，盘点先确认再提交 */
  onSubmitStock() {
    if (this.productWriteLocked() || !this.data.showInventory || this._stockConfirmPending) return
    const { stockMode, stockInput, setting } = this.data
    if (this.data.inboundRecovery && stockMode !== 'in') {
      wx.showToast({ title: '请先继续原入库核实', icon: 'none' }); return
    }
    if (stockMode === 'in') {
      try { if (inboundIntent.read(this.inboundActor(), setting.id)) return this.doInbound() }
      catch (error) { this.setData({ inboundError: error.message }); return }
    }
    const qty = exactNumber(stockInput)
    if (qty === null || !Number.isSafeInteger(qty) || qty < 0 || (stockMode === 'in' && qty <= 0)) {
      wx.showToast({ title: stockMode === 'in' ? '请输入入库数量' : '请输入实际数量', icon: 'none' })
      return
    }
    if (stockMode === 'in') {
      return this.doInbound(qty)
    }
    if (stockMode === 'loss') {
      if (qty >= (setting.quantity || 0)) { wx.showToast({ title: '剩余数量须少于当前库存', icon: 'none' }); return }
      const proof = this.inventoryOperationProof()
      this._stockConfirmPending = true
      this.setData({ stockConfirmPending: true })
      wx.showModal({ confirmColor: '#1264D6', cancelColor: '#637083',  confirmText: '登记短缺', cancelText: '暂不登记', title: '登记商品短缺', editable: true, placeholderText: '短缺原因及凭据（必填）',
        content: '实际库存由 ' + setting.quantity + ' 件改为 ' + qty + ' 件。低于已预留数量时，较晚的订单会转为缺货待补，请与客户协商延期或取消。',
        success: res => {
          this.clearStockConfirmation()
          if (!res.confirm || !this.inventoryOperationCurrent(proof)) return
          return this.doRecordStockLoss(qty, res.content, proof)
        }, fail: () => this.clearStockConfirmation() })
      return
    }
    const after = qty
    const proof = this.inventoryOperationProof()
    this._stockConfirmPending = true
    this.setData({ stockConfirmPending: true })
    wx.showModal({ confirmColor: '#1264D6', cancelColor: '#637083',
      title: '确认实际库存',
      content: '「' + setting.name + '」库存由 ' + (setting.quantity || 0) + ' 件改为 ' + after + ' 件，并记录数量差额。',
      confirmText: '确认数量',
      success: (res) => {
        this.clearStockConfirmation()
        if (res.confirm && this.inventoryOperationCurrent(proof)) this.doStockCheck(after, proof)
      }, fail: () => this.clearStockConfirmation()
    })
  },

  async doRecordStockLoss(targetQuantity, note, proof = this.inventoryOperationProof()) {
    if (this.productWriteLocked() || !this.inventoryOperationCurrent(proof)) return
    const setting = productCopy(this.data.setting || {}), flight = this._stockOperationFlight = {}
    this.setData({ stockOperationBusy: true, inboundError: '' })
    try {
      const result = await businessRules.recordLoss(setting.id, { targetQuantity, expectedQuantity: setting.quantity, note })
      if (!this.inventoryOperationCurrent(proof, false)) return
      this.setData({ stockInput: '', stockAfterText: '', 'setting.quantity': targetQuantity })
      this._closedInventoryDraft = null
      this.setData({ inventoryDraftAvailable: false, inventoryDraftName: '' })
      this.loadData()
      wx.showModal({ confirmColor: '#1264D6', cancelColor: '#637083', confirmText: '知道了', title: '商品短缺已登记',
        content: '受影响订单 ' + ((result && result.data && result.data.affectedOrders) || []).length + ' 条；请到业务待办查看缺货订单。', showCancel: false })
    } catch (err) {
      if (this.inventoryOperationCurrent(proof, false)) wx.showToast({ title: err.message || '登记未成功', icon: 'none' })
    } finally {
      if (this._stockOperationFlight === flight) {
        this._stockOperationFlight = null
        if (!this._productDestroyed) this.setData({ stockOperationBusy: false })
      }
    }
  },

  /** 入库：在现有库存上加（写 INBOUND 流水） */
  inboundActor() {
    const app = getApp(), g = app.globalData || {}, u = g.userInfo || {}
    if (!g.isLogin || !productIdOf(u.staffId) || !productIdOf(u.stationId) || !app.canAccessStationBusiness()
      || !['STATION_MANAGER', 'manager', 'MANAGER'].includes(u.role)
      || this.data.stationId && Number(this.data.stationId) !== Number(u.stationId)) throw Error('身份或水站已变化，请重新进入商品页核对原入库')
    return u.staffId + ':' + u.stationId
  },

  syncInboundRecovery() {
    try {
      const id = this.data.setting && this.data.setting.id
      const intent = id ? inboundIntent.read(this.inboundActor(), id) : null
      this.setData({ inboundRecovery: intent ? { productId: intent.items[0].productId,
        quantity: intent.items[0].quantity, clientRequestId: intent.clientRequestId } : null,
        ...(intent && this.data.showInventory ? { stockMode: 'in' } : {}) }, () => this.refreshStockPreview())
      return intent
    } catch (error) { this.setData({ inboundRecovery: null, inboundError: error.message }); throw error }
  },

  onContinueOriginalInbound() { if (this.data.showInventory) return this.doInbound() },

  async doInbound(qty) {
    if (this.productWriteLocked() || this._productSettingClosed || this._productHidden || this._productDestroyed) return
    const s = productCopy(this.data.setting || {}), session = this._productSettingOwner || productSession(), epoch = this._productSettingEpoch || 0
    const current = () => sameProductSession(session) && getApp().canAccessStationBusiness() && !this._productHidden && !this._productDestroyed
      && epoch === (this._productSettingEpoch || 0) && Number(this.data.setting.id) === Number(s.id) && this.data.stockMode === 'in'
    const flight = this._inboundFlight = {}
    this.setData({ inboundBusy: true, inboundError: '' })
    let intent, applied = false
    try {
      if (!current()) return
      const actor = this.inboundActor(), original = inboundIntent.read(actor, s.id)
      intent = original || inboundIntent.prepare(actor, [{ productId: Number(s.id), quantity: qty }])
      this.syncInboundRecovery()
      if (original) {
        const confirmed = await new Promise(resolve => {
          flight.cancelConfirmation = () => resolve(false)
          wx.showModal({ confirmColor: '#1264D6', cancelColor: '#637083',  title: '继续原入库', confirmText: '继续入库', cancelText: '暂不继续',
            content: '商品 #' + intent.items[0].productId + '，原入库数量 ' + intent.items[0].quantity + ' 件。\n继续会沿用原编号核实或执行这笔入库，不会使用当前新输入数量，也不会重复增加已完成的原入库。',
            success: result => resolve(!!result.confirm), fail: () => resolve(false) })
        })
        delete flight.cancelConfirmation
        if (!confirmed) return
      }
      if (!current()) throw saveScopeError()
      const body = inboundIntent.payload(intent)
      const result = await inboundProducts(intent.stationId, body.items, body.clientRequestId)
      if (!current()) return
      if (!inboundIntent.acceptsReceipt(intent, result)) throw Object.assign(Error('入库结果暂未查到，请继续原入库，勿重复登记'), { malformedResponse: true })
      applied = true
      inboundIntent.clear(intent)
      this.syncInboundRecovery()
      this.setData({ stockInput: '', stockAfterText: '', 'setting.quantity': null })
      await this.loadData()
      if (!current()) return
      const item = !this.data.loadError && this.data.list.find(row => Number(row.id) === Number(s.id))
      const actual = item && exactNumber(item.quantity)
      if (Number.isSafeInteger(actual) && actual >= 0) this.setData({ 'setting.quantity': actual })
      else this.setData({ inboundError: '原入库已确认，当前库存尚未重新读取，请重新加载商品核对。' })
      wx.showToast({ title: '原入库 ' + intent.items[0].quantity + ' 件已确认', icon: 'success' })
    } catch (err) {
      if (current()) {
        try { this.syncInboundRecovery() } catch (_) { /* 保留读取失败的阻断提示。 */ }
        const message = applied ? '原入库已确认，记录清理仍待核实。' + err.message
          : intent ? (saveOutcomeUnknown(err) ? '原入库结果待核实。' : '原入库未完成。') + (err.message || '') + ' 原编号和数量保留，请继续原入库核实，勿另记新数量。'
            : err.message || '原入库记录未能保存，尚未提交'
        this.setData({ inboundError: message })
        wx.showToast({ title: message, icon: 'none' })
      }
    } finally {
      if (this._inboundFlight === flight) { this._inboundFlight = null; if (!this._productDestroyed) this.setData({ inboundBusy: false }) }
      if (current() && !this.data.showEdit && this._closedProductEditor) this.rememberProductImageDraft()
    }
  },

  /** 盘点：把库存设成目标值（写 ADJUST 流水） */
  async doStockCheck(target, proof = this.inventoryOperationProof()) {
    if (this.productWriteLocked() || !this.inventoryOperationCurrent(proof)) return
    const s = productCopy(this.data.setting || {}), flight = this._stockOperationFlight = {}
    this.setData({ stockOperationBusy: true, inboundError: '' })
    try {
      await setCatalogStock(s.id, target, '商品页核对库存')
      if (!this.inventoryOperationCurrent(proof, false)) return
      wx.showToast({ title: '库存已核对', icon: 'success' })
      this.setData({ stockInput: '', stockAfterText: '', 'setting.quantity': target })
      this._closedInventoryDraft = null
      this.setData({ inventoryDraftAvailable: false, inventoryDraftName: '' })
      this.loadData()
    } catch (err) {
      if (this.inventoryOperationCurrent(proof, false)) wx.showToast({ title: err.message || '库存核对未成功', icon: 'none' })
    } finally {
      if (this._stockOperationFlight === flight) {
        this._stockOperationFlight = null
        if (!this._productDestroyed) this.setData({ stockOperationBusy: false })
      }
    }
  },

  /** 价格输入失焦时统一成两位小数（真机上少打一个小数点很常见） */
  onPriceBlur(e) {
    if (this.productWriteLocked()) return
    const field = e.currentTarget.dataset.field
    const raw = this.data.setting[field]
    if (raw === '' || raw === null || raw === undefined) return
    const n = exactNumber(raw)
    if (n === null) {
      wx.showToast({ title: '请输入完整的数字金额', icon: 'none' })
      return
    }
    this.setData({ ['setting.' + field]: n.toFixed(2) })
  },

  /** 保存本站设置：价格留空 = 用平台参考价；填 0 = 清除覆盖（后端归一化为 NULL） */
  async onSaveSetting() {
    if (this.data.productInactive) return
    const s = productCopy(this.data.setting || {})
    if (!s.id || this.productWriteLocked() || this._productSettingClosed) return
    if (s.ticketEnabled && (this.data.pkgLoading || this.data.pkgError)) { wx.showToast({ title: '请先读取原水票档位', icon: 'none' }); return }
    const rows = productCopy(this.data.pkgRows || [])
    if (s.ticketEnabled) {
      const packageError = ticketPackageValidationError(rows)
      if (packageError) { this.setData({ pkgValidationError: packageError }); wx.showToast({ title: packageError, icon: 'none' }); return }
    }
    const payload = {
      enabled: s.enabled ? 1 : 0,
      ticketEnabled: s.ticketEnabled ? 1 : 0,
      priorityDisplay: s.priorityDisplay ? 1 : 0
    }
    for (const field of ['salePrice', 'depositPrice', 'ticketPrice']) {
      if (field === 'depositPrice' && s.category !== 1) continue
      if (!blankNumber(s[field]) && exactNumber(s[field]) === null) {
        wx.showToast({ title: '请输入完整的数字金额', icon: 'none' })
        return
      }
    }
    if (s.imageObjectName != null) payload.imageObjectName = s.imageObjectName

    // 空字符串不传（= 保持原值）；填了数字才传，0 会被后端解释为"清除覆盖"
    if (!blankNumber(s.salePrice)) payload.salePrice = exactNumber(s.salePrice)
    // 押金只对桶装水(category=1)提交：非桶装不显示这个输入框，若把表单里的残留值一并提交，
    // 后端会按"只有桶装水能设押金"拒绝整次保存 —— 那就变成"改个售价也被拒"的怪事。
    // 判据与后端 util/BarrelScope 一致（品类值正本：1 桶装水 / 2 瓶装水 / 3 饮水器）。
    if (s.category === 1 && !blankNumber(s.depositPrice)) payload.depositPrice = exactNumber(s.depositPrice)
    if (!blankNumber(s.ticketPrice)) payload.ticketPrice = exactNumber(s.ticketPrice)

    // 2026-10-25：原保存曾在await后读取新商品档位；保存目标、表单与档位须在首个请求前冻结。
    const proof = this.productSaveProof('setting', s.id)
    if (!this.productSaveCurrent(proof)) { wx.showToast({ title: '身份或商品已变化，请重新进入', icon: 'none' }); return }
    const flight = this._productSaveFlight = {}
    this.setData({ saving: true, productSaveError: '' })
    try {
      const res = await updateCatalogSetting(s.id, payload)
      if (!this.productSaveCurrent(proof)) throw saveScopeError()
      if (payload.imageObjectName != null && (!res || !res.data || res.data.stationImageSaved !== true
          || res.data.stationImageRef !== payload.imageObjectName.trim())) {
        throw new Error('本站设置可能已保存，图片保存尚未确认，请重新读取商品核对')
      }
      this.toastWarnings(res)
      // 档位跟在同一次"保存"里落库（站长不必再跑一趟水票档位页）——
      // 关掉水票开关时不动档位：档位是价目表，下架商品不该顺手把它删了。
      if (s.ticketEnabled) await this.savePkgRows(s.id, rows, () => this.productSaveCurrent(proof))
      if (!this.productSaveCurrent(proof)) throw saveScopeError()
      wx.showToast({ title: this.data.warningsInline.length ? '已保存，请核对价格' : '已保存', icon: this.data.warningsInline.length ? 'none' : 'success' })
      this._productSettingClosed = true
      this._productEditorClosed = true
      this._closedProductEditor = null
      this.setData({ showSetting: false, showEdit: false, productEditorResume: false })
      this.loadData()
    } catch (err) {
      if (this.productSaveCurrent(proof)) {
        this.setData({ productSaveError: err.message || '保存结果尚未核实，请核对原商品配置' })
        wx.showToast({ title: err.message || '保存失败', icon: 'none' })
      }
    } finally {
      if (this._productSaveFlight === flight) { this._productSaveFlight = null; if (!this._productDestroyed) this.setData({ saving: false }) }
    }
  },

  /** 移除本站选用关系；确认回调必须仍属于同一身份，库存门槛由原接口校验。 */
  onRemoveProduct(e) {
    if (this.productWriteLocked()) return
    const item = e && e.currentTarget ? e.currentTarget.dataset.item : this.data.setting
    const session = productSession()
    if (!item || !item.id || item.ownerStationId) return
    wx.showModal({ cancelColor: '#637083', title: '移除商品',
      content: '移除后顾客在本站看不到该商品；库存必须先核对为 0。确认移除？',
      confirmText: '移除', confirmColor: '#BD3328', success: res => {
        if (!res.confirm || !sameProductSession(session) || this.productWriteLocked()) return
        removeCatalogProduct(item.id).then(() => {
          if (!sameProductSession(session)) return
          wx.showToast({ title: '已移除', icon: 'success' }); this.loadData()
        }).catch(err => { if (sameProductSession(session)) wx.showToast({ title: err.message || '移除失败', icon: 'none' }) })
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
    const seq = this._recordsSeq = (this._recordsSeq || 0) + 1
    this.setData({ recordsLoading: true, recordsError: '', recordsReady: false, records: [] })
    try {
      const res = await getInventoryRecords(this.data.recordsLimit, this.data.recordsProductId)
      if (seq !== this._recordsSeq) return
      if (!Array.isArray(res.data)) throw new Error('流水资料未读取')
      const records = res.data.map(r => ({
        ...r, typeText: inventoryTypeText(r.type, r.note),
        deltaText: (r.delta > 0 ? '+' : '') + r.delta,
        timeText: (r.createTime || '').replace('T', ' ').slice(0, 16),
        productText: r.productName || ('商品 #' + (r.productId || '')),
        refText: r.refId ? '单据 #' + r.refId : ''
      }))
      this.setData({ records, recordsReady: true })
    } catch (err) {
      if (seq !== this._recordsSeq) return
      this.setData({ recordsError: '库存流水尚未读取，请重试' })
      wx.showToast({ title: err.message || '流水加载失败', icon: 'none' })
    } finally {
      if (seq === this._recordsSeq) this.setData({ recordsLoading: false })
    }
  },

  /** 把流水弹窗过滤到当前设置里的这个商品 */
  onFilterRecordsByCurrent() {
    const s = this.data.setting
    if (!s || !s.id) return
    // The native editor layer must close before the ordinary records modal can be shown.
    // leaveProductEditor retains the same unsaved form; it is not a product save.
    if (this.data.showEdit) this.leaveProductEditor()
    if (this.data.showInventory) this.closeInventory()
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
    const seq = this._submissionsSeq = (this._submissionsSeq || 0) + 1
    this.setData({ showSubmissions: true, submissionsLoading: true, submissionsError: '', submissionsReady: false })
    try {
      const res = await getMySubmissions()
      if (seq !== this._submissionsSeq) return
      if (!Array.isArray(res.data)) throw new Error('上报记录尚未读取')
      const nameById = {}
      this.data.list.forEach(i => { nameById[i.id] = i.displayName })
      const submissions = (res.data || []).map(s => ({
        ...s,
        productName: nameById[s.productId] || ('商品 #' + s.productId),
        statusText: SUBMISSION_STATUS_TEXT[s.status] || '处理状态待核实',
        timeText: (s.createTime || '').replace('T', ' ').slice(0, 16)
      }))
      this.setData({ submissions, submissionsReady: true })
    } catch (err) {
      if (seq !== this._submissionsSeq) return
      this.setData({ submissionsError: '上报记录尚未读取，请重试' })
      wx.showToast({ title: err.message || '上报记录加载失败', icon: 'none' })
    } finally { if (seq === this._submissionsSeq) this.setData({ submissionsLoading: false }) }
  },

  closeSubmissions() {
    this.setData({ showSubmissions: false })
  },

  /* ==================== 自己定义商品 ==================== */

  onToggleWaterTemplates() {
    if (this.data.loading || this.data.loadError) return
    const showWaterTemplates = !this.data.showWaterTemplates
    this.setData({ showWaterTemplates }, () => {
      if (showWaterTemplates && typeof wx.pageScrollTo === 'function') {
        wx.pageScrollTo({ selector: '.water-templates', duration: 200 })
      }
    })
  },

  /** 优先复用精确公共SKU；否则只填本站新商品草稿，不发创建/上传请求。 */
  onUseWaterTemplate(e) {
    if (this.data.saving || this.data.uploading || !getApp().canAccessStationBusiness()) return
    const template = this.data.waterTemplates.find(item => item.id === e.currentTarget.dataset.id)
    if (!template) return
    this.setData({ showWaterTemplates: false })
    const matches = matchingCatalogProducts(template, this.data.list)
    if (matches.length === 1) {
      const item = matches[0]
      if (item.selected) return this.onOpenSetting({ currentTarget: { dataset: { item } } })
      return this.onSelectProduct({ currentTarget: { dataset: { id: item.id } } }, true)
    }
    if (matches.length > 1) {
      this.setData({ tab: 'all', keyword: template.brand, category: 1, quick: '' }, () => this.applyFilter())
      wx.showToast({ title: '目录已有同款，请核对后选用', icon: 'none' })
      return
    }
    this.openWaterTemplateDraft(template)
  },

  /** 公共SKU本身不可改；需按实物改基础资料时，明确另存本站商品。 */
  onEditWaterTemplate(e) {
    if (this.data.saving || this.data.uploading || !getApp().canAccessStationBusiness()) return
    const template = this.data.waterTemplates.find(item => item.id === e.currentTarget.dataset.id)
    if (template) { this.setData({ showWaterTemplates: false }); this.openWaterTemplateDraft(template) }
  },

  openWaterTemplateDraft(template) {
    if (this.productWriteLocked()) return
    if (this.data.saving || this.data.productImageBusy || this.data.uploading) return
    this.openAdd()
    // 2026-10-21：换模板是新商品意图，独立图片草稿保留原未知记录并隔离旧选图回调。
    this.setData({ editTemplateId: template.id, editForm: { ...this.data.editForm,
      name: template.name, brand: template.brand, spec: template.spec,
      category: template.category, imageObjectName: template.imageObjectName || '', imageUrl: template.imageUrl, description: template.description } })
    this.refreshProductImageState()
  },

  openAdd() {
    if (this.productWriteLocked() || this.data.productImageBusy || this.data.uploading) return
    this._productEditorClosed = false
    this._closedProductEditor = null
    this.rememberProductImageDraft()
    this._productEditOwner = productSession()
    this._productEditEpoch = (this._productEditEpoch || 0) + 1
    this._productImageFailure = null
    this._productPreviewUnavailable = null
    this._productEditorScrollTop = 0
    this._productSectionOffsets = null
    this.captureProductEditorWindow(true)
    this._pkgSeq = (this._pkgSeq || 0) + 1
    // 每份新商品保持独立图片意图；关闭后的同页继续使用原编号。
    try { this._uploadDraftId = uploadDraft({ kind: 'product-image', id: 'new' }, true) } catch (_) { this._uploadDraftId = null }
    this.setData({
      showEdit: true, showSetting: false, showInventory: false,
      productEditorResume: false,
      productSaveError: '',
      productInactive: false, isAdd: true,
      editId: null,
      editTemplateId: '',
      editCategoryIndex: 0,
      productImagePreviewUrl: '',
      pkgRows: [], pkgReady: true, pkgLoading: false, pkgError: '', pkgProductId: null, pkgValidationError: '', // 新商品没有旧档位，可以直接填写草稿
      editForm: {
        name: '', brand: '', spec: '', category: 1,
        price: '', deposit: '', quantity: '', imageObjectName: '', imageUrl: '',
        description: '', enabled: false, priorityDisplay: false, ticketEnabled: false, ticketPrice: ''
      }
    })
    this.layoutProductEditor()
    this.setData({ editHelpExpanded: false })
    this.refreshProductImageState()
    try { this.syncProductCreationRecovery() } catch (_) { /* 由恢复提示阻止另建。 */ }
    setTimeout(() => this.measureProductEditorSections(), 0)
  },

  openEdit(e, platform = false) {
    if (this.productWriteLocked() || this.data.productImageBusy || this.data.uploading) return
    this._productEditorClosed = false
    this._closedProductEditor = null
    this.rememberProductImageDraft()
    this._productEditOwner = productSession()
    this._productEditEpoch = (this._productEditEpoch || 0) + 1
    this._productImageFailure = null
    this._productPreviewUnavailable = null
    this._productEditorScrollTop = 0
    this._productSectionOffsets = null
    this.captureProductEditorWindow(true)
    const item = e.currentTarget.dataset.item
    this._productSettingEpoch = (this._productSettingEpoch || 0) + 1
    this._productSettingClosed = false
    this._productSettingOwner = this._productEditOwner
    try { this._uploadDraftId = uploadDraft({ kind: 'product-image', id: Number(item.id) }) } catch (_) { this._uploadDraftId = null }
    const idx = CATEGORY_OPTIONS.findIndex(c => c.value === item.category)
    this.setData({
      showEdit: true,
      showInventory: false,
      showSetting: platform,
      stationImageEditable: item.stationImageEditable === true,
      productInactive: item.status === 0 || item.status === 2,
      stationImageOverridden: !!item.stationImageObjectName,
      platformImageObjectName: item.platformImageObjectName || '', platformImageUrl: item.platformImageUrl || '',
      editOriginalImageObjectName: item.imageObjectName || '', restorePlatformImage: false,
      productEditorSection: platform ? 'pricing' : 'details',
      productScrollTarget: platform ? 'product-section-pricing' : '',
      productEditorResume: false,
      productSaveError: '', pkgValidationError: '',
      productImagePreviewUrl: '',
      isAdd: false,
      editId: item.id, pkgRows: [], pkgReady: false, pkgError: '', pkgProductId: item.id,
      editTemplateId: '',
      editCategoryIndex: idx >= 0 ? idx : 0,
      setting: { id: item.id, name: item.displayName || item.name, category: item.category || 1,
        quantity: item.quantity == null ? null : item.quantity, ownerStationId: item.ownerStationId,
        salePrice: item.salePrice == null ? '' : String(item.salePrice),
        depositPrice: item.depositPrice == null ? '' : String(item.depositPrice),
        enabled: item.enabled === 1, priorityDisplay: item.priorityDisplay === 1,
        ticketEnabled: item.ticketEnabled === 1, ticketPrice: item.ticketPrice == null ? '' : String(item.ticketPrice),
        refPriceText: item.refPriceText, refDepositText: item.refDepositText },
      stockMode: 'in', stockInput: '', stockAfterText: '', inboundError: '',
      editForm: {
        name: item.name || '',
        brand: item.brand || '',
        spec: item.spec || '',
        category: item.category || 1,
        price: platform ? (item.salePrice == null ? '' : String(item.salePrice)) : item.salePrice > 0 ? String(item.salePrice) : item.price == null ? '' : String(item.price),
        deposit: platform ? (item.depositPrice == null ? '' : String(item.depositPrice)) : item.depositPrice > 0 ? String(item.depositPrice) : item.deposit == null ? '' : String(item.deposit),
        quantity: '',
        imageObjectName: item.imageObjectName || '', imageUrl: item.imageUrl || '',
        description: item.description || '',
        enabled: item.enabled === 1,
        priorityDisplay: item.priorityDisplay === 1,
        ticketEnabled: item.ticketEnabled === 1,
        ticketPrice: item.ticketPrice === null || item.ticketPrice === undefined ? '' : String(item.ticketPrice)
      }
    })
    // 两类商品共享表单、图片区和档位，保存仍使用各自已有的本站写入边界。
    this.loadPkgRows(item.id)
    this.layoutProductEditor()
    try { this.syncProductCreationRecovery() } catch (_) { /* 由恢复提示阻止另建。 */ }
    this.setData({ editHelpExpanded: false })
    this.refreshProductImageState()
    try { this.syncInboundRecovery() } catch (_) { /* 原入库继续按原编号处理。 */ }
    setTimeout(() => this.measureProductEditorSections(), 0)
  },

  closeEdit() {
    if (this.productWriteLocked()) return
    this.leaveProductEditor()
  },

  // page-container consumes the first native Back. Its leave event is a close, never a navigateBack.
  // Native close cannot be vetoed with an invented onBackPress handler. A dispatched save keeps its
  // immutable proof/lock until it settles; its hidden form cannot be changed or submitted again.
  onProductEditorBeforeLeave() {
    if (this.data.showEdit) this.leaveProductEditor()
  },

  leaveProductEditor() {
    if (!this.data.showEdit) return
    this._productEditorClosed = true
    const owner = this._productEditOwner || productSession()
    if (!sameProductSession(owner)) {
      this._closedProductEditor = null
      this._productEditEpoch = (this._productEditEpoch || 0) + 1
      this.setData({ showEdit: false, showSetting: false, showPresetPicker: false, productEditorResume: false, editForm: {}, pkgRows: [], productImagePreviewUrl: '' })
      return
    }
    this.rememberProductImageDraft()
    this._closedProductEditor = { session: owner, draftId: this._uploadDraftId, settingMode: !!this.data.showSetting }
    if (!this.productWriteLocked()) this._productEditEpoch = (this._productEditEpoch || 0) + 1
    this._productSettingClosed = true
    this.setData({ showEdit: false, showSetting: false, showPresetPicker: false, productEditorResume: true })
    this._productKeyboardHeight = 0
    if (typeof wx.hideKeyboard === 'function') wx.hideKeyboard()
    this.refreshProductImageState()
  },

  onResumeProductEditor() {
    if (this.productWriteLocked()) return
    const saved = this._closedProductEditor
    if (!saved || !sameProductSession(saved.session) || !getApp().canAccessStationBusiness()) {
      this._closedProductEditor = null; this.setData({ productEditorResume: false }); return
    }
    const task = this._productImageTask
    if (task && sameProductSession(task.session) && task.draftId === saved.draftId) {
      wx.showToast({ title: '图片仍在处理中，请稍候继续编辑', icon: 'none' }); return
    }
    this._productEditOwner = productSession()
    this._productEditEpoch = (this._productEditEpoch || 0) + 1
    this._productEditorClosed = false
    if (saved.draftId) {
      this._uploadDraftId = saved.draftId
      this.restoreProductImageDraft(saved.draftId)
    }
    this.setData({ showEdit: true, showSetting: !!saved.settingMode, productEditorResume: false })
    if (!this.data.isAdd) {
      this._productSettingOwner = this._productEditOwner
      this._productSettingEpoch = (this._productSettingEpoch || 0) + 1
      this._productSettingClosed = false
      try { this.syncInboundRecovery() } catch (_) { /* 保留原入库提示。 */ }
    }
    this.captureProductEditorWindow(true)
    this.refreshProductImageState()
  },

  onProductSectionTap(e) {
    const section = e.currentTarget.dataset.section
    if (!['details', 'pricing'].includes(section) || !this.data.showEdit) return
    this.setData({ productEditorSection: section, productScrollTarget: '' }, () => this.setData({ productScrollTarget: 'product-section-' + section }))
  },

  measureProductEditorSections() {
    if (!this.data.showEdit || !wx.createSelectorQuery) return
    try {
      const query = wx.createSelectorQuery().in(this)
      query.select('.product-edit-body').boundingClientRect()
      query.selectAll('.product-section-heading').boundingClientRect()
      query.exec(result => {
        const body = result && result[0], headings = result && result[1]
        if (!body || !Array.isArray(headings) || headings.length < 2 || !this.data.showEdit) return
        const top = Number(this._productEditorScrollTop || 0)
        this._productSectionOffsets = { details: headings[0].top - body.top + top, pricing: headings[1].top - body.top + top }
      })
    } catch (_) { this._productSectionOffsets = null }
  },

  onProductEditorScroll(e) {
    const top = Number(e && e.detail && e.detail.scrollTop) || 0
    this._productEditorScrollTop = top
    const offsets = this._productSectionOffsets
    if (!offsets) return
    const section = top + 24 >= offsets.pricing ? 'pricing' : 'details'
    if (section !== this.data.productEditorSection) this.setData({ productEditorSection: section })
  },
  onRestorePlatformImage() {
    if (this.data.productInactive) return
    if (this.data.showSetting && !this.data.stationImageEditable) { wx.showToast({title:'本站图片编辑尚未就绪，请重新读取商品',icon:'none'}); return }
    if (!this.data.showSetting || this.productWriteLocked() || this.data.productImageBusy) return
    this._productImageFailure = null; this._productPreviewUnavailable = null
    this.setData({ 'editForm.imageObjectName': '', 'editForm.imageUrl': this.data.platformImageUrl,
      restorePlatformImage: true, productImagePreviewUrl: '' })
    this.refreshProductImageState()
  },

  onToggleEditHelp() { this.setData({ editHelpExpanded: !this.data.editHelpExpanded }) },

  rememberProductImageDraft() {
    if (!this._uploadDraftId || this._productEditOwner && !sameProductSession(this._productEditOwner)) return
    const drafts = this._productEditorDrafts || (this._productEditorDrafts = new Map())
    const snapshot = {}
    for (const field of ['productInactive','stationImageEditable','stationImageOverridden','platformImageObjectName','platformImageUrl','editOriginalImageObjectName','restorePlatformImage','productEditorSection','productScrollTarget','showSetting','isAdd','editId','editForm','editTemplateId','editCategoryIndex','pkgRows','pkgReady','pkgError','pkgProductId','productImagePreviewUrl',
      'setting','stockMode','stockInput','stockAfterText','inboundError']) snapshot[field] = this.data[field]
    snapshot._imageChoice = this._productImageChoiceSeq || 0
    snapshot._imageFailed = !!(this._productImageFailure && this._productImageFailure.draftId === this._uploadDraftId)
    snapshot._imageDiagnostic = snapshot._imageFailed ? this._productImageFailure.diagnostic : null
    snapshot._previewUnavailable = !!(this._productPreviewUnavailable && this._productPreviewUnavailable.draftId === this._uploadDraftId)
    drafts.set(productSession().key + ':' + this._uploadDraftId, JSON.parse(JSON.stringify(snapshot)))
  },

  restoreProductImageDraft(id) {
    const snapshot = this._productEditorDrafts && this._productEditorDrafts.get(productSession().key + ':' + id)
    if (snapshot) {
      const restored = JSON.parse(JSON.stringify(snapshot))
      this._productImageFailure = restored._imageFailed ? { draftId: id, diagnostic: restored._imageDiagnostic } : null
      this._productPreviewUnavailable = restored._previewUnavailable ? { draftId: id } : null
      delete restored._imageChoice; delete restored._imageFailed; delete restored._previewUnavailable; delete restored._imageDiagnostic
      this.setData({ ...restored, showEdit: true })
    }
  },

  refreshProductImageState() {
    const task = this._productImageTask
    const busy = !!(task && sameProductSession(task.session) && task.epoch === (this._productEditEpoch || 0) && task.draftId === this._uploadDraftId)
    const failed = this._productImageFailure && this._productImageFailure.draftId === this._uploadDraftId
    const state = busy ? task.phase : (failed ? 'failed' : (this.data.editForm.imageUrl || this.data.editForm.imageObjectName ? 'ready' : 'empty'))
    const hints = { empty: '尚未设置商品图片', ready: '商品图片已选好，可更换', selecting: '正在选择图片…', uploading: '图片上传中，请稍候…',
      failed: '图片上传失败，可重试或重新选择。保存商品会沿用原图；未设置原图时不带图片。' }
    const unavailable = this._productPreviewUnavailable && this._productPreviewUnavailable.draftId === this._uploadDraftId
    this.setData({ productImageState: state, productImageHint: state === 'ready' && unavailable ? '图片已上传，预览暂不可用；商品资料可保存。' : hints[state], productImageBusy: busy, uploading: busy && task.phase === 'uploading' })
  },

  stopPropagation() {},

  /** 弹窗遮罩上吞掉 touchmove，防止滚动穿透到页面（wxml 用 catchtouchmove） */
  preventMove() {},

  onEditFieldInput(e) {
    if (this.data.productInactive) return
    if (this.productWriteLocked() || this.data.productCreationPending) return
    const field = e.currentTarget.dataset.field
    if (this.data.showSetting && !['price', 'deposit', 'ticketPrice'].includes(field)) return
    this.setData({ ['editForm.' + field]: e.detail.value })
  },

  onEditCategoryChange(e) {
    if (this.data.productInactive) return
    if (this.productWriteLocked() || this.data.productCreationPending || this.data.showSetting) return
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
    if (this.data.productInactive) return
    if (this.productWriteLocked() || this.data.productCreationPending) return
    const field = e.currentTarget.dataset.field
    this.setData({ ['editForm.' + field]: e.detail.value })
  },

  async onSaveEdit() {
    if (this.data.productInactive) { wx.showToast({title:'商品停用中，库存请单独提交',icon:'none'}); return }
    if (this.productWriteLocked() || this._productEditorClosed) return
    if (this.data.showSetting) {
      this.refreshProductImageState()
      if (this.data.productImageBusy) { wx.showToast({title: '图片仍在处理中，请稍候再保存', icon: 'none'}); return }
      const f = this.data.editForm
      const restored = this.data.restorePlatformImage && !f.imageObjectName && f.imageUrl === this.data.platformImageUrl
      const image = restored ? '' : f.imageObjectName || (f.imageUrl && f.imageUrl.startsWith('/') ? f.imageUrl : '')
      const imageChanged = restored || image !== this.data.editOriginalImageObjectName
      if (imageChanged && !this.data.stationImageEditable) { wx.showToast({ title: '本站图片编辑尚未就绪，请重新读取商品', icon: 'none' }); return }
      this.setData({ 'setting.imageObjectName': imageChanged ? image : null })
      this.setData({ 'setting.salePrice': f.price, 'setting.depositPrice': f.deposit,
        'setting.ticketPrice': f.ticketPrice, 'setting.enabled': f.enabled,
        'setting.priorityDisplay': f.priorityDisplay, 'setting.ticketEnabled': f.ticketEnabled })
      return this.onSaveSetting()
    }
    let existingCreation
    try { existingCreation = this.syncProductCreationRecovery() } catch (_) { return }
    if (existingCreation && (this.data.isAdd || Number(this.data.editId) === existingCreation.productId)) {
      if (existingCreation.phase === 'packages-pending') return this.onContinueCreatedProduct()
      wx.showToast({ title: '原商品创建结果待核实，不能再次新建', icon: 'none' }); return
    }
    this.refreshProductImageState()
    if (this.data.productImageBusy) { wx.showToast({ title: '图片仍在处理中，请稍候再保存', icon: 'none' }); return }
    const { isAdd, editId } = this.data, editForm = productCopy(this.data.editForm || {})
    if (editForm.ticketEnabled && (this.data.pkgLoading || this.data.pkgError)) { wx.showToast({ title: '请先读取原水票档位', icon: 'none' }); return }
    if (this.data.editTemplateId) {
      const error = templateBusinessError(editForm)
      if (error) { wx.showToast({ title: error, icon: 'none' }); return }
    }
    if (!editForm.name || !editForm.name.trim()) {
      wx.showToast({ title: '商品名称不能为空', icon: 'none' })
      return
    }
    const price = exactNumber(editForm.price)
    if (price === null) {
      wx.showToast({ title: '请输入正确的售价', icon: 'none' })
      return
    }
    const deposit = blankNumber(editForm.deposit) ? 0 : exactNumber(editForm.deposit)
    const quantity = blankNumber(editForm.quantity) ? null : exactNumber(editForm.quantity)
    const ticketPrice = blankNumber(editForm.ticketPrice) ? null : exactNumber(editForm.ticketPrice)
    if (deposit === null || (editForm.ticketEnabled && !blankNumber(editForm.ticketPrice) && ticketPrice === null)) {
      wx.showToast({ title: '请输入完整的数字金额', icon: 'none' })
      return
    }
    if (!blankNumber(editForm.quantity) && (quantity === null || !Number.isSafeInteger(quantity) || quantity < 0)) {
      wx.showToast({ title: '库存须填写非负整数', icon: 'none' })
      return
    }
    const rows = productCopy(this.data.pkgRows || [])
    if (editForm.ticketEnabled) {
      const packageError = ticketPackageValidationError(rows)
      if (packageError) { this.setData({ pkgValidationError: packageError }); wx.showToast({ title: packageError, icon: 'none' }); return }
    }
    const payload = {
      name: editForm.name.trim(),
      brand: editForm.brand || '',
      spec: editForm.spec || '',
      category: editForm.category || 1,
      price,
      deposit,
      enabled: editForm.enabled ? 1 : 0,
      ticketEnabled: editForm.ticketEnabled ? 1 : 0,
      priorityDisplay: editForm.priorityDisplay ? 1 : 0,
      imageUrl: editForm.imageUrl || '',
      imageObjectName: editForm.imageObjectName || '',
      description: editForm.description || ''
    }
    if (editForm.ticketEnabled && !blankNumber(editForm.ticketPrice)) {
      payload.ticketPrice = ticketPrice
    }
    if (isAdd && !blankNumber(editForm.quantity)) {
      payload.quantity = quantity
    }
    if (!isAdd) {
      // A station-owned product now has one price editor. Keep its visible effective prices
      // as the owned product's prices and clear former station overrides in the same PUT.
      payload.salePrice = 0
      payload.depositPrice = 0
    }

    const proof = this.productSaveProof('edit', isAdd ? 'new' : editId)
    if (!this.productSaveCurrent(proof)) { wx.showToast({ title: '身份或商品已变化，请重新进入', icon: 'none' }); return }
    const flight = this._productSaveFlight = {}
    let creation = null, createDispatched = false
    this.setData({ saving: true, productSaveError: '' })
    try {
      if (isAdd) {
        // createMyProduct 返回的是新建商品的 id（Result<Long>）—— 拿到它才能把档位挂上去，
        // 否则「新建商品」这一步永远配不出档位，站长还得回头去水票档位页补一次
        creation = { version: 1, key: creationKey(), phase: 'create-unknown', productId: null, draftId: this._uploadDraftId || '',
          name: payload.name, rows: editForm.ticketEnabled ? rows : [], nextRow: 0 }
        this.writeProductCreation(creation)
        if (!this.productSaveCurrent(proof)) throw saveScopeError()
        createDispatched = true
        const created = await createMyProduct(payload), newId = productIdOf(created && created.data)
        if (!newId || created && created.code != null && created.code !== 0) throw Object.assign(Error('新建结果暂未查到，请查看商品目录，勿重复新建'), { malformedResponse: true })
        // 已取得可信ID即进入第二阶段；即使档位失败或页面离开，原商品编号也不得丢失。
        creation.productId = newId; creation.phase = 'packages-pending'
        this.writeProductCreation(creation)
        if (!this.productSaveCurrent(proof)) throw saveScopeError()
        this.setData({ isAdd: false, editId: newId, pkgProductId: newId })
        proof.targetId = newId
        await this.savePkgRows(newId, creation.rows, () => this.productSaveCurrent(proof), index => {
          creation.nextRow = index; this.writeProductCreation(creation)
        })
        if (!this.productSaveCurrent(proof)) throw saveScopeError()
        this.clearProductCreation(creation)
        this.syncProductCreationRecovery()
        wx.showToast({ title: '已创建', icon: 'success' })
      } else {
        const res = await updateMyProduct(editId, payload)
        if (!this.productSaveCurrent(proof)) throw saveScopeError()
        this.toastWarnings(res)
        if (editForm.ticketEnabled) await this.savePkgRows(editId, rows, () => this.productSaveCurrent(proof))
        if (!this.productSaveCurrent(proof)) throw saveScopeError()
        wx.showToast({ title: this.data.warningsInline.length ? '已保存，请核对价格' : '已保存', icon: this.data.warningsInline.length ? 'none' : 'success' })
      }
      this._productEditorClosed = true
      this._closedProductEditor = null
      this.setData({ showEdit: false, productEditorResume: false })
      this.loadData()
    } catch (err) {
      if (creation && !creation.productId && (!createDispatched || !saveOutcomeUnknown(err) && !err.productSaveScopeChanged)) {
        try { this.clearProductCreation(creation); creation = null } catch (_) { /* 清理失败仍保留阻断，不另建。 */ }
      }
      if (this.productSaveCurrent(proof)) {
        try { this.syncProductCreationRecovery() } catch (_) { /* 保留原记录核实提示。 */ }
        const message = creation && creation.productId ? '商品 #' + creation.productId + ' 已创建，原保存进度仍待核对。' + err.message
          : creation && createDispatched ? '原商品创建结果待核实，请先核对目录，勿重复新建。'
            : !createDispatched && isAdd ? '原建档记录未能保存，尚未提交。' + err.message : err.message || '保存失败'
        this.setData({ productSaveError: message })
        wx.showToast({ title: message, icon: 'none' })
      }
    } finally {
      if (this._productSaveFlight === flight) { this._productSaveFlight = null; if (!this._productDestroyed) this.setData({ saving: false }) }
    }
  },

  /** 停用本站自定义商品（软删：历史订单/桶账仍能引用它） */
  onDeactivate(e) {
    if (this.productWriteLocked()) return
    const item = e.currentTarget.dataset.item, session = productSession()
    if (!item || Number(item.ownerStationId) !== Number((getApp().globalData.userInfo || {}).stationId)) return
    wx.showModal({ cancelColor: '#637083',
      title: '移除商品',
      content: '移除后本站顾客看不到它，历史订单仍保留。确认移除？',
      confirmText: '移除',
      confirmColor: '#BD3328',
      success: (res) => {
        if (!res.confirm || !sameProductSession(session) || this.productWriteLocked()) return
        deleteMyProduct(item.id)
          .then(() => {
            if (!sameProductSession(session)) return
            wx.showToast({ title: '已移除', icon: 'success' })
            this.loadData()
          })
          .catch(err => wx.showToast({ title: err.message || '操作失败', icon: 'none' }))
      }
    })
  },

  /** 上报给开发者，请其考虑补进通用库 */
  onSubmitToPlatform(e) {
    const item = e.currentTarget.dataset.item, owner=productSession()
    if (!item || this.productWriteLocked() || [0,2].includes(item.status) || Number(item.ownerStationId)!==Number((getApp().globalData.userInfo||{}).stationId)) return
    wx.showModal({ confirmColor: '#1264D6', cancelColor: '#637083',
      title: '上报给开发者',
      editable: true,
      placeholderText: '补充说明（规格/品牌/进货渠道等，可留空）',
      confirmText: '上报',
      success: (res) => {
        if (!res.confirm || !sameProductSession(owner) || this.productWriteLocked()) return
        submitMyProduct(item.id, res.content || '')
          .then(() => {if(sameProductSession(owner)){wx.showToast({title:'已上报，等待开发者处理',icon:'success',duration:2000});this.loadData();if(this.data.showSubmissions)this.onOpenSubmissions()}})
          .catch(err => {if(sameProductSession(owner))wx.showToast({title:err.message||'上报结果待核实',icon:'none'})})
      }
    })
  },

  /**
   * 打开平台预设图选择面板。
   *
   * 产品口径（docs/design/14）：平台提供一套**统一商品图**，站长不必自己拍照上传 ——
   * 这样顾客端看到的是同一套视觉，不会出现各站五花八门的糊图。
   * 后端下发包内路径或COS签名预览，并分开提供持久对象键。
   */
  async openPresetPicker() {
    if (this.data.productInactive) return
    if (this.data.showSetting && !this.data.stationImageEditable) { wx.showToast({ title: '本站图片编辑尚未就绪，请重新读取商品', icon: 'none' }); return }
    if (this.data.productCreationPending) return
    this.refreshProductImageState()
    if (this.data.saving || this.data.productImageBusy) return
    this.setData({ showPresetPicker: true })
    if (this.data.presetImages.length) return   // 已拉过就不重复请求
    this.setData({ presetLoading: true, presetError: '', presetReady: false })
    try {
      const res = await getPresetImages()
      if (!Array.isArray(res.data)) throw new Error('图库资料未读取')
      this.setData({ presetImages: res.data, presetReady: true })
    } catch (err) {
      this.setData({ presetError: '平台图库尚未读取，请重试；也可关闭后上传实际商品照片' })
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
   * 预览写imageUrl；COS图另存objectName。包内路径仍保留旧契约，
   * 不能把临时预览URL当对象键或从URL反推对象键。
   */
  onPickPresetImage(e) {
    if (this.data.showSetting && !this.data.stationImageEditable) { wx.showToast({ title: '本站图片编辑尚未就绪，请重新读取商品', icon: 'none' }); return }
    if (this.data.productCreationPending) return
    this.refreshProductImageState()
    if (this.data.saving || this.data.productImageBusy) return
    const path = e.currentTarget.dataset.path
    const objectName = e.currentTarget.dataset.objectName || ''
    if (!path) return
    this.setData({
      'editForm.imageUrl': path,
      'editForm.imageObjectName': objectName,
      productImagePreviewUrl: '',
      showPresetPicker: false
    })
    this._productImageFailure = null
    this._productPreviewUnavailable = null
    this.refreshProductImageState()
  },

  onRetryProductImage() {
    this.refreshProductImageState()
    if (this.data.productImageState !== 'failed' || !this.data.productImagePreviewUrl) return
    return this.uploadImage(this.data.productImagePreviewUrl)
  },

  onCopyProductImageProblem() {
    if (this.data.productImageState !== 'failed' || !this._productEditOwner || !sameProductSession(this._productEditOwner)) return
    const diagnostic = this._productImageFailure && this._productImageFailure.diagnostic
    wx.setClipboardData({ data: diagnosticText(diagnostic),
      success: () => wx.showToast({ title: '问题信息已复制', icon: 'none' }),
      fail: () => wx.showToast({ title: '未能复制，请重试', icon: 'none' }) })
  },

  async uploadImage(event) {
    if (this.data.showSetting && !this.data.stationImageEditable) { wx.showToast({ title: '本站图片编辑尚未就绪，请重新读取商品', icon: 'none' }); return }
    if (this.data.productCreationPending) return
    this.refreshProductImageState()
    if (this.data.saving || this.data.productImageBusy) return
    const currentImageTarget = () => this.data.isAdd ? 'new' : Number(this.data.editId)
    const imageTarget = currentImageTarget()
    let uploadDraftId
    try { uploadDraftId = uploadDraft({ kind: 'product-image', id: imageTarget }) }
    catch (error) { wx.showToast({ title: '暂时无法选择图片，请重试', icon: 'none' }); return }
    this._uploadDraftId = uploadDraftId
    this.refreshProductImageState()
    const retryPath = typeof event === 'string' ? event : null
    const task = { session: productSession(), draftId: uploadDraftId, epoch: this._productEditEpoch || 0, phase: 'selecting',
      choice: this._productImageChoiceSeq = (this._productImageChoiceSeq || 0) + 1 }
    this._productImageTask = task
    this.rememberProductImageDraft()
    const current = () => this._productImageTask === task && sameProductSession(task.session) && currentImageTarget() === imageTarget
      && this._uploadDraftId === uploadDraftId && task.epoch === (this._productEditEpoch || 0)
    const settled = () => {
      if (this._productImageTask === task) this._productImageTask = null
      if (sameProductSession(task.session)) {
        this.refreshProductImageState()
      }
    }
    this.refreshProductImageState()
    try {
    const pickerOptions = {
      count: 1,
      onPickerSettled: settled,
      success: async (res) => {
        if (!current()) { settled(); return }
        const tempFile = res.tempFilePaths[0]
        task.phase = 'uploading'
        this._productImageFailure = null
        // 临时选图只用于当前草稿预览；结果未确认前绝不写进可保存的 URL / 对象键。
        this.setData({ productImagePreviewUrl: tempFile })
        this.rememberProductImageDraft()
        this.refreshProductImageState()
        try {
          const uploadRes = await upload({
            filePath: tempFile, session: res.uploadSession,
            url: API.GENERAL_UPLOAD,
            intent: { kind: 'product-image', id: imageTarget, draftId: uploadDraftId },
            name: 'file'
          })
          if (!isUploadSessionCurrent(res.uploadSession) || !sameProductSession(task.session)) return
          if (uploadRes.code === 0) {
            const imageUrl = typeof uploadRes.data === 'string' ? uploadRes.data : ''
            const snapshots = this._productEditorDrafts, key = task.session.key + ':' + uploadDraftId
            const saved = snapshots && snapshots.get(key)
            if (saved && saved._imageChoice === task.choice) {
              saved.editForm.imageUrl = imageUrl; saved.editForm.imageObjectName = uploadRes.objectName
              saved._imageFailed = false; saved._previewUnavailable = !!uploadRes.previewUnavailable; snapshots.set(key, saved)
            }
            if (!current()) return
            this._productPreviewUnavailable = uploadRes.previewUnavailable ? { draftId: uploadDraftId } : null
            this.setData({ 'editForm.imageUrl': imageUrl, 'editForm.imageObjectName': uploadRes.objectName })
            wx.showToast({ title: uploadRes.previewUnavailable ? '图片已上传，预览暂不可用' : '图片上传成功', icon: uploadRes.previewUnavailable ? 'none' : 'success' })
          } else {
            throw new Error('图片上传失败，请重试')
          }
        } catch (err) {
          // 仅固定诊断码/数值状态可出日志；不得带本地路径、预览URL、账号或服务原文。
          const diagnostic = uploadDiagnostic(err)
          console.warn('[product-image-upload]', diagnostic)
          if (!isUploadSessionCurrent(res.uploadSession) || !sameProductSession(task.session)) return
          const snapshots = this._productEditorDrafts, savedKey = task.session.key + ':' + uploadDraftId
          const saved = snapshots && snapshots.get(savedKey)
          if (saved && saved._imageChoice === task.choice) { saved._imageFailed = true; saved._imageDiagnostic = diagnostic; snapshots.set(savedKey, saved) }
          if (!current()) return
          this._productImageFailure = { draftId: uploadDraftId, diagnostic }
          wx.showToast({ title: '图片上传失败，可重试', icon: 'none' })
        } finally {
          settled()
        }
      }
    }
    const accepted = retryPath ? (await pickerOptions.success({ tempFilePaths: [retryPath], uploadSession: captureSession() }), true) : chooseImages(pickerOptions)
    if (accepted === false) settled()
    } catch (error) {
      if (current()) { this._productImageFailure = { draftId: uploadDraftId }; wx.showToast({ title: error.message || '选取图片失败', icon: 'none' }) }
      settled()
    }
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
      wx.showModal({ confirmColor: '#1264D6', cancelColor: '#637083',
        title: '价格请再核对一次',
        content: warnings.join('\n'),
        showCancel: false,
        confirmText: '我知道了'
      })
      return
    }
    // 保存调用方统一反馈；价格原因持续显示在目录顶部，避免连续toast覆盖。
  }
})
