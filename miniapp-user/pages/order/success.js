const { setFromOrder, getQuickOrder } = require('../../api/template')
const { createPayment } = require('../../api/order')
const { getOrderDetail } = require('../../api/order')
const { stationStorage } = require('../../utils/storage')
const { getCustomerId, captureSession, isCurrentSession } = require('../../utils/token')
const { notifyPayResult } = require('../../utils/pay')
const { errorText, money } = require('./visual')

Page({
  data: {
    orderId: '',
    stationId: null,
    isDefaultSet: false,
    hasTemplate: false,
    savingTemplate: false,
    paying: false,
    actionError: '',
    paymentStatus: null,
    orderAmount: 0,
    orderPaymentMethod: null,
    // 能否在线支付 / 按钮文案 / 付款说明：**一律用后端下发的**，绝不用 paymentStatus 自己判断。
    // 后端投影见 Orders.getCanRepay() + PaymentService.canSelfPay()：
    // 只有"当前渠道真的能付掉"才为 true（现金单永远 false；微信单取决于模拟渠道是否开启）。
    canRepay: false,
    repayLabel: '去支付',
    payHint: '',
    // ===== 结果区按**服务端事实**渲染（契约 A3）=====
    // loadState: 'loading' | 'ok' | 'failed' —— failed 时**不许**落到"下单成功 / 尽快配送"
    loadState: 'loading',
    loadErrorText: '',
    // 结果区的**每一句业务文案都来自后端**（Orders 派生字段）：
    //   statusText → 标题（本单现在处于哪一步：待配送 / 配送中 / 已送达 / 已完成 / 已取消）
    //   payHint    → 付款说明；payStateText → 支付状态
    // 前端**只做展示决策**（有没有文案、要不要提示色），不再把数字翻成中文。
    // 为什么拆成 heroTitle/heroDesc 而不复用一个字符串：wxml 要按"有没有"分别控制渲染。
    heroTitle: '',             // order.statusText
    heroDesc: '',              // order.payHint
    heroTone: 'ok',            // 'ok' | 'warn'，只由后端状态编码选配色
    statusText: '',
    payStateText: '',
    amountText: ''             // 预格式化金额，wxml 里不做算术
  },

  onLoad(options = {}) {
    this._directOrderId = options.id || null
    const orderId = options.id || wx.getStorageSync('lastOrderId') || ''
    const app = getApp()
    const stationId = options.stationId || stationStorage.getId() || app.globalData.tempStationId || null
    if (orderId && orderId !== 'mock') {
      this.setData({ orderId: parseInt(orderId) || orderId, stationId })
      wx.setStorageSync('lastOrderId', orderId)
      this.checkTemplateStatus(stationId)
      this.loadOrderStatus(orderId)
    } else {
      this.setData({ loadState: 'failed', loadErrorText: '未提供有效订单编号，请到「查看订单」核对本次下单结果。' })
    }
  },

  onUnload() { this._destroyed = true; this._readSeq = (this._readSeq || 0) + 1 },

  /** 重试查一次（契约 A3：加载失败要有能做的下一步，而不是显示一个假成功） */
  onReload() {
    if (!this.data.orderId || this.data.loadState === 'loading') return
    this.setData({ loadState: 'loading' })
    return this.loadOrderStatus(this.data.orderId)
  },

  async loadOrderStatus(orderId) {
    if (this._destroyed) return
    const seq = this._readSeq = (this._readSeq || 0) + 1, session = captureSession()
    const current = () => !this._destroyed && seq === this._readSeq && isCurrentSession(session)
    this.setData({ loadState: 'loading', canRepay: false })
    try {
      const res = await getOrderDetail(orderId)
      if (!current()) return
      const order = res && res.data
      // 必须真的拿到订单才算"查到了"：原来 data 为空只 console.warn，
      // 页面继续渲染"我们会尽快为您配送"——而订单可能根本不存在（AGENTS §8.22 的同一类坑）。
      if (!order || !order.id || String(order.id) !== String(orderId)) {
        this.setData({
          loadState: 'failed',
          loadErrorText: '没有取到这张订单的信息，可能是订单号不对或网络问题。可以先到「我的订单」核对。'
        })
        return
      }
      this.applyOrderFacts(order)
      // 路由到达或旧订单便捷缓存不能证明本次下单成功；先核对明确传入的原单与站别。
      if (String(this._directOrderId) === String(order.id) && order.stationId
        && String(order.stationId) === String(this.data.stationId) && !this._cartCleared) {
        getApp().clearCart(this.data.stationId)
        this._cartCleared = true
      }
    } catch (e) {
      if (!current()) return
      console.warn('loadOrderStatus error:', e)
      this.setData({
        loadState: 'failed',
        loadErrorText: errorText(e, '没能读取这张订单，请重新查询，或到「查看订单」核对。')
      })
    } finally {
      if (!this._destroyed && seq === this._readSeq && !isCurrentSession(session)) {
        this.setData({ loadState: 'failed', canRepay: false, loadErrorText: '登录状态已变化，请到订单列表重新核对。' })
      }
    }
  },

  /**
   * 把后端下发的订单事实渲染成结果页文案（契约 A3）。
   *
   * <p><b>[2026-09-26] 拆掉前端自建的 1/2/3 → 中文映射表。</b>原来这里用
   * `status===5→'订单已取消'` / `payStatus===3→'已退款'` / `payStatus===2→'下单成功'` ……
   * 在前端把数字翻成中文，而同页又渲染后端下发的 `statusText` —— 同一屏两套状态文案，
   * 后端改叫法前端不跟，客户就会看到互相矛盾的句子（AGENTS §6：前端禁止自带映射表）。</p>
   *
   * <p>现在只做两件事：<b>取</b>后端字段（statusText / payHint / payStateText）、
   * <b>决定</b>展示形态（标题用哪个字段、要不要换暖色、金额怎么排）。</p>
   *
   * <p>⚠️ 保留的硬护栏，一条都不许回退：① 文案为空时**标题留空**，绝不用"下单成功"
   * 之类的兜底去盖住一个没查到的事实（AGENTS §8.22 的同一类坑：`data` 为空只 warn、
   * 页面照旧渲染"尽快配送"）；② 已取消 / 已退款 / 支付已取消一律换暖色，不用绿勾暗示成功。</p>
   */
  applyOrderFacts(order) {
    const amount = order.totalAmount != null ? order.totalAmount : order.amount

    const statusText = order.statusText || ''
    const payHint = order.payHint || ''
    const payState = order.payState || ''

    // 标题就是**后端给的状态文案**；没给就留空（wxml 不渲染标题），不自己编一句。
    const heroTitle = statusText
    // 付款说明：后端没给 payHint 时，退到后端给的支付状态文案（同样是后端字段）。
    const heroDesc = payHint || order.payStateText || ''
    // 配色（不是文案）：已取消 / 退款类 / 支付被取消 = 需要客户留意，其余用常态色。
    const heroTone = payState === 'PAID' && order.status !== 5 ? 'ok' : 'warn'

    this.setData({
      loadState: 'ok',
      paymentStatus: order.paymentStatus == null ? null : Number(order.paymentStatus),
      orderAmount: amount,
      orderPaymentMethod: order.paymentMethod == null ? null : Number(order.paymentMethod),
      canRepay: order.canRepay === true,
      repayLabel: order.repayLabel || '去支付',
      payHint,
      statusText,
      payStateText: order.payStateText || '',
      // 金额只做格式化，口径仍是后端总额（前端不参与任何金额计算）。
      amountText: money(amount) === '待核实' ? '金额待核实' : '¥' + money(amount),
      heroTitle,
      heroDesc,
      heroTone
    })
  },

  async checkTemplateStatus(stationId) {
    try {
      const res = await getQuickOrder(stationId)
      if (res.data && res.data.items && res.data.items.length > 0) {
        this.setData({ hasTemplate: true })
      }
    } catch (error) {
      // 无模板，显示保存入口
    }
  },

  async onSaveTemplate() {
    const { orderId, stationId } = this.data
    if (!orderId || this.data.loadState !== 'ok' || this.data.savingTemplate) return
    const session = captureSession()
    this.setData({ savingTemplate: true, actionError: '' })
    try {
      await setFromOrder(orderId, stationId)
      if (this._destroyed || !isCurrentSession(session)) return
      this.setData({ isDefaultSet: true })
      wx.showToast({ title: '已保存', icon: 'success' })
    } catch (error) {
      if (!this._destroyed && isCurrentSession(session)) this.setData({ actionError: errorText(error, '常用订单未能保存，请重试；订单本身不受影响。') })
    } finally {
      if (!this._destroyed) this.setData({ savingTemplate: false })
    }
  },

  async onPayNow() {
    const { orderId, orderAmount, orderPaymentMethod } = this.data
    if (!orderId || this.data.loadState !== 'ok' || this.data.paying) return
    const session = captureSession()
    this.setData({ paying: true, actionError: '' })
    try {
      const res = await createPayment({
        orderId,
        customerId: getCustomerId(),
        amount: orderAmount,
        paymentMethod: orderPaymentMethod || 1,
        waterAmount: 0,
        barrelDeposit: 0,
        extraDepositBuckets: 0,
        extraDepositAmount: 0,
        ticketProductId: null,
        ticketQty: null
      })
      // 按真实支付状态提示，不再无条件报"支付成功"
      if (this._destroyed || !isCurrentSession(session)) return
      if (!res || !res.data || res.data.status == null) throw new Error('支付结果尚未确认，请重新查询原订单')
      notifyPayResult(res.data)
      await this.loadOrderStatus(orderId)
    } catch (e) {
      if (!this._destroyed && isCurrentSession(session)) this.setData({ actionError: errorText(e, '支付结果尚未确认，请先重新查询原订单') })
    } finally {
      if (!this._destroyed) this.setData({ paying: false })
    }
  },

  onBackHome() {
    wx.switchTab({ url: '/pages/home/index' })
  },

  onViewOrders() {
    wx.switchTab({ url: '/pages/order/list' })
  }
})
