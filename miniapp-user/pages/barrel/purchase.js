const { getStationProducts } = require('../../api/product')
const { quoteBarrelRight, purchaseBarrelRight, getBarrelRightPurchases, withdrawBarrelRightPurchase } = require('../../api/barrel')
const { resolveStationId } = require('../../utils/station')
const { stationStorage } = require('../../utils/storage')
const { getAssetStation } = require('../../api/asset-stations')
const { getCustomerId, captureSession, isCurrentSession } = require('../../utils/token')
const intents = require('../../utils/independent-purchase-intent')

Page({
  data: { stationId: null, stationName: '', products: [], index: 0, quantity: 1,
    quote: null, purchases: [], paymentMethod: 2, busy: false, error: '',
    productsLoading: false, productsLoaded: false, productsError: '',
    quoteLoading: false, quoteError: '', purchasesLoading: false,
    purchasesLoaded: false, purchasesError: '', withdrawingId: null,
    recoveryEntries: [], recoveryError: '', recoveryHint: '', recoveryBusy: false,
    originalActive: false, safetyBlocked: false, canAnother: false, fromOrder: false,
    onlinePayEnabled: false, onlinePayLabel: '', onlinePayHint: '' },
  syncRecovery() {
    let session
    try { session = captureSession() }
    catch (error) {
      this.setData({ recoveryError: '原购买暂时无法读取，请稍后重试；暂不能另买', safetyBlocked: true, canAnother: false })
      return { error, session: null }
    }
    if (this._viewEpoch !== session.epoch) {
      this._viewEpoch = session.epoch
      for (const field of ['_submitSeq', '_recoverySeq', '_anotherSeq', '_quoteSeq', '_purchasesSeq', '_productsSeq']) this[field] = (this[field] || 0) + 1
      this.setData({ busy: false, recoveryBusy: false, quote: null, quoteLoading: false,
        onlinePayEnabled: false, onlinePayLabel: '', onlinePayHint: '', recoveryEntries: [], recoveryError: '', recoveryHint: '',
        productsLoading: false, purchases: [], purchasesLoading: false, purchasesLoaded: false, purchasesError: '' })
    }
    try {
      const state = intents.load(getCustomerId()), registry = state.registry
      const active = registry.entries.find(entry => entry.body.idempotencyKey === registry.activeKey)
      this.setData({ recoveryEntries: registry.entries.map(entry => ({ key: entry.body.idempotencyKey,
        summary: '原水站 ' + entry.body.stationId + ' · 商品 ' + entry.body.productId + ' · ' + entry.body.quantity + ' 份',
        active: entry.body.idempotencyKey === registry.activeKey, known: !!entry.record,
        record: entry.record, paymentMethod: entry.body.paymentMethod })),
      recoveryError: '', recoveryHint: state.dirty ? '原购买仍保留，请可靠保存后恢复原请求。' : '',
      originalActive: !!active, safetyBlocked: false,
      canAnother: !!active && !!active.record && registry.entries.every(entry => !!entry.record) })
      return { state, session }
    } catch (error) {
      this.setData({ recoveryError: error.message || '原购买暂时无法读取，请联系水站核实',
        originalActive: false, safetyBlocked: true, canAnother: false })
      return { error, session }
    }
  },
  onShow() {
    const previous = this._viewEpoch
    this.syncRecovery()
    if (previous !== undefined && previous !== this._viewEpoch && this.data.stationId) return this.refresh()
  },
  async onLoad(options) {
    const { session } = this.syncRecovery()
    this.initialProductId = Number(options.productId) || null
    this.setData({ quantity: Math.max(1, Math.min(1000, Number(options.quantity) || 1)), fromOrder: options.from === 'order' })
    try {
      const stationId = Number(options.stationId) || await resolveStationId()
      if (!isCurrentSession(session)) return
      if (!stationId) throw new Error('请先选择水站')
      const station = stationStorage.get()
      this.setData({ stationId, stationName: station && Number(station.id) === stationId ? station.name || '' : '水站 #' + stationId })
      getAssetStation(stationId).then(res => {
        if (isCurrentSession(session) && this.data.stationId === stationId && res && res.code === 0 && res.data)
          this.setData({ stationName: res.data.name })
      }).catch(() => { /* 历史站名未取得时显示站号，原款记录仍可独立读取。 */ })
      // [2026-10-02 F-55] 原来历史绑在商品/报价成功后，商品下架或报价失败便查不到原款。
      // 两条读取独立启动；撤回和历史恢复都不能以当前商品在售为前提。
      await Promise.all([this.loadProducts(), this.loadPurchases()])
    } catch (err) { if (isCurrentSession(session)) this.setData({ error: err.message || '加载失败' }) }
  },
  async loadProducts() {
    const { session } = this.syncRecovery()
    if (!this.data.stationId || this.data.busy || this.data.productsLoading) return
    const seq = this._productsSeq = (this._productsSeq || 0) + 1
    this._quoteSeq = (this._quoteSeq || 0) + 1
    this.setData({ productsLoading: true, productsLoaded: false, productsError: '', quote: null,
      quoteLoading: false, quoteError: '' })
    try {
      const res = await getStationProducts(this.data.stationId)
      if (!isCurrentSession(session) || seq !== this._productsSeq) return
      const products = (res.data || []).filter(p => p.category === 1)
      const selected = this.data.products[this.data.index]
      const productId = selected ? selected.id : this.initialProductId
      const index = Math.max(0, products.findIndex(p => p.id === productId))
      this.setData({ products, index, productsLoaded: true })
    } catch (err) {
      if (isCurrentSession(session) && seq === this._productsSeq) this.setData({ productsError: err.message || '商品加载失败，请重试' })
    } finally { if (isCurrentSession(session) && seq === this._productsSeq) this.setData({ productsLoading: false }) }
    if (isCurrentSession(session) && seq === this._productsSeq && this.data.productsLoaded) await this.refreshQuote()
  },
  async refresh() {
    await Promise.all([this.refreshQuote(), this.loadPurchases()])
  },
  async refreshQuote() {
    const session = captureSession()
    const product = this.data.products[this.data.index]
    const seq = this._quoteSeq = (this._quoteSeq || 0) + 1
    this.setData({ quote: null, quoteError: '', quoteLoading: false, onlinePayEnabled: false, onlinePayLabel: '', onlinePayHint: '' })
    if (!product || !this.data.productsLoaded || this.data.productsError || this.data.productsLoading) return
    this.setData({ quoteLoading: true })
    try {
      const res = await quoteBarrelRight(this.data.stationId, product.id, this.data.quantity)
      if (!isCurrentSession(session) || seq !== this._quoteSeq) return
      if (!res.data) throw new Error('金额暂时不可用，请重试')
      const channel = res.data.wechatPay
      const labelled = !!channel && channel.method === 1 && typeof channel.enabled === 'boolean'
        && typeof channel.simulated === 'boolean' && typeof channel.label === 'string' && !!channel.label.trim()
        && typeof res.data.onlineAvailable === 'boolean' && res.data.onlineAvailable === channel.enabled
      this.setData({ quote: res.data, stationName: res.data.stationName,
        onlinePayEnabled: res.data.onlineAvailable === true && labelled && channel.enabled === true,
        onlinePayLabel: labelled ? channel.label : '',
        onlinePayHint: !labelled ? '线上付款方式尚未核实，可重试确认金额或向水站交现金。'
          : (!channel.enabled ? channel.label : '') })
    } catch (err) {
      if (isCurrentSession(session) && seq === this._quoteSeq) this.setData({ quoteError: err.message || '金额暂时不可用，请重试' })
    } finally { if (isCurrentSession(session) && seq === this._quoteSeq) this.setData({ quoteLoading: false }) }
  },
  async loadPurchases() {
    const { session } = this.syncRecovery()
    if (!this.data.stationId) return
    const customerId = getCustomerId()
    const seq = this._purchasesSeq = (this._purchasesSeq || 0) + 1
    if (customerId !== this._purchasesCustomerId) {
      this._purchasesCustomerId = customerId
      this.setData({ purchases: [], purchasesLoaded: false })
    }
    this.setData({ purchasesLoading: true, purchasesError: '' })
    try {
      if (!customerId) throw new Error('请先登录后查看购买记录')
      const records = await getBarrelRightPurchases(this.data.stationId)
      if (!isCurrentSession(session)) {
        if (this._viewEpoch === session.epoch) { this.syncRecovery(); this.setData({ purchasesError: '登录身份已变化，请重新加载购买记录' }) }
        return
      }
      if (seq !== this._purchasesSeq) return
      if (customerId !== getCustomerId()) throw new Error('登录身份已变化，请重新加载购买记录')
      this.setData({ purchases: records.data || [], purchasesLoaded: true })
    } catch (err) {
      if (isCurrentSession(session) && seq === this._purchasesSeq) {
        const changed = customerId !== getCustomerId()
        this.setData({ purchasesError: changed ? '登录身份已变化，请重新加载购买记录' : (err.message || '购买记录加载失败，请重试'),
          ...(changed ? { purchases: [], purchasesLoaded: false } : {}) })
      }
    } finally { if (isCurrentSession(session) && seq === this._purchasesSeq) this.setData({ purchasesLoading: false }) }
  },
  mayEdit() {
    this.syncRecovery()
    if (this.data.originalActive || this.data.safetyBlocked || this.data.busy || this.data.recoveryBusy) {
      wx.showToast({ title: '请先核实原购买；另买需明确选择', icon: 'none' }); return false
    }
    return true
  },
  onProduct(e) { if (!this.mayEdit()) return; this.setData({ index: Number(e.detail.value) }); return this.refreshQuote() },
  onQuantity(e) { if (!this.mayEdit()) return; this.setData({ quantity: Math.max(1, Math.min(1000, parseInt(e.detail.value) || 1)) }); return this.refreshQuote() },
  onMethod(e) {
    if (!this.mayEdit()) return
    const method = Number(e.currentTarget.dataset.method)
    if (method === 1 && (!this.data.onlinePayEnabled || this.data.quoteLoading)) {
      wx.showToast({ title: '线上付款方式尚未确认或不可用', icon: 'none' }); return
    }
    if (method === 1 || method === 2) this.setData({ paymentMethod: method })
  },
  onWithdraw(e) {
    if (this.data.busy || this.data.withdrawingId || this._withdrawPromptOpen || this.data.purchasesLoading || this.data.purchasesError) return
    const id = e.currentTarget.dataset.id
    const customerId = getCustomerId()
    const record = this.data.purchases.find(p => String(p.id) === String(id))
    if (!customerId || customerId !== this._purchasesCustomerId || !record || record.status !== 'PENDING') {
      wx.showToast({ title: '请重新加载购买记录后核实状态', icon: 'none' })
      return
    }
    this._withdrawPromptOpen = true
    wx.showModal({ title: '撤回未付款的押金购买', content: '已交钱但尚未确认的，请先联系水站核实收款；已确认押金须走退还申请。', success: async r => {
      this._withdrawPromptOpen = false
      if (!r.confirm) return
      const current = this.data.purchases.find(p => String(p.id) === String(id))
      // 弹窗期间可能换身份或刷新到已收款状态，不能拿旧画面继续撤回；最终判权仍由服务端执行。
      if (customerId !== getCustomerId() || customerId !== this._purchasesCustomerId || this.data.purchasesLoading || this.data.purchasesError || !current || current.status !== 'PENDING') {
        wx.showToast({ title: '身份或购买状态已变化，请重新加载记录', icon: 'none' })
        return
      }
      this.setData({ withdrawingId: id })
      try { await withdrawBarrelRightPurchase(id); await this.loadPurchases() }
      catch (err) { wx.showToast({ title: err.message || '撤回失败', icon: 'none' }) }
      finally { this.setData({ withdrawingId: null }) }
    }, fail: () => { this._withdrawPromptOpen = false } })
  },
  async onPurchase() {
    const context = this.syncRecovery()
    if (context.error) {
      if (context.session && !context.session.customerId) wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }
    if (!getCustomerId()) { wx.showToast({ title: '请先登录', icon: 'none' }); return }
    if (this.data.busy || this.data.recoveryBusy) return
    const { session } = context
    let state = context.state, registry = state.registry
    const original = registry.entries.find(entry => entry.body.idempotencyKey === registry.activeKey)
    // 2026-10-02 F-77：改表单曾换key覆盖未知购买；恢复只用已保留的完整body，与在售/报价无关。
    if (original) {
      if (original.record && original.record.status !== 'PENDING') return this.onQueryOriginal({ currentTarget: { dataset: { key: original.body.idempotencyKey } } })
      return this.submitOriginal(state, original.body, session, registry)
    }
    if (registry.entries.some(entry => !entry.record) || !this.data.quote || this.data.quoteLoading
      || !this.data.productsLoaded || this.data.productsLoading || this.data.productsError) return
    const product = this.data.products[this.data.index]
    if (!product) return
    // 仅拦新的在线购买；未知原请求仍按原 body/key 恢复，不受现价/现渠道展示影响。
    if (this.data.paymentMethod === 1 && !this.data.onlinePayEnabled) {
      wx.showToast({ title: '请先核实线上付款方式', icon: 'none' }); return
    }
    const draft = { stationId: this.data.stationId, productId: product.id, quantity: this.data.quantity,
      paymentMethod: this.data.paymentMethod }
    if (registry.entries.length) {
      // 完整缓存也不能证明原款仍可查回；重启后“另买”状态须再次核实每份保留原件。
      const seq = this._recoverySeq = (this._recoverySeq || 0) + 1
      this.setData({ recoveryBusy: true })
      try {
        const next = intents.clone(registry)
        for (const entry of next.entries) {
          const response = await getBarrelRightPurchases(entry.body.stationId)
          if (!isCurrentSession(session) || seq !== this._recoverySeq) return
          const record = intents.queryRecord(response, entry.body, state.customerId)
          if (!record) {
            this.rememberRecord(entry.body, null, state.customerId)
            throw new Error('保留的原购买尚未查回，暂不能另买；请核实原请求')
          }
          entry.record = intents.mergeRecord(entry.record, record, true)
        }
        const latest = intents.load(state.customerId)
        if (!intents.same(latest.registry, registry)) throw new Error('购买凭据已变化，请重新核实原购买')
        state = intents.save(latest, next); registry = state.registry
      } catch (error) {
        if (isCurrentSession(session) && seq === this._recoverySeq) {
          this.syncRecovery(); this.setData({ recoveryError: error.message || '原购买暂时无法核实，暂不能另买', canAnother: false })
        }
        return
      } finally { if (isCurrentSession(session) && seq === this._recoverySeq) this.setData({ recoveryBusy: false }) }
    }
    if (!isCurrentSession(session)) return
    this._keySequence = (this._keySequence || 0) + 1
    const key = 'br-' + Date.now() + '-' + this._keySequence + '-' + Math.random().toString(36).slice(2)
    const body = { ...draft, idempotencyKey: key }
    if (!intents.validBody(body)) { wx.showToast({ title: '请核实水站、商品、数量和付款方式', icon: 'none' }); return }
    const next = intents.clone(registry)
    next.entries.push({ body, record: null }); next.activeKey = key
    return this.submitOriginal(state, body, session, next, false)
  },
  async submitOriginal(state, body, session, registry, recovering = true) {
    if (!isCurrentSession(session) || this.data.busy || this.data.recoveryBusy) return
    const seq = this._submitSeq = (this._submitSeq || 0) + 1
    this.setData({ busy: true })
    try {
      intents.save(state, registry)
      this.syncRecovery()
      const res = await purchaseBarrelRight(intents.clone(body))
      if (!isCurrentSession(session) || seq !== this._submitSeq) return
      const record = intents.responseRecord(res, body, state.customerId)
      this.rememberRecord(body, record, state.customerId)
      this.syncRecovery()
      const content = res.data.status === 3
        ? '原付款状态：' + (res.data.statusText || '请向水站核实') + '。原凭据仍保留，请查询当前权益及退还结果。'
        : record.status === 'CANCELLED'
          ? '原购买已撤回，恢复原请求不会重新购买。原凭据仍保留；如需购买，请明确另买一笔。'
          : record.status === 'PAID'
            ? (recovering ? '原押金已确认' : '本次桶押金已确认') + '，当前可用权益以资产页为准。若仍有待领取容量，水桶随下一次送水送达，本次新领的桶无需回空桶。押金与水款分别办理，本次仅办理桶押金，订水需另行提交。'
            : '请向该水站交付押金并索取收据。水站确认实际收款后，才可以使用这份权益买票和下单。'
      const returnToOrder = record.status === 'PAID' && res.data.status === 2 && this.canReturnToOrder(body.stationId)
      wx.showModal({
        title: recovering || res.data.status === 3 || record.status === 'CANCELLED'
          ? '原桶押金购买已查回' : (record.status === 'PAID' ? '桶押金已确认' : '桶押金待确认'),
        content, showCancel: returnToOrder, confirmText: returnToOrder ? '继续订水' : '确定',
        ...(returnToOrder ? { cancelText: '留在此页', success: result => {
          if (result.confirm && isCurrentSession(session) && this.canReturnToOrder(body.stationId)) this.onBack()
        } } : {})
      })
      if (body.stationId === this.data.stationId) await this.loadPurchases()
    } catch (err) {
      if (isCurrentSession(session) && seq === this._submitSeq) {
        this.syncRecovery(); this.setData({ recoveryError: err.message || '原购买结果尚未确认，请查询或恢复原请求', canAnother: false })
        wx.showToast({ title: err.message || '原购买结果尚未确认，请重试原请求', icon: 'none' })
      }
    } finally { if (isCurrentSession(session) && seq === this._submitSeq) this.setData({ busy: false }) }
  },
  rememberRecord(body, record, customerId) {
    const state = intents.load(customerId), next = intents.clone(state.registry)
    const entry = next.entries.find(item => item.body.idempotencyKey === body.idempotencyKey)
    if (!entry || !intents.same(entry.body, body)) throw new Error('原购买凭据已变化，请重新核实')
    // 查不到只表示本次未核实；终态不能被另一页迟到的待确认/矛盾结果倒滚。
    entry.record = intents.mergeRecord(entry.record, record, true)
    if (!record && !next.activeKey) next.activeKey = body.idempotencyKey
    return intents.save(state, next)
  },
  originalFor(context, event) {
    if (!context.state) return null
    const key = event && event.currentTarget && event.currentTarget.dataset.key || context.state.registry.activeKey
    return context.state.registry.entries.find(entry => entry.body.idempotencyKey === key)
  },
  async onRetryOriginal(event) {
    const context = this.syncRecovery(), entry = this.originalFor(context, event)
    if (entry && entry.record && entry.record.status !== 'PENDING') return this.onQueryOriginal(event)
    if (entry) return this.submitOriginal(context.state, entry.body, context.session, context.state.registry)
  },
  async onQueryOriginal(event) {
    const context = this.syncRecovery(), entry = this.originalFor(context, event)
    if (!entry || this.data.busy || this.data.recoveryBusy) return
    const seq = this._recoverySeq = (this._recoverySeq || 0) + 1
    this.setData({ recoveryBusy: true, recoveryError: '' })
    try {
      const response = await getBarrelRightPurchases(entry.body.stationId)
      if (!isCurrentSession(context.session) || seq !== this._recoverySeq) return
      const record = intents.queryRecord(response, entry.body, context.state.customerId)
      this.rememberRecord(entry.body, record, context.state.customerId); this.syncRecovery()
      this.setData({ recoveryHint: record ? '已查回原购买，请按原记录办理；另买需明确选择。'
        : '暂未查到原购买，不代表没有提交。原凭据已保留，请稍后查询或联系原水站核实。',
        ...(!record ? { canAnother: false } : {}) })
    } catch (error) {
      if (isCurrentSession(context.session) && seq === this._recoverySeq) {
        this.syncRecovery(); this.setData({ recoveryError: error.message || '原购买查询失败，请稍后重试', canAnother: false })
      }
    } finally { if (isCurrentSession(context.session) && seq === this._recoverySeq) this.setData({ recoveryBusy: false }) }
  },
  async onAnotherPurchase() {
    const context = this.syncRecovery(), entry = this.originalFor(context)
    if (!entry || !this.data.canAnother || this.data.busy || this.data.recoveryBusy) return
    const seq = this._anotherSeq = (this._anotherSeq || 0) + 1
    const confirm = await new Promise(resolve => wx.showModal({ title: '另买一笔桶押金',
      content: '原购买仍会保留，新购买将另行登记。已交款但尚未确认的，请先联系原水站核实。',
      confirmText: '另买', cancelText: '返回', success: result => resolve(result.confirm), fail: () => resolve(false) }))
    if (!confirm || !isCurrentSession(context.session) || seq !== this._anotherSeq || this.data.busy || this.data.recoveryBusy) return
    this.setData({ recoveryBusy: true })
    try {
      const response = await getBarrelRightPurchases(entry.body.stationId)
      if (!isCurrentSession(context.session) || seq !== this._anotherSeq) return
      const record = intents.queryRecord(response, entry.body, context.state.customerId)
      if (!record) {
        this.rememberRecord(entry.body, null, context.state.customerId)
        throw new Error('原购买尚未查回，暂不能另买；请查询或恢复原请求')
      }
      const state = intents.load(context.state.customerId), next = intents.clone(state.registry)
      const current = next.entries.find(item => item.body.idempotencyKey === entry.body.idempotencyKey)
      if (next.activeKey !== entry.body.idempotencyKey || !current || !intents.same(current.body, entry.body)
        || next.entries.some(item => item.body.idempotencyKey !== entry.body.idempotencyKey && !item.record)) throw new Error('原购买状态已变化，请重新核实')
      current.record = intents.mergeRecord(current.record, record, true); next.activeKey = null
      intents.save(state, next); this.syncRecovery()
      this.setData({ recoveryHint: '原购买凭据仍保留在下方；请明确选择本次新购买的内容。' })
    } catch (error) {
      if (isCurrentSession(context.session) && seq === this._anotherSeq) {
        this.syncRecovery(); this.setData({ recoveryError: error.message || '暂不能另买，请核实原购买', canAnother: false })
      }
    } finally { if (isCurrentSession(context.session) && seq === this._anotherSeq) this.setData({ recoveryBusy: false }) }
  },
  canReturnToOrder(stationId) {
    const pages = getCurrentPages(), previous = pages[pages.length - 2]
    return this.data.fromOrder && previous && previous.route === 'pages/order/create'
      && Number(previous.data.stationId) === Number(stationId)
  },
  onBack() { wx.navigateBack() }
})
