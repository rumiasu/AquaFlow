const { getProductDetail } = require('../../api/product')
const { getOrderDetail } = require('../../api/order')
const { getAddresses } = require('../../api/address')
const { getBarrelSummary, getBarrelSummaryByType } = require('../../api/barrel')
const { getTicketAccounts } = require('../../api/ticket')
const { createOrder, createPayment } = require('../../api/order')
const { getQuote } = require('../../api/payment')
const { getStationPublicPhone } = require('../../api/station')
const { storage, stationStorage } = require('../../utils/storage')
const { getBaseUrl, API } = require('../../config/api')
const { getAccessToken, getCustomerId } = require('../../utils/token')
const { formatAddress } = require('../../utils/address')
const app = getApp()

Page({
  data: {
    loading: true,
    items: [],
    products: [],
    address: null,
    addressText: '',
    note: '',
    barrelByType: [],
    barrelSummary: [],
    stationId: null,
    stationName: '',
    // 金额校准相关
    totalWaterCost: 0,
    totalDeposit: 0,
    extraDepositBuckets: 0,
    extraDepositAmount: 0,
    totalAmount: 0,
    totalWaterCostText: '0.00',
    totalDepositText: '0.00',
    extraDepositAmountText: '',
    totalAmountText: '0.00',
    // 支付方式：枚举以后端 PayMethod 为准 —— 1=微信 2=现金(货到付款) 3=水票。
    // 历史 bug：这里曾按「1微信 2水票 3货到付款」自造映射，与后端 2/3 恰好相反，
    // 导致默认项（2）被后端判为现金而撞上货到付款授权校验 → 新客户 100% 下单失败；
    // 选"货到付款"(3) 反被当成水票 → 下单即视同已付、无人收款。
    // 现在选项与文案一律由服务端 /api/payments/quote 的 methods 下发，前端不再自带映射。
    selectedMethod: 3,
    payMethods: [],
    submitting: false,
    // 幂等键：onLoad 生成一次，下单成功后才刷新（保证同一意图只产生一单）
    idempotencyKey: '',
    // 支付方式确认弹窗
    showOfflineConfirm: false,
    // 首次资产业务确认弹窗
    showAssetConfirm: false,
    assetConfirmed: false,
    // 资产使用说明详情弹窗
    showAssetDetail: false,
    stationPhone: '',
    pendingOrderRes: null,
    // 费用明细弹窗
    showDetailPopup: false,
    // 配送中桶提醒弹窗
    showInTransitReminder: false,
    inTransitReminderAck: false,   // 同一次进入页面只提示一次
    hasInTransitBarrels: false
  },

  onLoad(options) {
    if (!app.globalData.isLogin) {
      wx.redirectTo({ url: '/pages/login/index' })
      return
    }

    // 一次下单意图 = 一个幂等键（提交失败重试也复用），避免重复下单
    this.setData({ idempotencyKey: this.genIdempotencyKey() })

    // 优先级：URL参数 > 全局临时站点 > 本地存储
    let stationId = null
    if (options.stationId) {
      const parsed = parseInt(options.stationId)
      if (!isNaN(parsed) && parsed > 0) {
        stationId = parsed
      }
    }
    if (!stationId && app.globalData.tempStationId) {
      stationId = app.globalData.tempStationId
    }
    if (!stationId) {
      stationId = stationStorage.getId()
    }
    if (stationId) {
      app.globalData.tempStationId = stationId
    }
    this.setData({ stationId })

    if (!stationId || stationId <= 0) {
      this.setData({ loading: false })
      wx.showModal({
        title: '请选择服务水站',
        content: '下单前需先选择一个水站。资产（桶、水票、押金）按水站隔离，互不干扰。',
        confirmText: '去选站',
        cancelText: '取消',
        success: (res) => {
          if (res.confirm) {
            wx.navigateTo({ url: '/pages/home/index' })
          } else {
            wx.navigateBack()
          }
        }
      })
      return
    }

    if (options.reorderId) {
      this.loadReorder(parseInt(options.reorderId))
      return
    }

    let items = []
    if (options.source === 'cart') {
      const cart = app.getCart(stationId) || {}
      Object.keys(cart).forEach(pid => {
        const qty = parseInt(cart[pid]) || 0
        if (qty > 0) items.push({ productId: parseInt(pid), quantity: qty })
      })
    } else if (options.items) {
      try {
        items = JSON.parse(decodeURIComponent(options.items)) || []
      } catch (e) { items = [] }
    }
    if (options.productId && items.length === 0) {
      items = [{ productId: parseInt(options.productId), quantity: parseInt(options.quantity) || 1 }]
    }

    const addressId = options.addressId ? parseInt(options.addressId) : null
    const specialNote = options.specialNote ? decodeURIComponent(options.specialNote) : ''
    this.setData({ items, note: specialNote, _preferAddressId: addressId })

    this.loadItemsProducts()
    this.loadAddress(addressId)
    this.loadBarrel()
  },

  onShow() {
    const selectedAddress = storage.get('selectedAddress')
    if (selectedAddress) {
      this.setData({
        address: selectedAddress,
        addressText: formatAddress(selectedAddress)
      })
      storage.remove('selectedAddress')
    }
  },

async loadItemsProducts() {
    const { items, stationId } = this.data
    const products = []

    // 1. 必须有选中的服务水站
    let effectiveStationId = stationId
    let effectiveStationName = ''

    if (!effectiveStationId || effectiveStationId <= 0) {
      // 无站点：提示去选择水站
      this.setData({ loading: false })
      const confirm = await new Promise(resolve => {
        wx.showModal({
          title: '请选择服务水站',
          content: '下单前需先选择一个水站。资产（桶、水票、押金）按水站隔离，互不干扰。',
          confirmText: '去选站',
          cancelText: '取消',
          success: (res) => resolve(res.confirm)
        })
      })
      if (!confirm) {
        wx.navigateBack()
      } else {
        wx.navigateTo({ url: '/pages/home/index' })
      }
      return
}

// 获取站点名称
    if (app.globalData.tempStationId === effectiveStationId && app.globalData.tempStation) {
      effectiveStationName = app.globalData.tempStation.name || ''
    } else {
      const station = stationStorage.get()
      if (station && station.id === effectiveStationId) {
        effectiveStationName = station.name || ''
      }
    }

    // 加载商品详情
    for (const it of items) {
      try {
        const res = await getProductDetail(it.productId)
        if (res && res.data) {
          const p = { ...res.data, quantity: it.quantity || 1, waterTypeId: res.data.water_type_id }
          p.subtotal = (parseFloat(p.price) || 0) * (p.quantity || 1)
          p.subtotalText = p.subtotal.toFixed(2)
          products.push(p)
        }
      } catch (e) {
        console.warn('Load product error:', e.message)
      }
    }

this.setData({ products, stationName: effectiveStationName })
    this.syncBarrelSummary()
    this.refreshQuote()
    this.setData({ loading: false })
  },

  onSelectMethod(e) {
    const { id, enabled } = e.currentTarget.dataset
    // 不可用的支付方式（如未接入的微信支付、未授权的货到付款）不响应点击
    if (enabled === false || String(enabled) === 'false') {
      const tip = (this.data.payMethods.find(m => m.id === parseInt(id)) || {}).desc
      wx.showToast({ title: tip || '该支付方式暂不可用', icon: 'none' })
      return
    }
    this.setData({ selectedMethod: parseInt(id) })
    this.refreshQuote()
  },

  onOfflineConfirmOk() {
    this.setData({ showOfflineConfirm: false })
  },

  onOfflineConfirmCancel() {
    this.setData({ showOfflineConfirm: false })
  },

  onInTransitReminderCancel() {
    this.setData({ showInTransitReminder: false })
  },

  onInTransitReminderOk() {
    // 用户确认继续：关闭提醒后重跑提交（此时 inTransitReminderAck 已为 true，会跳过提醒并真正下单）
    this.setData({ showInTransitReminder: false })
    this.onSubmit()
  },

  async loadReorder(orderId) {
    try {
      const res = await getOrderDetail(orderId)
      const order = res.data
      if (!order) {
        wx.showToast({ title: '订单不存在', icon: 'none' })
        wx.navigateBack()
        return
      }

      const baseUrl = getBaseUrl()
      const token = getAccessToken()
      const stationRes = await new Promise((resolve, reject) => {
        wx.request({
          url: baseUrl + API.STATIONS_MY_CURRENT,
          method: 'GET',
          header: { 'Authorization': 'Bearer ' + token },
          success: (r) => resolve(r.data),
          fail: reject
        })
      })

      const currentStationId = stationRes && stationRes.code === 0 ? stationRes.data : null
      const orderStationId = order.deliveryStationId || order.stationId

      if (currentStationId && orderStationId && currentStationId !== orderStationId) {
        wx.showModal({
          title: '水站不一致',
          content: '该订单属于其他水站，当前水站可能无法供应此商品。是否继续？',
          confirmText: '继续',
          cancelText: '取消',
          success: (modalRes) => {
            if (modalRes.confirm) {
              this._doLoadFromOrder(order)
            } else {
              wx.navigateBack()
            }
          }
        })
        return
      }

      await this._doLoadFromOrder(order)
    } catch (error) {
      console.error('Load reorder error:', error)
      this.setData({ loading: false })
    }
  },

  async _doLoadFromOrder(order) {
    let items = []
    if (order.items && order.items.length > 0) {
      items = order.items.map(it => ({
        productId: it.productId || it.waterTypeId,
        quantity: it.quantity || 1
      }))
    } else if (order.productId || order.waterTypeId) {
      items = [{
        productId: order.productId || order.waterTypeId,
        quantity: order.quantity || 1
      }]
    }

    this.setData({
      items,
      note: order.specialNote || '',
      stationId: order.deliveryStationId || order.stationId || this.data.stationId
    })
    this.loadAddress(order.addressId)
    await this.loadItemsProducts()
    this.loadBarrel()
  },

  async loadAddress(preferId) {
    try {
      const res = await getAddresses()
      if (res.data && res.data.length > 0) {
        const list = res.data
        const picked = list.find(a => a.id === preferId)
        const fallback = list.find(a => a.isDefault) || list[0]
        const addr = picked || fallback
        this.setData({ address: addr, addressText: addr ? formatAddress(addr) : '' })
      }
    } catch (error) {
      console.error('Load address error:', error)
    }
  },

  async loadBarrel() {
    try {
      const { stationId } = this.data
      const res = await getBarrelSummaryByType(stationId)
      if (res.data) {
        this.setData({ barrelByType: res.data })
        this.syncBarrelSummary()
      }
      // 统计「配送中」的桶：只有 PENDING（已购待送、尚未送达确认）才算配送中，
      // 已送达的 DELIVERED 记录不应再触发提醒；首单仍在配送的也已被包含。
      const sumRes = await getBarrelSummary(stationId)
      if (sumRes.data) {
        const pending = sumRes.data.pendingDeliveryBuckets || 0
        this.setData({ hasInTransitBarrels: pending > 0 })
      }
    } catch (error) {
      console.error('Load barrel error:', error)
    }
  },

  syncBarrelSummary() {
    const { products, barrelByType } = this.data
    const summary = products.map(p => {
      const pid = p.id
      const held = (barrelByType || []).find(b => (b.productId || b.waterTypeId) === pid)
      const actualBuckets = held
        ? Math.max(0, held.assetQty != null ? held.assetQty : (held.holdingQty || 0) - (held.confirmedQty || 0))
        : 0
      return { productId: pid, productName: p.name, actualBuckets, quantity: p.quantity || 1 }
    })
    this.setData({ barrelSummary: summary })
  },

  onQtyChange(e) {
    const { index, type } = e.currentTarget.dataset
    const products = [...this.data.products]
    let qty = products[index].quantity || 1
    if (type === 'add') qty++
    else if (type === 'minus' && qty > 1) qty--
    products[index].quantity = qty
    products[index].subtotal = (parseFloat(products[index].price) || 0) * qty
    products[index].subtotalText = products[index].subtotal.toFixed(2)

    const items = products.map(p => ({ productId: p.id, quantity: p.quantity || 1 }))
    this.setData({ products, items })
    this.syncBarrelSummary()
    this.refreshQuote()
  },

  onQtyInput(e) {
    const { index } = e.currentTarget.dataset
    const qty = Math.max(1, parseInt(e.detail.value) || 1)
    const products = [...this.data.products]
    products[index].quantity = qty
    products[index].subtotal = (parseFloat(products[index].price) || 0) * qty
    products[index].subtotalText = products[index].subtotal.toFixed(2)
    const items = products.map(p => ({ productId: p.id, quantity: p.quantity || 1 }))
    this.setData({ products, items })
    this.syncBarrelSummary()
    this.refreshQuote()
  },

  onNoteInput(e) {
    this.setData({ note: e.detail.value })
  },

  onAddressTap() {
    wx.navigateTo({ url: '/pages/address/list?from=order' })
  },

  getTotalEstimate() {
    const { products } = this.data
    let total = 0
    products.forEach(p => {
      const price = parseFloat(p.price) || 0
      total += price * (p.quantity || 1)
    })
    return total.toFixed(2)
  },

  async refreshQuote() {
    const { products, selectedMethod, stationId, barrelSummary } = this.data
    if (!products || products.length === 0 || !stationId) return

    try {
      const quoteItems = products.map(p => ({
        productId: p.id,
        quantity: p.quantity || 1
      }))
      const res = await getQuote({
        items: quoteItems,
        paymentMethod: selectedMethod,
        stationId: stationId
      })
      if (res.data) {
        const d = res.data
        const totalWaterCost = d.waterAmount || 0
        const totalDeposit = d.barrelDeposit || 0
        const extraDepositBuckets = d.extraDepositBuckets || 0
        const extraDepositAmount = d.extraDeposit || 0
        const totalAmount = d.totalAmount || (totalWaterCost + totalDeposit + extraDepositAmount)
        const allowOfflinePayment = d.allowOfflinePayment === true

        // 支付方式列表由服务端下发（含文案、可用性、默认项），前端不再硬编码 1/2/3 的含义
        const payMethods = Array.isArray(d.methods) && d.methods.length
          ? d.methods
          : [{ id: 3, name: '水票支付', desc: '使用账户水票抵扣', enabled: true }]
        // 当前选中项若已不可用（权限被收回），回退到服务端给的默认值
        const selectedStillOk = payMethods.some(m => m.id === selectedMethod && m.enabled)
        const nextMethod = selectedStillOk ? selectedMethod : (d.defaultMethod || payMethods[0].id)

        // 计算每个商品的押金明细
        const updatedProducts = products.map(p => {
          const deposit = parseFloat(p.deposit) || 0
          const isBarrel = p.category === 1
          let depositAmount = 0
          let shortage = 0
          let shortageDeposit = 0

          if (isBarrel) {
            // 桶装水：只对缺少的空桶收押金
            const held = (barrelSummary || []).find(s => s.productId === p.id)
            const actualBuckets = held ? held.actualBuckets : 0
            shortage = Math.max(0, (p.quantity || 1) - actualBuckets)
            shortageDeposit = deposit * shortage
          } else {
            // 非桶装水：每个都收押金
            depositAmount = deposit * (p.quantity || 1)
          }

          return {
            ...p,
            depositAmountText: depositAmount > 0 ? depositAmount.toFixed(2) : '0.00',
            shortage,
            shortageDepositText: shortageDeposit > 0 ? shortageDeposit.toFixed(2) : '0.00'
          }
        })

        const updates = {
          products: updatedProducts,
          totalWaterCost,
          totalDeposit,
          extraDepositBuckets,
          extraDepositAmount,
          totalAmount,
          totalWaterCostText: totalWaterCost.toFixed(2),
          totalDepositText: totalDeposit.toFixed(2),
          extraDepositAmountText: extraDepositBuckets > 0 ? extraDepositAmount.toFixed(2) : '',
          totalAmountText: totalAmount.toFixed(2),
          allowOfflinePayment,
          payMethods,
          selectedMethod: nextMethod
        }

        this.setData(updates)
      }
    } catch (e) {
      console.warn('[OrderCreate] refreshQuote error:', e.message)
    }
  },

  // 生成一个下单幂等键（仅在"新的一次下单意图"时调用：进入页面 / 下单成功后）
  genIdempotencyKey() {
    return 'order_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9)
  },

  async onSubmit() {
    if (this.data.submitting) return

    const { products, address, note, stationId, selectedMethod } = this.data

    if (!address) {
      wx.showToast({ title: '请选择配送地址', icon: 'none' })
      return
    }

    if (!products || products.length === 0) {
      wx.showToast({ title: '请选择商品', icon: 'none' })
      return
    }

    // 2 = PayMethod.CASH（货到付款）：需先弹窗确认"送达后付款"
    if (selectedMethod === 2 && !this.data.showOfflineConfirm) {
      this.setData({ showOfflineConfirm: true })
      return
    }

    // 配送中桶提醒：有水桶正在配送中，且本次下单会产生额外桶押金时，先友好提示
    if (this.data.hasInTransitBarrels && this.data.extraDepositBuckets > 0 && !this.data.inTransitReminderAck) {
      this.setData({ showInTransitReminder: true, inTransitReminderAck: true })
      return
    }

    // 复用本次下单意图的幂等键：失败重试不会产生第二单
    const idempotencyKey = this.data.idempotencyKey || this.genIdempotencyKey()

    this.setData({ submitting: true, showOfflineConfirm: false, idempotencyKey })

    // 资产确认弹窗未处理完前保持 submitting=true，防止用户重复点击造成重复下单
    let keepSubmitting = false
    try {
      const items = this.data.products.map(p => ({
        productId: p.id,
        waterTypeId: p.waterTypeId || null,
        productName: p.name,
        productSpec: p.spec || '',
        productPrice: parseFloat(p.price) || 0,
        productDeposit: parseFloat(p.deposit) || 0,
        quantity: p.quantity || 1
      }))

      const orderRes = await createOrder({
        items: items,
        addressId: this.data.address.id,
        specialNote: this.data.note,
        source: 3,
        paymentMethod: this.data.selectedMethod,
        stationId: this.data.stationId,
        extraDeposit: this.data.extraDepositAmount || 0,
        idempotencyKey: idempotencyKey
      })

      // 订单已创建成功 -> 刷新幂等键，之后再主动下单才是新的一单
      this.setData({ idempotencyKey: this.genIdempotencyKey() })

      if (orderRes.data && orderRes.data.firstStationAsset) {
        keepSubmitting = true
        this.setData({ showAssetConfirm: true, assetConfirmed: false, pendingOrderRes: orderRes })
        return
      }

      this.proceedToPayment(orderRes)
    } catch (error) {
      console.error('[OrderCreate] 下单失败:', error)
      wx.showToast({ title: '下单失败: ' + (error.message || ''), icon: 'none', duration: 3000 })
    } finally {
      if (!keepSubmitting) this.setData({ submitting: false })
    }
  },

  async proceedToPayment(orderRes) {
    const orderId = orderRes.data?.orderId || orderRes.data || null
    if (!orderId) return

    // 3 = PayMethod.TICKET（水票支付）：下单即视同已付，补一条支付流水用于对账
    if (this.data.selectedMethod === 3) {
      try {
        await createPayment({
          orderId,
          customerId: getCustomerId(),
          amount: this.data.totalAmount,
          waterAmount: this.data.totalWaterCost,
          barrelDeposit: this.data.totalDeposit,
          extraDepositBuckets: this.data.extraDepositBuckets,
          extraDepositAmount: this.data.extraDepositAmount,
          paymentMethod: 3,
          ticketProductId: null,
          ticketQty: null
        })
      } catch (e) {
        console.warn('水票支付记录创建失败:', e.message)
      }
    }

    wx.setStorageSync('lastOrderId', orderId)
    wx.redirectTo({ url: `/pages/order/success?id=${orderId}&stationId=${this.data.stationId}` })
  },

  // ===== 首次资产业务确认弹窗 =====
  // 注意：订单在弹窗出现之前就已创建成功。这里的"取消"只是不继续支付，
  // 必须明确提示订单已存在，否则用户会以为没下单而再次提交，造成重复下单。
  onAssetConfirmCancel() {
    this.setData({
      showAssetConfirm: false,
      assetConfirmed: false,
      pendingOrderRes: null,
      submitting: false
    })
    wx.showModal({
      title: '订单已创建',
      content: '订单已提交成功，可在「我的订单」中查看或取消。',
      showCancel: false,
      confirmText: '查看订单',
      success: () => wx.switchTab({ url: '/pages/order/list' })
    })
  },

  onAssetCheckboxChange(e) {
    this.setData({ assetConfirmed: e.detail.value.length > 0 })
  },

  onAssetConfirmOk() {
    if (!this.data.assetConfirmed) {
      wx.showToast({ title: '请勾选确认后继续', icon: 'none' })
      return
    }
    const orderRes = this.data.pendingOrderRes
    this.setData({ showAssetConfirm: false, assetConfirmed: false, pendingOrderRes: null })
    this.proceedToPayment(orderRes)
  },

  // ===== 资产使用说明详情弹窗 =====
  onAssetDetailTap() {
    // 获取水站电话
    this.fetchStationPhone()
    this.setData({ showAssetDetail: true })
  },

  onAssetDetailClose() {
    this.setData({ showAssetDetail: false })
  },

  async fetchStationPhone() {
    if (this.data.stationPhone) return
    try {
      const res = await getStationPublicPhone(this.data.stationId)
      if (res.data && res.data.phone) {
        this.setData({ stationPhone: res.data.phone })
      }
    } catch (e) {
      console.warn('获取水站电话失败:', e)
    }
  },

  // ===== 费用明细弹窗 =====
  onShowDetail() {
    this.setData({ showDetailPopup: true })
  },

  onDetailPopupClose() {
    this.setData({ showDetailPopup: false })
  }
})