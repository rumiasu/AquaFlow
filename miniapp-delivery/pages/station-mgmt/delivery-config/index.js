const { showBusinessModal } = require('../../../utils/business-dialog')
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
 *   2. 限制项的 null/0 语义分别沿用后端规则；收费金额 0 表示不收，免运费门槛 0 则会让对应订单都达到门槛。
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

const THRESHOLD_FIELDS = ['minOrderBuckets', 'minOrderAmount', 'deliveryRadiusM', 'freeDeliveryBuckets', 'freeDeliveryAmount']
const RULE_FIELDS = {
  minOrder: ['minOrderBuckets', 'minOrderAmount', 'minOrderMode', 'minOrderFee'],
  range: ['deliveryRadiusM', 'overRadiusMode', 'remoteFee'],
  delivery: ['baseDeliveryFee', 'freeDeliveryBuckets', 'freeDeliveryAmount'],
  floor: ['floorFreeLevel', 'floorFeePerLevel', 'floorFeeMode']
}
const SECTION_ORDER = Object.keys(RULE_FIELDS)
const SECTION_GUIDES = {
  minOrder: '先确认几桶或多少元起送，再选择未达门槛时怎么处理。',
  range: '确认可配送的距离，再选择超出范围时怎么处理。',
  delivery: '确认基础配送费，再决定达到多少桶或多少元免配送费。',
  floor: '确认免费楼层、每层费用，以及按单还是按桶收取。'
}
const INTEGER_FIELDS = ['minOrderBuckets', 'deliveryRadiusM', 'freeDeliveryBuckets', 'floorFreeLevel']
const DRAFT_PREFIX = 'delivery-fee-draft-v1:'

function fieldProblem(field, value, form, enabled) {
  if (THRESHOLD_FIELDS.includes(field) && !enabled[field]) return ''
  if ((field === 'minOrderFee' && form.minOrderMode !== 'FEE')
      || (field === 'remoteFee' && form.overRadiusMode !== 'FEE') || field.endsWith('Mode')) return ''
  const required = (THRESHOLD_FIELDS.includes(field) && enabled[field])
    || field === 'minOrderFee' || field === 'remoteFee'
  if (value === '' || value == null) return required ? '请填写数值，或明确选择不限 / 不设门槛' : ''
  const text = String(value), n = Number(text)
  if (!/^\d+(\.\d{1,2})?$/.test(text) || !Number.isFinite(n) || n < 0) return '请填写非负数，金额最多两位小数'
  if (INTEGER_FIELDS.includes(field) && (!Number.isSafeInteger(n) || n > 2147483647)) return '请填写有效的非负整数'
  if ((field === 'minOrderFee' || field === 'remoteFee') && n <= 0) return '选择加收费用后，请填写大于 0 的金额'
  return ''
}

function thresholdDefaults(form) {
  const present = field => form[field] !== '' && form[field] !== null && form[field] !== undefined
  const positive = field => present(field) && Number(form[field]) > 0
  return {
    minOrderBuckets: present('minOrderBuckets'), minOrderAmount: present('minOrderAmount'),
    deliveryRadiusM: positive('deliveryRadiusM'),
    // 零免运费门槛是有效且影响所有适用订单的配置，不能折叠成“不设门槛”。
    freeDeliveryBuckets: present('freeDeliveryBuckets'), freeDeliveryAmount: present('freeDeliveryAmount')
  }
}

