// 客户画像（站长视角）
const { getCustomerProfile, getCustomerAssets, updateOfflinePayment, returnEmptyBuckets } = require('../../../../api/station-mgmt')
// ⚠️ 特权接口走 utils/request + 本文件内的路径常量，不往 api/station-mgmt.js 里加函数：
// 那个文件正被另一个工作流（商品图片库）改动，往里加东西会让两边未提交的改动纠缠在一起。
const { get: httpGet, post: httpPost, del: httpDel } = require('../../../../utils/request')
const privilegesPath = (customerId) => `/api/manager/customers/${customerId}/privileges`

Page({
  data: {
    loading: true,
    saving: false,
    id: null,
    profile: null,
    error: '',
    // ===== 本站资产 =====
    // 资产接口是站长专属（@RequireRole STATION_MANAGER）。本页的 canAccessStationBusiness()
    // 同时放行配送员，所以这里再判一次角色：非站长不请求、不渲染该区块，
    // 否则配送员进来会看到一条与页面无关的"权限不足"，容易被当成页面故障。
    canViewAssets: false,
    assets: null,
    assetsError: '',
    assetsLoading: false,
    // 流水默认只展示前若干条，点"展开全部"再放开
    recordsExpanded: false,
    recordsPreviewCount: 5,
    // ===== 纯还桶（只冲减 over，不扣权益、不退款） =====
    showReturnEmpty: false,
    returnItems: [],
    returnSubmitting: false,
    // ===== 客户特权（v40） =====
    // 与资产接口同理，是站长专属：配送员不请求、不渲染该区块。
    privileges: [],
    // 可授予类型由**服务端下发**（只含已实现的），前端不写死枚举 ——
    // 否则后端新增一种特权，前端不改就永远看不到
    grantableTypes: [],
    privilegesLoading: false,
    privilegesError: ''
  },

  onLoad(options) {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    const id = options.id
    const canViewAssets = app.isStationManager()
    this.setData({ id, canViewAssets })
    this.loadProfile(id)
    if (canViewAssets) {
      this.loadAssets(id)
      this.loadPrivileges(id)
    }
  },

  async loadProfile(id) {
    this.setData({ loading: true, error: '' })
    try {
      const res = await getCustomerProfile(id)
      if (res.code === 0 && res.data) {
        this.setData({ profile: this.decorate(res.data) })
      } else {
        this.setData({ error: res.message || '客户不存在', profile: null })
      }
    } catch (err) {
      // 把真实错误暴露出来（例如后端未部署该接口会返回 404/网络错误），便于定位"空白"根因
      this.setData({ error: err.message || '加载失败', profile: null })
    } finally {
      this.setData({ loading: false })
    }
  },

  /**
   * 客户特权（v40）。规格见 docs/design/20 §4。
   *
   * 产品决定（docs/design/16 D6）：不做个人/企业客户的显式区分，
   * 差异化一律落到「站长在客户画像里给特权」—— 这个区块就是那个落点。
   * ⚠️ 未实现的类型（折扣率/免配送次数/允许退票）后端会在授予时直接拒，
   * 且**不**出现在 grantableTypes 里，所以界面上根本不会出现"点了没反应"的开关。
   */
  async loadPrivileges(id) {
    this.setData({ privilegesLoading: true, privilegesError: '' })
    try {
      const res = await httpGet(privilegesPath(id))
      const d = res.data || {}
      this.setData({ privileges: d.privileges || [], grantableTypes: d.grantableTypes || [] })
    } catch (err) {
      this.setData({ privilegesError: err.message || '特权加载失败' })
    } finally {
      this.setData({ privilegesLoading: false })
    }
  },

  onGrantPrivilege(e) {
    const type = e.currentTarget.dataset.type
    const t = this.data.grantableTypes.find(x => x.type === type)
    if (!t) return
    wx.showModal({
      title: '授予特权',
      content: (t.text || type) + '：' + (t.desc || ''),
      success: async (r) => {
        if (!r.confirm) return
        try {
          await httpPost(privilegesPath(this.data.id), { type })
          wx.showToast({ title: '已授予', icon: 'success' })
          await this.loadPrivileges(this.data.id)
        } catch (err) {
          wx.showToast({ title: err.message || '授予失败', icon: 'none' })
        }
      }
    })
  },

  /** 撤销。后端在"本来就没有"时会报错——如实弹出，不要无条件提示成功（AGENTS §8.20）。 */
  onRevokePrivilege(e) {
    const type = e.currentTarget.dataset.type
    wx.showModal({
      title: '撤销特权？',
      content: '撤销后该客户在本站立即恢复默认规则。',
      success: async (r) => {
        if (!r.confirm) return
        try {
          await httpDel(privilegesPath(this.data.id) + '/' + type)
          wx.showToast({ title: '已撤销', icon: 'success' })
          await this.loadPrivileges(this.data.id)
        } catch (err) {
          wx.showToast({ title: err.message || '撤销失败', icon: 'none' })
        }
      }
    })
  },

  /**
   * 加载「本站资产」。
   * 独立于画像主接口：画像挂了（例如接口未部署）不应该连带把资产区块也变成一片空白，
   * 反之亦然，两个区块各自给出错误提示，便于定位。
   */
  async loadAssets(id) {
    this.setData({ assetsLoading: true, assetsError: '' })
    try {
      const res = await getCustomerAssets(id)
      if (res && res.code === 0 && res.data) {
        this.setData({ assets: this.decorateAssets(res.data), recordsExpanded: false })
      } else {
        this.setData({ assets: null, assetsError: (res && res.message) || '资产信息不可用' })
      }
    } catch (err) {
      this.setData({ assets: null, assetsError: err.message || '资产加载失败' })
    } finally {
      this.setData({ assetsLoading: false })
    }
  },

  /** 金额/文案统一在 JS 侧兜底，模板只做取值，避免出现 undefined/NaN */
  decorateAssets(a) {
    const money = (v) => Number(v || 0).toFixed(2)
    const int = (v) => Number(v || 0)

    a.heldBuckets = int(a.heldBuckets)
    a.inTransitBuckets = int(a.inTransitBuckets)
    a.owedBuckets = int(a.owedBuckets)
    a.ticketQuantity = int(a.ticketQuantity)
    a.depositBalanceText = money(a.depositBalance)
    a.ticketValueText = money(a.ticketValue)
    a.totalAssetValueText = money(a.totalAssetValue)
    a.barrelDepositTotalText = money(a.barrelDepositTotal)
    a.owedBucketsText = a.owedBucketsText || (a.owedBuckets > 0 ? a.owedBuckets + ' 个' : '无欠桶')
    a.hasOwed = a.owedBuckets > 0

    a.barrels = (a.barrels || []).map((b) => {
      const over = int(b.overQty)
      const occupied = b.occupiedQty != null ? int(b.occupiedQty) : (int(b.heldQty) + over)
      return {
        ...b,
        overQty: over,
        occupiedQty: occupied,
        // 还桶上限 = 占用（权益 + over）。over 可为负，负数表示多还的桶寄存在水站，
        // 那时占用小于权益，能还的也就更少——不能用权益数当上限，否则后端会拒。
        maxReturnableQty: Math.max(0, occupied),
        // 欠桶 / 暂存文案：over=0 时留空，模板用 wx:if 控制
        overText: over > 0 ? ('欠桶 ' + over + ' 个') : (over < 0 ? ('水站暂存 ' + (-over) + ' 个') : ''),
        hasOver: over !== 0,
        depositPerBucketText: money(b.depositPerBucket),
        depositAmountText: money(b.depositAmount)
      }
    })

    a.tickets = (a.tickets || []).map((t) => ({
      ...t,
      remainQuantity: int(t.remainQuantity),
      unitPriceText: money(t.unitPrice),
      totalValueText: money(t.totalValue)
    }))

    a.records = (a.records || []).map((r, i) => ({
      ...r,
      idx: i,
      // direction 由后端给（IN/OUT/FLAT），模板据此配色
      directionClass: r.direction === 'IN' ? 'in' : (r.direction === 'OUT' ? 'out' : 'flat')
    }))

    // 预切一份预览列表：WXML 里不让 wx:for 与 wx:if 同节点（官方不推荐），
    // 改成"展开/收起切换的是两个现成数组"，模板里只做取值。
    a.recordsPreview = a.records.slice(0, this.data.recordsPreviewCount)
    a.hasMoreRecords = a.records.length > this.data.recordsPreviewCount
    return a
  },

  onToggleRecords() {
    this.setData({ recordsExpanded: !this.data.recordsExpanded })
  },

  onRefreshAssets() {
    if (this.data.assetsLoading) return
    this.loadAssets(this.data.id)
  },

  /**
   * 资产调整单入口（站长专属，见 pages/station-mgmt/customers/adjust/）。
   * 调整是「人工补录/订正」的唯一入口：桶权益、欠桶、押金、水票的历史漏录都走这里，
   * 不再新开第二条改账路径。
   */
  onOpenAdjust() {
    wx.navigateTo({
      url: `/pages/station-mgmt/customers/adjust/index?customerId=${this.data.id}`
    })
  },

  // ============ 纯还桶：只冲减 over，不扣权益、不退款 ============
  // 为什么必须有这个入口：旧模型里消掉欠桶的唯一途径是"下次配送时多还"，
  // 而欠桶超阈值又会被拒单 → 顾客一旦欠桶就永久锁死、押金退不出来。
  // 有了它，随时能把桶还回来，over 可以为负（多还的桶由水站暂存，合法）。

  onShowReturnEmpty() {
    const barrels = (this.data.assets && this.data.assets.barrels) || []
    const items = barrels
      .filter(b => b.maxReturnableQty > 0)
      .map(b => ({
        productId: b.productId,
        productName: b.productName + (b.productSpec ? ' ' + b.productSpec : ''),
        max: b.maxReturnableQty,
        over: b.overQty || 0,
        // 有欠桶时预填欠桶数，站长点一下就能把欠桶冲平；没欠桶就留空让站长自己填
        qty: b.overQty > 0 ? Math.min(b.overQty, b.maxReturnableQty) : 0
      }))
    if (items.length === 0) {
      wx.showToast({ title: '该客户当前没有可还的桶', icon: 'none' })
      return
    }
    this.setData({ showReturnEmpty: true, returnItems: items })
  },

  onCloseReturnEmpty() {
    this.setData({ showReturnEmpty: false })
  },

  onReturnEmptyQtyInput(e) {
    const idx = Number(e.currentTarget.dataset.idx)
    const max = Number(e.currentTarget.dataset.max)
    let v = parseInt(e.detail.value) || 0
    if (v < 0) v = 0
    if (v > max) v = max
    this.setData({ ['returnItems[' + idx + '].qty']: v })
  },

  async onSubmitReturnEmpty() {
    const items = (this.data.returnItems || [])
      .filter(i => i.qty > 0)
      .map(i => ({ productId: i.productId, qty: i.qty }))
    if (items.length === 0) {
      wx.showToast({ title: '请填写还桶数量', icon: 'none' })
      return
    }
    // 幂等 token：后端 uk_record_client_token 保证同一 token 只生效一次，防重复提交
    const clientToken = 'RE-' + Date.now() + '-' + Math.floor(Math.random() * 100000)
    this.setData({ returnSubmitting: true })
    try {
      const res = await returnEmptyBuckets(this.data.id, items, clientToken, '站长代客还空桶')
      const changes = (res && res.data && res.data.changes) || []
      wx.showToast({ title: '已登记还桶 ' + changes.length + ' 项', icon: 'success' })
      this.setData({ showReturnEmpty: false })
      this.loadAssets(this.data.id)
    } catch (err) {
      wx.showToast({ title: err.message || '登记失败', icon: 'none', duration: 3000 })
    } finally {
      this.setData({ returnSubmitting: false })
    }
  },

  // 金额格式化在 JS 里做（wxml 不做数值格式化，避免兼容问题）
  decorate(p) {
    const n = (v) => Number(v || 0).toFixed(2)
    p.avgOrderAmountText = n(p.avgOrderAmount)
    p.totalConsumptionText = n(p.totalConsumption)
    p.monthConsumptionText = n(p.monthConsumption)
    p.depositBalanceText = n(p.depositBalance)
    p.monthOrders = p.monthOrders || 0
    p.totalOrders = p.totalOrders || 0
    p.ticketBalance = p.ticketBalance || 0
    return p
  },

  onPullDownRefresh() {
    const tasks = [this.loadProfile(this.data.id)]
    if (this.data.canViewAssets) {
      tasks.push(this.loadAssets(this.data.id))
    }
    Promise.all(tasks).then(() => wx.stopPullDownRefresh())
  },

  onCall(e) {
    const { phone } = e.currentTarget.dataset
    if (phone) wx.makePhoneCall({ phoneNumber: phone })
  },

  // 切换货到付款权限
  async onToggleCod(e) {
    const enabled = e.detail.value
    if (this.data.saving) return
    this.setData({ saving: true })
    try {
      // 显式给全 body：`updateOfflinePayment` 只接受 payload 形态
      // （它曾在 api 模块里以 `(id, boolean)` 重载出现，两次声明直接让整个模块语法错误，2026-09-19）
      const res = await updateOfflinePayment(this.data.id, { offlinePaymentEnabled: enabled ? 1 : 0 })
      if (res.code === 0) {
        this.setData({
          'profile.codEnabled': enabled,
          'profile.offlinePaymentEnabled': enabled ? 1 : 0
        })
        wx.showToast({ title: enabled ? '已开通货到付款' : '已关闭货到付款', icon: 'success' })
      } else {
        wx.showToast({ title: res.message || '操作失败', icon: 'none' })
        this.setData({ 'profile.codEnabled': !enabled })
      }
    } catch (err) {
      wx.showToast({ title: err.message || '操作失败', icon: 'none' })
      this.setData({ 'profile.codEnabled': !enabled })
    } finally {
      this.setData({ saving: false })
    }
  }
})
