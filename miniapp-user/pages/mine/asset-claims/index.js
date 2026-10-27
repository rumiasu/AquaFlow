const api = require('../../../api/asset-claims')
const { getPublicStations } = require('../../../api/station')
const { stationStorage } = require('../../../utils/storage')
const { captureSession, isCurrentSession } = require('../../../utils/token')
const claim = require('../../../utils/asset-claim')
const emptyForm = () => ({ depositBalance: '', note: '', items: [] })
const copy = value => JSON.parse(JSON.stringify(value))
Page({
  data: { loading: false, ready: false, error: '', notice: '', stations: [], stationsError: '', stationId: null,
    stationName: '请选择认领水站', catalog: [], catalogReady: false, productIndex: -1, records: [],
    form: emptyForm(), pending: false, submitting: false, canCreate: false },
  onLoad(options) { this._initialStation = claim.id(options && options.stationId) },
  onShow() {
    if (this._unloaded) return
    const app = getApp(), session = captureSession(), customer = claim.id(session.customerId)
    const owner = app.globalData.isLogin && customer ? 'customer:' + customer : null
    this.setData({ needLogin: !owner })
    this._hidden = false; this._epoch = (this._epoch || 0) + 1; this._intent = null
    if (!owner) {
      this._actor = null; this._intent = null
      this.setData({ ready: false, loading: false, records: [], form: emptyForm(), pending: false, submitting: false,
        catalog: [], canCreate: false, error: '请先以客户身份登录后查看历史资产认领' }); return
    }
    if (this._actor !== owner || !this._session || !isCurrentSession(this._session)) {
      const station = stationStorage.get()
      this._stationId = this._initialStation || claim.id(station && station.id)
      this._actor = owner
      this.setData({ form: this.readDraft(this._stationId), records: [], catalog: [], stations: [], notice: '', pending: false })
    }
    this._actor = owner; this._customer = customer; this._session = session
    this.setData({ stationId: this._stationId, stationName: this._stationId ? '水站编号：' + this._stationId : '请选择认领水站', submitting: false })
    return Promise.all([this.loadStations(), this.load()])
  },
  context(read) { return { session: this._session, actor: this._actor, station: this._stationId, epoch: this._epoch, read } },
  draftKey(station) { return 'asset-claim-draft:' + this._actor + ':' + station },
  readDraft(station) {
    if (!station || !this._actor) return emptyForm()
    try { const draft = wx.getStorageSync(this.draftKey(station)); return draft && Array.isArray(draft.items) ? draft : emptyForm() }
    catch (_) { return emptyForm() }
  },
  saveDraft(form) {
    if (!this._actor || !this._stationId) return true
    try { wx.setStorageSync(this.draftKey(this._stationId), copy(form)); return true }
    catch (_) { this.setData({ notice: '草稿未能保存，切换水站前请先提交或记下填写内容。' }); return false }
  },
  current(c) { return !!c && !this._hidden && !this._unloaded && c.actor === this._actor && c.station === this._stationId
    && c.epoch === this._epoch && isCurrentSession(c.session) && (c.read == null || c.read === this._read) },
  onHide() { this._hidden = true; this._epoch = (this._epoch || 0) + 1 },
  onUnload() { this.onHide(); this._unloaded = true },
  async loadStations() {
    const c = this.context(), sequence = this._stationRead = (this._stationRead || 0) + 1
    try {
      const rows = claim.data(await getPublicStations())
      if (!this.current(c) || sequence !== this._stationRead) return
      if (!Array.isArray(rows) || rows.some(r => !r || !claim.id(r.id))) throw Error('stations')
      const stations = rows.map(r => ({ id: claim.id(r.id), name: r.name || '水站编号：' + r.id }))
      this.setData({ stations, stationsError: '', stationName: (stations.find(r => r.id === this._stationId) || {}).name
        || (this._stationId ? '水站编号：' + this._stationId : '请选择认领水站') })
    } catch (e) { if (this.current(c) && sequence === this._stationRead) this.setData({ stationsError: '可选水站暂未加载，请重试' }) }
  },
  async load() {
    if (!this._actor || !this.current(this.context())) return
    const c = this.context(this._read = (this._read || 0) + 1)
    const previousRecords = this.data.records.length > 0
    this.setData({ loading: true, ready: false, canCreate: false, error: '', catalogReady: false })
    if (!c.station) { this.setData({ loading: false, records: [], catalog: [], error: '请先选择需要认领的水站' }); return }
    try {
      this._intent = claim.read(this._actor, 'station:' + c.station)
      this.setData({ pending: !!this._intent })
      const [history, catalog] = await Promise.allSettled([api.mine(c.station), api.products(c.station)])
      if (!this.current(c)) return
      if (history.status !== 'fulfilled') throw history.reason
      let products = [], catalogError = ''
      try { if (catalog.status !== 'fulfilled') throw catalog.reason; products = claim.catalog(catalog.value) }
      catch (e) { catalogError = claim.errorText(e, '认领商品暂时无法加载，请重试或联系水站') }
      const records = claim.records(history.value, { stationId: c.station, customerId: this._customer }, products)
      if (this._intent) {
        const found = records.find(r => r.clientToken === this._intent.body.clientToken)
        if (found) {
          if (JSON.stringify(found.body) !== JSON.stringify(this._intent.body)) throw Object.assign(Error('原申请与查回清单不一致，请联系水站'), { localClaimError: true })
          claim.clear(this._intent); this._intent = null; this.saveDraft(emptyForm()); this.setData({ form: emptyForm() })
        }
      }
      const pending = !!this._intent, active = records.some(r => r.status !== 'REJECTED')
      this.setData({ ready: true, catalogReady: !catalogError, catalog: products, records, error: catalogError,
        pending, submitting: false, canCreate: !catalogError && !pending && !active })
      if (pending) this.setData({ form: { ...this._intent.body, items: this._intent.body.items.map(r => ({ ...r,
        name: (products.find(p => p.id === r.productId) || {}).name || '历史商品',
        rights: String(r.rights), held: String(r.held), tickets: String(r.tickets), unitPrice: r.unitPrice || '' })) },
        notice: '上次提交结果尚未确认，请恢复原申请。原清单和编号已保留。' })
    } catch (e) { if (this.current(c)) this.setData({ ready: false, canCreate: false,
      error: (previousRecords ? '以下为上次读取的认领记录。' : '') + claim.errorText(e, '认领记录暂时无法加载，请重试或联系水站') }) }
    finally { if (this.current(c)) this.setData({ loading: false, submitting: false }) }
  },
  onRetry() { if (!this.data.loading && !this._flight) return this.load() },
  onLogin() { wx.navigateTo({ url: '/pages/login/index' }) },
  async onStation(e) {
    if (this._flight || this.data.submitting || !this.current(this.context())) return
    const index = Number(e.detail.value), station = this.data.stations[index]
    if (!Number.isInteger(index) || !station) return
    if (!this.saveDraft(this.data.form)) return
    this._stationId = station.id; this._epoch++; this._intent = null
    this.setData({ stationId: station.id, stationName: station.name, records: [], catalog: [], form: this.readDraft(station.id), pending: false,
      notice: '', productIndex: -1, canCreate: false }); return this.load()
  },
  onProduct(e) { const n = Number(e.detail.value); if (this.data.canCreate && Number.isInteger(n) && this.data.catalog[n]) this.setData({ productIndex: n }) },
  onImageError(e) {
    const n = Number(e.currentTarget.dataset.index), catalog = this.data.catalog.slice()
    if (Number.isInteger(n) && catalog[n]) { catalog[n] = { ...catalog[n], imageUrl: '' }; this.setData({ catalog }) }
  },
  onAddProduct(e) {
    if (!this.data.canCreate || this._flight || this.data.pending) return
    const index = e && e.currentTarget && e.currentTarget.dataset.index
    const p = this.data.catalog[index == null ? this.data.productIndex : Number(index)]
    if (!p || this.data.form.items.some(r => r.productId === p.id) || this.data.form.items.length >= 100) {
      wx.showToast({ title: '请选择未添加的商品', icon: 'none' }); return
    }
    const form = { ...this.data.form, items: this.data.form.items.concat({ productId: p.id, name: p.name, spec: p.spec, category: p.category,
      rights: '0', held: '0', tickets: '0', unitPrice: '' }) }
    this.setData({ form, productIndex: -1 }); this.saveDraft(form)
  },
  onRemoveProduct(e) {
    if (!this.data.canCreate || this._flight || this.data.pending) return
    const n = Number(e.currentTarget.dataset.index)
    if (Number.isInteger(n) && n >= 0 && n < this.data.form.items.length) {
      const form = { ...this.data.form, items: this.data.form.items.filter((r, i) => i !== n) }
      this.setData({ form }); this.saveDraft(form)
    }
  },
  onField(e) {
    if (!this.data.canCreate || this.data.pending || this._flight) return
    const { field, index } = e.currentTarget.dataset, form = copy(this.data.form)
    if (index == null && ['note', 'depositBalance'].includes(field)) form[field] = e.detail.value
    else if (Number.isInteger(Number(index)) && form.items[Number(index)] && ['rights', 'held', 'tickets', 'unitPrice'].includes(field)) form.items[Number(index)][field] = e.detail.value
    else return
    this.setData({ form }); this.saveDraft(form)
  },
  async onSubmit() {
    if (!this.data.canCreate || this._flight || !this.current(this.context())) return
    try {
      const body = claim.body({ ...this.data.form, stationId: this._stationId, confirmed: true,
        clientToken: 'claim-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) })
      this._intent = claim.prepare(this._actor, 'station:' + this._stationId, 'SUBMIT', body)
      return this.send(this._intent)
    } catch (e) { this.setData({ error: claim.errorText(e, '原申请未能保存，请重试') }) }
  },
  onRecover() { if (this._intent && this._intent.actor === this._actor && this._intent.target === 'station:' + this._stationId
    && this.data.ready && !this._flight) return this.send(this._intent) },
  async send(intent) {
    const c = this.context(this._read), flight = {}; if (!this.current(c) || this._flight || intent.actor !== c.actor || intent.target !== 'station:' + c.station) return
    this._flight = flight; this.setData({ submitting: true, pending: true, canCreate: false, notice: '', error: '' })
    let verified = false
    try {
      const response = await api.submit(copy(intent.body)); if (!this.current(c)) return
      const received = claim.record(claim.data(response), { stationId: c.station, customerId: this._customer, body: intent.body }, this.data.catalog)
      verified = true
      const records = [received].concat(this.data.records.filter(r => r.id !== received.id))
      this.setData({ records }); claim.clear(intent); this._intent = null
      this.saveDraft(emptyForm())
      this.setData({ pending: false, form: emptyForm(), canCreate: this.data.catalogReady && !records.some(r => r.status !== 'REJECTED'),
        notice: received.status === 'PENDING' ? '申请已提交，待水站核对。通过后可在这里查看结果。' : '已查询到原申请结果，请查看记录。' })
    } catch (e) {
      if (!this.current(c)) return
      if (e.businessRejected && !verified) {
        try { claim.clear(intent); this._intent = null; this.setData({ pending: false, ready: false }) } catch (storageError) { e = storageError }
      }
      this.setData({ notice: claim.errorText(e, '提交结果尚未确认，请恢复原申请；原清单和编号已保留。'), canCreate: false })
    } finally { if (this._flight === flight) { this._flight = null; if (this.current(c)) this.setData({ submitting: false }) } }
  }
})
