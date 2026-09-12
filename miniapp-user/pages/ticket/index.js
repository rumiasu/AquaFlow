const { getTicketAccounts, getTicketRecords, purchaseTicket } = require('../../api/ticket')
const { getOnSaleProducts, getStationProducts } = require('../../api/product')
const { getPublicStations } = require('../../api/station')
const { getBaseUrl, API } = require('../../config/api')
const { getAccessToken } = require('../../utils/token')
const { stationStorage } = require('../../utils/storage')

Page({
  data: {
    loading: true,
    accounts: [],
    records: [],
    currentTab: 0,
    totalTickets: 0,
    totalValue: 0,
    tabs: [
      { id: 0, name: '水票明细' },
      { id: 1, name: '消费记录' }
    ],
    showPurchase: false,
    buyProducts: [],
    buyForm: {
      productId: null,
      productName: '',
      faceValue: 0,
      quantity: 1,
      totalPrice: 0,
      paymentMethod: 1
    },
    buyMethods: [
      // 微信支付渠道本身未接入；此处语义是「提交购票申请，由水站确认收款后水票到账」
      { id: 1, name: '微信支付', desc: '提交后由水站确认收款，到账后可用' }
    ],
    submitting: false,
    currentStationId: null,
    currentStation: null,
    showStationPicker: false,
    stationList: []
  },

  onShow() {
    this.loadData()
  },

  async loadData() {
    // 优先读取本地存储的水站
    let stationId = stationStorage.getId()
    let station = stationStorage.get()

    this.setData({ currentStationId: stationId, currentStation: station })

    this.setData({ loading: true })
    try {
      let productsRes = null
      if (stationId) {
        productsRes = await getStationProducts(stationId).catch(() => null)
      }
      if (!productsRes || !productsRes.data) {
        productsRes = await getOnSaleProducts().catch(() => null)
      }

      const [accountsRes, recordsRes] = await Promise.all([
        getTicketAccounts(stationId),
        getTicketRecords(stationId)
      ])
      if (accountsRes.data) {
        const accounts = accountsRes.data
        const totalTickets = accounts.reduce((sum, a) => sum + (a.remainQuantity || 0), 0)
        const totalValue = accounts.reduce((sum, a) => sum + (a.remainQuantity || 0) * (a.faceValue || a.price || 0), 0)
        this.setData({ accounts, totalTickets, totalValue })
      }
      if (recordsRes.data) {
        this.setData({ records: recordsRes.data })
      }
      if (productsRes && productsRes.data) {
        this.setData({ buyProducts: productsRes.data, currentStationId: stationId })
      }
    } catch (error) {
      console.error('Load ticket data error:', error)
    } finally {
      this.setData({ loading: false })
    }
  },

  async loadStationList() {
    try {
      const res = await getPublicStations().catch(() => null)
      if (res && res.code === 0 && res.data) {
        const activeStations = res.data.filter(s => s.status === 1)
        this.setData({ stationList: activeStations })
      }
    } catch (e) {
      console.error('加载水站列表失败:', e)
    }
  },

  onOpenStationPicker() {
    this.setData({ showStationPicker: true })
    this.loadStationList()
  },

  onCloseStationPicker() {
    this.setData({ showStationPicker: false })
  },

  async onSelectStation(e) {
    const { id } = e.currentTarget.dataset
    if (id === this.data.currentStationId) {
      this.setData({ showStationPicker: false })
      return
    }
    const station = this.data.stationList.find(s => s.id === id)

    // 本地提示：不同水站资产不互通
    const noticeDisabled = stationStorage.getSwitchNoticeDisabled()
    if (!noticeDisabled && this.data.currentStationId && this.data.currentStationId !== id) {
      const confirm = await new Promise(resolve => {
        wx.showModal({
          title: '切换水站提醒',
          content: '不同水站的水票、桶及押金等资产不互通，请确认后再切换。',
          confirmText: '知道了，继续',
          cancelText: '取消',
          showCancel: true,
          success: (r) => resolve(r.confirm)
        })
      })
      if (!confirm) {
        return
      }
      const dontShow = await new Promise(resolve => {
        wx.showModal({
          title: '提示',
          content: '下次不再提示？',
          confirmText: '不再提示',
          cancelText: '每次都提示',
          success: (r) => resolve(r.confirm)
        })
      })
      if (dontShow) {
        stationStorage.setSwitchNoticeDisabled(true)
      }
    }

    stationStorage.set(station)
    this.setData({ showStationPicker: false })
    await this.loadData()
  },

  onTabChange(e) {
    const { id } = e.currentTarget.dataset
    this.setData({ currentTab: id })
  },

  onShowPurchase() {
    if (!this.data.currentStationId) {
      wx.showToast({ title: '请先选择水站', icon: 'none' })
      return
    }
    this.setData({ showPurchase: true })
  },

  onClosePurchase() {
    this.setData({ showPurchase: false, buyForm: { productId: null, productName: '', faceValue: 0, quantity: 1, totalPrice: 0, paymentMethod: 1 } })
  },

  /** 弹窗内容区吞掉点击，避免冒泡到遮罩触发关闭（wxml 用 catchtap 绑定） */
  stopPropagation() {},

  onBuyProductSelect(e) {
    const { id } = e.currentTarget.dataset
    // dataset 类型可能是 string/number，统一按字符串比较，避免 === 恒 false
    const product = this.data.buyProducts.find(p => String(p.id) === String(id))
    if (!product) return
    const price = parseFloat(product.price) || 0
    this.setData({
      'buyForm.productId': product.id,
      'buyForm.productName': product.name,
      'buyForm.faceValue': price,
      'buyForm.totalPrice': price * (this.data.buyForm.quantity || 1)
    })
  },

  /** 步进器 +/-（tap 事件，data-type=minus/add），数量下限 1 */
  onBuyQtyStep(e) {
    const { type } = e.currentTarget.dataset
    const cur = this.data.buyForm.quantity || 1
    const next = type === 'add' ? cur + 1 : Math.max(1, cur - 1)
    if (next === cur) return
    this.setData({
      'buyForm.quantity': next,
      'buyForm.totalPrice': (this.data.buyForm.faceValue || 0) * next
    })
  },

  /** 手动输入数量（input 事件） */
  onBuyQuantityInput(e) {
    const qty = Math.max(1, parseInt(e.detail.value) || 1)
    this.setData({
      'buyForm.quantity': qty,
      'buyForm.totalPrice': (this.data.buyForm.faceValue || 0) * qty
    })
  },

  /** 选择支付方式（tap 事件，data-id） */
  onBuyPaymentMethodSelect(e) {
    const { id } = e.currentTarget.dataset
    this.setData({ 'buyForm.paymentMethod': parseInt(id) || 1 })
  },

  async onBuySubmit() {
    const { productId, quantity, faceValue, paymentMethod } = this.data.buyForm
    if (!productId) {
      wx.showToast({ title: '请选择商品', icon: 'none' })
      return
    }
    if (!quantity || quantity <= 0) {
      wx.showToast({ title: '请输入正确数量', icon: 'none' })
      return
    }
    if (!this.data.currentStationId) {
      wx.showToast({ title: '请先选择水站', icon: 'none' })
      return
    }

    this.setData({ submitting: true })
    try {
      const res = await purchaseTicket({
        productId: productId,
        waterTypeId: productId, // 兼容旧字段
        quantity: quantity,
        paymentMethod: paymentMethod,
        stationId: this.data.currentStationId
      })
      // 后端此时只创建了待支付流水，水票要等支付确认后才入账。
      // 旧实现无条件提示"购买成功"，客户看到余额为空会以为系统吞了钱。
      // 这里按真实 status 区分：2=已支付(票已到账)，1=待支付(等水站确认)。
      const status = (res && res.data && res.data.status) != null ? res.data.status : 1
      this.onClosePurchase()
      await this.loadData()
      if (status === 2) {
        wx.showToast({ title: '购买成功，水票已到账', icon: 'success' })
      } else {
        wx.showModal({
          title: '已提交，等待到账',
          content: '购买申请已提交给水站，水站确认收款后水票才会到账。如长时间未到账请联系水站。',
          showCancel: false,
          confirmText: '知道了'
        })
      }
    } catch (error) {
      console.error('Purchase ticket error:', error)
      wx.showToast({ title: error.message || '购买失败', icon: 'none' })
    } finally {
      this.setData({ submitting: false })
    }
  }
})