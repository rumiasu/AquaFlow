const api = require('../../../api/asset-claims')
const { getCatalog } = require('../../../api/station-mgmt')
const { captureSession, currentSession } = require('../../../utils/request')
const claim = require('../../../utils/asset-claim')
const copy = value => JSON.parse(JSON.stringify(value))
Page({
  data: { loading: false, ready: false, error: '', notice: '', stationId: null, catalog: [], catalogReady: false,
    records: [], selected: null, reviewNote: '', pending: false, submitting: false, originalVerified: false, originalDecision: '', originalId: null },
  onShow() {
    if (this._unloaded) return
    const app = getApp(), g = app.globalData, u = g.userInfo || {}, station = claim.id(u.stationId), staff = claim.id(u.staffId)
    this._hidden = false; this._epoch = (this._epoch || 0) + 1; this._flight = null; this._verifiedOriginal = false; this._intent = null
    if (!g.isLogin || u.role !== 'STATION_MANAGER' || !station || !staff || (app.canAccessStationBusiness && !app.canAccessStationBusiness())) {
      this._actor = null; this._intent = null
      this.setData({ ready: false, loading: false, records: [], selected: null, reviewNote: '', pending: false,
        submitting: false, catalog: [], error: '请以已绑定本站的站长身份查看认领' }); return
    }
    this._session = captureSession(); this._stationId = station; this._actor = 'manager:' + staff + ':' + station
    this._target = 'reviews:' + station; this._selectedId = null
    this.setData({ stationId: station, selected: null, records: [], reviewNote: '', pending: false, submitting: false,
      originalVerified: false, notice: '' }); return this.load()
  },
  context(read) { return { session: this._session, actor: this._actor, station: this._stationId, epoch: this._epoch, read } },
  current(c) { return !!c && !this._hidden && !this._unloaded && c.actor === this._actor && c.station === this._stationId
    && c.epoch === this._epoch && currentSession(c.session) && (c.read == null || c.read === this._read) },
  onHide() { this._hidden = true; this._epoch = (this._epoch || 0) + 1; this._verifiedOriginal = false },
  onUnload() { this.onHide(); this._unloaded = true },
  async load() {
    if (!this._actor || !this.current(this.context())) return
    const c = this.context(this._read = (this._read || 0) + 1)
    this._verifiedOriginal = false
    this.setData({ loading: true, ready: false, catalogReady: false, error: '', originalVerified: false })
    try {
      this._intent = claim.read(this._actor, this._target)
      this.setData({ pending: !!this._intent, originalId: this._intent && this._intent.objectId,
        originalDecision: this._intent ? (this._intent.body.approve ? '核对通过' : '拒绝申请') : '',
        reviewNote: this._intent ? this._intent.body.note || '' : this.data.reviewNote })
      const [pending, catalog] = await Promise.allSettled([api.pending(), getCatalog()])
      if (!this.current(c)) return
      if (pending.status !== 'fulfilled') throw pending.reason
      let products = [], catalogError = ''
      try { if (catalog.status !== 'fulfilled') throw catalog.reason; products = claim.catalog(catalog.value) }
      catch (e) { catalogError = claim.errorText(e, '商品名称暂未加载，请重试后核对；原清单编号已保留') }
      const records = claim.records(pending.value, { stationId: c.station }, products)
      if (records.some(r => r.status !== 'PENDING')) throw Object.assign(Error('待核对清单状态不一致，请刷新'), { localClaimError: true })
      const selected = records.find(r => r.id === this._selectedId) || null
      this.setData({ records, catalog: products, catalogReady: !catalogError, ready: true, error: catalogError,
        selected, reviewNote: this._intent ? this._intent.body.note || '' : selected ? this.data.reviewNote : '', submitting: false })
      if (selected) await this.loadPhoto(selected.id)
      if (this._intent) await this.loadOriginal(c)
    } catch (e) { if (this.current(c)) this.setData({ ready: false, error: claim.errorText(e, '认领清单暂时无法加载，请重试') }) }
    finally { if (this.current(c)) this.setData({ loading: false, submitting: false }) }
  },
  onRetry() { if (!this.data.loading && !this._flight) return this.load() },
  onSelect(e) {
    if (!this.current(this.context()) || !this.data.ready || this.data.pending || this._flight) return
    const id = claim.id(e.currentTarget.dataset.id), selected = this.data.records.find(r => r.id === id)
    if (!selected) return
    this._selectedId = id; this.setData({ selected, reviewNote: '', notice: '' }); return this.loadPhoto(id)
  },
  async loadPhoto(id) {
    const c = this.context(this._read)
    this.setData({ photoReady: false, photoLoading: true, photoUrl: '', photoError: '', photoExists: false })
    try {
      const response = await api.photo(id), value = claim.data(response)
      if (!this.current(c) || this._selectedId !== id) return
      if (!value || typeof value.exists !== 'boolean') throw Error('photo')
      this.setData({ photoReady: true, photoExists: value.exists, photoUrl: typeof value.url === 'string' ? value.url : '',
        photoError: value.exists && !value.url ? '照片暂时无法显示，请重试。' : '' })
    } catch (_) { if (this.current(c) && this._selectedId === id) this.setData({ photoError: '照片暂时无法核实，请重试。' }) }
    finally { if (this.current(c) && this._selectedId === id) this.setData({ photoLoading: false }) }
  },
  onRetryPhoto() { if (this._selectedId && !this.data.photoLoading) return this.loadPhoto(this._selectedId) },
  onPreviewPhoto() { if (this.data.photoUrl) wx.previewImage({ urls: [this.data.photoUrl] }) },
  onPhotoError() { this.setData({ photoUrl: '', photoError: '照片暂时无法显示，请重试。' }) },
  onNote(e) { if (this.current(this.context()) && !this.data.pending && !this._flight && this.data.selected) this.setData({ reviewNote: e.detail.value }) },
  onApprove() { return this.decide(true) },
  onReject() { return this.decide(false) },
  decide(approve) {
    const selected = this.data.selected
    if (!this.current(this.context()) || this._flight || this.data.pending || !this.data.ready || !this.data.catalogReady
      || (approve && (!this.data.photoReady || this.data.photoError))
      || !selected || selected.id !== this._selectedId || selected.status !== 'PENDING'
      || !this.data.records.some(r => r.id === selected.id)) return
    try {
      const body = claim.reviewBody({ approve, note: this.data.reviewNote })
      this._intent = claim.prepare(this._actor, this._target, 'REVIEW', body, selected.id)
      return this.send(this._intent)
    } catch (e) { this.setData({ notice: claim.errorText(e, '原核对记录未保存，请重试') }) }
  },
  async send(intent) {
    const c = this.context(this._read), flight = {}; if (!this.current(c) || this._flight || intent.actor !== c.actor || intent.target !== this._target) return
    const customer = this.data.selected && this.data.selected.customerId
    this._flight = flight; this.setData({ submitting: true, pending: true, notice: '', error: '', originalId: intent.objectId,
      originalDecision: intent.body.approve ? '核对通过' : '拒绝申请' })
    let verified = false
    try {
      const response = await api.review(intent.objectId, copy(intent.body)); if (!this.current(c)) return
      const received = claim.record(claim.data(response), { id: intent.objectId, stationId: c.station, customerId: customer }, this.data.catalog)
      if (received.status === 'PENDING') throw Object.assign(Error('核对结果尚未确定，请查询原申请'), { localClaimError: true })
      verified = true; await this.showResult(received); if (!this.current(c)) return
      claim.clear(intent); this._intent = null; this._verifiedOriginal = false
      this.setData({ pending: false, originalVerified: false, notice: '已查询到原申请的核对结果，请查看清单。' })
    } catch (e) {
      if (!this.current(c)) return
      if (e.businessRejected && !verified) {
        try { claim.clear(intent); this._intent = null; this.setData({ pending: false, ready: false }) } catch (storageError) { e = storageError }
      }
      this._verifiedOriginal = false
      this.setData({ originalVerified: false, notice: claim.errorText(e, '办理结果尚未确认，请先查询原申请；原决定和说明已保留。') })
    } finally { if (this._flight === flight) { this._flight = null; if (this.current(c)) this.setData({ submitting: false }) } }
  },
  showResult(received) {
    this._selectedId = received.id
    this.setData({ selected: received, records: received.status === 'PENDING' ? this.data.records
      : this.data.records.filter(r => r.id !== received.id) })
    return this.loadPhoto(received.id)
  },
  async loadOriginal(c) {
    const intent = this._intent; if (!intent || !this.current(c)) return
    const response = await api.detail(intent.objectId); if (!this.current(c)) return
    const received = claim.record(claim.data(response), { id: intent.objectId, stationId: c.station }, this.data.catalog)
    await this.showResult(received)
    if (!this.current(c)) return
    if (received.status !== 'PENDING') {
      claim.clear(intent); this._intent = null; this._verifiedOriginal = false
      this.setData({ pending: false, originalVerified: false, notice: '原申请已有核对结果，请查看记录。' })
    } else {
      this._verifiedOriginal = true
      this.setData({ pending: true, reviewNote: intent.body.note || '', originalVerified: true,
        notice: '原申请仍待核对，可按已保存的决定和说明重试原处理。' })
    }
  },
  async onRecover() {
    if (!this._intent || this._intent.actor !== this._actor || this._intent.target !== this._target || this._flight || !this.current(this.context())) return
    const c = this.context(this._read = (this._read || 0) + 1); this._verifiedOriginal = false
    this.setData({ loading: true, originalVerified: false, error: '' })
    try { await this.loadOriginal(c) }
    catch (e) { if (this.current(c)) this.setData({ notice: claim.errorText(e, '原申请结果暂时无法核实，请重试'), originalVerified: false }) }
    finally { if (this.current(c)) this.setData({ loading: false }) }
  },
  onRetryReview() {
    if (this._intent && this._verifiedOriginal && this.data.catalogReady && this.data.selected
      && (!this._intent.body.approve || (this.data.photoReady && !this.data.photoError))
      && this.data.selected.status === 'PENDING' && !this._flight && this.current(this.context())) return this.send(this._intent)
  }
})
