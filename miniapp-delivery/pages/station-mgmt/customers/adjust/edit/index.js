// 新建站长资产调整单（人工补录 / 代客订正）
const { getCatalog, previewAdjustment, createAdjustment } = require('../../../../../api/station-mgmt')
const { upload } = require('../../../../../utils/upload')
const { API } = require('../../../../../config/api')

/**
 * 新建表单的 7 个调整类型选项。
 *
 * label 与后端 constant/AdjustType.textOf 保持一致：**已存在的单据一律直接渲染后端下发的
 * adjustTypeText**，绝不做二次映射；这份目录只用于「新建」表单的选项与字段可见性开关。
 *
 * 语义要点：qty / amount 一律传正数，方向由 adjustType 决定；
 * 唯一例外 OVER_ADJUST 的 qty 是带符号增量（正=补记欠桶，负=核销欠桶），
 * 因此页面上必须让站长在「补记 / 核销」之间明确二选一。
 */
const ADJUST_OPTIONS = [
  { value: 'BARREL_GRANT', label: '补录桶权益', needProduct: true, needQty: true, needAmount: false, allowUnitPrice: true, isOverAdjust: false },
  { value: 'BARREL_REVOKE', label: '撤销桶权益', needProduct: true, needQty: true, needAmount: false, allowUnitPrice: false, isOverAdjust: false },
  { value: 'OVER_ADJUST', label: '订正欠桶', needProduct: true, needQty: true, needAmount: false, allowUnitPrice: false, isOverAdjust: true },
  { value: 'DEPOSIT_GRANT', label: '补录押金', needProduct: false, needQty: false, needAmount: true, allowUnitPrice: false, isOverAdjust: false },
  { value: 'DEPOSIT_DEDUCT', label: '扣减押金', needProduct: false, needQty: false, needAmount: true, allowUnitPrice: false, isOverAdjust: false },
  { value: 'TICKET_GRANT', label: '补录水票', needProduct: true, needQty: true, needAmount: false, allowUnitPrice: false, isOverAdjust: false },
  { value: 'TICKET_DEDUCT', label: '扣减水票', needProduct: true, needQty: true, needAmount: false, allowUnitPrice: false, isOverAdjust: false }
]

// 证据图上限：后端 evidence 字段 500 字符，超了直接拒绝而不是悄悄截断（截断会存下坏 URL）
const MAX_EVIDENCE = 3
const EVIDENCE_MAX_LEN = 500

