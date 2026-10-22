// 站长端「站间结算」台账（v67 / 2026-09-27 产品拍板 4.a + 4.b）。
//
// **这一页回答什么**：跨站单的钱收在**归属站**（票钱/微信款在它手上）、营收却算**接单站**
// （`coalesce(settle_station_id, delivery_station_id, station_id)`）——
// 以前系统里**没有任何一处**能回答"谁欠谁、欠多少、什么时候算办完"（实测：接单站名下 0 条支付流水，
// 看板却显示了一笔"已收款"）。
//
// 三条口径（正本 `docs/design/31` §8，**前端一个字都不自己算**）：
//   · 「欠多少」是**后端实时算**的（真相源＝订单与支付流水），页面上每个金额都来自响应；
//   · 「按什么价结」有两条：默认**折算实付**（票当初实付多少钱），卖票站可改成**按挂牌价**
//     （差价由卖票站承担）—— 后者是**站长点**，系统不会自动改；
//   · 「办完没有」只有两种事实：**登记结清**（付款方登记，钱线下走，系统只留痕、不假装打款）
//     与**冲销**（订单取消/退款后那笔应付不再成立）。
//
// ⚠️ 三个"不"（都是会真出错的形状）：
//   ① **不自己算金额**：`direction/basisText/statusText/amount` 全部用后端下发的值，
//      前端只做千分位与拼串；口径文案由服务端一处持有（同 statusText / payMethodText 的既定判据）。
//   ② **不判断谁有权**：能不能结清/改价由后端判（`canSettle` / `canChangePrice` 只是用来显示按钮，
//      真越权时后端会拒并把原因回给我们）。
//   ③ **不藏失败**：拉不到台账必须出声，静默失败会让站长以为"没人欠我钱"。

const {
  getInterStationSettlements,
  settleInterStation,
  reverseInterStation
} = require('../../../api/station-mgmt')
const { priceByListed } = require('../../../api/delivery')
const aftercareApi = require('../../../api/business-rules')
const intents = require('../../../utils/exception-action-intent')
const { positiveId } = require('../../../utils/business-history-page')
const aftercareFocus = actor => 'aftercare-order:' + actor
const exactMoney = value => (typeof value === 'string' || typeof value === 'number') && /^\d+(?:\.\d{1,2})?$/.test(String(value)) && Number(value) <= 99999999.99

