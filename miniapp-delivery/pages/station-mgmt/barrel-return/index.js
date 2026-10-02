const { getAllBarrelRecords, updateBarrelRecordStatus, markRefundPaid, getRefundUndelivered, approveBarrelReturn, confirmPayment } = require('../../../api/station-mgmt')
const businessRules = require('../../../api/business-rules')
const { getPendingReturnRecord, syncPendingReminder } = require('../../../utils/pending-reminder')

/**
 * 退桶审批页（站长端）。
 *
 * <p>[2026-09-27 v66] 第 3 步（2 → 3）的语义已收窄为**「退押金并当面交付」**：
 * 押金核销与"钱交到顾客手上"在同一次点击里完成，后端把两件事写在同一条记录里。
 * 所以这里的按钮不再叫「退押金」，提交前必须先选退款方式、并**当面把钱交给顾客**再确认
 * （产品口径"不现场给钱的不要退"，正本 `docs/design/35-退押金实际交付-决策件.md` §7.2）。
 * 线上原路退回由后端判定：微信退款通道未接入时它会明确拒绝并把原因回给我们，
 * 页面**不做**客户端拦截 —— 否则站长看不到"为什么不能线上退"。</p>
 *
 * <p>⚠️ 路径常量一律取自 `config/api.js`（那条表是路径的 SSOT），包装函数在
 * `api/station-mgmt.js` —— 页面里**不要**再写 `/api/...` 字面量：散落的字符串不会出现在
 * 任何一张表里，端点改名时改不干净，而且审计脚本会把它们当成"死端点"看不出来源。</p>
 */

// 与后端 BarrelRefundDTO.CHANNEL_* 同名同值；枚举取值以那一边为准，这里只是传参
const CHANNEL_CASH = 'CASH'
const CHANNEL_ONLINE = 'ONLINE'