Page({
  data: {
    customerId: null,
    missingCustomer: false,
    denied: false,

    typeOptions: ADJUST_OPTIONS,
    typeLabels: ADJUST_OPTIONS.map(o => o.label),
    typeIndex: -1,
    option: null,

    productList: [],
    productLabels: [],
    productIndex: -1,
    manualProduct: false,
    productIdInput: '',
    productHint: '',

    form: { qty: '', amount: '', unitPrice: '', reason: '' },
    overSign: 1,

    evidenceList: [],
    uploading: false,

    preview: null,
    previewRows: [],
    previewExtra: null,
    previewing: false,

    submitting: false,
    clientToken: ''
  },

  onLoad(options) {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      this.setData({ denied: true })
      app.routeByRole(true)
      return
    }
    // 站长专属（后端 @RequireRole("STATION_MANAGER")），非站长不放行也不发请求
    if (!app.isStationManager()) {
      this.setData({ denied: true })
      return
    }

    const customerId = options && options.customerId ? Number(options.customerId) : null
    this.setData({
      customerId: customerId,
      missingCustomer: !customerId,
      // 幂等键：整张表单在页面生命周期内复用同一个 token（重复点「提交」不会生成第二张单），
      // 生成规则与本端既有写法一致（客户端时间戳 + 随机数）。
      clientToken: 'ADJ-' + Date.now() + '-' + Math.floor(Math.random() * 1000)
    })
    if (customerId) this.loadProducts()
  },

  /** 本站已上架商品（后端按登录站长判定水站，前端不传 stationId） */
  async loadProducts() {
    try {
      // 2026-09-16 商品与库存重构：旧 /api/manager/products 已删除，
      // 选品目录接口返回的是"通用库 + 本站自定义"，这里只要**本站已上架**的。
      const res = await getCatalog()
      const list = (res.data || [])
        .filter(p => p.selected && p.enabled === 1)
        .map(p => ({
          id: p.id,
          name: p.name + (p.spec ? ' ' + p.spec : ''),
          deposit: p.deposit
        }))
      this.setData({
        productList: list,
        productLabels: list.map(p => p.name),
        productIndex: -1,
        manualProduct: list.length === 0,
        productHint: list.length === 0
          ? '本站没有在售商品，请手动填写商品 ID（数字），并核对确实是本站商品'
          : ''
      })
    } catch (err) {
      // 取不到商品列表时退回手填商品 ID（带校验与提示），而不是让页面直接不可用
      this.setData({
        productList: [],
        productLabels: [],
        productIndex: -1,
        manualProduct: true,
        productHint: '商品列表加载失败（' + (err.message || '未知原因') + '），请手动填写商品 ID'
      })
    }
  },

  // ===== 表单输入：任何改动都作废上一次试算结果，避免提交与试算不一致 =====
  resetPreview() {
    if (this.data.preview) {
      this.setData({ preview: null, previewRows: [], previewExtra: null })
    }
  },

  onTypeChange(e) {
    const idx = Number(e.detail.value)
    const option = this.data.typeOptions[idx] || null
    // 换类型后数量/金额/单价含义全变，一律清空（原因保留，站长已经写好的话不必重打）
    this.setData({
      typeIndex: idx,
      option: option,
      overSign: 1,
      form: { qty: '', amount: '', unitPrice: '', reason: this.data.form.reason }
    })
    this.resetPreview()
  },

  onProductChange(e) {
    this.setData({ productIndex: Number(e.detail.value) })
    this.resetPreview()
  },

  onToggleManualProduct() {
    const manualProduct = !this.data.manualProduct
    this.setData({ manualProduct: manualProduct, productIndex: -1, productIdInput: '' })
    this.resetPreview()
  },

  onManualProductInput(e) {
    this.setData({ productIdInput: e.detail.value })
    this.resetPreview()
  },

  onQtyInput(e) {
    this.setData({ 'form.qty': e.detail.value })
    this.resetPreview()
  },

  onAmountInput(e) {
    this.setData({ 'form.amount': e.detail.value })
    this.resetPreview()
  },

  onUnitPriceInput(e) {
    this.setData({ 'form.unitPrice': e.detail.value })
    this.resetPreview()
  },

  onOverSign(e) {
    const sign = Number(e.currentTarget.dataset.sign)
    this.setData({ overSign: sign === -1 ? -1 : 1 })
    this.resetPreview()
  },

  onReasonInput(e) {
    this.setData({ 'form.reason': e.detail.value })
  },

  // ===== 证据图：复用 utils/upload.js（wx.uploadFile）+ /api/common/upload =====
  onChooseEvidence() {
    if (this.data.uploading) return
    const rest = MAX_EVIDENCE - this.data.evidenceList.length
    if (rest <= 0) {
      wx.showToast({ title: '最多上传 ' + MAX_EVIDENCE + ' 张证据图', icon: 'none' })
      return
    }
    wx.chooseImage({
      count: rest,
      sizeType: ['compressed'],
      success: async (res) => {
        this.setData({ uploading: true })
        try {
          const urls = await Promise.all(res.tempFilePaths.map(p => upload({
            filePath: p,
            url: API.GENERAL_UPLOAD,
            name: 'file'
          }).then(r => r.data)))
          this.setData({ evidenceList: this.data.evidenceList.concat(urls.filter(Boolean)) })
        } catch (err) {
          wx.showToast({ title: err.message || '上传失败', icon: 'none' })
        } finally {
          this.setData({ uploading: false })
        }
      }
    })
  },

  onPreviewEvidence(e) {
    const index = Number(e.currentTarget.dataset.index)
    wx.previewImage({ current: this.data.evidenceList[index], urls: this.data.evidenceList })
  },

  onRemoveEvidence(e) {
    const index = Number(e.currentTarget.dataset.index)
    this.setData({ evidenceList: this.data.evidenceList.filter((_, i) => i !== index) })
  },

  // ===== 入参组装 + 本地校验（服务端仍会再校验一遍）=====
  buildPayload() {
    if (!this.data.customerId) return { error: '缺少客户，无法创建调整单' }
    const option = this.data.option
    if (!option) return { error: '请选择调整类型' }
    const payload = { customerId: this.data.customerId, adjustType: option.value }

    if (option.needProduct) {
      if (this.data.manualProduct) {
        const raw = String(this.data.productIdInput || '').trim()
        const n = Number(raw)
        if (!raw || !Number.isInteger(n) || n <= 0) {
          return { error: '商品 ID 必须是正整数，请核对本站商品 ID' }
        }
        payload.productId = n
      } else {
        const p = this.data.productList[this.data.productIndex]
        if (!p) return { error: '请选择商品' }
        payload.productId = p.id
      }
    }

    if (option.needQty) {
      const q = Number(String(this.data.form.qty || '').trim())
      if (!Number.isInteger(q) || q <= 0) return { error: '数量必须是大于 0 的整数' }
      // OVER_ADJUST 的符号由「补记 / 核销」决定；其余类型恒为正数，方向由类型决定
      payload.qty = option.isOverAdjust ? this.data.overSign * q : q
    }

    if (option.needAmount) {
      const a = Number(String(this.data.form.amount || '').trim())
      if (!(a > 0)) return { error: '金额必须大于 0' }
      payload.amount = a
    }

    if (option.allowUnitPrice) {
      const raw = String(this.data.form.unitPrice || '').trim()
      if (raw) {
        const u = Number(raw)
        if (!(u > 0)) return { error: '补录单价必须大于 0（留空则按商品当前押金推断）' }
        payload.unitPrice = u
      }
    }

    const reason = String(this.data.form.reason || '').trim()
    if (!reason) return { error: '调整原因必填' }
    payload.reason = reason

    return { payload: payload }
  },

  /**
   * 试算：金额与前后快照**只认服务端返回**（前端不做单价换算、更不自建文案映射）。
   * 未选商品时后端不算商品维度（权益/欠桶/占用/水票），这些行标「未涉及」，
   * 不能拿 0 冒充真实值——那会让站长误以为客户真的没有桶。
   */
  buildPreviewRows(data, payload) {
    const before = data.before || {}
    const after = data.after || {}
    const hasProduct = payload.productId !== null && payload.productId !== undefined
    const int = (v) => (v === null || v === undefined ? '0' : String(v))
    const money = (v) => (v === null || v === undefined ? '0.00' : Number(v).toFixed(2))
    const row = (label, key) => ({
      label: label,
      before: hasProduct ? int(before[key]) : '未涉及',
      after: hasProduct ? int(after[key]) : '未涉及'
    })
    return [
      row('桶权益(个)', 'right'),
      row('欠桶(个)', 'over'),
      row('占用(个)', 'occupied'),
      { label: '押金(元)', before: money(before.deposit), after: money(after.deposit) },
      row('水票(张)', 'ticket')
    ]
  },

  async onPreview() {
    if (this.data.previewing) return
    const built = this.buildPayload()
    if (built.error) {
      wx.showToast({ title: built.error, icon: 'none', duration: 3000 })
      return
    }
    this.setData({ previewing: true })
    wx.showLoading({ title: '试算中...' })
    try {
      const res = await previewAdjustment(built.payload)
      const data = res.data || {}
      this.setData({
        preview: data,
        previewRows: this.buildPreviewRows(data, built.payload),
        previewExtra: {
          estimatedRefund: (data.estimatedRefund === null || data.estimatedRefund === undefined)
            ? '' : Number(data.estimatedRefund).toFixed(2),
          hasMigratedPrice: !!data.hasMigratedPrice,
          estimatedRightAmount: (data.estimatedRightAmount === null || data.estimatedRightAmount === undefined)
            ? '' : Number(data.estimatedRightAmount).toFixed(2)
        }
      })
      wx.hideLoading()
    } catch (err) {
      wx.hideLoading()
      wx.showToast({ title: err.message || '试算失败', icon: 'none', duration: 3000 })
    } finally {
      this.setData({ previewing: false })
    }
  },

  /**
   * 提交：先试算再提交（未试算或试算后改过表单 → 拒绝），
   * 二次确认用**回调式** wx.showModal，确认文案里带上类型与数量/金额。
   */
  onSubmit() {
    if (this.data.submitting) return
    const built = this.buildPayload()
    if (built.error) {
      wx.showToast({ title: built.error, icon: 'none', duration: 3000 })
      return
    }
    if (!this.data.preview) {
      wx.showToast({ title: '请先点「试算」并确认试算结果', icon: 'none', duration: 3000 })
      return
    }
    const payload = built.payload
    const what = (payload.qty !== undefined)
      ? '数量 ' + (payload.qty > 0 ? '+' + payload.qty : payload.qty)
      : '金额 ¥' + Number(payload.amount).toFixed(2)
    let content = '类型：' + this.data.option.label + '；' + what + '。\n原因：' + payload.reason + '\n'
    if (this.data.previewExtra && this.data.previewExtra.hasMigratedPrice) {
      content += '注意：含历史推断单价批次，退款金额以执行结果为准。\n'
    }
    content += '创建后为「待执行」，需再点「执行」才会改写客户资产。'

    wx.showModal({
      title: '确认创建调整单',
      content: content,
      confirmText: '创建',
      confirmColor: '#4F8EF7',
      success: (res) => {
        // 回调式确认：只有确认为真才发请求，且复用同一个 clientToken（防重复提交）
        if (res.confirm) this.doSubmit(payload)
      }
    })
  },

  async doSubmit(payload) {
    const evidence = this.data.evidenceList.join(',')
    if (evidence.length > EVIDENCE_MAX_LEN) {
      wx.showToast({ title: '证据图链接合计超过 ' + EVIDENCE_MAX_LEN + ' 字符，请减少图片', icon: 'none', duration: 3000 })
      return
    }
    const body = Object.assign({}, payload, { clientToken: this.data.clientToken })
    if (evidence) body.evidence = evidence

    this.setData({ submitting: true })
    wx.showLoading({ title: '提交中...' })
    try {
      await createAdjustment(body)
      wx.hideLoading()
      wx.showToast({ title: '已创建，待执行', icon: 'success' })
      setTimeout(() => { wx.navigateBack() }, 1500)
    } catch (err) {
      wx.hideLoading()
      wx.showToast({ title: err.message || '创建失败', icon: 'none', duration: 3000 })
    } finally {
      this.setData({ submitting: false })
    }
  }
})
