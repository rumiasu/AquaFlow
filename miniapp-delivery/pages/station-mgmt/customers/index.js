// 站长客户查询
const { getCustomers, getOfflinePaymentSummary, updateOfflinePayment,
        getEnterpriseApplies, reviewEnterpriseApply,
        getEnterpriseConfig, updateEnterpriseConfig } = require('../../../api/station-mgmt')
const { STORAGE_KEYS } = require('../../../utils/storage-keys')

const AVATAR_COLORS = ['#409EFF', '#67C23A', '#E6A23C', '#F56C6B', '#909399', '#9254DE']

// 列表项前端派生展示字段（头像/脱敏等纯展示，不依赖后端）
function decorate(item) {
  const name = item.name || '?'
  item.avatarText = name.charAt(0)
  let h = 0
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0
  item.avatarColor = AVATAR_COLORS[h % AVATAR_COLORS.length]
  const phone = item.phone || ''
  item.phoneMasked = phone.length === 11 ? phone.replace(/(\d{3})\d{4}(\d{4})/, '$1****$2') : (phone || '未填写')
  item.totalOrders = item.totalOrders || 0
  item.totalConsumptionText = Number(item.totalConsumption || 0).toFixed(2)
  item.depositBalanceText = Number(item.depositBalance || 0).toFixed(2)
  item.customerLevel = item.customerLevel || '普通客户'
  item.customerLevelColor = item.customerLevelColor || '#909399'
  item.activityStatus = item.activityStatus || 'new'
  item.activityText = item.activityText || '新客'
  item.tagsList = item.tags ? String(item.tags).split(',').map(s => s.trim()).filter(Boolean) : []
  return item
}

