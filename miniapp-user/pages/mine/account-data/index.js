const api = require('../../../api/account-data-requests')
const { captureSession, isCurrentSession } = require('../../../utils/token')
Page({
  data: { options: null, loading: false, error: '', items: [], nextBeforeId: null, selectedIndex: -1,
    selectedLabel: '请选择请求类型', note: '', submitting: false, pending: false, submitNotice: '', detail: null, check: null },
  onShow() {
    if (this._unloaded) return
    if (this._session && !isCurrentSession(this._session)) {
      this._submission = null; this._submissionFlight = null
      this.setData({ selectedIndex: -1, selectedLabel: '请选择请求类型' })
    }
    this._session = captureSession(); this._hidden = false; this._epoch = (this._epoch || 0) + 1
    this.setData({ pending: !!this._submission, submitting: !!this._submissionFlight, note: '', submitNotice: '' }); this.load()
  },
  onHide() { this._hidden = true; this._epoch = (this._epoch || 0) + 1 },
  onUnload() { this._unloaded = true; this._epoch = (this._epoch || 0) + 1 },
  current(epoch) { return !this._unloaded && !this._hidden && epoch === this._epoch && isCurrentSession(this._session) },
  async load() {
    const epoch = this._epoch
    this.setData({ loading: true, error: '', options: null, items: [], detail: null, check: null })
    try {
      const res = await api.options()
      if (!this.current(epoch)) return
      if (!res || !res.data || typeof res.data.intakeEnabled !== 'boolean' || !Array.isArray(res.data.requestTypes)) throw Error('请求设置暂时无法核实')
      this.setData({ options: res.data })
      await this.loadList(false, epoch)
    } catch (error) { if (this.current(epoch)) this.setData({ error: '资料请求信息暂时无法加载，请重试。' }) }
    finally { if (this.current(epoch)) this.setData({ loading: false }) }
  },
  onRetry() { if (!this.data.loading) this.load() },
  async loadList(more, epoch) {
    const res = await api.mine(more ? this.data.nextBeforeId : undefined)
    if (!this.current(epoch)) return
    if (!res || !res.data || !Array.isArray(res.data.items)) throw Error('申请记录暂时无法加载')
    this.setData({ items: more ? this.data.items.concat(res.data.items) : res.data.items, nextBeforeId: res.data.nextBeforeId })
  },
  async onMore() {
    if (this.data.loading || !this.data.nextBeforeId) return
    const epoch = this._epoch; this.setData({ loading: true })
    try { await this.loadList(true, epoch) } catch (error) { if (this.current(epoch)) this.setData({ error: '申请记录暂时无法加载，请重试。' }) }
    finally { if (this.current(epoch)) this.setData({ loading: false }) }
  },
  onType(e) {
    if (this.data.pending || this.data.submitting) return
    const index = Number(e.detail.value), type = this.data.options && this.data.options.requestTypes[index]
    if (type) this.setData({ selectedIndex: index, selectedLabel: type.label })
  },
  onNote(e) { if (!this.data.pending && !this.data.submitting) this.setData({ note: e.detail.value }) },
  async onSubmit() {
    if (!this.current(this._epoch) || this.data.submitting || !this.data.options || !this.data.options.intakeEnabled) return
    const type = this.data.options.requestTypes[this.data.selectedIndex]
    if (!this._submission && !type) { wx.showToast({ title: '请选择请求类型', icon: 'none' }); return }
    const payload = this._submission || { requestType: type.value, note: this.data.note.trim(),
      idempotencyKey: 'account-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) }
    this._submission = payload
    const flight = this._submissionFlight = {}
    const epoch = this._epoch
    this.setData({ submitting: true, pending: true, submitNotice: '' })
    try {
      const res = await api.submit(payload)
      if (!this.current(epoch)) return
      if (!res || !res.data || !res.data.id) throw Error('登记结果待核实')
      this._submission = null
      this.setData({ pending: false, note: '', selectedIndex: -1, selectedLabel: '请选择请求类型', submitNotice: res.data.notice,
        detail: { request: res.data, closureCheck: null, checkNotice: '' } })
      try { await this.loadList(false, epoch) } catch (error) { if (this.current(epoch)) this.setData({ error: '申请已登记，列表暂时无法刷新，可重试查询。' }) }
    } catch (error) { if (this.current(epoch)) this.setData({ submitNotice: '登记结果尚未确认；重试将使用原提交编号和原申请内容。' }) }
    finally {
      if (this._submissionFlight === flight) {
        this._submissionFlight = null
        if (!this._unloaded && !this._hidden && isCurrentSession(this._session)) this.setData({ submitting: false })
      }
    }
  },
  async onDetail(e) {
    if (this.data.loading) return
    const id = e.currentTarget.dataset.id, epoch = this._epoch; this.setData({ loading: true })
    try { const res = await api.detail(id); if (this.current(epoch)) this.setData({ detail: res.data }) }
    catch (error) { if (this.current(epoch)) this.setData({ error: '申请详情暂时无法加载，请重试。' }) }
    finally { if (this.current(epoch)) this.setData({ loading: false }) }
  },
  async onCheck() {
    if (this.data.loading || !this.data.options || !this.data.options.closureCheckSupported || typeof api.closureCheck !== 'function') return
    const epoch = this._epoch; this.setData({ loading: true, check: null })
    try { const res = await api.closureCheck(); if (this.current(epoch)) this.setData({ check: res.data }) }
    catch (error) { if (this.current(epoch)) this.setData({ error: '注销前检查未完成，请重试。' }) }
    finally { if (this.current(epoch)) this.setData({ loading: false }) }
  }
})
