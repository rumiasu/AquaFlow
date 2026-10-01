const api = require('../../../api/business-rules')
Page({
  data: { loading: false, error: '', stock: [], returns: [], refusals: [], recoveries: [], barrels: [], tickets: [], stationId: null },
  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) { app.routeByRole(true); return }
    this.setData({ stationId: app.globalData.userInfo.stationId })
    this.loadData()
  },
  async loadData() {
    this.setData({ loading: true, error: '' })
    const results = await Promise.allSettled([api.getWaiting(), api.getRefusals(), api.getRecoveries(), api.getBarrelBalances(), api.getTicketExitBatches()])
    const errors = []
    const keys = [null, 'refusals', 'recoveries', 'barrels', 'tickets']
    results.forEach((r, i) => {
      if (r.status !== 'fulfilled') { errors.push((r.reason && r.reason.message) || '待办加载失败'); return }
      const data = r.value.data
      if (i === 0) this.setData({ stock: data.stock || [], returns: data.returns || [] })
      else this.setData({ [keys[i]]: data || [] })
    })
    this.setData({ loading: false, error: errors.join('；') })
  },
  onOrder(e) { wx.navigateTo({ url: '/pages/order/detail?id=' + e.currentTarget.dataset.id }) },
  onReturns() { wx.navigateTo({ url: '/pages/station-mgmt/barrel-return/index' }) },
  onTicketRefund(e) {
    const batch=this.data.tickets.find(t=>String(t.paymentId)===String(e.currentTarget.dataset.id))
    if(!batch)return
    wx.showModal({ title: '退本批剩余水票', content: batch.customerName+' · '+batch.productName+'。退 '+batch.remainingQty+' 张，原渠道 '+batch.paymentMethodText+'，金额 ¥'+batch.refundAmount+'。现金确认前须实际交付；微信须退款渠道成功。余额变化会拒绝本次操作，须重新确认。', confirmText:'实际退款', success:async r=>{
      if(!r.confirm)return
      try { await api.refundTickets(batch.paymentId,batch.remainingQty,batch.refundAmount); await this.loadData() }
      catch(err){wx.showToast({title:err.message||'退款未完成',icon:'none'})}
    }})
  },
  onProposal(e) {
    const id=e.currentTarget.dataset.id
    wx.showActionSheet({ itemList: ['补同型空桶（不折款）', '双方协商桶损或折款总金额'], success: picked => {
      const mode=picked.tapIndex===0?'RETURN_EMPTY':'SETTLE_BARREL'
      const submit = (amount) => wx.showModal({ title: '提出桶争议处理方案', editable: true, placeholderText: '桶来源、责任归属、交接安排及补偿凭据（必填）',
        content: (mode==='RETURN_EMPTY'?'补同型空桶':'桶补偿总额 ¥'+amount)+'。须履约站另行确认；不会改写客户押金及实际桶数。',
        success: async r => { if(!r.confirm)return
          try { await api.proposeBarrels(id,{barrelMode:mode,barrelAmount:amount,note:r.content}); await this.loadData() }
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
    const descriptions = { freeze: '核实他站拒付，冻结本站退押金资格（不扣押金）', sent: '已实际交付站间返还款', received: '站间返还款已经实际收到', barrels: '确认本方实际完成桶或桶款交接', dispute: '报告桶损或交接争议', agree: '同意归属站的桶争议处理方案' }
    const item=this.data.barrels.find(b=>b.orderId===id)
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
          await this.loadData()
        } catch (err) { wx.showToast({ title: err.message || '操作未成功', icon: 'none' }) }
      } })
  }
})
