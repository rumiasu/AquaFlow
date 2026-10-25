const { initialModuleData, moduleTaskPatch, refreshModuleTasks } = require('../../../utils/setup-progress')
// ⚠️ 这里直接用 utils/request 而不是 api/station-mgmt.js 的封装（含 2026-09-19 加的 /api/manager/setup-guide）：
// 后者的接口清单当前正被另一个工作流（商品图片库）改动，共用文件会让两边未提交的改动纠缠在一起。
// 路径常量也写在本文件里，同样是为了避开共享的 config/api.js。
const { get, put } = require('../../../utils/request')

// Login generation and identity isolate reads; token rotation during renewal remains the same login.
function staffSession() {
  const app = getApp(), g = app.globalData || {}, u = g.userInfo || {}
  return { app, generation: app._loginGeneration || 0,
    key: JSON.stringify([!!g.accessToken, g.isLogin, u.staffId, u.role, u.stationId, u.bindStatus]) }
}

const DELIVERY_CONFIG_PATH = '/api/manager/delivery-config'
const SETUP_GUIDE_PATH = '/api/manager/setup-guide'

const getDeliveryConfig = () => get(DELIVERY_CONFIG_PATH)
const saveDeliveryConfig = (data) => put(DELIVERY_CONFIG_PATH, data)
/** 本站配置完善度清单（2026-09-19）：路径同样写在本文件里，理由同上（避开共享文件）。 */
const getSetupGuide = () => get(SETUP_GUIDE_PATH)

/**
 * 配送计费配置（站长）：起送量 / 配送范围 / 运费 / 楼层费。规格见 docs/design/17。
 *
 * ⚠️ 三条口径，改这个页面时必须守住：
 *   1. 三个「处理方式」是三选一：WARN 仅提示 / REJECT 不接单 / FEE 加收费用。
 *      **默认必须是 WARN** —— 默认硬拦等于站长一建站就把客户挡在门外。
 *   2. 数值留空表示"不限"或"不收"，不要用 0 去表达"不限"（0 是合法的收费金额）。
 *   3. 费用会在下单时**快照进订单**，所以改这里的配置**不会**影响历史订单的金额。
 */

const LIMIT_MODES = [
  { value: 'WARN', name: '仅提示', desc: '照常接单，只在结算页提醒客户' },
  { value: 'REJECT', name: '不接单', desc: '直接拒绝下单（客户会看到原因）' },
  { value: 'FEE', name: '加收费用', desc: '照常接单，另加一笔费用' }
]

const FLOOR_MODES = [
  { value: 'PER_ORDER', name: '按单', desc: '每单加收一次，客户更容易接受（推荐）' },
  { value: 'PER_BUCKET', name: '按桶', desc: '无电梯 2 元/桶这类口头报价，多桶时金额增长快' }
]

// 布局映射只用于锚点和错误定位，不参与计费或提交数据转换。
const FEE_FIELD_SECTIONS = {
  minOrderBuckets: 'minOrder', minOrderAmount: 'minOrder', minOrderMode: 'minOrder', minOrderFee: 'minOrder',
  deliveryRadiusM: 'range', overRadiusMode: 'range', remoteFee: 'range',
  baseDeliveryFee: 'delivery', freeDeliveryBuckets: 'delivery', freeDeliveryAmount: 'delivery',
  floorFreeLevel: 'floor', floorFeePerLevel: 'floor', floorFeeMode: 'floor'
}