function buildRuleState(form, enabled, configured, savedForm, savedEnabled) {
  const same = (a, b) => String(a == null ? '' : a) === String(b == null ? '' : b)
  const changed = Object.keys(form || {}).some(k => !savedForm || !same(form[k], savedForm[k]))
    || THRESHOLD_FIELDS.some(k => !savedEnabled || !!enabled[k] !== !!savedEnabled[k])
  const modeName = value => value === 'REJECT' ? '超限不接单' : value === 'FEE' ? '超限加收' : '超限仅提示'
  const numberText = (value, unit) => value === '' || value == null ? '待填写' : `${value}${unit}`
  const limitText = (field, isEnabled, unit, zeroText) => {
    if (!isEnabled) return '不限'
    if (form[field] === '' || form[field] == null) return '待填写'
    return Number(form[field]) === 0 ? zeroText : numberText(form[field], unit)
  }
  const minParts = [
    `桶数 ${limitText('minOrderBuckets', enabled.minOrderBuckets, ' 桶', '0 桶（按现行规则等同不限）')}`,
    `金额 ${limitText('minOrderAmount', enabled.minOrderAmount, ' 元', '0 元（按现行规则等同不限）')}`
  ]
  const minHandling = form.minOrderMode === 'FEE'
    ? `${modeName(form.minOrderMode)} ${numberText(form.minOrderFee, ' 元')}` : modeName(form.minOrderMode)
  const radius = limitText('deliveryRadiusM', enabled.deliveryRadiusM, ' 米', '0 米（按现行规则不执行距离限制）')
  const rangeHandling = form.overRadiusMode === 'FEE'
    ? `${modeName(form.overRadiusMode)} ${numberText(form.remoteFee, ' 元')}` : modeName(form.overRadiusMode)
  const freeParts = []
  if (enabled.freeDeliveryBuckets) freeParts.push(Number(form.freeDeliveryBuckets) === 0
    ? '0 桶门槛（所有桶装订单都达到）' : `桶数 ≥ ${numberText(form.freeDeliveryBuckets, ' 桶')}（仅桶装订单）`)
  if (enabled.freeDeliveryAmount) freeParts.push(Number(form.freeDeliveryAmount) === 0
    ? '0 元门槛（所有非负金额订单都达到）' : `金额 ≥ ${numberText(form.freeDeliveryAmount, ' 元')}`)
  const baseFeeText = form.baseDeliveryFee === '' || form.baseDeliveryFee == null
    ? '空值保存按现有规则不收费' : Number(form.baseDeliveryFee) === 0 ? '0 元（不收费）' : numberText(form.baseDeliveryFee, ' 元')
  const deliveryText = `基础配送费 ${baseFeeText}；${freeParts.length
    ? `达到任一免运费门槛：${freeParts.join('；')}` : '未设置免运费门槛，基础配送费照收'}`
  const floorFree = form.floorFreeLevel === '' || form.floorFreeLevel == null
    ? '空值保存后按 1 层免费' : `${form.floorFreeLevel} 层免费`
  const floorFee = Number(form.floorFeePerLevel) > 0
    ? `${numberText(form.floorFeePerLevel, ' 元')}/${form.floorFeeMode === 'PER_BUCKET' ? '桶' : '单'}`
    : '每层费用为空或 0，不收楼层费'
  const descriptions = {
    minOrder: `桶数或金额任一达标即可；${minParts.join('；')}；${minHandling}`,
    range: `半径 ${radius}；${rangeHandling}`,
    delivery: deliveryText,
    floor: `无电梯：${floorFree}，超出后${floorFee}。未确认楼层或电梯信息时不收。`
  }
  const labels = { minOrder: '起送条件', range: '配送范围', delivery: '配送费', floor: '楼层费' }
  const summaries = Object.keys(RULE_FIELDS).map(key => {
    const sectionChanged = RULE_FIELDS[key].some(field => !savedForm || !same(form[field], savedForm[field]))
      || THRESHOLD_FIELDS.filter(field => RULE_FIELDS[key].includes(field)).some(field => !savedEnabled || !!enabled[field] !== !!savedEnabled[field])
    return { key, label: labels[key], text: descriptions[key], guide: SECTION_GUIDES[key],
      stateText: configured ? (sectionChanged ? '未保存草稿' : '已保存') : (sectionChanged ? '未保存草稿' : '默认值待确认') }
  })
  return { hasUnsavedChanges: changed, ruleSummaries: summaries }
}

