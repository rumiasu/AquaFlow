const api = require('../../../api/business-rules')
const { syncPendingReminder } = require('../../../utils/pending-reminder')
const closeoutIntent = require('../../../utils/exception-action-intent')
const { positiveId, readPage } = require('../../../utils/business-history-page')
const recoveryKey = s => 'refusal-recovery:STAFF:' + s.staffId + ':' + s.stationId
const sectionLabels = { stock: '缺货待补', returns: '退桶与退款', recoveries: '返还款', barrels: '净桶交接', refusals: '拒付核实', tickets: '原款水票批次' }
Page({
  data: { loading: false, error: '', stock: [], returns: [], refusals: [], recoveries: [], barrels: [], tickets: [], stationId: null,
    ready: {}, errors: {}, counts: {}, limit: 200, focusSection: '', selectedRefusal: null, refusalAction: '',
    refusalReason: '', refusalBusy: false, pendingRefusal: false, refusalHistory: [], refusalHistoryError: '',
    refusalScope: 'ACTIVE', refusalNextBeforeId: null, refusalOrderId: null, refusalSearch: '', refusalSearchError: '', refusalLoading: false, refusalMoreError: '' },
  onLoad(options) {
    const section = options && options.section
    this.setData({ focusSection: ['stock', 'returns', 'recoveries', 'barrels'].includes(section) ? section : '' })
    if (options && positiveId(options.orderId)) this.setData({ refusalOrderId: String(options.orderId), refusalSearch: String(options.orderId) })
  },
  onShow() {
    this._refusalEpoch = (this._refusalEpoch || 0) + 1
    const app = getApp()
    const user = app.globalData.userInfo || {}
    if (!app.canAccessStationBusiness() || !['STATION_MANAGER', 'manager'].includes(user.role)) { app.routeByRole(true); return }
    const previous = this._refusalRecordsSession
    if (previous && (previous.user !== user || previous.staffId !== user.staffId || previous.stationId !== user.stationId || previous.generation !== (app._loginGeneration || 0))) {
      this.setData({ refusals: [], refusalScope: 'ACTIVE', refusalOrderId: null, refusalSearch: '', refusalNextBeforeId: null })
    }
    this.setData({ stationId: user.stationId, selectedRefusal: null, refusalBusy: false, pendingRefusal: false, refusalHistory: [], refusalHistoryError: '' })
    this._recoveryError = ''
    try {
      const id = wx.getStorageSync(recoveryKey(this.refusalSession()))
      if (id && !positiveId(id)) throw new Error('上次办理订单编号无法识别，请联系负责人核实')
      if (id) this.setData({ refusalOrderId: String(id), refusalSearch: String(id) })
    } catch (err) { this._recoveryError = err.message || '上次办理定位未能核对' }
    return this.loadData()
  },
  onHide() { this._refusalEpoch = (this._refusalEpoch || 0) + 1; this._loadSeq = (this._loadSeq || 0) + 1 },
  onUnload() { this.onHide() },
  async loadData() {
    const seq = this._loadSeq = (this._loadSeq || 0) + 1
    this._refusalReadSeq = (this._refusalReadSeq || 0) + 1
    const session = this.refusalSession(), request = this.refusalRequest()
    this.setData({ loading: true, refusalLoading: false, refusalMoreError: '', error: '', ready: {}, errors: {} })
    // 2026-10-02：旧查询含已结案历史及上限，不能当日常责任清单；新清单与首页 COUNT 同源。
    const results = await Promise.allSettled([api.getWaiting(), api.getRefusals(request), api.getTicketExitBatches()])
    if (seq !== this._loadSeq || !this.currentRefusalSession(session)) return
    const ready = {}, errors = {}, patch = {}
    const fail = (keys, message) => keys.forEach(key => { ready[key] = false; errors[key] = message || '读取失败，请重试' })
    results.forEach((r, i) => {
      const keys = i === 0 ? ['stock', 'returns', 'recoveries', 'barrels'] : [i === 1 ? 'refusals' : 'tickets']
      if (r.status !== 'fulfilled') { fail(keys, r.reason && r.reason.message); return }
      if (!r.value || r.value.code !== 0 || r.value.data == null) { fail(keys, '读取未完成，请重试'); return }
      const data = r.value.data
      if (i === 0) {
        const c = data.counts || {}
        const totals = { stock: c.waitingStock, returns: c.returnsTotal, recoveries: c.recoveriesTotal, barrels: c.barrelsTotal }
        patch.counts = totals
        patch.limit = data.limit
        keys.forEach(key => {
          if ((key !== 'stock' && data.schemaAvailable !== true) || !Array.isArray(data[key]) || totals[key] == null || !Number.isFinite(Number(totals[key]))) {
            fail([key], '暂时无法核对，请重试或联系负责人')
          } else { ready[key] = true; patch[key] = data[key] }
        })
      } else if (i === 1) {
        try {
          if (this._recoveryError) throw new Error(this._recoveryError)
          const page = readPage(r.value, request, 'order_id', session.stationId)
          patch.refusals = this.decorateRefusals(page.items, session)
          patch.refusalNextBeforeId = page.nextBeforeId
          ready.refusals = true; this._refusalRecordsSession = session
        } catch (err) { fail(['refusals'], err.message || '拒付清单未能核对，请重试') }
      } else if (!Array.isArray(data)) { fail(keys, '读取未完成，请重试') }
      else {
        ready[keys[0]] = true; patch[keys[0]] = data
      }
    })
    // 保留上次行但明确标为未核对，隐藏办理按钮；失败时不出现“暂无待办”。
    this.setData(Object.assign(patch, { ready, errors, loading: false,
      error: Object.keys(errors).map(key => sectionLabels[key] + '：' + errors[key]).join('；') }))
    if (this.data.focusSection && ready[this.data.focusSection] && typeof wx.pageScrollTo === 'function') {
      wx.pageScrollTo({ selector: '#waiting-' + this.data.focusSection, duration: 0 })
    }
  },
  onRetry() { return this.loadData() },
  refusalRequest() { return this.data.refusalOrderId ? { scope: this.data.refusalScope, orderId: this.data.refusalOrderId } : { scope: this.data.refusalScope } },
  decorateRefusals(rows, s) {
    const actor = 'STAFF:' + s.staffId + ':' + s.stationId
    return rows.map(row => Object.assign({}, row, {
      retryRevoke: !!closeoutIntent.read(actor, 'REFUSAL:' + row.order_id, 'REVOKE'),
      retryRelease: !!closeoutIntent.read(actor, 'REFUSAL:' + row.order_id, 'RELEASE')
    }))
  },
  resetRefusals(patch) {
    this.setData(Object.assign({ refusals: [], refusalNextBeforeId: null, selectedRefusal: null, refusalAction: '', refusalReason: '',
      refusalHistory: [], refusalHistoryError: '', refusalSearchError: '', refusalMoreError: '' }, patch))
  },
  onRefusalSearchInput(e) { this.setData({ refusalSearch: e.detail.value, refusalSearchError: '' }) },
  onRefusalScope(e) {
    const scope = e.currentTarget.dataset.scope
    if (!['ACTIVE','ALL'].includes(scope) || this.data.refusalBusy || this.data.pendingRefusal) return
    this.resetRefusals({ refusalScope: scope, refusalOrderId: null, refusalSearch: '' }); return this.loadData()
  },
  onRefusalSearch() {
    if (this.data.refusalBusy || this.data.pendingRefusal) return
    const id = String(this.data.refusalSearch || '').trim()
    if (!positiveId(id)) { this.setData({ refusalSearchError: '请输入有效的订单编号' }); return }
    this.resetRefusals({ refusalOrderId: id }); return this.loadData()
  },
  onClearRefusalSearch() {
    if (this.data.refusalBusy || this.data.pendingRefusal) return
    this.resetRefusals({ refusalOrderId: null, refusalSearch: '' }); return this.loadData()
  },
  async onMoreRefusals() {
    if (this.data.loading || this.data.refusalLoading || this.data.refusalBusy || !this.data.ready.refusals ||
        this.data.refusalOrderId || !this.data.refusalNextBeforeId || !this.currentRefusalSession(this._refusalRecordsSession)) return
    const s = this.refusalSession(), seq = this._refusalReadSeq = (this._refusalReadSeq || 0) + 1
    const request = { scope: this.data.refusalScope, beforeId: this.data.refusalNextBeforeId }
    const current = () => seq === this._refusalReadSeq && this.currentRefusalSession(s)
    this.setData({ refusalLoading: true, refusalMoreError: '' })
    try {
      const page = readPage(await api.getRefusals(request), request, 'order_id', s.stationId)
      if (!current()) return
      const rows = this.decorateRefusals(page.items, s)
      this.setData({ refusals: this.data.refusals.concat(rows.filter(r => !this.data.refusals.some(old => String(old.order_id) === String(r.order_id)))), refusalNextBeforeId: page.nextBeforeId })
    } catch (err) { if (current()) this.setData({ refusalMoreError: err.message || '更早拒付记录未能核对，请重试' }) }
    finally { if (current()) this.setData({ refusalLoading: false }) }
  },
  async afterAction() { await this.loadData(); await syncPendingReminder() },
  onOrder(e) { wx.navigateTo({ url: '/pages/order/detail?id=' + e.currentTarget.dataset.id }) },
  refusalSession() {
    const user = getApp().globalData.userInfo || {}
    return { user, staffId: user.staffId, stationId: user.stationId, epoch: this._refusalEpoch, generation: getApp()._loginGeneration || 0 }
  },
  currentRefusalSession(s) {
    const app = getApp(), user = app.globalData.userInfo
    return !!s && !!app.globalData.isLogin && user === s.user && user.staffId === s.staffId && user.stationId === s.stationId &&
      this._refusalEpoch === s.epoch && (app._loginGeneration || 0) === s.generation && ['STATION_MANAGER', 'manager'].includes(user.role)
  },
  async onRefusalHistory(e) {
    if (!this.data.ready.refusals || this.data.loading || this.data.refusalLoading || this.data.refusalBusy || !this.currentRefusalSession(this._refusalRecordsSession)) return
    const row = this.data.refusals.find(r => String(r.order_id) === String(e.currentTarget.dataset.id))
    if (!row) return
    const s = this.refusalSession()
    this.setData({ selectedRefusal: row, refusalAction: '', refusalHistory: [], refusalHistoryError: '' })
    try {
      const response = await api.refusalHistory(row.order_id)
      if (!this.currentRefusalSession(s) || this.data.selectedRefusal !== row) return
      if (!Array.isArray(response.data)) throw new Error('处理历史未完整加载')
      this.setData({ refusalHistory: response.data })
    } catch (err) { if (this.currentRefusalSession(s)) this.setData({ refusalHistoryError: err.message || '历史没加载出来，请重试' }) }
  },
  onOpenRefusalResolution(e) {
    if (!this.data.ready.refusals || this.data.loading || this.data.refusalLoading || this.data.refusalBusy || !this.currentRefusalSession(this._refusalRecordsSession)) return
    const { id, action } = e.currentTarget.dataset
    if (!['REVOKE', 'RELEASE'].includes(action)) return
    const row = this.data.refusals.find(r => String(r.order_id) === String(id))
    const s = this.refusalSession()
    if (!row || !s.staffId || !s.stationId || !this.currentRefusalSession(s)) return
    try {
      const pending = closeoutIntent.read('STAFF:' + s.staffId + ':' + s.stationId, 'REFUSAL:' + row.order_id, action)
      if (!pending && !(action === 'REVOKE' ? row.canRevoke : row.canRelease)) return
      this.setData({ selectedRefusal: row, refusalAction: action, refusalReason: pending ? pending.reason : '', pendingRefusal: !!pending,
        refusalHistory: [], refusalHistoryError: '' })
    } catch (err) { wx.showToast({ title: err.message, icon: 'none' }) }
  },
  onRefusalReason(e) { this.setData({ refusalReason: e.detail.value }) },
  onRetryRefusalResolution() {
    const s = this.refusalSession(), row = this.data.selectedRefusal
    try {
      const pending = closeoutIntent.read('STAFF:' + s.staffId + ':' + s.stationId, 'REFUSAL:' + row.order_id, this.data.refusalAction)
      if (!pending) return
      this.setData({ refusalReason: pending.reason }); return this.onSubmitRefusalResolution()
    } catch (err) { wx.showToast({ title: err.message, icon: 'none' }) }
  },
  async onSubmitRefusalResolution() {
    const row = this.data.selectedRefusal, action = this.data.refusalAction, s = this.refusalSession()
    if (this.data.refusalBusy || !row || !['REVOKE', 'RELEASE'].includes(action) || !this.currentRefusalSession(s) || !this.currentRefusalSession(this._refusalRecordsSession)) return
    this.setData({ refusalBusy: true })
    let intent
    try {
      intent = closeoutIntent.prepare('STAFF:' + s.staffId + ':' + s.stationId, 'REFUSAL:' + row.order_id, action, this.data.refusalReason, row.version)
      this.setData({ pendingRefusal: true })
      wx.setStorageSync(recoveryKey(s), String(row.order_id))
      const response = await (action === 'REVOKE' ? api.revokeRefusal : api.releaseRefusalFreeze)(row.order_id, closeoutIntent.payload(intent))
      if (!this.currentRefusalSession(s)) return
      if (!response.data || !response.data.id || Number(response.data.orderId) !== Number(row.order_id) || response.data.action !== action) throw new Error('办理结果未确认，请原样重试')
      closeoutIntent.clear(intent)
      if (String(wx.getStorageSync(recoveryKey(s))) === String(row.order_id)) wx.removeStorageSync(recoveryKey(s))
      this.setData({ selectedRefusal: null, refusalAction: '', refusalReason: '', pendingRefusal: false })
      wx.showToast({ title: '处理记录已保存', icon: 'success' }); await this.afterAction()
    } catch (err) {
      if (this.currentRefusalSession(s)) {
        if (intent && err.businessRejected) {
          closeoutIntent.clear(intent)
          if (String(wx.getStorageSync(recoveryKey(s))) === String(row.order_id)) wx.removeStorageSync(recoveryKey(s))
          this.setData({ pendingRefusal: false, selectedRefusal: null }); await this.loadData()
        }
        wx.showToast({ title: err.message || '办理结果未确认，请重试', icon: 'none' })
      }
    }
    finally { if (this.currentRefusalSession(s)) this.setData({ refusalBusy: false }) }
  },
  onReturns(e) {
    if (!this.data.ready.returns || this.data.loading) return
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: '/pages/station-mgmt/barrel-return/index' + (id ? '?recordId=' + encodeURIComponent(id) : '') })
  },
  onAllReturns() { wx.navigateTo({ url: '/pages/station-mgmt/barrel-return/index' }) },
  onTicketRefund(e) {
    const batch=this.data.tickets.find(t=>String(t.paymentId)===String(e.currentTarget.dataset.id))
    if(!batch || !this.data.ready.tickets || this.data.loading)return
    wx.showModal({ title: '退本批剩余水票', content: batch.customerName+' · '+batch.productName+'。退 '+batch.remainingQty+' 张，原渠道 '+batch.paymentMethodText+'，金额 ¥'+batch.refundAmount+'。现金确认前须实际交付；微信须退款渠道成功。余额变化会拒绝本次操作，须重新确认。', confirmText:'实际退款', success:async r=>{
      if(!r.confirm)return
      try { await api.refundTickets(batch.paymentId,batch.remainingQty,batch.refundAmount); await this.afterAction() }
      catch(err){wx.showToast({title:err.message||'退款未完成',icon:'none'})}
    }})
  },
  onProposal(e) {
    const id=e.currentTarget.dataset.id
    if (!this.data.ready.barrels || this.data.loading || !this.data.barrels.some(b => String(b.orderId) === String(id) && b.nextAction === 'proposal')) return
    wx.showActionSheet({ itemList: ['补同型空桶（不折款）', '双方协商桶损或折款总金额'], success: picked => {
      const mode=picked.tapIndex===0?'RETURN_EMPTY':'SETTLE_BARREL'
      const submit = (amount) => wx.showModal({ title: '提出桶争议处理方案', editable: true, placeholderText: '桶来源、责任归属、交接安排及补偿凭据（必填）',
        content: (mode==='RETURN_EMPTY'?'补同型空桶':'桶补偿总额 ¥'+amount)+'。须履约站另行确认；不会改写客户押金及实际桶数。',
        success: async r => { if(!r.confirm)return
          try { await api.proposeBarrels(id,{barrelMode:mode,barrelAmount:amount,note:r.content}); await this.afterAction() }
          catch(err) { wx.showToast({title:err.message||'方案未提交',icon:'none'}) }
        } })
      if(mode==='RETURN_EMPTY')submit(0)
      else wx.showModal({title:'桶补偿总金额',editable:true,placeholderText:'金额（元，最多两位小数）',content:'金额只属于本次桶争议，不包含水费、服务报酬和客户押金。',success:r=>{
        if(!r.confirm)return
        if(!/^\d+(\.\d{1,2})?$/.test(r.content||'')){wx.showToast({title:'金额不合法',icon:'none'});return}
        submit(Number(r.content))
      }})
    } })
  },
  async action(e) {
    const { action, id } = e.currentTarget.dataset
    const kind = action === 'freeze' ? 'refusals' : ['sent', 'received'].includes(action) ? 'recoveries' : 'barrels'
    const row = this.data[kind].find(b => String(b.orderId || b.order_id) === String(id))
    if (!row || !this.data.ready[kind] || this.data.loading) return
    if (kind !== 'refusals' && action !== 'dispute' && row.nextAction !== action) return
    const refusalSession = kind === 'refusals' ? this.refusalSession() : null, loadSeq = this._loadSeq
    if (action === 'freeze' && (!row.canFreeze || this.data.refusalLoading || this.data.refusalBusy || !this.currentRefusalSession(this._refusalRecordsSession))) return
    if (kind === 'refusals') this.setData({ refusalBusy: true })
    const current = () => !refusalSession || this.currentRefusalSession(refusalSession) && loadSeq === this._loadSeq
    const release = () => { if (refusalSession && this.currentRefusalSession(refusalSession)) this.setData({ refusalBusy: false }) }
    const descriptions = { freeze: '核实他站拒付，冻结本站退押金资格（不扣押金）', sent: '已实际交付站间返还款', received: '站间返还款已经实际收到', barrels: '确认本方实际完成桶或桶款交接', dispute: '报告桶损或交接争议', agree: '同意归属站的桶争议处理方案' }
    const item=this.data.barrels.find(b=>String(b.orderId)===String(id))
    wx.showModal({ title: descriptions[action], editable: ['sent','barrels','dispute'].includes(action), placeholderText: '实际交付凭据及差异处理说明',
      content: action==='agree' && item ? item.resolutionNote+'；桶方案 '+item.barrelMode+'，总额 ¥'+item.barrelAmount+'。同意方案后仍须确认实际交付。' : '确认必须与实际交付一致；桶交接需双方分别确认。', success: async (r) => {
        if (!r.confirm || !current()) { release(); return }
        try {
          if (action === 'freeze') await api.confirmFreeze(id)
          if (action === 'sent') await api.recoverySent(id, r.content)
          if (action === 'received') await api.recoveryReceived(id)
          if (action === 'barrels') await api.barrelReceived(id, r.content)
          if (action === 'dispute') await api.disputeBarrels(id, r.content)
          if (action === 'agree') await api.agreeBarrels(id)
          if (current()) await this.afterAction()
        } catch (err) { if (current()) wx.showToast({ title: err.message || '操作未成功', icon: 'none' }) }
        finally { release() }
      }, fail: release })
  }
})