Page({
  data: {
    ...initialModuleData(),
    loading: true, configReady: false, configError: '', setupError: '',
    saving: false,
    activeSection: 'minOrder', setupExpanded: false, setupRequiredText: '',
    sectionTabs: [{ key: 'minOrder', label: '起送条件' }, { key: 'range', label: '配送范围' },
      { key: 'delivery', label: '配送费' }, { key: 'floor', label: '楼层费' }],
    fieldError: '', fieldErrorText: '', saveError: '', feeKeyboardOffset: 0, feeBodyStyle: '',
    modeDescriptions: { minOrderMode: LIMIT_MODES[0].desc, overRadiusMode: LIMIT_MODES[0].desc, floorFeeMode: FLOOR_MODES[0].desc },
    configured: false,
    // 「配送范围」那段说明的正文（在 loadSetup 里拼好）。为什么不在 wxml 里拼：
    // 见 loadSetup 的 ⚠️（WXML 表达式解析器不支持带换行的字符串拼接，会编译报错）。
    rangeHelpText: '按站点坐标到收货地址的直线距离判断。',
    limitModes: LIMIT_MODES,
    floorModes: FLOOR_MODES,
    // 表单值一律用字符串存：留空表示"不限/不收"，用 '' 才能与数字 0 区分开
    form: {
      minOrderBuckets: '',
      minOrderAmount: '',
      minOrderMode: 'WARN',
      minOrderFee: '',
      deliveryRadiusM: '',
      overRadiusMode: 'WARN',
      remoteFee: '',
      baseDeliveryFee: '',
      freeDeliveryBuckets: '',
      freeDeliveryAmount: '',
      floorFreeLevel: '1',
      floorFeePerLevel: '',
      floorFeeMode: 'PER_ORDER'
    },
    // 引导信息（2026-09-19）：本站已上架非桶装商品时，金额类门槛必须补上 ——
    // 非桶装不占桶，只配桶数门槛等于那条规则对它不生效（后端 DeliveryFeeUtil 的"条件不适用"分支）。
    // 建议值由后端按"本站最便宜的一桶水"算好下发，前端只展示与回填，**不自算倍数、不自造文案**。
    guide: {
      nonBarrelOnShelf: false,
      requiredAmountFields: [],
      cheapestWaterPrice: null,
      cheapestPriceBasis: '',
      suggestedMinOrderAmount: null,
      suggestedFreeDeliveryAmount: null
    },
    guideText: '',
    // 本站「配置完善度」（2026-09-19）：规则目录在后端 StationSetupGuideService，
    // 页面只渲染后端给的中文（label/why/where/action），**不自建 key→文案映射**。
    setup: { items: [], summaryText: '', p0PendingCount: 0, doneCount: 0, totalCount: 0 },
    setupPending: []
  },

  refreshModuleSetup() { return this.loadSetup() },
  onModuleSetupTask(e) {
    const key = e.currentTarget.dataset.key
    if (!(this.data.moduleSetupTasks || []).some(it => it.key === key)) return
    this.setData({ activeSection: 'minOrder' }, () => this.scrollToFeeTarget('#fee-minOrder'))
  },

  onShow() {
    this.captureFeeWindow()
    this.setData({ feeKeyboardOffset: 0 })
    return this.reload()
  },

  reload() {
    if (this._unloaded) return
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      this._configVersion = (this._configVersion || 0) + 1
      this._setupVersion = (this._setupVersion || 0) + 1
      app.routeByRole(true)
      return
    }
    return Promise.all([this.load(), this.loadSetup()])
  },

  async onPullDownRefresh() {
    const version = this._pullVersion = (this._pullVersion || 0) + 1
    this._pullRefreshing = true
    try { await this.reload() } finally {
      if (version === this._pullVersion) {
        this._pullRefreshing = false
        wx.stopPullDownRefresh()
      }
    }
  },

  onUnload() {
    this._unloaded = true
    if (this._feeSpyTimer) clearTimeout(this._feeSpyTimer)
    this._configVersion = (this._configVersion || 0) + 1
    this._setupVersion = (this._setupVersion || 0) + 1
    this._pullVersion = (this._pullVersion || 0) + 1
    if (this._pullRefreshing) {
      this._pullRefreshing = false
      wx.stopPullDownRefresh()
    }
  },

  captureLoad(kind) {
    return { ...staffSession(), version: this[kind] = (this[kind] || 0) + 1 }
  },

  isLoadCurrent(kind, context) {
    const current = staffSession()
    return !this._unloaded && context.version === this[kind]
      && context.app === current.app && context.generation === current.generation
      && context.key === current.key && current.app.canAccessStationBusiness()
  },

  /**
   * 读"本站配置完善度"（2026-09-19）：规则目录在后端，页面只渲染。
   *
   * 静默失败是有意的：这是页面顶部的一张提示卡，取不到就当没有 —— 不该因为一个提示
   * 把整个配送计费页变成错误页（同客户列表页对待"待审列表"的口径）。
   */
  async loadSetup() {
    if (this._unloaded) return
    const context = this.captureLoad('_setupVersion')
    this.setData({ setupError: '', moduleSetupLoading: true, moduleSetupReady: false, moduleSetupError: '' })
    try {
      const res = await getSetupGuide()
      if (!this.isLoadCurrent('_setupVersion', context)) return
      const d = (res && res.data) || {}
      const items = Array.isArray(d.items) ? d.items : []
      // 待填项每条要显示的两段说明，**在这里拼好**（`helpText`）——
      // ⚠️ 不要写进 wxml 的 `{{a}}{{b ? '\n\n' + b : ''}}`：WXML 表达式解析器吃不下
      //    带换行的字符串拼接，直接**编译报错**（Bad attr `text`，2026-09-24 实测）。
      //     本仓口径也是"wxml 里不做计算"（禁止调 Page 方法 / Math. / Date. 的同一条理由）。
      const pending = items
        .filter(i => !i.done)
        .sort((a, b) => String(a.level).localeCompare(String(b.level)))
        .map(it => Object.assign({}, it, {
          helpText: (it.why || '') + (it.suggestion ? '\n\n' + it.suggestion : '')
        }))

      // 「配送范围」那句说明要不要带上"没选点会被跳过"（2026-09-24 产品要求：坐标这类概念也藏）：
      // 判据取完善度清单里的 `stationPosition` —— 它未完成 ⇔ 地址或坐标缺 ⇔ 算不出距离
      // ⇔ 范围校验整段跳过。⚠️ 别自己再写一套"查坐标"的请求：两处判据一定会分叉。
      const pos = items.find(i => i && i.key === 'stationPosition')
      const rangeHelp = '按站点坐标到收货地址的直线距离判断。'
      this.setData({
        ...moduleTaskPatch(d, '/pages/station-mgmt/delivery-config/index'),
        rangeHelpText: (pos && !pos.done)
          ? rangeHelp + '本站还没选点，所以现在这条校验会被跳过、不拦单。'
          : rangeHelp,
        setup: {
          items,
          summaryText: d.summaryText || '',
          p0PendingCount: Number(d.p0PendingCount || 0),
          doneCount: Number(d.doneCount || 0),
          totalCount: Number(d.totalCount || items.length)
        },
        setupRequiredText: pending.filter(it => it.level === 'P0' || it.levelText === '必填').map(it => it.label).join('、'),
        setupPending: pending
      })
    } catch (err) {
      if (!this.isLoadCurrent('_setupVersion', context)) return
      console.warn('[DeliveryConfig] 完善度清单获取失败（当作没有）:', err && err.message)
      // 拉不到就**不显示**那句"没选点"：宁可不提醒，也不要凭空断言"你没选点"（说错了更糟）
      this.setData({ ...moduleTaskPatch(null, '/pages/station-mgmt/delivery-config/index'), setupError: '配置完善度清单尚未读取，请重试', rangeHelpText: '按站点坐标到收货地址的直线距离判断。', setup: { items: [], summaryText: '', p0PendingCount: 0, doneCount: 0, totalCount: 0 }, setupPending: [], setupRequiredText: '' })
    }
  },

  async load() {
    if (this._unloaded) return
    const context = this.captureLoad('_configVersion')
    this.setData({ loading: true, configReady: false, configError: '' })
    try {
      const res = await getDeliveryConfig()
      if (!this.isLoadCurrent('_configVersion', context)) return
      const d = res.data || {}
      const c = d.config || {}
      // 后端把 null 原样下发（表示"不限/不收"），这里统一转成 '' 便于输入框编辑
      const s = (v) => (v === null || v === undefined ? '' : String(v))
      const g = d.guidance || {}
      const guide = {
        nonBarrelOnShelf: g.nonBarrelOnShelf === true,
        requiredAmountFields: Array.isArray(g.requiredAmountFields) ? g.requiredAmountFields : [],
        cheapestWaterPrice: g.cheapestWaterPrice == null ? null : Number(g.cheapestWaterPrice),
        cheapestPriceBasis: g.cheapestPriceBasis || '',
        suggestedMinOrderAmount: g.suggestedMinOrderAmount == null ? null : Number(g.suggestedMinOrderAmount),
        suggestedFreeDeliveryAmount: g.suggestedFreeDeliveryAmount == null ? null : Number(g.suggestedFreeDeliveryAmount)
      }
      this.setData({
        configReady: true, configured: d.configured === true,
        form: {
          minOrderBuckets: s(c.minOrderBuckets),
          minOrderAmount: s(c.minOrderAmount),
          minOrderMode: c.minOrderMode || 'WARN',
          minOrderFee: s(c.minOrderFee),
          deliveryRadiusM: s(c.deliveryRadiusM),
          overRadiusMode: c.overRadiusMode || 'WARN',
          remoteFee: s(c.remoteFee),
          baseDeliveryFee: s(c.baseDeliveryFee),
          freeDeliveryBuckets: s(c.freeDeliveryBuckets),
          freeDeliveryAmount: s(c.freeDeliveryAmount),
          floorFreeLevel: s(c.floorFreeLevel),
          floorFeePerLevel: s(c.floorFeePerLevel),
          floorFeeMode: c.floorFeeMode || 'PER_ORDER'
        },
        guide,
        guideText: this.describeGuide(guide)
      }, () => { this.syncModeDescriptions(); this.measureFeeSaveBar() })
    } catch (err) {
      if (this.isLoadCurrent('_configVersion', context)) this.setData({ configError: '配送计费尚未读取，请重新核对；当前不能修改计费规则' })
      if (this.isLoadCurrent('_configVersion', context)) wx.showToast({ title: (err && err.message) || '配置加载失败', icon: 'none' })
    } finally {
      if (this.isLoadCurrent('_configVersion', context)) this.setData({ loading: false }, () => this.measureFeeSaveBar())
    }
  },

  /** 把后端下发的引导信息说成一句人话（wxml 不做拼接）。 */
  describeGuide(g) {
    if (!g || !g.requiredAmountFields || !g.requiredAmountFields.length) {
      return ''
    }
    const names = g.requiredAmountFields.map(f => (f === 'minOrderAmount' ? '起送量金额' : '免运费金额'))
    const parts = [`你上架了${g.nonBarrelOnShelf ? '瓶装水/一次性桶/饮水器' : '非桶装商品'}，` +
      `但这些商品不占桶，只配桶数门槛对它没有意义 —— 请补上：${names.join('、')}。`]
    if (g.cheapestPriceBasis) {
      parts.push(`建议值按${g.cheapestPriceBasis} ¥${g.cheapestWaterPrice} 推算（起送约两桶水、免运费约三桶水），可直接点下方按钮填入。`)
    } else {
      parts.push('本站还没有已上架的商品，无法推算建议值，请手动填写。')
    }
    return parts.join('')
  },

  /** 一键填入建议值（只填后端给了建议的那几项，其余不动）。 */
  onFillSuggested() {
    const { guide, form } = this.data
    const updates = {}
    if (guide.suggestedMinOrderAmount != null && !form.minOrderAmount) {
      updates['form.minOrderAmount'] = String(guide.suggestedMinOrderAmount)
    }
    if (guide.suggestedFreeDeliveryAmount != null && !form.freeDeliveryAmount) {
      updates['form.freeDeliveryAmount'] = String(guide.suggestedFreeDeliveryAmount)
    }
    if (!Object.keys(updates).length) {
      wx.showToast({ title: '没有可填入的建议值，请手动填写', icon: 'none' })
      return
    }
    this.setData(updates)
    wx.showToast({ title: '已填入建议值，确认后保存', icon: 'none' })
  },

  onReady() { this.captureFeeWindow(); this.measureFeeSaveBar() },
  onResize() { this.onFeeKeyboardHeightChange({ detail: { height: this._feeKeyboardHeight || 0 } }); this.measureFeeSections() },
  onHide() { this._feeKeyboardHeight = 0; this._feeFocusedField = ''; this.setData({ feeKeyboardOffset: 0 }) },

  captureFeeWindow() {
    try { const info = typeof wx.getWindowInfo === 'function' ? wx.getWindowInfo() : wx.getSystemInfoSync()
      if (info && Number.isFinite(info.windowHeight) && !this._feeKeyboardHeight) this._feeWindowHeight = info.windowHeight
    } catch (_) { /* 保留样式中的安全区和底部占位。 */ }
  },

  onToggleSetup() { this.setData({ setupExpanded: !this.data.setupExpanded }) },
  syncModeDescriptions() {
    const form = this.data.form, desc = (items, value) => (items.find(it => it.value === value) || {}).desc || ''
    this.setData({ modeDescriptions: { minOrderMode: desc(LIMIT_MODES, form.minOrderMode),
      overRadiusMode: desc(LIMIT_MODES, form.overRadiusMode), floorFeeMode: desc(FLOOR_MODES, form.floorFeeMode) } }, () => this.measureFeeSections())
  },

  onPageScroll(e) {
    this._feeScrollTop = Math.max(0, Number(e.scrollTop) || 0)
    if (this._feeSpyTimer || this._unloaded) return
    this._feeSpyTimer = setTimeout(() => { this._feeSpyTimer = null; this.syncFeeSection() }, 80)
  },
  onFeeManualScroll() { this._feeProgramUntil = 0; this.measureFeeSections() },
  syncFeeSection() {
    if (this._unloaded || !this.data.configReady || !this._feeSections || Date.now() < (this._feeProgramUntil || 0)) return
    const top = this._feeScrollTop || 0, offset = this._feeNavHeight || 64
    let active = this._feeSections[0].key
    for (const section of this._feeSections) if (section.top <= top + offset + 20) active = section.key
    let height = this._feeWindowHeight || 0
    try { height = (typeof wx.getWindowInfo === 'function' ? wx.getWindowInfo() : wx.getSystemInfoSync()).windowHeight || height } catch (_) {}
    if (height > 0 && this._feePageHeight > height && top + height >= this._feePageHeight - 8) active = 'floor'
    if (active !== this.data.activeSection) this.setData({activeSection: active})
  },
  measureFeeSections() {
    if (typeof wx.createSelectorQuery !== 'function') return
    const seq = this._feeSpyVersion = (this._feeSpyVersion || 0) + 1
    try {
      const q = wx.createSelectorQuery().in(this)
      this.data.sectionTabs.forEach(it => q.select('#fee-' + it.key).boundingClientRect())
      q.select('.fee-section-nav').boundingClientRect(); q.select('.container').boundingClientRect(); q.selectViewport().scrollOffset()
      q.exec(rows => {
        if (this._unloaded || seq !== this._feeSpyVersion || !rows || this.data.sectionTabs.some((_,i) => !rows[i] || !Number.isFinite(rows[i].top))) return
        const scroll = rows[6] && Number.isFinite(rows[6].scrollTop) ? rows[6].scrollTop : this._feeScrollTop || 0
        this._feeSections = this.data.sectionTabs.map((it,i) => ({key:it.key, top:rows[i].top + scroll}))
        this._feeNavHeight = rows[4] && rows[4].height || 64
        this._feePageHeight = rows[5] && rows[5].height || 0
        this.syncFeeSection()
      })
    } catch (_) { /* 保留导航点击和手动滚动。 */ }
  },

  onSectionTap(e) {
    const section = e.currentTarget.dataset.section
    if (!this.data.sectionTabs.some(it => it.key === section)) return
    this.setData({ activeSection: section }, () => this.scrollToFeeTarget('#fee-' + section))
  },

  // 2026-10-25：锚点减去真实导航高度，避免固定导航盖住标题或校验字段。
  scrollToFeeTarget(selector) {
    if (typeof wx.createSelectorQuery !== 'function' || typeof wx.pageScrollTo !== 'function') return
    try {
      const query = wx.createSelectorQuery().in(this)
      query.select(selector).boundingClientRect()
      query.select('.fee-section-nav').boundingClientRect()
      query.selectViewport().scrollOffset()
      query.exec(rects => {
        if (this._unloaded || !rects || !rects[0] || !Number.isFinite(rects[0].top)) return
        const nav = rects[1] && Number.isFinite(rects[1].height) ? rects[1].height : 64
        const scroll = rects[2] && Number.isFinite(rects[2].scrollTop) ? rects[2].scrollTop : 0
        this._feeProgramUntil = Date.now() + 320
        wx.pageScrollTo({ scrollTop: Math.max(0, rects[0].top + scroll - nav - 12), duration: 180 })
      })
    } catch (_) { /* 测量失败不改表单，仍可正常滚动和保存。 */ }
  },

  feeFieldFailure(field, message) {
    this.setData({ fieldError: field, fieldErrorText: message, activeSection: FEE_FIELD_SECTIONS[field] || this.data.activeSection },
      () => this.scrollToFeeTarget('#fee-field-' + field))
    wx.showToast({ title: message, icon: 'none' })
  },

  onFeeFieldFocus(e) { this._feeFocusedField = e.currentTarget.dataset.field; if (!this._feeWindowHeight) this.captureFeeWindow() },
  onFeeKeyboardHeightChange(e) {
    const height = Number(e.detail && e.detail.height) || 0
    this._feeKeyboardHeight = Math.max(0, height)
    let current = this._feeWindowHeight
    try { current = (typeof wx.getWindowInfo === 'function' ? wx.getWindowInfo() : wx.getSystemInfoSync()).windowHeight } catch (_) {}
    const consumed = Math.max(0, (this._feeWindowHeight || current || 0) - (current || 0))
    const offset = Math.max(0, this._feeKeyboardHeight - consumed)
    this.setData({ feeKeyboardOffset: offset }, () => { this.measureFeeSaveBar();
      if (height && this._feeFocusedField) this.scrollToFeeTarget('#fee-field-' + this._feeFocusedField)
    })
    if (!height) this.captureFeeWindow()
  },

  measureFeeSaveBar() {
    if (typeof wx.createSelectorQuery !== 'function') return
    const version = this._feeLayoutVersion = (this._feeLayoutVersion || 0) + 1
    try { wx.createSelectorQuery().in(this).select('.fee-save-bar').boundingClientRect(rect => {
      if (this._unloaded || version !== this._feeLayoutVersion || !rect || !Number.isFinite(rect.height) || rect.height <= 0) return
      this.setData({ feeBodyStyle: 'padding-bottom:calc(' + (rect.height + this.data.feeKeyboardOffset) + 'px + 24rpx)' }, () => this.measureFeeSections())
    }).exec() } catch (_) { /* 未拿到测量时由静态安全区占位保底。 */ }
  },

  onInput(e) {
    const field = e.currentTarget.dataset.field
    if (!Object.prototype.hasOwnProperty.call(this.data.form, field)) return
    this.setData({ ['form.' + field]: e.detail.value, saveError: '',
      fieldError: this.data.fieldError === field ? '' : this.data.fieldError })
  },

  onPickMode(e) {
    const field = e.currentTarget.dataset.field
    const value = e.currentTarget.dataset.value
    const modes = field === 'floorFeeMode' ? FLOOR_MODES : LIMIT_MODES
    if (!['minOrderMode', 'overRadiusMode', 'floorFeeMode'].includes(field) || !modes.some(it => it.value === value)) return
    this.setData({ ['form.' + field]: value, fieldError: '', saveError: '' }, () => { this.syncModeDescriptions(); this.measureFeeSections() })
  },

  /**
   * 提交。
   *
   * ⚠️ 空串必须转成 null 而不是 0：
   *   · minOrderBuckets 传 0 会被后端当成"起送量 0 桶"（等于没门槛，语义上恰好等价，但很含糊）；
   *   · floorFreeLevel 传 0 会变成"0 层以下免费"，含义全变。
   * 所以这里统一 ''→null，让"不限/不收"与"填 0"在数据上就是两件事。
   */
  async onSave() {
    if (this.data.saving) return
    const f = this.data.form
    const num = (v) => {
      if (v === '' || v === null || v === undefined) return null
      const n = Number(v)
      return isNaN(n) ? null : n
    }

    const payload = {
      minOrderBuckets: num(f.minOrderBuckets),
      minOrderAmount: num(f.minOrderAmount),
      minOrderMode: f.minOrderMode,
      minOrderFee: num(f.minOrderFee),
      deliveryRadiusM: num(f.deliveryRadiusM),
      overRadiusMode: f.overRadiusMode,
      remoteFee: num(f.remoteFee),
      baseDeliveryFee: num(f.baseDeliveryFee),
      freeDeliveryBuckets: num(f.freeDeliveryBuckets),
      freeDeliveryAmount: num(f.freeDeliveryAmount),
      floorFreeLevel: num(f.floorFreeLevel),
      floorFeePerLevel: num(f.floorFeePerLevel),
      floorFeeMode: f.floorFeeMode
    }

    // 只在"选了加收费用"时才提醒填金额 —— 选了 FEE 却不填金额，等于门槛白配了
    if (payload.minOrderMode === 'FEE' && !payload.minOrderFee) {
      this.feeFieldFailure('minOrderFee', '选「加收费用」请填写加收金额')
      return
    }
    if (payload.overRadiusMode === 'FEE' && !payload.remoteFee) {
      this.feeFieldFailure('remoteFee', '选「加收费用」请填写远程费金额')
      return
    }

    this.setData({ saving: true, fieldError: '', saveError: '' })
    try {
      await saveDeliveryConfig(payload)
      wx.showToast({ title: '已保存', icon: 'success' })
      this.load()
      this.loadSetup()
    } catch (err) {
      this.setData({ saveError: err.message || '保存失败，输入已保留，请核对后重试' })
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ saving: false })
    }
  }
})