Page({
  data: {
    loading: true,
    loaded: false, ledgerError: '',
    // 三个数：别人欠本站 / 本站欠别人 / 净额（正 = 净收）
    summary: { receivableText: '待核实', payableText: '待核实', netText: '待核实', netPositive: true },
    scopeNote: '',
    items: [],
    dangling: [],
    unsettledCount: 0,
    aftercareOrderId: null, aftercareItems: [], aftercareReady: false, aftercareLoading: false, aftercareSearch: '', aftercarePendingOrders: [], aftercareHasMore: false,
    aftercareError: '', aftercareNote: '', aftercareBusy: false, aftercareSelected: null,
    aftercareAction: '', aftercareAmount: '', aftercareReason: '', aftercarePending: false
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    const previous = this._ledgerReadSession, current = this.aftercareSession()
    if (previous && (!current || previous.app !== current.app || previous.identity !== current.identity)) this.setData({ loaded: false, items: [], dangling: [], aftercarePendingOrders: [],
      summary: { receivableText: '待核', payableText: '待核', netText: '待核', netPositive: true, netLabel: '待核对' } })
    this._aftercareHidden = false
    this.restoreAftercare()
    this.loadData()
  },

  async onPullDownRefresh() {
    await this.loadData()
    wx.stopPullDownRefresh()
  },

  onRetryLedger() { return this.loadData() },

  async loadData() {
    const snapshot = this.aftercareSession(), seq = this._ledgerSeq = (this._ledgerSeq || 0) + 1
    this.setData({ loading: true, ledgerError: '' })
    try {
      const res = await getInterStationSettlements()
      if (seq !== this._ledgerSeq || snapshot && !this.aftercareCurrent(snapshot)) return
      const d = res.data
      if (!d || !Array.isArray(d.items) || !Array.isArray(d.danglingSettled) || !['receivableAmount', 'payableAmount', 'netAmount'].every(key => d[key] !== null && d[key] !== undefined && d[key] !== '' && Number.isFinite(Number(d[key])))) throw new Error('台账资料不完整，请重新核对')
      this._ledgerReadSession = snapshot
      this.setData({
        loaded: true,
        summary: this.decorateSummary(d),
        scopeNote: d.scopeNote || '',
        items: (d.items || []).map((it) => this.decorateItem(it)),
        dangling: (d.danglingSettled || []).map((it) => this.decorateItem(it)),
        unsettledCount: Number(d.unsettledCount) || 0,
        aftercarePendingOrders: Array.isArray(d.aftercarePendingOrders) ? d.aftercarePendingOrders : [], aftercareHasMore: d.aftercareHasMore === true
      })
    } catch (err) {
      if (seq !== this._ledgerSeq || snapshot && !this.aftercareCurrent(snapshot)) return
      // 静默失败 = 站长以为"本站没有站间欠款"，而这正是这一页要回答的问题
      this.setData({ ledgerError: '站间台账未完成核对；如有记录，为上次读取结果。请重新读取后办理。' })
      console.error('[InterStation] 台账加载失败:', err)
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    } finally {
      if (seq === this._ledgerSeq && (!snapshot || this.aftercareCurrent(snapshot))) this.setData({ loading: false })
    }
  },

  /** 金额与方向全部来自后端；这里只做展示格式化（wxml 不做计算）。 */
  decorateSummary(d) {
    const money = (v) => v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? '待核实' : Number(v).toFixed(2)
    const net = Number(d.netAmount || 0)
    return {
      receivableText: money(d.receivableAmount),
      payableText: money(d.payableAmount),
      netText: money(Math.abs(net)),
      // 正 = 净收（别人欠我多）；负 = 净付。**方向由文案承担**，不显示负号（站长看数字更顺）
      netPositive: net >= 0,
      netLabel: net >= 0 ? '别人净欠本站' : '本站净欠别人'
    }
  },

  /**
   * 逐单展示字段。口径说明（`_ruleText`）把"这个数是怎么来的"写在行里 ——
   * 柜台与站长按同一句话对账，不用回去翻设计文档。
   */
  decorateItem(it) {
    const money = (v) => v === null || v === undefined || v === '' || !Number.isFinite(Number(v)) ? '待核实' : Number(v).toFixed(2)
    const basis = Number(it.basis)
    let rule = it.basisText || ''
    if (basis === 2) {
      rule += `（${it.ticketQty} 张 × 约 ${money(it.unitPrice)}）`
    } else if (basis === 3) {
      rule += `（${it.ticketQty} 张 × 约 ${money(it.unitPrice)}，比折算实付多出的部分由本单卖票站承担）`
    }
    // [2026-09-29 拍板] 票覆盖的配送费/楼层费一起结进金额：费用 > 0 必须点破，
    // 否则站长拿「张数 × 单价」一乘对不上金额，差的正是这笔费用（谁也不解释）
    if ((basis === 2 || basis === 3) && Number(it.feeAmount || 0) > 0) {
      rule += `，另含配送/楼层费 ${money(it.feeAmount)}`
    }
    // 结清了就把"什么时候算办完、凭据是什么"显示出来 —— 那正是这一页的验收标准
    let settledText = ''
    if (Number(it.status) === 2 && it.settledTime) {
      settledText = '已结清 · ' + String(it.settledTime).replace('T', ' ').slice(0, 16)
      if (it.settleNote) settledText += ' · ' + it.settleNote
    }
    return Object.assign({}, it, {
      _key: String(it.orderId),
      _amountText: exactMoney(it.amount) ? money(it.amount) : '待核实',
      _amountKnown: exactMoney(it.amount),
      _settlementPartiesKnown: !!positiveId(it.fromStationId) && !!positiveId(it.toStationId),
      _ruleText: rule,
      _routeText: (it.fromStationName || '？') + ' → ' + (it.toStationName || '？'),
      _settledText: settledText,
      _payMethodText: it.paymentMethodText || ''
    })
  },

  /** 登记结清：只有**付款方**（本站）能点；凭据说明可空 —— 钱是线下走的，系统只留痕。 */
  onSettle(e) {
    if (this._settleAction) return
    const snapshot = this.aftercareSession(), version = this._ledgerSeq
    const row = this.data.items.find(item => String(item.orderId) === String(e.currentTarget.dataset.id))
    const expectedAmount = row && row.amount
    const action = { used: false, stage: 'facts' }
    const current = () => this._settleAction === action && !action.used && this.aftercareCurrent(snapshot)
      && this.aftercareCurrent(this._ledgerReadSession) && version === this._ledgerSeq
      && this.data.loaded && !this.data.loading && !this.data.ledgerError
      && row && this.data.items.includes(row) && row.canSettle === true && exactMoney(row.amount) && Number(row.amount) === Number(expectedAmount)
      && positiveId(row.fromStationId) && positiveId(row.toStationId) && String(row.fromStationId) === String(snapshot.station)
    this._settleAction = action
    if (!current()) {
      this._settleAction = null
      wx.showToast({ title: '本笔金额或台账尚未核实，请重新读取后办理', icon: 'none' })
      return
    }
    const id = row.orderId, amount = row._amountText
    const showReceipt = () => wx.showModal({
      title: '登记这笔已结清',
      // editable 的输入框用于凭据说明（转账流水号/经手人）：可留空，
      // 因为"什么时候算办完"靠的是时间与登记人，凭据说明是加分项而不是门槛。
      editable: true,
      placeholderText: '凭据说明：转账流水号 / 经手人（可留空）',
      content: '',
      confirmText: '登记结清',
      success: async (res) => {
        if (this._settleAction !== action || action.used || action.stage !== 'receipt') return
        if (!res.confirm) { this._settleAction = null; return }
        if (!current()) {
          this._settleAction = null
          wx.showToast({ title: '台账或身份已变化，请重新核对后办理', icon: 'none' })
          return
        }
        action.used = true
        wx.showLoading({ title: '处理中...' })
        try {
          await settleInterStation(id, res.content || '')
          if (!this.aftercareCurrent(snapshot)) return
          wx.showToast({ title: `已登记 ¥${amount} 结清`, icon: 'none' })
          await this.loadData()
        } catch (err) {
          if (!this.aftercareCurrent(snapshot)) return
          this.setData({ ledgerError: '本次登记结果尚未核实，请重新读取台账后再办理。' })
          // 后端会给出可读原因（"这笔钱在 X 站手上，应由该站登记结清"等），原样透出
          wx.showToast({ title: err.message || '登记失败', icon: 'none' })
        } finally {
          if (this._settleAction === action) this._settleAction = null
          if (this.aftercareCurrent(snapshot)) wx.hideLoading()
        }
      },
      complete: () => { if (!action.used && this._settleAction === action) this._settleAction = null }
    })
    wx.showModal({
      title: '核对站间结清',
      content: '订单 #' + id + '\n结清金额：¥' + amount
        + '\n付款站：' + (row.fromStationName || '水站（编号' + row.fromStationId + '）')
        + '\n收款站：' + (row.toStationName || '水站（编号' + row.toStationId + '）')
        + '\n仅登记已经实际结清的这一笔，不执行转账。核对后可补充凭据，最终确认才登记。',
      confirmText: '继续登记', cancelText: '暂不登记',
      success: res => {
        if (this._settleAction !== action || action.used || action.stage !== 'facts') return
        if (!res.confirm) { this._settleAction = null; return }
        if (!current()) {
          this._settleAction = null
          wx.showToast({ title: '台账或身份已变化，请重新核对后办理', icon: 'none' })
          return
        }
        action.stage = 'receipt'
        showReceipt()
      },
      complete: () => { if (action.stage === 'facts' && this._settleAction === action) this._settleAction = null }
    })
  },

  /** 卖出站改按挂牌价结这一单（差价由本站承担）——**是站长点，系统不会自动改**。 */
  onPriceByListed(e) {
    const id = e.currentTarget.dataset.id
    wx.showModal({
      title: '按挂牌价结这一单',
      content: '改成按挂牌价结后，接单站能拿到更多，多出来的差价由本站承担。'
        + '系统不会自动改价，也不会改动客户已付的钱。确定要改吗？',
      confirmText: '按挂牌价',
      success: async (res) => {
        if (!res.confirm) return
        wx.showLoading({ title: '处理中...' })
        try {
          await priceByListed(id)
          wx.hideLoading()
          wx.showToast({ title: '已改为按挂牌价结', icon: 'success' })
          this.loadData()
        } catch (err) {
          wx.hideLoading()
          wx.showToast({ title: err.message || '改价失败', icon: 'none' })
        }
      }
    })
  },

  /** 冲销：订单取消/退款后那笔应付不再成立（那笔钱还没真付出去时才该点）。 */
  onReverse(e) {
    const id = e.currentTarget.dataset.id
    const amount = e.currentTarget.dataset.amount
    wx.showModal({
      title: '冲销这一笔',
      content: `这一单已经取消或退款，¥${amount} 这笔站间应付不再成立。`
        + '冲销只改状态、不删记录，事后仍查得到。若钱**已经真的付给对方**了，请先与对方协商，不要直接冲销。',
      confirmText: '冲销',
      success: async (res) => {
        if (!res.confirm) return
        wx.showLoading({ title: '处理中...' })
        try {
          await reverseInterStation(id)
          wx.hideLoading()
          wx.showToast({ title: '已冲销', icon: 'success' })
          this.loadData()
        } catch (err) {
          wx.hideLoading()
          wx.showToast({ title: err.message || '冲销失败', icon: 'none' })
        }
      }
    })
  },

  aftercareSession() {
    const app = getApp(), g = app.globalData || {}, u = g.userInfo || {}
    if (!g.isLogin || !positiveId(u.staffId) || !positiveId(u.stationId) || !['STATION_MANAGER', 'manager'].includes(u.role)) return null
    return { app, epoch: this._aftercareEpoch || 0, identity: JSON.stringify([app._loginGeneration || 0, u.staffId, u.stationId, u.role, u.bindStatus]), actor: 'STAFF:' + u.staffId + ':' + u.stationId, station: u.stationId }
  },
  aftercareCurrent(snapshot) {
    const now = this.aftercareSession()
    return !this._aftercareHidden && snapshot && now && snapshot.app === now.app && snapshot.identity === now.identity && snapshot.epoch === now.epoch
  },
  onHide() { this._aftercareHidden = true; this._aftercareEpoch = (this._aftercareEpoch || 0) + 1; this._aftercareSeq = (this._aftercareSeq || 0) + 1; this._ledgerSeq = (this._ledgerSeq || 0) + 1 },
  onUnload() { this.onHide() },
  restoreAftercare() {
    const snapshot = this.aftercareSession()
    this.setData({ aftercareSelected: null, aftercareBusy: false, aftercareReady: false, aftercareItems: [], aftercareOrderId: null })
    if (!snapshot) return
    try {
      const id = wx.getStorageSync(aftercareFocus(snapshot.actor))
      if (id && !positiveId(id)) throw new Error('上次责任订单编号无法识别，请保留记录核实')
      if (id) return this.loadAftercare(id)
    } catch (err) { this.setData({ aftercareError: err.message }) }
  },
  onOpenAftercare(e) {
    if (this.data.aftercareBusy) return
    const id = e.currentTarget.dataset.id
    return this.loadAftercare(id)
  },
  onAftercareSearchInput(e) { this.setData({ aftercareSearch: e.detail.value }) },
  onAftercareSearch() {
    if (this.data.aftercareBusy) return
    const id = String(this.data.aftercareSearch || '').trim()
    if (!positiveId(id)) { this.setData({ aftercareError: '请输入原订单编号' }); return }
    return this.loadAftercare(id)
  },
  onRetryAftercareRead() { if (!this.data.aftercareBusy) return this.loadAftercare(this.data.aftercareOrderId) },
  async loadAftercare(id) {
    const snapshot = this.aftercareSession(), seq = this._aftercareSeq = (this._aftercareSeq || 0) + 1
    if (!snapshot || !positiveId(id)) return
    this.setData({ aftercareOrderId: String(id), aftercareLoading: true, aftercareReady: false, aftercareError: '', aftercareSelected: null })
    try {
      const response = await aftercareApi.getStationAftercare(id), data = response && response.data
      if (!this.aftercareCurrent(snapshot) || seq !== this._aftercareSeq) return
      if (response.code !== 0 || !data || String(data.orderId) !== String(id) || typeof data.eligible !== 'boolean' || !Array.isArray(data.items)) throw new Error('本单售后责任未能核对，请保留原记录')
      const items = data.items.map(row => {
        if (!row || typeof row.refundVerified !== 'boolean' || !positiveId(row.refundPaymentId) || !exactMoney(row.refundAmount) || Number(row.refundAmount) <= 0 || !Number.isSafeInteger(row.version) || row.version < 0
            || !['UNRESOLVED','PROPOSED','AGREED','PAID'].includes(row.status) || !row.statusText || !Array.isArray(row.history)
            || !['canPropose','canAgree','canPay'].every(k => typeof row[k] === 'boolean')
            || ![row.refundStationId,row.otherStationId].some(station => String(station) === String(snapshot.station))
            || row.status !== 'UNRESOLVED' && !exactMoney(row.amount)) throw new Error('原实退或责任金额未能核对')
        const result = Object.assign({}, row)
        for (const action of ['PROPOSE','AGREE','PAID']) {
          const pending = intents.read(snapshot.actor, 'AFTERCARE:' + row.refundPaymentId, 'AFTERCARE_' + action)
          if (!pending) continue
          const found = row.history.find(h => h.actorKey === snapshot.actor && h.requestKey === pending.idempotencyKey && h.action === action)
          if (found) {
            if (!positiveId(found.id) || found.fromVersion !== pending.expectedVersion || found.reason !== pending.reason || action !== 'AGREE' && Number(found.amount) !== pending.context.amount) throw new Error('原办理凭据内容不一致，请保留记录核实')
            intents.clear(pending)
          } else result['retry' + action] = true
        }
        return result
      })
      this._aftercareReadSession = snapshot
      this.setData({ aftercareItems: items, aftercareNote: data.note || '', aftercareReady: true })
      if (!items.some(row => row.retryPROPOSE || row.retryAGREE || row.retryPAID) && String(wx.getStorageSync(aftercareFocus(snapshot.actor))) === String(id)) wx.removeStorageSync(aftercareFocus(snapshot.actor))
    } catch (err) {
      if (this.aftercareCurrent(snapshot) && seq === this._aftercareSeq) this.setData({ aftercareError: err.message || '本单责任未能核对，请重试', aftercareReady: false })
    } finally { if (this.aftercareCurrent(snapshot) && seq === this._aftercareSeq) this.setData({ aftercareLoading: false }) }
  },
  onChooseAftercare(e) {
    if (!this.data.aftercareReady || this.data.aftercareBusy || !this.aftercareCurrent(this._aftercareReadSession)) return
    const { id, action } = e.currentTarget.dataset
    if (!['PROPOSE','AGREE','PAID'].includes(action)) return
    const row = this.data.aftercareItems.find(item => String(item.refundPaymentId) === String(id))
    if (!row) return
    try {
      const pending = intents.read(this._aftercareReadSession.actor, 'AFTERCARE:' + id, 'AFTERCARE_' + action)
      const flag = { PROPOSE:'canPropose', AGREE:'canAgree', PAID:'canPay' }[action]
      if (!pending && !row[flag]) return
      this.setData({ aftercareSelected: row, aftercareAction: action, aftercarePending: !!pending, aftercareError: '',
        aftercareAmount: pending ? pending.context.amount === null ? '' : String(pending.context.amount) : row.amount == null ? '' : String(row.amount),
        aftercareReason: pending ? pending.reason : '' })
    } catch (err) { this.setData({ aftercareError: err.message }) }
  },
  onAftercareAmount(e) { if (!this.data.aftercarePending && !this.data.aftercareBusy) this.setData({ aftercareAmount: e.detail.value }) },
  onAftercareReason(e) { if (!this.data.aftercarePending && !this.data.aftercareBusy) this.setData({ aftercareReason: e.detail.value }) },
  async onSubmitAftercare() {
    const snapshot = this._aftercareReadSession, row = this.data.aftercareSelected, action = this.data.aftercareAction
    if (this.data.aftercareBusy || !row || !this.data.aftercareReady || !this.aftercareCurrent(snapshot)) return
    let intent, prior
    this.setData({ aftercareBusy: true, aftercareError: '' })
    try {
      prior = intents.read(snapshot.actor, 'AFTERCARE:' + row.refundPaymentId, 'AFTERCARE_' + action)
      const flag = { PROPOSE:'canPropose', AGREE:'canAgree', PAID:'canPay' }[action]
      if (!flag || !prior && !row[flag]) throw new Error('当前责任状态不允许本次办理，请查原结果')
      const text = String(this.data.aftercareAmount).trim()
      if (action !== 'AGREE' && (!exactMoney(text) || Number(text) > Number(row.refundAmount) || action === 'PAID' && Number(text) <= 0)) throw new Error('请核对本笔实退范围内的责任金额')
      if (action === 'PAID' && !prior && Number(text) !== Number(row.amount)) throw new Error('实际交付金额须与双方核定金额一致')
      const reason = prior ? prior.reason : String(this.data.aftercareReason || (action === 'AGREE' ? '核对原责任方案' : action === 'PAID' ? '登记原责任款已实际交付' : '')).trim()
      intent = intents.prepare(snapshot.actor, 'AFTERCARE:' + row.refundPaymentId, 'AFTERCARE_' + action, reason, row.version, undefined, { amount: action === 'AGREE' ? null : Number(text) })
      wx.setStorageSync(aftercareFocus(snapshot.actor), String(this.data.aftercareOrderId))
      if (String(wx.getStorageSync(aftercareFocus(snapshot.actor))) !== String(this.data.aftercareOrderId)) throw new Error('原订单定位未能保存，请勿办理交付')
      this.setData({ aftercarePending: true })
      if (action === 'PAID') {
        const confirm = await new Promise(resolve => wx.showModal({ title: '仅登记已实际交付', content: '原退款 #' + row.refundPaymentId + '，原责任款 ¥' + intent.context.amount.toFixed(2) + '。仅核对已经实际交给对方的这一笔并同步原登记，不执行转账；已交过请勿再次交款。', confirmText: '仅登记', cancelText: '暂不登记', success: res => resolve(res.confirm), fail: () => resolve(false) }))
        if (!this.aftercareCurrent(snapshot)) return
        if (!confirm) { if (!prior) { intents.clear(intent); this.setData({ aftercarePending: false }) } return }
      }
      const write = { PROPOSE: aftercareApi.proposeStationAftercare, AGREE: aftercareApi.agreeStationAftercare, PAID: aftercareApi.recordStationAftercarePaid }[action]
      const response = await write(row.refundPaymentId, intents.payload(intent))
      if (!this.aftercareCurrent(snapshot)) return
      const receipt = response && response.data
      if (!response || response.code !== 0 || !receipt || !positiveId(receipt.id) || String(receipt.refundPaymentId) !== String(row.refundPaymentId) || String(receipt.orderId) !== String(this.data.aftercareOrderId) || receipt.action !== action) throw new Error('原责任办理结果待核对，请勿重复交款')
      intents.clear(intent); this.setData({ aftercareSelected: null, aftercarePending: false })
      wx.showToast({ title: action === 'PAID' ? '已核对交付登记' : '责任记录已保存', icon: 'none' })
      await this.loadAftercare(this.data.aftercareOrderId)
    } catch (err) {
      if (this.aftercareCurrent(snapshot)) {
        if (intent && action !== 'PAID' && err.businessRejected) { intents.clear(intent); this.setData({ aftercarePending: false }) }
        this.setData({ aftercareError: (err.message || '办理结果待核对') + (intent ? '；请查原记录或重试原请求，勿重复交款。' : '') })
      }
    } finally { if (this.aftercareCurrent(snapshot)) this.setData({ aftercareBusy: false }) }
  },

  onOpenOrder(e) {
    const id = e.currentTarget.dataset.id
    if (!id) return
    wx.navigateTo({ url: `/pages/order/detail?id=${id}` })
  }
})
