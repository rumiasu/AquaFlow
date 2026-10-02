const api = require('../../../api/business-rules')
const { syncPendingReminder } = require('../../../utils/pending-reminder')
const sectionLabels = { stock: '缺货待补', returns: '退桶与退款', recoveries: '返还款', barrels: '净桶交接', refusals: '拒付核实', tickets: '原款水票批次' }
Page({
  data: { loading: false, error: '', stock: [], returns: [], refusals: [], recoveries: [], barrels: [], tickets: [], stationId: null,
    ready: {}, errors: {}, counts: {}, limit: 200, focusSection: '' },
  onLoad(options) {
    const section = options && options.section
    this.setData({ focusSection: ['stock', 'returns', 'recoveries', 'barrels'].includes(section) ? section : '' })
  },
  onShow() {
    const app = getApp()
    const user = app.globalData.userInfo || {}
    if (!app.canAccessStationBusiness() || !['STATION_MANAGER', 'manager'].includes(user.role)) { app.routeByRole(true); return }
    this.setData({ stationId: user.stationId })
    return this.loadData()
  },
  async loadData() {
    const seq = this._loadSeq = (this._loadSeq || 0) + 1
    this.setData({ loading: true, error: '', ready: {}, errors: {} })
    // 2026-10-02：旧查询含已结案历史及上限，不能当日常责任清单；新清单与首页 COUNT 同源。
    const results = await Promise.allSettled([api.getWaiting(), api.getRefusals(), api.getTicketExitBatches()])
    if (seq !== this._loadSeq) return
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
      } else if (!Array.isArray(data)) { fail(keys, '读取未完成，请重试') }
      else { ready[keys[0]] = true; patch[keys[0]] = data }
    })
    // 保留上次行但明确标为未核对，隐藏办理按钮；失败时不出现“暂无待办”。
    this.setData(Object.assign(patch, { ready, errors, loading: false,
      error: Object.keys(errors).map(key => sectionLabels[key] + '：' + errors[key]).join('；') }))
    if (this.data.focusSection && ready[this.data.focusSection] && typeof wx.pageScrollTo === 'function') {
      wx.pageScrollTo({ selector: '#waiting-' + this.data.focusSection, duration: 0 })
    }
  },
  onRetry() { return this.loadData() },
  async afterAction() { await this.loadData(); await syncPendingReminder() },
  onOrder(e) { wx.navigateTo({ url: '/pages/order/detail?id=' + e.currentTarget.dataset.id }) },
  onReturns(e) {
    if (!this.data.ready.returns || this.data.loading) return
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: '/pages/station-mgmt/barrel-return/index' + (id ? '?recordId=' + encodeURIComponent(id) : '') })
  },
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
    if (action === 'freeze' && (Number(row.asset_station_id) !== Number(this.data.stationId) || row.asset_freeze_confirmed || row.paymentStatus !== 1)) return
    const descriptions = { freeze: '核实他站拒付，冻结本站退押金资格（不扣押金）', sent: '已实际交付站间返还款', received: '站间返还款已经实际收到', barrels: '确认本方实际完成桶或桶款交接', dispute: '报告桶损或交接争议', agree: '同意归属站的桶争议处理方案' }
    const item=this.data.barrels.find(b=>String(b.orderId)===String(id))
    wx.showModal({ title: descriptions[action], editable: ['sent','barrels','dispute'].includes(action), placeholderText: '实际交付凭据及差异处理说明',
      content: action==='agree' && item ? item.resolutionNote+'；桶方案 '+item.barrelMode+'，总额 ¥'+item.barrelAmount+'。同意方案后仍须确认实际交付。' : '确认必须与实际交付一致；桶交接需双方分别确认。', success: async (r) => {
        if (!r.confirm) return
        try {
          if (action === 'freeze') await api.confirmFreeze(id)
          if (action === 'sent') await api.recoverySent(id, r.content)
          if (action === 'received') await api.recoveryReceived(id)
          if (action === 'barrels') await api.barrelReceived(id, r.content)
          if (action === 'dispute') await api.disputeBarrels(id, r.content)
          if (action === 'agree') await api.agreeBarrels(id)
          await this.afterAction()
        } catch (err) { wx.showToast({ title: err.message || '操作未成功', icon: 'none' }) }
      } })
  }
})