Page({
  data: {
    loading: true,
    list: [],
    keyword: '',
    filterType: 'all', // all | 1 个人 | 2 企业
    // 货到付款开通弹窗（v48）：站长在"设置是否允许货到付款"的那一刻就要看到该客户欠了多少、
    // 为什么现在用不了（原因文案来自后端唯一判据，前端不自己编）
    codModal: { visible: false, customerId: null, customerName: '', orderCount: 0, overdueCount: 0, overdueAmount: '0.00', blockReason: '' },
    codForm: { enabled: false },
    // 企业身份（v50 审核 + v51 阈值）：**一个入口、一个弹窗、两个视图**（2026-09-19 IA 重组 C5）。
    //   · entApplies 为空且平台开关关着 → 入口整行不显示（见 wxml 的 entCfg.enabled）；
    //   · entVisible / entView 是这一个弹窗的两个状态，**不要再加第二个弹窗**。
    entApplies: [],
    entVisible: false,
    entView: 'review', // review 待审 | settings 阈值
    entSaving: false,
    // 企业身份提示阈值（v51）：站长按站配。entCfg.enabled=false（平台总开关关着）时整块隐藏 ——
    // 注意平台级开关**没有**前端入口，这里说的开关只是"要不要显示这一块"。
    entCfg: { enabled: false, barrelThreshold: null, waterAmountThreshold: null, defaultBarrels: 30, usingDefault: true },
    entCfgText: '',
    entCfgSaving: false,
    entCfgForm: { barrels: '', amount: '' }
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this.loadData()
  },

  onPullDownRefresh() {
    this.loadData().then(() => wx.stopPullDownRefresh())
  },

  onKeywordInput(e) {
    this.setData({ keyword: e.detail.value })
  },

  onSearch() {
    this.loadData()
  },

  onFilterType(e) {
    this.setData({ filterType: e.currentTarget.dataset.type })
    this.loadData()
  },

  async loadData() {
    const app = getApp()
    // 冷启动时 globalData 可能尚未水合，需回退本地存储，否则列表一直空白
    const stationId = (app.globalData.userInfo && app.globalData.userInfo.stationId)
      || app.globalData.stationId
      || wx.getStorageSync(STORAGE_KEYS.STATION_ID)
      || null

    this.setData({ loading: true })
    try {
      // 关键字交给服务端：站长认人靠地址，而「阳光81301」这种缩写与「八栋/8栋」的
      // 数字混用只有归一化之后才匹配得上（本地 includes 一定漏）。
      // 因此这里**不再**本地 filter name/phone —— 服务端已经把结果筛好了。
      const keyword = this.data.keyword.trim()
      // 企业身份待审与客户列表是两个独立请求，并行发；它自己吞掉异常，
      // 绝不能让"待审列表取不到"把客户列表也变成一片空白。
      const entTask = this.loadEnterpriseApplies()
      const cfgTask = this.loadEnterpriseConfig()
      const res = await getCustomers(stationId, keyword)
      const filterType = this.data.filterType
      let list = (res.data || []).map(decorate)
      if (filterType !== 'all') {
        const t = Number(filterType)
        list = list.filter(c => c.customerType === t)
      }
      this.setData({ list })
      await entTask
      await cfgTask
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  /**
   * 本站待审的企业身份申请（v50）。
   *
   * 静默失败是有意的：这是客户列表页顶部的一行"顺带提示"，取不到就当作没有 ——
   * 为一个附加提示在客户查询页弹红字，属于喧宾夺主。
   * ⚠️ 功能总开关关着时后端给的是**空列表**，与"真的没有申请"同形，前端无需区分。
   */
  async loadEnterpriseApplies() {
    try {
      const res = await getEnterpriseApplies()
      const list = (res.data || []).map(a => {
        // 时间只用后端下发的 ISO 串做切片展示：不 new Date()、不做时区换算
        // （这是"谁什么时候申请的"，精度到分钟足够）
        a.applyTimeText = a.applyTime ? String(a.applyTime).replace('T', ' ').slice(0, 16) : ''
        a.statusText = a.statusText || '待审核'
        return a
      })
      this.setData({ entApplies: list })
    } catch (err) {
      console.warn('[Customers] 企业身份待审列表获取失败（当作没有）:', err.message)
      this.setData({ entApplies: [] })
    }
  },

  onCall(e) {
    const { phone } = e.currentTarget.dataset
    if (phone) wx.makePhoneCall({ phoneNumber: phone })
  },

  // 跳转客户画像/权限管理
  onManage(e) {
    const { id } = e.currentTarget.dataset
    wx.navigateTo({ url: `/pages/station-mgmt/customers/detail/index?id=${id}` })
  },

  /* ==================== 货到付款开通与约束（v48） ==================== */

  /**
   * 打开「货到付款」设置弹窗。
   *
   * 先把后端算好的依据拉下来（欠款/逾期/历史订单数/当前能不能用 + 原因），再回填当前配置 ——
   * 站长是**在设置的那一刻**看到"这个客户欠着多少、为什么现在用不了"，
   * 而不是开通完再被下单拒绝。
   */
  async onCod(e) {
    const { id, name } = e.currentTarget.dataset
    try {
      const res = await getOfflinePaymentSummary(id)
      const d = res.data || {}
      this.setData({
        codModal: {
          visible: true,
          customerId: id,
          customerName: name || '',
          orderCount: d.orderCount || 0,
          overdueCount: d.overdueCount || 0,
          overdueAmount: d.overdueAmount != null ? d.overdueAmount : '0.00',
          blockReason: d.blockReason || ''
        },
        codForm: { enabled: Number(d.offlinePaymentEnabled) === 1 }
      })
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    }
  },

  onCodClose() {
    this.setData({ 'codModal.visible': false })
  },

  /** 弹窗内容区的空处理器：阻止点击穿透到遮罩（否则点输入框就把弹窗关了）。 */
  onCodNoop() {},

  onCodToggle(e) {
    this.setData({ 'codForm.enabled': e.detail.value })
  },

  async onCodSave() {
    const { customerId } = this.data.codModal
    const { enabled } = this.data.codForm
    const payload = { offlinePaymentEnabled: enabled ? 1 : 0 }
    try {
      const res = await updateOfflinePayment(customerId, payload)
      if (res.code !== 0) {
        wx.showToast({ title: res.message || '保存失败', icon: 'none' })
        return
      }
      wx.showToast({ title: '已保存', icon: 'success' })
      this.setData({ 'codModal.visible': false })
      this.loadData()
    } catch (err) {
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    }
  },

  /* ==================== 企业身份提示阈值（v51，站长按站配） ==================== */

  /**
   * 读本站阈值。**静默失败**：这是客户列表页顶部的一行附加设置，取不到就当作功能没开、
   * 整块不显示，不为它弹红字（与待审列表同一处理口径）。
   *
   * 回填时把"没配过 → 用平台默认"一并展示出来：站长得看得出这个 30 桶是平台给的还是自己设的
   * （`usingDefault`），否则他会以为自己设过。
   */
  async loadEnterpriseConfig() {
    try {
      const res = await getEnterpriseConfig()
      const c = (res && res.data) || {}
      // 「未启用」只有一种表示法：null。这里把 0/负数也归一到 null ——
      // 否则站长打开设置会看到"水费达到 0 元"，一保存又被"必须为正"拒掉（后端已保证不下发 0，
      // 这一层是防御：老版本后端 / 人为改库都可能给出 0）。
      const positive = (v) => {
        const n = Number(v)
        return Number.isFinite(n) && n > 0 ? n : null
      }
      const cfg = {
        enabled: c.enabled === true,
        barrelThreshold: positive(c.barrelThreshold),
        waterAmountThreshold: positive(c.waterAmountThreshold),
        defaultBarrels: c.defaultBarrels == null ? 30 : Number(c.defaultBarrels),
        usingDefault: c.usingDefault === true
      }
      this.setData({
        entCfg: cfg,
        entCfgText: this.describeEnterpriseConfig(cfg)
      })
    } catch (err) {
      console.warn('[Customers] 企业身份阈值配置获取失败（当作功能未开启）:', err.message)
      this.setData({ entCfg: { enabled: false }, entCfgText: '' })
    }
  },

  /** 把两项阈值说成一句人话（wxml 不做格式化，一律在 js 里拼好）。 */
  describeEnterpriseConfig(cfg) {
    const parts = []
    if (cfg.barrelThreshold) {
      parts.push(`达到 ${cfg.barrelThreshold} 桶`)
    }
    if (cfg.waterAmountThreshold) {
      parts.push(`水费达到 ¥${cfg.waterAmountThreshold.toFixed(2)}`)
    }
    if (!parts.length) {
      return '本站不提示（两项都留空）'
    }
    const suffix = cfg.usingDefault ? '（平台默认，可改）' : ''
    return parts.join(' 或 ') + suffix
  },

  /** 把当前阈值回填进表单（进阈值视图时调）。 */
  openEntCfgForm() {
    const { barrelThreshold, waterAmountThreshold } = this.data.entCfg
    this.setData({
      entCfgForm: {
        barrels: barrelThreshold == null ? '' : String(barrelThreshold),
        amount: waterAmountThreshold == null ? '' : String(waterAmountThreshold)
      }
    })
  },

  onEntCfgBarrelInput(e) {
    this.setData({ 'entCfgForm.barrels': e.detail.value })
  },

  onEntCfgAmountInput(e) {
    this.setData({ 'entCfgForm.amount': e.detail.value })
  },

  /**
   * 保存阈值。两条都留空是**合法**的（= 本站不提示），所以这里不拦"空"，
   * 只拦"填了但不是正数" —— 后端也会再校验一次，前端这层只是为了少一次往返。
   */
  async onEntCfgSave() {
    if (this.data.entCfgSaving) return
    const barrelsRaw = (this.data.entCfgForm.barrels || '').trim()
    const amountRaw = (this.data.entCfgForm.amount || '').trim()
    const barrels = barrelsRaw === '' ? null : Number(barrelsRaw)
    const amount = amountRaw === '' ? null : Number(amountRaw)
    if (barrels !== null && (!Number.isInteger(barrels) || barrels <= 0)) {
      wx.showToast({ title: '桶数要填大于 0 的整数，或留空', icon: 'none' })
      return
    }
    if (amount !== null && !(amount > 0)) {
      wx.showToast({ title: '金额要填大于 0 的数字，或留空', icon: 'none' })
      return
    }
    this.setData({ entCfgSaving: true })
    try {
      const res = await updateEnterpriseConfig({ barrelThreshold: barrels, waterAmountThreshold: amount })
      if (res.code !== 0) {
        wx.showToast({ title: res.message || '保存失败', icon: 'none' })
        return
      }
      wx.showToast({ title: '已保存', icon: 'success' })
      // 保存后**留在弹窗里、切回待审视图**（而不是把弹窗关掉）：站长刚设完阈值，
      // 下一步多半就是回去看待审申请；关掉弹窗会让他重新找入口。
      this.setData({ entView: 'review' })
      await this.loadEnterpriseConfig()
    } catch (err) {
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ entCfgSaving: false })
    }
  },

  /* ==================== 企业身份申请审核（v50） ==================== */

  /**
   * 打开企业身份弹窗（唯一入口）。`data-view` 决定先看哪个视图：
   * review = 待审列表（点入口左半边）/ settings = 阈值（点右半边「提示阈值 ›」）。
   * 进 settings 时才去拉一次阈值配置（不在 onShow 里预拉，省一次请求）。
   */
  onEntOpen(e) {
    const view = (e.currentTarget.dataset.view === 'settings') ? 'settings' : 'review'
    this.setData({ entVisible: true, entView: view })
    if (view === 'settings') this.openEntCfgForm()
  },

  /** 弹窗内两个视图互切（审核 ⇄ 阈值）。 */
  onEntSwitch(e) {
    const view = e.currentTarget.dataset.view === 'settings' ? 'settings' : 'review'
    this.setData({ entView: view })
    if (view === 'settings') this.openEntCfgForm()
  },

  onEntClose() {
    this.setData({ entVisible: false })
  },

  /** 弹窗内容区的空处理器：阻止点击穿透到遮罩（否则点一下就把弹窗关了）。 */
  onEntNoop() {},

  /**
   * 通过申请。
   *
   * 先确认再动手：通过之后这个客户就转成**企业客户**了（企业资料一并落库），
   * 站长该在这个时刻知道"我认下了这笔生意"，而不是点错一下悄悄改了客户类型。
   */
  onEntApprove(e) {
    const { id, name } = e.currentTarget.dataset
    wx.showModal({
      title: '确认为企业客户',
      content: `通过后「${name}」将转为企业客户，企业资料同步建档。`,
      confirmText: '通过',
      cancelText: '再想想',
      success: (r) => {
        if (r.confirm) this.doReview(id, true, '')
      }
    })
  },

  /** 驳回申请：驳回理由（可不填）用 editable 弹窗问一次，它会随申请一起留痕。 */
  onEntReject(e) {
    const { id, name } = e.currentTarget.dataset
    wx.showModal({
      title: '驳回申请',
      editable: true,
      placeholderText: `驳回「${name}」的理由（可不填）`,
      success: (r) => {
        if (r.confirm) this.doReview(id, false, (r.content || '').trim())
      }
    })
  },

  async doReview(id, approve, note) {
    if (this.data.entSaving) return
    this.setData({ entSaving: true })
    try {
      const res = await reviewEnterpriseApply(id, approve, note)
      if (res.code !== 0) {
        wx.showToast({ title: res.message || '操作失败', icon: 'none' })
        return
      }
      wx.showToast({ title: approve ? '已通过' : '已驳回', icon: 'success' })
      // 重新拉一次：待审列表少一条，且通过后客户列表里的身份标签要跟着变。
      // [2026-09-19 C5] 处理完最后一条**不再自动关弹窗** —— 弹窗里已有「暂无待审申请」的空态，
      // 关掉反而像"操作完不知道发生了什么"；要关由站长自己点。
      await this.loadEnterpriseApplies()
      this.loadData()
    } catch (err) {
      wx.showToast({ title: err.message || '操作失败', icon: 'none' })
    } finally {
      this.setData({ entSaving: false })
    }
  }
})