function deliveryConfigReadbackMatches(form, payload, enabled) {
  const zeroMatchesNull = new Set(['deliveryRadiusM', 'baseDeliveryFee',
    'minOrderFee', 'remoteFee', 'floorFeePerLevel'])
  const number = value => value === '' || value == null ? null : Number(value)
  return Object.keys(payload).every(field => {
    let expected = payload[field], actual = form[field]
    if (THRESHOLD_FIELDS.includes(field) && enabled && enabled[field] === false) actual = null
    if (field === 'floorFreeLevel') {
      expected = expected == null ? 1 : Number(expected)
      actual = number(actual)
      actual = actual == null ? 1 : actual
      return actual === expected
    }
    if (typeof payload[field] === 'number' || payload[field] === null) {
      expected = expected == null ? null : Number(expected)
      actual = number(actual)
      if (zeroMatchesNull.has(field) && (actual === 0 || actual == null) && (expected === 0 || expected == null)) return true
      return actual === expected
    }
    return actual === expected
  })
}

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
    activeSection: 'minOrder', expandedSection: 'minOrder', showRuleDetails: false,
    setupExpanded: false, setupRequiredText: '',
    sectionTabs: [{ key: 'minOrder', label: '起送条件' }, { key: 'range', label: '配送范围' },
      { key: 'delivery', label: '配送费' }, { key: 'floor', label: '楼层费' }],
    fieldError: '', fieldErrorText: '', saveError: '', feeKeyboardOffset: 0, feeBodyStyle: '',
    modeDescriptions: { minOrderMode: LIMIT_MODES[0].desc, overRadiusMode: LIMIT_MODES[0].desc, floorFeeMode: FLOOR_MODES[0].desc },
    configured: false,
    thresholdEnabled: { minOrderBuckets: false, minOrderAmount: false, deliveryRadiusM: false,
      freeDeliveryBuckets: false, freeDeliveryAmount: false },
    savedForm: null, savedThresholdEnabled: null, hasUnsavedChanges: false, ruleSummaries: [],
    draftNotice: '', draftStorageError: '', completedSections: {}, exitGuardError: '',
    // 「配送范围」那段说明的正文（在 loadSetup 里拼好）。为什么不在 wxml 里拼：
    // 见 loadSetup 的 ⚠️（WXML 表达式解析器不支持带换行的字符串拼接，会编译报错）。
    rangeHelpText: '按站点坐标到收货地址的直线距离判断。',
    limitModes: LIMIT_MODES,
    floorModes: FLOOR_MODES,
    // 表单值用字符串存；限制项由明确选项控制，保留空串和数字 0 的业务差异。
    form: {
      minOrderBuckets: '0',
      minOrderAmount: '0',
      minOrderMode: 'WARN',
      minOrderFee: '',
      deliveryRadiusM: '0',
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
    this.setData({ activeSection: 'minOrder', expandedSection: 'minOrder' }, () => this.scrollToFeeTarget('#fee-minOrder'))
  },

  onShow() {
    this.captureFeeWindow()
    this._feeKeyboardHeight = 0
    this._feeFocusedField = ''
    this.setData({ feeKeyboardOffset: 0, feeBodyStyle: '' }, () => this.measureFeeSaveBar())
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
    this.persistFeeDraft()
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
    // 2026-10-26：onShow/下拉原先会覆盖未保存输入。仅保存后的核对读取允许替换草稿。
    if (!this._readingSavedFee && this.data.configReady && this._feeOwner === staffSession().key
        && (this.data.hasUnsavedChanges || !this.data.configured)) {
      this.setData({ draftNotice: '当前输入已保留；请在底部保存后完成上线准备。' })
      this.syncFeeExitGuard()
      return
    }
    const context = this.captureLoad('_configVersion')
    this.setData({ loading: true, configReady: false, configError: '' })
    try {
      const res = await getDeliveryConfig()
      if (!this.isLoadCurrent('_configVersion', context)) return
      const d = res.data || {}
      const c = d.config || {}
      // 后端原样返回 null；编辑框用空串承载，另由明确的规则选项决定是否提交 null。
      const s = (v) => (v === null || v === undefined ? '' : String(v))
      const g = d.guidance || {}
      const guide = {
        nonBarrelOnShelf: g.nonBarrelOnShelf === true,
        requiredAmountFields: Array.isArray(g.requiredAmountFields) ? g.requiredAmountFields : [],
        cheapestWaterPrice: g.cheapestWaterPrice == null ? null : Number(g.cheapestWaterPrice),
        cheapestPrice: g.cheapestPrice == null ? g.cheapestWaterPrice : Number(g.cheapestPrice),
        cheapestPriceBasis: g.cheapestPriceBasis || '',
        suggestedMinOrderAmount: g.suggestedMinOrderAmount == null ? null : Number(g.suggestedMinOrderAmount),
        suggestedFreeDeliveryAmount: g.suggestedFreeDeliveryAmount == null ? null : Number(g.suggestedFreeDeliveryAmount)
      }
      const form = {
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
        }
      const thresholdEnabled = thresholdDefaults(form)
      // 0 与 null 在这三项的现行规则中均不限；不为免运费门槛猜值（其 0 会免所有适用订单）。
      for (const field of ['minOrderBuckets', 'minOrderAmount', 'deliveryRadiusM']) if (form[field] === '') form[field] = '0'
      const configured = d.configured === true
      this._feeOwner = context.key
      this.setData({
        configReady: true, configured, form, savedForm: { ...form },
        thresholdEnabled, savedThresholdEnabled: { ...thresholdEnabled },
        ...buildRuleState(form, thresholdEnabled, configured, form, thresholdEnabled),
        guide,
        guideText: this.describeGuide(guide)
      }, () => {
        if (!this._readingSavedFee) this.restoreFeeDraft()
        this.syncModeDescriptions(); this.measureFeeSaveBar(); this.syncFeeExitGuard()
      })
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
    const parts = [`桶数条件只适用于含桶装水的订单。金额选择不限时，非桶装订单按已保存规则正常配送；需要金额条件时，可补充${names.join('、')}。`]
    if (g.cheapestPriceBasis) {
      parts.push(`建议值按${g.cheapestPriceBasis} ¥${g.cheapestPrice == null ? g.cheapestWaterPrice : g.cheapestPrice} 推算（起送约两桶水、免运费约三桶水），填入后仍需确认并保存。`)
    } else {
      parts.push('本站还没有已上架的商品，无法推算建议值，请手动填写。')
    }
    return parts.join('')
  },

  /** 一键填入建议值（只填后端给了建议的那几项，其余不动）。 */
  onFillSuggested() {
    if (this.data.saving) return
    const { guide, form } = this.data
    const updates = {}
    if (guide.suggestedMinOrderAmount != null && (!this.data.thresholdEnabled.minOrderAmount || form.minOrderAmount === '' || form.minOrderAmount == null)) {
      updates['form.minOrderAmount'] = String(guide.suggestedMinOrderAmount)
    }
    if (guide.suggestedFreeDeliveryAmount != null && (!this.data.thresholdEnabled.freeDeliveryAmount || form.freeDeliveryAmount === '' || form.freeDeliveryAmount == null)) {
      updates['form.freeDeliveryAmount'] = String(guide.suggestedFreeDeliveryAmount)
    }
    if (!Object.keys(updates).length) {
      wx.showToast({ title: '没有可填入的建议值，请手动填写', icon: 'none' })
      return
    }
    const nextForm = { ...form }
    if (updates['form.minOrderAmount']) nextForm.minOrderAmount = updates['form.minOrderAmount']
    if (updates['form.freeDeliveryAmount']) nextForm.freeDeliveryAmount = updates['form.freeDeliveryAmount']
    const enabled = { ...this.data.thresholdEnabled,
      ...(updates['form.minOrderAmount'] ? { minOrderAmount: true } : {}),
      ...(updates['form.freeDeliveryAmount'] ? { freeDeliveryAmount: true } : {}) }
    this.updateFeeDraft(nextForm, enabled, { completedSections: {} })
    wx.showToast({ title: '已填入建议值，确认后保存', icon: 'none' })
  },

  onReady() { this.captureFeeWindow(); this.measureFeeSaveBar() },
  onResize() { this.onFeeKeyboardHeightChange({ detail: { height: this._feeKeyboardHeight || 0 } }); this.measureFeeSections() },
  onHide() {
    this.persistFeeDraft()
    this._feeKeyboardHeight = 0
    this._feeFocusedField = ''
    this.setData({ feeKeyboardOffset: 0, feeBodyStyle: '' })
  },

  feeDraftKey() {
    const u = (getApp().globalData || {}).userInfo || {}
    return u.staffId && u.stationId ? DRAFT_PREFIX + u.staffId + ':' + u.stationId : ''
  },
  persistFeeDraft() {
    const key = this.feeDraftKey()
    if (!key || !this.data.configReady || this._feeOwner !== staffSession().key) return
    try {
      if (this.data.hasUnsavedChanges || !this.data.configured || this.data.saveError) {
        wx.setStorageSync(key, { owner: this._feeOwner, form: this.data.form, enabled: this.data.thresholdEnabled,
          savedForm: this.data.savedForm, savedEnabled: this.data.savedThresholdEnabled,
          completed: this.data.completedSections, expanded: this.data.expandedSection })
      } else wx.removeStorageSync(key)
    } catch (_) { this.setData({ draftStorageError: '本机暂存失败，请留在本页完成保存。' }) }
  },
  restoreFeeDraft() {
    const key = this.feeDraftKey()
    if (!key) return
    try {
      const d = wx.getStorageSync(key)
      if (!d || d.owner !== staffSession().key || !d.form || !d.enabled
          || !Object.keys(this.data.form).every(k => typeof d.form[k] === 'string')
          || !THRESHOLD_FIELDS.every(k => typeof d.enabled[k] === 'boolean')) return
      const unchanged = JSON.stringify(d.savedForm) === JSON.stringify(this.data.savedForm)
        && JSON.stringify(d.savedEnabled) === JSON.stringify(this.data.savedThresholdEnabled)
      this.setData({ form: d.form, thresholdEnabled: d.enabled, completedSections: d.completed || {},
        expandedSection: SECTION_ORDER.includes(d.expanded) ? d.expanded : 'minOrder',
        ...buildRuleState(d.form, d.enabled, this.data.configured, this.data.savedForm, this.data.savedThresholdEnabled),
        draftNotice: unchanged ? '已恢复上次未保存的输入，请核对后在底部保存。'
          : '已恢复未保存输入；已保存规则有变化，请逐项核对后再保存。' })
    } catch (_) { this.setData({ draftStorageError: '本机草稿暂时无法读取，请保留当前输入并保存。' }) }
  },
  syncFeeExitGuard() {
    if (this._unloaded) return
    const needed = this.data.configReady && (this.data.hasUnsavedChanges || !this.data.configured || !!this.data.saveError)
    if (needed && typeof wx.enableAlertBeforeUnload === 'function') {
      wx.enableAlertBeforeUnload({ message: '配送计费尚未保存，上线准备还未完成。',
        fail: () => { if (!this._unloaded) this.setData({ exitGuardError: '退出提醒未能开启，请先保存或使用本页返回按钮。' }) } })
    } else if (!needed && typeof wx.disableAlertBeforeUnload === 'function') wx.disableAlertBeforeUnload({})
  },
  onExitFee() {
    if (this.data.saving) return
    const leave = () => {
      this.persistFeeDraft()
      if (typeof wx.disableAlertBeforeUnload === 'function') wx.disableAlertBeforeUnload({})
      wx.navigateBack({ delta: 1, fail: () => this.syncFeeExitGuard() })
    }
    if (!this.data.hasUnsavedChanges && this.data.configured && !this.data.saveError) return leave()
    showBusinessModal({ title: '计费规则尚未保存', content: '上线准备还未完成。离开后可继续本机草稿，当前订单仍按已保存规则计算。',
      confirmText: '暂时离开', cancelText: '继续填写', success: res => { if (res.confirm) leave() } })
  },
  updateFeeDraft(form, enabled, extra = {}) {
    this.setData({ form, thresholdEnabled: enabled,
      ...buildRuleState(form, enabled, this.data.configured, this.data.savedForm, this.data.savedThresholdEnabled), ...extra },
    () => { this.persistFeeDraft(); this.syncFeeExitGuard() })
  },
  onCompleteSection(e) {
    if (this.data.saving) return
    const section = e.currentTarget.dataset.section || this.data.expandedSection
    if (!RULE_FIELDS[section]) return
    for (const field of RULE_FIELDS[section]) {
      const error = fieldProblem(field, this.data.form[field], this.data.form, this.data.thresholdEnabled)
      if (error) return this.feeFieldFailure(field, error)
    }
    const next = SECTION_ORDER[SECTION_ORDER.indexOf(section) + 1]
    const completedSections = { ...this.data.completedSections, [section]: true }
    const allReviewed = SECTION_ORDER.every(key => completedSections[key])
    // 2026-10-26：直接跳到最后一项不等于核对过四项，不能提前给出「四项已核对」。
    this.setData({ completedSections, fieldError: '',
      ...(next ? { activeSection: next, expandedSection: next } : {}),
      draftNotice: next ? '本项输入已保留，请继续下一项；最后仍需点击底部保存。'
        : allReviewed ? '四项已核对，请点击底部保存完成上线准备。' : '本项已核对，请继续核对其余项目，最后在底部保存。' },
    () => { this.persistFeeDraft(); this.syncFeeExitGuard(); if (next) this.scrollToFeeTarget('#fee-' + next) })
  },
  onFeeFieldConfirm(e) {
    const field = e.currentTarget.dataset.field
    if (!Object.prototype.hasOwnProperty.call(this.data.form, field)) return
    // confirm 可能先于最后一次 input；以当前输入事件为准，不逐位跳转。
    if (e.detail && typeof e.detail.value === 'string' && this.data.form[field] !== e.detail.value) this.onInput(e)
    this.onCompleteSection({ currentTarget: { dataset: { section: FEE_FIELD_SECTIONS[field] } } })
  },

  captureFeeWindow() {
    try { const info = typeof wx.getWindowInfo === 'function' ? wx.getWindowInfo() : wx.getSystemInfoSync()
      if (info && Number.isFinite(info.windowHeight) && !this._feeKeyboardHeight) this._feeWindowHeight = info.windowHeight
    } catch (_) { /* 保留样式中的安全区和底部占位。 */ }
  },

  onToggleSetup() { this.setData({ setupExpanded: !this.data.setupExpanded }) },
  onToggleRuleDetails() { this.setData({ showRuleDetails: !this.data.showRuleDetails }) },
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
    this.setData({ activeSection: section, expandedSection: section }, () => this.scrollToFeeTarget('#fee-' + section))
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
    const section = FEE_FIELD_SECTIONS[field] || this.data.activeSection
    this.setData({ fieldError: field, fieldErrorText: message, activeSection: section, expandedSection: section },
      () => this.scrollToFeeTarget('#fee-field-' + field))
    wx.showToast({ title: message, icon: 'none' })
  },

  onFeeFieldFocus(e) {
    this._feeFocusedField = e.currentTarget.dataset.field || ''
    if (!this._feeWindowHeight) this.captureFeeWindow()
  },
  onFeeFieldBlur(e) {
    const field = e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.field
    if (field && this._feeFocusedField && field !== this._feeFocusedField) return
    // Keyboard cancel/dismiss does not consistently send a final height=0 event on every client.
    // Blur is the authoritative end of the focused-input lifecycle: release both the fixed-save
    // offset and its matching body spacer while leaving the local draft untouched.
    this._feeFocusedField = ''
    this._feeKeyboardHeight = 0
    this.setData({ feeKeyboardOffset: 0 }, () => this.measureFeeSaveBar())
  },
  onFeeKeyboardHeightChange(e) {
    const reportedHeight = Math.max(0, Number(e.detail && e.detail.height) || 0)
    // Ignore stale non-zero callbacks after the active input blurred; otherwise they can
    // resurrect the save-bar offset and blank scroll spacer after the keyboard was dismissed.
    const height = this._feeFocusedField ? reportedHeight : 0
    this._feeKeyboardHeight = height
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
    if (this.data.saving) return
    const field = e.currentTarget.dataset.field
    if (!Object.prototype.hasOwnProperty.call(this.data.form, field)) return
    const form = { ...this.data.form, [field]: e.detail.value }
    const enabled = { ...this.data.thresholdEnabled, ...(THRESHOLD_FIELDS.includes(field) ? { [field]: true } : {}) }
    this.updateFeeDraft(form, enabled, { completedSections: { ...this.data.completedSections, [FEE_FIELD_SECTIONS[field]]: false }, saveError: '',
      fieldError: this.data.fieldError === field ? '' : this.data.fieldError })
  },

  onThresholdToggle(e) {
    if (this.data.saving) return
    const field = e.currentTarget.dataset.field
    if (!THRESHOLD_FIELDS.includes(field)) return
    const enabled = { ...this.data.thresholdEnabled, [field]: e.currentTarget.dataset.enabled === 'on' }
    this.updateFeeDraft(this.data.form, enabled, { completedSections: { ...this.data.completedSections, [FEE_FIELD_SECTIONS[field]]: false },
      saveError: '', fieldError: this.data.fieldError === field ? '' : this.data.fieldError })
  },

  onPickMode(e) {
    if (this.data.saving) return
    const field = e.currentTarget.dataset.field
    const value = e.currentTarget.dataset.value
    const modes = field === 'floorFeeMode' ? FLOOR_MODES : LIMIT_MODES
    if (!['minOrderMode', 'overRadiusMode', 'floorFeeMode'].includes(field) || !modes.some(it => it.value === value)) return
    const form = { ...this.data.form, [field]: value }
    this.updateFeeDraft(form, this.data.thresholdEnabled, { completedSections: { ...this.data.completedSections, [FEE_FIELD_SECTIONS[field]]: false }, fieldError: '', saveError: '' })
    this.syncModeDescriptions(); this.measureFeeSections()
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

    const enabled = this.data.thresholdEnabled || thresholdDefaults(f)
    for (const field of Object.keys(f)) {
      const problem = fieldProblem(field, f[field], f, enabled)
      if (problem) return this.feeFieldFailure(field, problem)
    }
    const requiredLabels = {
      minOrderBuckets: '设置起送桶数后请填写数值', minOrderAmount: '设置起送金额后请填写数值',
      deliveryRadiusM: '设置配送半径后请填写数值', freeDeliveryBuckets: '设置免运费桶数后请填写数值',
      freeDeliveryAmount: '设置免运费金额后请填写数值'
    }
    for (const field of THRESHOLD_FIELDS) {
      if (enabled[field] && num(f[field]) === null) {
        this.feeFieldFailure(field, requiredLabels[field])
        return
      }
    }

    const payload = {
      minOrderBuckets: enabled.minOrderBuckets ? num(f.minOrderBuckets) : null,
      minOrderAmount: enabled.minOrderAmount ? num(f.minOrderAmount) : null,
      minOrderMode: f.minOrderMode,
      minOrderFee: num(f.minOrderFee),
      deliveryRadiusM: enabled.deliveryRadiusM ? num(f.deliveryRadiusM) : null,
      overRadiusMode: f.overRadiusMode,
      remoteFee: num(f.remoteFee),
      baseDeliveryFee: num(f.baseDeliveryFee),
      freeDeliveryBuckets: enabled.freeDeliveryBuckets ? num(f.freeDeliveryBuckets) : null,
      freeDeliveryAmount: enabled.freeDeliveryAmount ? num(f.freeDeliveryAmount) : null,
      floorFreeLevel: num(f.floorFreeLevel),
      floorFeePerLevel: num(f.floorFeePerLevel),
      floorFeeMode: f.floorFeeMode
    }
    const submittedForm = { ...f }, submittedEnabled = { ...enabled }

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
    const saveContext = staffSession()
    const stillHere = () => !this._unloaded && saveContext.app === getApp()
      && saveContext.generation === staffSession().generation && saveContext.key === staffSession().key
    try {
      const receipt = await saveDeliveryConfig(payload)
      if (!stillHere()) return
      if (receipt && receipt.code != null && receipt.code !== 0) throw new Error(receipt.message || '保存未成功，请重试')
      this._readingSavedFee = true
      try { await this.load() } finally { this._readingSavedFee = false }
      if (!stillHere()) return
      this.loadSetup()
      if (this.data.configReady && this.data.configured && deliveryConfigReadbackMatches(this.data.form, payload, this.data.thresholdEnabled)) {
        const confirmed = { ...this.data.form }
        for (const field of THRESHOLD_FIELDS) if (!submittedEnabled[field]) confirmed[field] = submittedForm[field]
        this.setData({ form: confirmed, savedForm: { ...confirmed }, thresholdEnabled: submittedEnabled,
          savedThresholdEnabled: { ...submittedEnabled }, completedSections: {}, hasUnsavedChanges: false,
          ...buildRuleState(confirmed, submittedEnabled, true, confirmed, submittedEnabled), draftNotice: '', saveError: '' })
        this.persistFeeDraft(); this.syncFeeExitGuard()
        wx.showToast({ title: '已保存并核对', icon: 'success' })
      } else {
        const readbackKnown = this.data.configReady && this.data.configured
        this.setData({ configReady: true, loading: false, configError: '', form: submittedForm, thresholdEnabled: submittedEnabled,
          ...buildRuleState(submittedForm, submittedEnabled, this.data.configured, this.data.savedForm, this.data.savedThresholdEnabled) })
        this.setData({ hasUnsavedChanges: true, saveError: readbackKnown
          ? '保存请求已提交，但回读内容与本次修改不一致；当前输入已保留，请重新读取后核对。'
          : '保存请求已提交，但配置回读未能确认；当前输入已保留，请重新读取后再判断状态。' })
      }
    } catch (err) {
      if (!stillHere()) return
      this.setData({ saveError: err.message || '保存失败，输入已保留，请核对后重试' })
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    } finally {
      if (stillHere()) { this.setData({ saving: false }); this.persistFeeDraft(); this.syncFeeExitGuard() }
    }
  }
})