Page({
  onRefundPickupFee(e) {
    wx.showModal({ title: '单独退收桶服务费', content: '请先向客户实际交付退款，再确认。本操作不退押金，也不抹去已经收桶的事实。未交接申请可在退服务费后撤回。', success: async r => {
      if (!r.confirm) return
      try { await businessRules.refundService(e.currentTarget.dataset.id, '站长确认实际退还收桶服务费'); await this.loadData() }
      catch (err) { wx.showModal({ title: '退款未成功', content: err.message || '请核实原款', showCancel: false }) }
    } })
  },
  data: {
    list: [],
    recordId: null,
    loading: false,
    recordsReady: false,
    loadError: '',
    // 「已核销未交付」只读自查：升级前退过的押金没有交付时间，属历史欠账（后端给计数与明细）
    undelivered: [],
    undeliveredCount: 0,
    undeliveredAmount: '0'
  },

  onLoad(options) {
    const id = options && options.recordId
    this.setData({ recordId: id && /^\d+$/.test(String(id)) ? String(id) : null })
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    const loading = this.loadData()
    this.loadUndelivered()
    return loading
  },

  onRetry() { return this.loadData() },

  async loadData() {
    const seq = this._recordsSeq = (this._recordsSeq || 0) + 1
    this.setData({ loading: true, recordsReady: false, loadError: '' })
    try {
      // 2026-10-02：按首页原申请编号读取，历史列表可能被新流水挤出上限，不能据此说“已办完”。
      const res = this.data.recordId ? await getPendingReturnRecord(this.data.recordId) : await getAllBarrelRecords()
      if (seq !== this._recordsSeq) return
      if (!res || res.code !== 0 || res.data == null) throw new Error('退桶申请未能核对，请重试')
      const records = this.data.recordId ? [res.data] : res.data
      if (!Array.isArray(records)) throw new Error('退桶申请未能核对，请重试')
      // owedBuckets 后端给的是 over（可为负）。负数=顾客多还的桶寄存在水站，是合法状态，
      // 不能当成 0 显示——那是顾客打电话来问"我的桶呢"的直接来源。
      // wxml 里不能做取负运算，所以在 JS 里预先拆成两个非负字段。
      const list = records.map(item => {
        const over = item.owedBuckets || 0
        // ⚠️ 退桶审批的按钮/文案只对 **type=2（退桶）** 成立：type=7 纯还桶、type=8 配送收发
        //    也把 status 写成 3，直接按 status 渲染会给出"退押金"按钮，
        //    点了必然被后端以「仅退桶记录可审批」拒掉（判据同后端 getStatusText 只对 type=2 下发文案）。
        const isReturn = item.type === 2
        return Object.assign({}, item, {
          isReturn,
          owedQty: over > 0 ? over : 0,
          storageQty: over < 0 ? -over : 0,
          delivered: isReturn && item.status === 3 && !!item.refundPaidTime,
          noDeliveryRecord: isReturn && item.status === 3 && !item.refundPaidTime
        })
      })
      this.setData({ list, recordsReady: true })
    } catch (err) {
      if (seq === this._recordsSeq) this.setData({ loadError: '退桶申请未能核对，请重试；已有记录是上次读取的结果' })
    } finally {
      if (seq === this._recordsSeq) this.setData({ loading: false })
    }
  },

  /**
   * 拉「已核销未交付」计数与明细（只读自查，违规数据）。
   * 它是**辅助面板**：拉不到时不阻断主流程，只是不显示这块提示（不要在 catch 里报"成功"）。
   */
  async loadUndelivered() {
    try {
      const res = await getRefundUndelivered()
      const data = res.data || {}
      this.setData({
        undelivered: data.records || [],
        undeliveredCount: data.count || 0,
        undeliveredAmount: data.amount || '0'
      })
    } catch (err) {
      console.error('[barrel-return] 未交付计数拉取失败:', err && err.message)
      this.setData({ undelivered: [], undeliveredCount: 0, undeliveredAmount: '0' })
    }
  },

  onApprove(e) {
    const id = e.currentTarget.dataset.id
    wx.showModal({
      title: '确认收到空桶',
      content: '确定客户已退回空桶？',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' })
          try {
            await updateBarrelRecordStatus(id, 2)
            wx.hideLoading()
            wx.showToast({ title: '已确认', icon: 'success' })
            this.loadData()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '操作失败', icon: 'none' })
          }
        }
      }
    })
  },
  onApproveArrangement(e) {
    const record = this.data.list.find(r => r.id === Number(e.currentTarget.dataset.id))
    if (!record || !record.returnDetail) return
    const needsFee = record.returnDetail.pickupMode === 'PICKUP' && record.returnDetail.requiredBarrels > 0
    wx.showModal({ title: '批准退桶安排', editable: needsFee, placeholderText: '独立上门费（元，可填 0）',
      content: needsFee ? '先填写本次独立上门费，客户确认后才能收桶。' : '本次不另收上门费。批准后等待客户确认安排。',
      success: async (r) => {
        if (!r.confirm) return
        const fee = needsFee ? Number(r.content) : 0
        if (!Number.isFinite(fee) || fee < 0 || fee > 10000) { wx.showToast({ title: '费用不合法', icon: 'none' }); return }
        try { await approveBarrelReturn(record.id, fee, '批准交接安排'); await this.loadData() }
        catch (err) { wx.showToast({ title: err.message || '批准失败', icon: 'none' }) }
      } })
  },
  onCollectPickupFee(e) {
    wx.showModal({ title: '确认收到取桶费', content: '须已经实际收到这笔上门收桶费，才可确认。', success: async (r) => {
      if (!r.confirm) return
      try { await confirmPayment(e.currentTarget.dataset.id); wx.showToast({ title: '已登记收款' }); this.loadData() }
      catch (err) { wx.showToast({ title: err.message || '登记失败', icon: 'none' }) }
    } })
  },

  /**
   * 已确认收到空桶(status=2) → 退押金**并登记交付**(status=3)。
   *
   * 这一步以前根本没做按钮：站长点完"确认收到"就只能干瞪眼，申请永远停在"已确认"。
   * 现在按钮存在，但"退钱"与"把钱交到顾客手上"是一件事：先选怎么退（现金当面 / 线上原路），
   * 现金还要再确认一次"钱已经当面给出去了"，然后才提交。
   */
  onRefund(e) {
    const id = e.currentTarget.dataset.id
    const amount = e.currentTarget.dataset.amount || 0
    wx.showActionSheet({
      itemList: ['现金当面交付（当场把钱交给顾客）', '线上原路退回（微信）'],
      success: (res) => {
        if (res.tapIndex === 0) {
          this.confirmCashAndRefund(id, amount)
        } else if (res.tapIndex === 1) {
          this.submitRefund(id, CHANNEL_ONLINE)
        }
      }
    })
  },

  /** 现金通道的必答项：钱到底给出去了没有。没有这一步就成了"先核销、钱以后再给"。 */
  confirmCashAndRefund(id, amount) {
    wx.showModal({
      title: '退押金并当面交付',
      content: `请确认：已经或此刻把 ¥${amount} 押金当面交给顾客。确认后押金立即核销并记下交付时间，不可撤销。`,
      confirmText: '已交付',
      cancelText: '取消',
      success: (res) => {
        if (res.confirm) {
          this.submitRefund(id, CHANNEL_CASH)
        }
      }
    })
  },

  async submitRefund(id, channel) {
    wx.showLoading({ title: '处理中...' })
    try {
      await updateBarrelRecordStatus(id, 3, { refundChannel: channel })
      wx.hideLoading()
      wx.showToast({ title: '已退押金并登记交付', icon: 'success' })
      this.loadData()
      this.loadUndelivered()
      // 原写流程成功后仅刷新应用内提醒；不另造退款或桶账写入口。
      syncPendingReminder()
    } catch (err) {
      wx.hideLoading()
      // 线上通道未接入时后端会把原因写清楚（"微信退款通道未接入…"），
      // 这里原样透出——别改写成"操作失败"，否则站长不知道换现金还能退。
      wx.showToast({ title: err.message || '操作失败', icon: 'none', duration: 3000 })
    }
  },

  /**
   * 补登记交付（历史欠账）：只补"钱已交给顾客"这一事实，不动金额、不动状态。
   * 后端幂等——重复点不会重复记账，也不会改写原来的交付时间。
   */
  onConfirmPaid(e) {
    const id = e.currentTarget.dataset.id
    const amount = e.currentTarget.dataset.amount || 0
    wx.showModal({
      title: '登记押金已交付',
      content: `这笔 ¥${amount} 押金已经交到顾客手上了吗？登记后记下时间与你，重复点击不会重复记账。`,
      confirmText: '已交付',
      cancelText: '取消',
      success: async (res) => {
        if (!res.confirm) return
        wx.showLoading({ title: '处理中...' })
        try {
          const r = await markRefundPaid(id)
          wx.hideLoading()
          wx.showToast({
            title: r.data && r.data.alreadyPaid ? '此前已登记过' : '已登记',
            icon: 'success'
          })
          this.loadData()
          this.loadUndelivered()
        } catch (err) {
          wx.hideLoading()
          wx.showToast({ title: err.message || '操作失败', icon: 'none', duration: 3000 })
        }
      }
    })
  },

  onReject(e) {
    const id = e.currentTarget.dataset.id
    wx.showModal({
      title: '驳回退桶申请',
      content: '确定驳回该申请？',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' })
          try {
            await updateBarrelRecordStatus(id, 4)
            wx.hideLoading()
            wx.showToast({ title: '已驳回', icon: 'success' })
            this.loadData()
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '操作失败', icon: 'none' })
          }
        }
      }
    })
  }
})
