const { getProductDetail } = require('../../api/product')
const { getOrderDetail } = require('../../api/order')
const { getAddresses } = require('../../api/address')
const { getBarrelSummary, getBarrelSummaryByType } = require('../../api/barrel')
const { getTicketAccounts } = require('../../api/ticket')
const { createOrder, createPayment } = require('../../api/order')
const { getQuote } = require('../../api/payment')
const { getStationPublicPhone, getStationStatus } = require('../../api/station')
const { submitEnterpriseApply, getMyEnterpriseApplies } = require('../../api/enterprise')
const { storage, stationStorage, payMethodStorage } = require('../../utils/storage')
const { resolveStationId } = require('../../utils/station')
const { getCustomerId, captureSession, isCurrentSession } = require('../../utils/token')
const { formatAddress } = require('../../utils/address')
const orderIntent = require('../../utils/orderIntent')
const app = getApp()

// 只清理展示占位值，原始规格和报价/提交字段保持原样。
function specTextOf(value) {
  if (value == null) return ''
  const text = String(value).trim()
  return /^(null|undefined)$/i.test(text) ? '' : text
}

// 同一次报价的不同告知全部保留；完全相同的提示只展示一次。
function checkoutWarningsOf(blocked, blockReason, feeWarnings, ticketShortfallHint) {
  const seen = new Set()
  return [blocked ? blockReason : '', ...feeWarnings, ticketShortfallHint].filter(text => {
    if (typeof text !== 'string' || !text.trim() || seen.has(text.trim())) return false
    seen.add(text.trim())
    return true
  })
}

/**
 * 从建单响应里取**合法订单号**（契约 A1 的硬要求）。
 * <p>形状以实际 API 为准：`OrderCreateResult.orderId` 是 Long ⇒ JSON 里是数字；
 * 兼容服务端可能把它序列化成数字字符串的情况，但**绝不接受**对象 / 空值 / 非法字符串。
 * 旧写法 `orderRes.data?.orderId || orderRes.data || null` 会把整个响应对象当成订单号。</p>
 */
function pickOrderId(data) {
  if (!data || typeof data !== 'object') return null
  const v = data.orderId
  if (typeof v === 'number' && isFinite(v) && v > 0) return v
  if (typeof v === 'string' && /^[0-9]+$/.test(v) && Number(v) > 0) return Number(v)
  return null
}

/**
 * 「刚才已经下过一模一样的一单」的保护窗口（毫秒）。
 *
 * <p>[2026-09-26 实测缺陷] 客户在开发者工具里点了 3 下「立即下单」，**成交 3 单**
 * （订单 37/38/39，`request_digest` 完全相同、幂等键三个都不一样，各扣一次库存与押金）。
 * 根因不是"连点没防住"——`submitting` 闸门只管"请求还没回来的那几秒"；而
 * `orderIntent.clear()` 在**建单成功那一刻**就把键清了，于是同一页面上再点一次 =
 * 新键 = 新单（旧用例「成功建单之后：下一次提交是新意图」正是在守护这个行为）。</p>
 *
 * <p>窗口取 10 分钟：比"手滑连点"宽得多，又短于正常复购的间隔。窗口内提交**同一份内容**
 * 时不再静默下单，而是先问一句；选了"再下一单"才换新键（见 {@link #_createOrder}）。</p>
 */
const RECENT_ORDER_WINDOW_MS = 10 * 60 * 1000

/** 「刚刚下过的那一单」在本地的存放键（页面重进后闸门仍要生效，见 _rememberSubmittedOrder）。 */
const RECENT_ORDER_KEY = 'recentOrder.v1'

Page({
  data: {
    loading: true,
    items: [],
    products: [],
    address: null,
    addressText: '',
    checkoutWarnings: [],
    note: '',
    barrelByType: [],
    barrelSummary: [],
    stationId: null,
    stationName: '',
    // 金额校准相关
    totalWaterCost: 0,
    // 非桶装押金合计：后端 quote 的 barrelDeposit 键（历史误称）装着它，2026-09-19 起恒为 0，
    // 只用于支付请求的兼容字段（后端会用订单金额重算、丢弃客户端值），页面上不再展示
    totalDeposit: 0,
    extraDepositBuckets: 0,
    extraDepositAmount: 0,
    totalAmount: 0,
    // 水站营业状态提示（软状态）：有值时页面顶部显示横幅，**不阻断下单**
    stationStatusHint: '',
    totalWaterCostText: '0.00',
    extraDepositAmountText: '',
    totalAmountText: '0.00',
    // 支付方式：枚举以后端 PayMethod 为准 —— 1=微信 2=现金(货到付款) 3=水票。
    // 历史 bug：这里曾按「1微信 2水票 3货到付款」自造映射，与后端 2/3 恰好相反，
    // 导致默认项（2）被后端判为现金而撞上货到付款授权校验 → 新客户 100% 下单失败；
    // 选"货到付款"(3) 反被当成水票 → 下单即视同已付、无人收款。
    // 现在选项与文案一律由服务端 /api/payments/quote 的 methods 下发，前端不再自带映射。
    // [2026-09-19] 默认值不是写死的 3，而是**上次用过的支付方式**（本地偏好，见 utils/storage.js）；
    // 首次下单没有记录时回到 3（水票），再由 refreshQuote 按后端下发的 enabled 校正。
    selectedMethod: payMethodStorage.get() || 3,
    payMethods: [],
    quoteLoading: false,
    quoteReady: false,
    barrelPurchases: [], barrelPurchaseConfirmed: false,
    quoteError: '',
    // [2026-09-26] 收款渠道能力（后端 quote 的 wechatPay 原样存下来）：建单成功后要不要对
    // **同一张订单**发起 createPayment，判据是这里的 enabled（= 服务端模拟渠道开着），
    // 前端不按 id===1 猜渠道、也不碰真实 wx.requestPayment。null = 还没报价，按"不可用"处理。
    wechatPay: null,
    // [2026-09-19] 水票抵扣预览（后端 quote 下发，仅当选中水票时有值）：
    // 产品口径「水票支付时不显示计费，只计费除去水票的部分」靠它实现 ——
    // 金额一律后端算，前端只渲染，绝不在前端做抵扣算术。
    ticketPay: null,
    ticketCoverText: '',      // 「水票抵扣 N 张 · ¥X」（仅整单可被票结清时才有值）
    ticketShortfallHint: '',  // 票不足时的一句结论（后端下发）
    payableAmountText: '0.00',// 计费区展示的"这次要付"的金额；真实订单金额看 totalAmountText
    isTicketPay: false,       // 当前是否选中水票（决定计费区怎么渲染）
    submitting: false,
    // 幂等键：**一次下单意图 = 一个键**。见 utils/orderIntent.js 的说明：
    // 只有"明确换了站/地址/商品/付款方式"或"这一单已经成功建出来"才换新键；
    // 缺货未确认、超时、响应丢失、点了重试都继续用同一个键（否则重试会变成第二张单）。
    idempotencyKey: '',
    // 刚刚成功建出来的那一单（{orderId, fingerprint, at, paid}）；**购物车没动时**页面据此显示
    // "已下单"态（按钮变「继续支付/查看订单」）。做成业内主流形态的关键：
    // 重复提交不是靠确认框拦，而是**这个状态让"再下一单"没有入口**。
    // 判据见 _pendingOrderForCurrentCart（指纹 = 客户+水站+地址+商品+支付方式）。
    lastSubmittedOrder: null,
    // 投影给 wxml 的两个值（按钮文案与提示行都读它们）：pendingOrderId 为 null = 正常「立即下单」
    pendingOrderId: null,
    pendingOrderPaid: false,
    // ===== 缺货确认（契约 A1）：needConfirm=true 表示**还没建单**，绝不能当成功 =====
    showShortageConfirm: false,
    shortageItems: [],
    shortageText: '',
    // ===== 提交结果三态：'idle' / 'creating' / 'unknown'（服务端可能已建单但响应没回来）=====
    submitState: 'idle',
    unknownResultText: '',
    // 支付方式确认弹窗
    showOfflineConfirm: false,
    // 首次资产业务确认弹窗（契约 A2：**建单之前**弹，取消 = 零请求）
    showAssetConfirm: false,
    assetConfirmed: false,
    // 客户是否在水桶与押金说明里勾了"我已阅读并了解"（说明弹窗里的那个 ☐）。
    // 它是「确认下单」的前置：未勾选时按钮是灰的、点了会给提示（见 onAssetConfirmOk）。
    assetReadAgreed: false,
    // 首次资产告知的内容（全部来自 /api/payments/quote，前端不另写资产规则）
    assetNotice: { stationName: '', depositAmount: '0.00', buckets: 0, totalAmountText: '0.00' },
    // 桶与押金说明弹窗
    showAssetDetail: false,
    stationPhone: '',
    // 费用明细弹窗
    showDetailPopup: false,
    // 配送中桶提醒弹窗
    showInTransitReminder: false,
    inTransitReminderAck: false,   // 同一次进入页面只提示一次
    hasInTransitBarrels: false,
    // 企业身份提示（v50）：由服务端在报价里下发（金额达阈值且客户还不是企业身份时才有值），
    // 前端不自算阈值、不自造文案。**不做独立入口** —— 只在拿到它的那一刻弹一次。
    enterpriseHint: '',
    // 数据没加载出来时的页面提示（空串 = 全部正常），见 _noteLoadError。
    loadError: '',
    // 收货地址**没加载出来**的原因（空串 = 拉到了）。与"确实没有地址"分开：
    // 两者都让 address 为空，但一句"请选择配送地址"会把接口失败说成用户没设地址。
    addressLoadError: ''
  },

  onLoad(options) {
    if (!app.globalData.isLogin) {
      wx.redirectTo({ url: '/pages/login/index' })
      return
    }

    // 一次下单意图 = 一个幂等键（提交失败/缺货重提/超时重试都复用）。
    // 键的持久化与比对在 utils/orderIntent.js：**按客户隔离**（换账号不会继承上一位客户的待确认请求），
    // 意图五要素（客户/站/地址/商品/支付方式）任一变化才换新键。
    // 这里不再"进页面就生成一个新键"—— 那正是"离页重进 = 新意图 = 又下一单"的根因。
    this.setData({ idempotencyKey: '' })

    // 「刚刚下过的那一单」从本地读回来：不然"跳结果页 → 返回下单页"之后页面实例是新的、
    // "已下单"态就没了 —— 实测那次"点 3 下成交 3 单"正是这个形状。
    // 过期或指纹不符（换了内容/换了客户）都不会生效，判据见 _pendingOrderForCurrentCart。
    this._restorePendingOrderFromStorage()
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
        content: '下单前要先选一家水站。水桶、水票和押金都算在这家水站名下，别家水站用不了。',
        confirmText: '去选站',
        cancelText: '取消',
        success: (res) => {
          if (res.confirm) {
            // [2026-09-18 修] `/pages/home/index` 是 tabBar 页面，**只能用 switchTab**。
            // 原来写 navigateTo，微信会直接 fail（控制台报 "can not navigateTo a tabbar page"）——
            // 用户点了「去选站」什么都不会发生，属"点击静默无反应"那一类（AGENTS §6）。
            wx.switchTab({ url: '/pages/home/index' })
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
      } catch (e) {
        // [2026-09-20 真机联调] 原来 `catch (e) { items = [] }`：跳转参数解析失败时商品清单直接为空，
        // 页面渲染成一张**空白结算页**，提交时再拦一句"请选择商品" —— 把"参数坏了"说成"你没选商品"。
        items = []
        console.error('[OrderCreate] 下单参数 items 解析失败:', e)
        this._noteLoadError('下单参数解析失败，商品清单可能是空的，请返回上一页重新下单')
      }
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
    if ((this.data.products || []).length) { this.loadBarrel(); this.refreshQuote() }
    const selectedAddress = storage.get('selectedAddress')
    if (selectedAddress) {
      this.setData({
        address: selectedAddress,
        addressText: formatAddress(selectedAddress)
      })
      storage.remove('selectedAddress')
    }
  },

  /**
   * 记一条"某项数据没加载出来"的页面提示（页面级 helper，多处失败汇总成顶部一条）。
   *
   * [2026-09-20 真机联调] 本页原来失败一律只 console.warn/error：商品详情缺件、地址拉不到、
   * 桶权益拉不到都表现为"页面上少一块 / 数字是 0"，与真实业务状态无法区分（AGENTS §8.22）。
   * 汇总成一条而不是各处 toast：本页一次会打 4~5 个请求，弱网下连弹几个 toast 会盖住页面。
   * 样式复用 styles/common.wxss 的 .load-error（全局 app.wxss 已 import，不要另写一份）。
   */
  _noteLoadError(text) {
    const cur = this.data.loadError || ''
    if (!text || cur.indexOf(text) >= 0) return
    this.setData({ loadError: cur ? cur + '；' + text : text })
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
          content: '下单前要先选一家水站。水桶、水票和押金都算在这家水站名下，别家水站用不了。',
          confirmText: '去选站',
          cancelText: '取消',
          success: (res) => resolve(res.confirm)
        })
      })
      if (!confirm) {
        wx.navigateBack()
      } else {
        // [2026-09-18 修] 同 :103 —— tabBar 页面必须用 switchTab，navigateTo 必然 fail。
        wx.switchTab({ url: '/pages/home/index' })
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
    // [2026-09-20 真机联调] 原来每件商品各自 `catch (e) { console.warn('Load product error:', e.message) }`：
    // 拉不到的**那一件会静默从清单里消失** —— 客户看到"我明明加了 3 样，结算页只剩 2 样"，
    // 与"这件商品本站下架了"完全无法区分；全部失败时页面还照旧提示"请选择商品"（把加载失败
    // 说成"你没选商品"）。仍然降级（其余商品照常可下单），但必须把失败件数与原因说出来。
    let productFailCount = 0
    let productFailReason = ''
    for (const it of items) {
      try {
        // 必须带 stationId：本站自定义商品只有该站能读（后端按 owner_station_id 过滤）
        const res = await getProductDetail(it.productId, effectiveStationId)
        if (res && res.data) {
          const d = res.data
          // 后端下发的是**本站有效价**（站级覆盖 → 通用库参考价）。
          // 这里统一映射回 price/deposit：本页下方的小计、桶押金估算、提交快照都沿用旧字段名，
          // 避免"有的地方改了、有的地方没改"又变成双口径（本仓计价双轨的历史事故）。
          const p = {
            ...d,
            specText: specTextOf(d.spec),
            price: d.effectivePrice != null ? d.effectivePrice : d.price,
            deposit: d.effectiveDeposit != null ? d.effectiveDeposit : d.deposit,
            quantity: it.quantity || 1,
            waterTypeId: d.water_type_id
          }
          p.subtotal = (parseFloat(p.price) || 0) * (p.quantity || 1)
          p.subtotalText = p.subtotal.toFixed(2)
          products.push(p)
        } else {
          // HTTP 200 但没 data（业务失败 / 商品已下架）：同样算一件没加载出来，不能当"加载成功且为空"
          productFailCount++
          productFailReason = (res && res.message) || productFailReason
          console.warn('[OrderCreate] 商品详情返回空:', it.productId, res && res.message)
        }
      } catch (e) {
        productFailCount++
        productFailReason = (e && e.message) || productFailReason
        console.warn('[OrderCreate] 商品详情加载失败:', it.productId, e && (e.message || e.errMsg))
      }
    }
    if (productFailCount > 0) {
      this._noteLoadError(productFailCount + ' 件商品的信息没加载出来（'
        + (productFailReason || '网络异常') + '），清单里会缺这几行，请退出重进本页重试')
      wx.showToast({ title: '部分商品信息没加载出来', icon: 'none' })
    }

this.setData({ products, stationName: effectiveStationName })
    this.loadStationStatus(effectiveStationId)
    this.syncBarrelSummary()
    this.refreshQuote()
    this.setData({ loading: false })
  },

  /**
   * 水站营业状态（软状态 v32）：横幅文案由后端下发，前端不做 operatingStatus 1..4 的映射
   * （本仓明文禁止自带映射表）。本页 data 只用到 customerHint，就只取它。
   *
   * [2026-09-20 真机联调 · 补漏] 原来本页**调用了这个方法却从未定义它**（c215b2a 2026-09-17
   * 加营业状态横幅时只加了上面那行调用与 data/wxml 字段，漏了方法本体）。后果不是"少个横幅"：
   * 它抛的是 TypeError，且抛在 `loadItemsProducts()` 的 await 链中段 —— 后面的
   * `syncBarrelSummary()` / `refreshQuote()` / `setData({ loading: false })` **一行都不会执行**，
   * 下单页永久停在 `<loading>` 转圈（走「再来一单」时异常被 loadReorder 的 catch 吃掉，
   * 只剩一句 console.error，页面渲染成**商品清单全空**）。静态门禁查不到：audit_wxml_handlers
   * 只管 wxml→js 的绑定，不管 js→js 的调用。
   * 口径与 home/index.js 的同名方法一致：**拉不到就不显示**（宁可不显示，也不编造一个"营业中"），
   * 且绝不把失败抛出去连累后面的渲染。
   */
  async loadStationStatus(stationId) {
    if (!stationId) {
      this.setData({ stationStatusHint: '' })
      return
    }
    try {
      const res = await getStationStatus(stationId)
      const d = (res && res.data) || {}
      this.setData({ stationStatusHint: d.customerHint || '' })
    } catch (e) {
      console.warn('[OrderCreate] 取水站营业状态失败（按"无额外提醒"继续）:', e && (e.message || e.errMsg))
      this.setData({ stationStatusHint: '' })
    }
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

  /**
   * 货到付款确认弹窗的「确认」—— 确认后**必须继续提交**。
   *
   * ⚠️ [2026-09-20 实测修] 原实现只有 `setData({ showOfflineConfirm: false })`：
   *   而 onSubmit 的闸门是 `selectedMethod === 2 && !showOfflineConfirm`（在本文件 onSubmit 内，
   *   搜这个条件即可定位；**不要在这里写行号** —— 加注释本身就会让行号失效）：
   *   于是「点立即下单 → 弹窗 → 点确认」只把弹窗关掉、**什么都没发生**；
   *   再点「立即下单」时标记已被置回 false → 又弹同一个窗 ——
   *   **现金单永远提交不出去**，而后台报错是看不到的（前端根本没发请求）。
   *   判据：确认按钮必须让流程**继续往下走**，不能只改 UI 状态。
   *
   * 为什么不再加一个 offlineConfirmed 标记：showOfflineConfirm 本身就是那个标记 ——
   *   弹窗之所以显示就是因为它为 true；真正开始提交时（onSubmit 里 setData submitting 那一处）
   *   才清零，所以提交失败后下次点击会重新弹窗确认，正是想要的语义。
   */
  onOfflineConfirmOk() {
    this.onSubmit()
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

      // [2026-09-14 修正] 原实现调 /api/stations/mine —— 员工专属接口（@RequireRole
      // STATION_MANAGER/DELIVERY），顾客 token 恒 403，currentStationId 永远为 null，
      // 于是下面那段「水站不一致」提醒成了永不触发的死分支：从 A 站订单「再来一单」时，
      // 即便人已在 B 站也不会得到任何提示，可能按 B 站的价格/库存下单。
      // 改用与首页同一套来源：stationStorage 优先，回退 my-station。
      const currentStationId = await resolveStationId()
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
      // [2026-09-20 真机联调] 原实现只 console.error + 把 loading 关掉：订单详情拉不到时
      // 页面渲染成**一张空白的结算页**（商品清单空、金额 0），客户看到的是"这单不能再来一单了"，
      // 完全不知道是断网还是这单真的没了。
      console.error('[OrderCreate] 再来一单取订单详情失败:', error)
      this.setData({
        loading: false,
        loadError: '没能取到原订单（' + ((error && error.message) || '网络异常') + '），本页内容不可用，请返回重进'
      })
      wx.showToast({ title: '原订单没加载出来，请重试', icon: 'none' })
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
        this.setData({ address: addr, addressText: addr ? formatAddress(addr) : '', addressLoadError: '' })
      }
    } catch (error) {
      // [2026-09-20 真机联调] 原来只 console.error：拉不到地址时页面照旧显示「请选择配送地址」，
      // 而提交时又拦一句「请选择配送地址」—— 把"接口失败"说成了"你没设地址"，客户会去反复
      // 重设地址而问题根本不在那儿。现在把失败标记出来，空态与提交拦截的文案都据此区分。
      console.error('[OrderCreate] 加载收货地址失败:', error)
      this.setData({ addressLoadError: (error && error.message) || '网络异常' })
      this._noteLoadError('收货地址没加载出来（' + ((error && error.message) || '网络异常') + '），可能显示成"未设置地址"')
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
      // [2026-09-20 真机联调] 原来只 console.error：桶权益拉不到时 barrelByType 为空，
      // syncBarrelSummary 会把"已有权益"一律算成 0 → 页面按**全额押金**估算，而提交时后端
      // 按真实权益算 —— 又是"界面一个价、结算另一个价"（本仓计价双轨的老坑）。
      // 拿不到就不假装知道：明确告诉客户这笔押金估算可能偏高。
      console.error('[OrderCreate] 加载桶权益失败:', error)
      this._noteLoadError('桶权益没加载出来（' + ((error && error.message) || '网络异常')
        + '），押金估算可能偏高（真实金额由后端算）')
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
    const { products, selectedMethod, stationId, barrelSummary, address } = this.data
    const seq = this._quoteRequestSeq = (this._quoteRequestSeq || 0) + 1
    const session = captureSession()
    const current = () => seq === this._quoteRequestSeq && isCurrentSession(session)
    this.setData({ quoteLoading: true, quoteReady: false, quoteError: '', blocked: true, checkoutWarnings: [],
      blockReason: '正在核实最新报价，请稍候', payMethods: [], wechatPay: null, missingRights: [], barrelPurchases: [], barrelPurchaseConfirmed: false })

    try {
      if (!session.loggedIn || !session.customerId) throw new Error('请先登录后重新报价')
      if (!products || products.length === 0 || !stationId) throw new Error('请先确认水站和商品')
      const quoteItems = products.map(p => ({
        productId: p.id,
        quantity: p.quantity || 1
      }))
      const res = await getQuote({
        items: quoteItems,
        paymentMethod: selectedMethod,
        stationId: stationId,
        // [v35] 必须带上收货地址：配送范围要靠它取坐标、楼层费要靠它取楼层。
        // 不传的话后端算不出距离与楼层（按"拿不准就不收"处理），
        // 而**下单时是带地址的** → 报价与订单金额就会不一致（本仓记过的"计价双轨"事故）。
        addressId: address && address.id ? address.id : undefined
      })
      if (!current()) return false
      if (!res || (res.code !== undefined && ![0, 200].includes(res.code)) || !res.data) {
        throw new Error('报价结果未核实，请重试')
      }
      {
        const d = res.data
        const totalWaterCost = d.waterAmount || 0
        const totalDeposit = d.barrelDeposit || 0
        const extraDepositBuckets = d.extraDepositBuckets || 0
        const extraDepositAmount = d.extraDeposit || 0
        const totalAmount = d.totalAmount || (totalWaterCost + totalDeposit + extraDepositAmount)
        const allowOfflinePayment = d.allowOfflinePayment === true
        // [v35] 配送费与楼层费：金额与文案都由后端下发，前端只负责展示，不自算、不自造文案
        const deliveryFee = d.deliveryFee || 0
        const floorFee = d.floorFee || 0
        const feeWarnings = Array.isArray(d.warnings) ? d.warnings : []
        // blocked 是"起送量/配送范围配成了不接单"的硬拦结论 —— 与下单侧的拒绝判据同源
        const blocked = d.blocked === true
        const blockReason = d.blockReason || ''
        // 企业身份提示（v50）：服务端开关关着 / 没到阈值 / 已是企业身份时都是空串
        const enterpriseHint = d.enterpriseHint || ''

        // 支付方式列表由服务端下发（含文案、可用性、默认项），前端不再硬编码 1/2/3 的含义
        // 缺失/损坏列表不能被客户端补成可用渠道；金额与支付方式须一起核实。
        // 当前页面仅能执行 PayMethod.java ALL 中的渠道；名称与可用性仍取服务端。
        const payMethods = d.methods
        if (!Array.isArray(payMethods) || !payMethods.length || payMethods.some(m => !m
          || !Number.isInteger(m.id) || ![1, 2, 3].includes(m.id) || typeof m.enabled !== 'boolean'
          || typeof m.name !== 'string' || !m.name.trim())
          || new Set(payMethods.map(m => m.id)).size !== payMethods.length
          || !payMethods.some(m => m.enabled)) throw new Error('支付方式未核实，请重试报价或联系水站')
        // 当前选中项若已不可用（权限被收回），回退到服务端给的默认值。
        // [2026-09-19] selectedMethod 的初值来自"上次用过的支付方式"（本地偏好），
        // 所以这一句同时也是**记住的方式在本站不可用时的回退点**。
        const selectedStillOk = payMethods.some(m => m.id === selectedMethod && m.enabled)
        const defaultMethod = payMethods.find(m => m.id === d.defaultMethod && m.enabled)
        const nextMethod = selectedStillOk ? selectedMethod : (defaultMethod || payMethods.find(m => m.enabled)).id

        const barrelPurchases = d.barrelPurchases === undefined && d.independentRights !== true ? [] : d.barrelPurchases
        if (!Number.isFinite(Number(d.extraDeposit || 0)) || !Array.isArray(barrelPurchases) || barrelPurchases.some(l => !l || !Number.isInteger(l.productId)
          || !Number.isInteger(l.quantity) || l.quantity < 1 || !Number.isFinite(Number(l.unitPrice)) || Number(l.unitPrice) <= 0
          || !Number.isFinite(Number(l.amount)) || Number(l.amount) <= 0
          || Math.abs(Number(l.amount) - Number(l.unitPrice) * l.quantity) > 0.009
          || !quoteItems.some(it => it.productId === l.productId && it.quantity >= l.quantity))
          || new Set(barrelPurchases.map(l => l.productId)).size !== barrelPurchases.length
          || Math.abs(barrelPurchases.reduce((sum,l) => sum+Number(l.amount),0)-Number(d.extraDeposit || 0)) > 0.009)
          throw new Error('本次桶押金明细未核实，请重试报价')
        // ===== 水票抵扣预览（产品口径：水票支付时不显示计费，只计费除去水票的部分）=====
        // 金额一律后端算好下发（quote.ticketPay），前端只渲染，绝不在前端做抵扣算术 ——
        // 前端算一遍就会出现"结算页一个价、扣票另一个价"（本仓记过的计价双轨）。
        // 只在选中水票时用：切到现金/微信后这个键不再相关，留着会让计费区显示错。
        const ticketPay = (nextMethod === 3 && d.ticketPay) ? d.ticketPay : null
        const ticketFullyCovered = !!(ticketPay && ticketPay.fullyCovered)
        // 水票抵扣那行只在**整单能被票结清**时显示：抵不掉时本单根本用不了票，
        // 这时显示"水票抵扣 ¥X"却又要付全额，客户只会以为系统算错了。
        const ticketCoverText = ticketFullyCovered
          ? '水票抵扣 ' + ticketPay.coverQty + ' 张 · ¥' + (Number(ticketPay.coverAmount) || 0).toFixed(2)
          : ''
        const ticketShortfallHint = (ticketPay && !ticketPay.fullyCovered) ? (ticketPay.hint || '') : ''
        // [2026-09-27 走查 C03] 票不足时页面上直接给出**下一步的两个动作**（不再只留一句结论）：
        //   ① 能不能"去买水票"：只有**余额不够**（reason=INSUFFICIENT）才给 ——
        //      商品根本不能用票时补票没用，把人引去买票是错的（走查原文的硬要求）。
        //   ② 能不能"改用 X 支付"：从**后端下发且 enabled** 的方式里挑第一个非水票的；
        //      标签也用后端下发的 name（前端禁止自带 1/2/3 映射表）。
        //      ⚠️ 它只**切换页面上的选择**并重走一次报价，**绝不自动提交**
        //      （走查验收原文：「不得自动改变支付方式并提交」）。
        const ticketCanBuy = !!(ticketPay && !ticketPay.fullyCovered
          && ticketPay.reason === 'INSUFFICIENT')
        const ticketAlt = (payMethods || []).find(m => m.enabled && m.id !== 3)
        const ticketAltMethod = ticketAlt ? ticketAlt.id : null
        const ticketAltLabel = ticketAlt ? ('改用' + (ticketAlt.name || '其它支付方式')) : ''
        // 客户这次**实际要付**的钱：票能全抵 → 0（水费/押金/配送费随票一并结清）；
        // 抵不掉 → 订单全额（本单用不了票，改选方式后就是全额付）。
        // ⚠️ 只用于展示，不参与任何提交参数 —— 提交金额一律由后端按订单重算。
        const payableAmount = ticketPay ? (Number(ticketPay.payableAmount) || 0) : totalAmount

        // 计算每个商品的**缺桶押金**明细（非桶装没有押金，见下）
        const updatedProducts = products.map(p => {
          const deposit = parseFloat(p.deposit) || 0
          const isBarrel = p.category === 1
          let shortage = 0
          let shortageDeposit = 0

          if (isBarrel && d.independentRights === true) {
            const line = barrelPurchases.find(l => l.productId === p.id)
            shortage = line ? line.quantity : 0
            shortageDeposit = line ? Number(line.amount) : 0
          } else if (isBarrel) {
            // 桶装水：只对缺少的空桶收押金
            const held = (barrelSummary || []).find(s => s.productId === p.id)
            const actualBuckets = held ? held.actualBuckets : 0
            shortage = Math.max(0, (p.quantity || 1) - actualBuckets)
            shortageDeposit = deposit * shortage
          }
          // 非桶装（瓶装水 / 一次性桶 / 饮水器）：**押金恒为 0**（2026-09-19）。
          // 押金是循环桶的押金（正本：product.deposit 列注释"只有桶装水使用"），
          // 这里原来写的是"每个都收押金"，与后端同口径一起收口 —— 否则结算页会显示一笔
          // 后端根本不收的押金（计价双轨的老坑）。后端 quote 的 barrelDeposit 同样恒为 0。

          return {
            ...p,
            shortage,
            shortageDepositText: shortageDeposit > 0 ? shortageDeposit.toFixed(2) : '0.00'
          }
        })

        const updates = {
          quoteReady: true,
          quoteError: '',
          barrelPurchases, barrelPurchaseConfirmed: false,
          products: updatedProducts,
          totalWaterCost,
          totalDeposit,
          extraDepositBuckets,
          extraDepositAmount,
          totalAmount,
          totalWaterCostText: totalWaterCost.toFixed(2),
          extraDepositAmountText: extraDepositBuckets > 0 ? extraDepositAmount.toFixed(2) : '',
          totalAmountText: totalAmount.toFixed(2),
          // 计费区展示用（水票支付时"只计费除去水票的部分"）：
          // 票能全抵 → 0.00；否则 = 订单全额。totalAmount/totalAmountText 仍是**真实订单金额**，
          // 两者刻意的分开：展示值绝不能混进任何提交参数。
          payableAmountText: payableAmount.toFixed(2),
          ticketPay,
          ticketCoverText,
          ticketShortfallHint,
          checkoutWarnings: checkoutWarningsOf(blocked, blockReason, feeWarnings, ticketShortfallHint),
          isTicketPay: nextMethod === 3,
          // [走查 C03] 票不足时的两个下一步动作（可用性与标签都由后端数据推导，见上面的注释）
          ticketCanBuy,
          ticketAltMethod,
          ticketAltLabel,
          allowOfflinePayment,
          payMethods,
          // [2026-09-26] 服务端下发的**收款渠道能力**（PayMethod.payChannel）：
          // 建单成功后要不要对同一张单发起 createPayment，判据在这里，不在前端按 id 猜。
          wechatPay: d.wechatPay || null,
          selectedMethod: nextMethod,
          deliveryFee,
          floorFee,
          deliveryFeeText: deliveryFee > 0 ? deliveryFee.toFixed(2) : '',
          floorFeeText: floorFee > 0 ? floorFee.toFixed(2) : '',
          // 费用合计文案（两个都为 0 时不显示这一行）
          feeTotalText: (deliveryFee + floorFee) > 0 ? (deliveryFee + floorFee).toFixed(2) : '',
          feeWarnings,
          blocked,
          missingRights: d.missingRights || [],
          blockReason,
          enterpriseHint,
          // ===== 首次资产/押金告知（契约 A2）=====
          // 判据（firstStationAsset）与金额（depositAmount = 本次缺桶押金，totalAmount 含它）
          // 全部来自服务端 quote —— 与下单侧同一个 AssetService / 同一套计价，前端不另写资产规则。
          firstStationAsset: d.firstStationAsset === true,
          assetNotice: {
            stationName: d.stationName || '',
            depositAmount: (Number(d.depositAmount) || 0).toFixed(2),
            buckets: Number(d.depositBuckets) || 0,
            totalAmountText: totalAmount.toFixed(2),
            // [2026-09-27 产品裁定，正本 docs/design/34] 押金收款方式的两句话
            // （「建议第一单押金单走线上」+「线下提示风险，系统无法检测，纠纷自负」）。
            // ⚠️ **文案由服务端下发**（PaymentServiceImpl.quote 的 depositOnlineAdvice /
            //   depositOfflineRiskNote），前端一个字都不自己拼 —— 文案属口径、不属呈现。
            // ⚠️ 线上渠道当前不可用时服务端不下发 onlineAdvice（不给走不通的建议），
            //   所以这里只做"有没有值"的判断，不在前端判渠道。
            onlineAdvice: d.depositOnlineAdvice || '',
            // 恒有值（只要本单收押金）；由 wxml 按"客户当前选了现金没有"决定显不显示
            offlineRiskNote: d.depositOfflineRiskNote || ''
          },
          // 报价一刷新就作废上一次的"已确认"：站/商品/数量/支付方式变了，告知里的金额与归属就变了，
          // 旧确认不能继续有效（契约 A2 最后一条）。
          assetConfirmed: false,
          // 勾选一并作废：这次的金额/水站可能已经不同，不能拿上次的勾选顶过去
          assetReadAgreed: false
        }

        this.setData(updates)
        // "已下单"态跟着这次报价重算：客户改了数量/地址/支付方式 ⇒ 指纹变了 ⇒ 提示行与按钮文案
        // 自动回到常态「立即下单」，**不需要**在每个改内容的入口手动清状态。
        this._syncPendingOrderState()
        // 弹窗放在 setData 之后、不 await：提示而已，绝不能拖住报价渲染或下单按钮
        this.maybePromptEnterprise(enterpriseHint)
        return true
      }
    } catch (e) {
      if (!current()) return false
      // [2026-09-20 真机联调] 原来只 console.warn 就完了：报价失败时页面停在
      // 「合计 ¥0.00」、支付方式还是上一轮的，而**下单按钮照样可点** ——
      // 真机弱网下会提交一张金额陈旧的订单（钱的事，宁可挡住）。
      // 失败保持 quoteReady=false；点击与提交前等待两处都检查，只有重试成功才能重新提交。
      console.error('[OrderCreate] refreshQuote 失败:', e)
      this.setData({
        blocked: true,
        quoteReady: false,
        payMethods: [], wechatPay: null, ticketPay: null, ticketCoverText: '',
        ticketShortfallHint: '', ticketCanBuy: false, ticketAltMethod: null, missingRights: [],
        barrelPurchases: [], barrelPurchaseConfirmed: false,
        quoteError: '没能核实最新金额和支付方式（' + ((e && e.message) || '网络异常') + '），已暂停下单，请重试报价',
        blockReason: '最新金额和支付方式尚未核实，请重试报价',
        checkoutWarnings: ['最新金额和支付方式尚未核实，请重试报价']
      })
      wx.showToast({ title: '报价加载失败，已暂停下单', icon: 'none' })
      return false
    } finally {
      if (current()) this.setData({ quoteLoading: false })
    }
  },
  onRetryQuote() { return this.refreshQuote() },

  // 生成一个下单幂等键（仅在"新的一次下单意图"时调用：进入页面 / 下单成功后）
  // ⚠️ 现在由 utils/orderIntent.js 统一管理（一次意图一个键、按客户隔离、成功后才清），
  // 这里保留一个薄封装给可能的老调用方，**不要**再绕过 orderIntent 直接用随机键：
  // 那样每次重试都会生成新键，缺货确认/超时重试就会变成第二张单。
  genIdempotencyKey() {
    return orderIntent.newKey()
  },

  /**
   * [2026-09-27 走查 C03] 票不足 → 去购票页补齐。
   * ⚠️ 只有"余额不够"才会露出这个入口（wxml 的 `ticketCanBuy`）：商品根本不能用票时
   * 补票也没用，把人引去买票是错的。购票页是全仓唯一的自助购票入口。
   */
  onGoBuyTicket() {
    wx.navigateTo({ url: '/pages/ticket/index' })
  },

  /**
   * [2026-09-27 走查 C03] 票不足 → 改用其它支付方式。
   *
   * <p>⚠️ **只改页面上的选择、再走一次报价，绝不自动提交** ——
   * 走查的验收原文是「不得自动改变支付方式并提交」：替客户改支付方式还替他下单，
   * 等于替他做了一笔钱的决定。所以这里只 setData + refreshQuote，提交仍要他自己点。</p>
   *
   * <p>id 来自后端下发的可用方式列表（`methods[].id`），前端**不自造** 1/2/3 映射；
   * 页面里再校验一次"它确实在可用列表里"，防止拿数据集的旧值来切。</p>
   */
  onSwitchPayMethod(e) {
    const id = Number(e.currentTarget.dataset.id)
    if (!id) return
    const available = (this.data.payMethods || []).some(m => m.enabled && m.id === id)
    if (!available) {
      wx.showToast({ title: '这个支付方式当前不可用', icon: 'none' })
      return
    }
    if (id === this.data.selectedMethod) return
    // 现金要弹"送达后付款"的确认，切换过去时先清掉上一次的确认态，由 onSubmit 重新触发
    this.setData({ selectedMethod: id, showOfflineConfirm: false })
    // 金额/费用/票预览都要按新的支付方式重算 —— 走页面既有的报价入口，不在前端自己算
    this.refreshQuote()
  },

  /**
   * 提交下单。⚠️ 这里的闸门顺序（报价新鲜度 → 支付方式确认 → 水票是否够 → 押金告知）
   * 是**防"钱与单不一致"**的，改动前先读方法体里的注释，别为了少点一次弹窗调整顺序。
   */
  async onSubmit() {
    if (this.data.submitting) return

    // ===== 购物车没动 = 按钮此刻显示的是「继续支付/查看这笔订单」⇒ 去那张单，不建新单 =====
    // 放在最前面（先于地址/商品校验）：这是"去看已有订单"，不是一次新的提交，
    // 不该因为"这一单的商品刚好下架了"之类的校验被拦下。
    const pending = this._pendingOrderForCurrentCart()
    if (pending) {
      this.onViewPendingOrder()
      return
    }

    if (this.data.quoteLoading) {
      wx.showToast({ title: '正在核实报价，请稍候', icon: 'none' }); return
    }
    if (this.data.quoteError || !this.data.quoteReady) {
      wx.showModal({ title: '报价尚未核实', content: this.data.quoteError || '请先核实最新金额和支付方式，再提交订单。',
        confirmText: '重试报价', cancelText: '留在本页',
        success: result => { if (result.confirm) this.onRetryQuote() } })
      return
    }

    // [v35] 硬拦（起送量/配送范围被站长配成不接单）在前端就地挡住：
    // 让客户填完地址、点了提交才被后端拒，体验上像是"系统坏了"。
    // reason 由后端下发（与 createOrder 的拒绝判据同源），前端不自己判断该不该拦。
    if (this.data.blocked) {
      const missing = (this.data.missingRights || [])[0]
      wx.showModal({
        title: '暂不可下单',
        content: this.data.blockReason || '当前订单暂不满足下单条件，请调整后重试',
        showCancel: !!missing,
        confirmText: missing ? '交桶押金' : '知道了',
        success: (res) => {
          if (missing && res.confirm) wx.navigateTo({ url: '/pages/barrel/purchase?stationId=' + this.data.stationId + '&productId=' + missing.productId + '&quantity=' + missing.quantity + '&from=order' })
        }
      })
      return
    }

    const { products, address, note, stationId, selectedMethod } = this.data

    if (!address) {
      // [2026-09-20 真机联调] address 为空有两种原因：客户确实没设地址 / 地址接口没拉到。
      // 原来一律说「请选择配送地址」，把后者也说成前者 —— 客户会去反复重设地址，
      // 而问题根本不在那儿（AGENTS §8.22 的"失败被当成事实"）。这里按 addressLoadError 分开说。
      wx.showToast({
        title: this.data.addressLoadError
          ? '收货地址没加载出来，请退出重进本页重试'
          : '请选择配送地址',
        icon: 'none'
      })
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

    // [2026-09-19] 水票不足就地拦住（产品口径：「点击水票后优先水票支付，但经校验后
    // 发现水票不足以覆盖该订单时…」——校验点就在这里，而不是等支付时报"余额不足"）。
    //
    // ⚠️ 为什么是"拦"而不是"放过去让它失败"：水票支付是**整单**结清，票不够时
    // `deductTickets` 会抛错 → 订单已创建但 `payment_status` 停在 0 → 按派单判据
    // **这单进不了站长视野**（客户以为下单成功了，实际没人看得见）。宁可当场拦住。
    //
    // 提交前**重跑一次报价**再判定：客户可能刚在别处补了票，用页面上的旧数据会误伤。
    // 重跑不会重复弹企业身份窗（maybePromptEnterprise 有 this.enterprisePrompted 闸门）。
    if (selectedMethod === 3) {
      const verified = await this.refreshQuote()
      // 报价错误已转为可读状态；await 返回后仍须检查，不能继续使用旧票预览建单。
      if (!verified || !this.data.quoteReady || this.data.quoteLoading || this.data.quoteError || this.data.blocked) return
      if (this.data.selectedMethod !== selectedMethod) {
        wx.showToast({ title: '支付方式已变化，请确认后再提交', icon: 'none' }); return
      }
      const tp = this.data.ticketPay
      if (tp && !tp.fullyCovered) {
        // 标题与结论都由后端下发：它要区分"某商品根本不能用票（补票也没用）"
        // 与"票不够（补票或者改选）"，前端分不出来也不该分。
        // [2026-09-20] 按钮从只有一个"知道了"改成**可执行的两个动作**：
        //   · 「去买水票」→ 跳购票页（这就是"先补齐水票再下单"那条路，也是购票页的**唯一入口**）；
        //   · 「留在本页」→ 关掉弹窗回结算页改选支付方式（货到付款/微信在那里切）。
        // 原实现客户看完提示只能自己去找购票入口 —— 而全仓根本没有跳转购票页的地方，等于死路。
        // 判定用后端下发的结构化 reason，**不比对 title 文案**（文案是给人看的，不是判据）。
        const canBuyTicket = tp.reason === 'INSUFFICIENT'
        wx.showModal({
          title: tp.title || '暂不能用票支付',
          content: tp.hint || '水票不足以覆盖本单，请改选支付方式或先补齐水票',
          showCancel: canBuyTicket,
          confirmText: canBuyTicket ? '去买水票' : '改选支付方式',
          cancelText: '留在本页',
          success: (r) => {
            // 只有"余额不够"才把人引去买票；"商品根本不能用票"时补票没用（后端 hint 已写明）
            if (canBuyTicket && r.confirm) {
              wx.navigateTo({ url: '/pages/ticket/index' })
            }
          }
        })
        return
      }
    }

    // 配送中桶提醒：有水桶正在配送中，且本次下单会产生额外桶押金时，先友好提示
    if (this.data.hasInTransitBarrels && this.data.extraDepositBuckets > 0 && !this.data.inTransitReminderAck) {
      this.setData({ showInTransitReminder: true, inTransitReminderAck: true })
      return
    }

    if ((this.data.barrelPurchases || []).length && !this.data.barrelPurchaseConfirmed) {
      this.confirmBarrelPurchase(); return
    }
    // ===== 建单之前必须做完的确认（契约 A2）=====
    // 首次资产/押金告知原来在**建单之后**弹：客户点"取消"时订单已经存在，只能提示他
    // "订单已创建，可在我的订单里取消"。现在改到这里 —— 取消 = 一个写请求都不发。
    // 判据来自 /api/payments/quote 的 firstStationAsset（与下单侧同一个 AssetService），
    // 前端不另写资产规则。
    if (this.data.firstStationAsset === true && !this.data.assetConfirmed) {
      // 弹之前先把勾选清掉：这条路径可能被重复走到（下单失败重试、客户点「返回修改」后再提交），
      // 留着上次的勾选等于"新的一次确认不需要读说明" —— 那是装饰性勾选框，不是确认。
      this.setData({ showAssetConfirm: true, assetReadAgreed: false })
      return
    }

    await this._createOrder(false)
  },

  /** 同次付款仍分项记账；占用中的旧容量可等待，不静默多收一份押金。 */
  confirmBarrelPurchase() {
    const session = captureSession(), seq = this._quoteRequestSeq
    const lines = this.data.barrelPurchases || []
    const busy = lines.some(l => Number(l.busyRights) > 0)
    const text = lines.map(l => (l.productName || '桶装水') + '：新增 ' + l.quantity + ' 份押金 ¥' + Number(l.amount).toFixed(2)).join('；')
    wx.showModal({ title: '确认本次新增押金',
      content: text + '。与本单水款一起付款，收齐款项后押金生效。' + (busy ? '已有容量正在办理其他订单，可等待释放，或明确再买一份。' : '也可返回，在水桶与押金页单独办理。'),
      confirmText: '一起付款', cancelText: busy ? '等待释放' : '返回修改',
      success: result => {
        if (!result.confirm || seq !== this._quoteRequestSeq || !isCurrentSession(session) || !this.data.quoteReady || this.data.blocked) return
        this.setData({ barrelPurchaseConfirmed: true }); this.onSubmit()
      }
    })
  },

  onStandalonePurchase() {
    const missing = (this.data.barrelPurchases || this.data.missingRights || [])[0]
    if (!missing || this.data.submitting || this.data.quoteLoading) return
    wx.navigateTo({ url: '/pages/barrel/purchase?stationId=' + this.data.stationId + '&productId=' + missing.productId + '&quantity=' + missing.quantity + '&from=order' })
  },

  /**
   * 本次下单意图（幂等键的作用域）：这五项任一变化 = 新意图 = 新键。
   * 与 utils/orderIntent.js 的 fingerprint 一一对应。
   */
  _currentIntent() {
    return {
      customerId: getCustomerId(),
      stationId: this.data.stationId,
      addressId: this.data.address && this.data.address.id,
      paymentMethod: this.data.selectedMethod,
      items: (this.data.products || []).map(p => ({ productId: p.id, quantity: p.quantity || 1 }))
    }
  },

  /**
   * 刚刚下的那一单是否仍然对应**当前这个购物车**（= 页面该显示"已下单"态）。
   *
   * <p>判据两条：① 五要素指纹完全相同；② 在 {@link RECENT_ORDER_WINDOW_MS} 内。</p>
   *
   * <p><b>为什么用指纹而不是"键"</b>：指纹里就是客户 + 水站 + 地址 + 商品明细 + 支付方式 ——
   * 客户只要动过其中任何一项，就是另一笔生意，这里自动为 false，"立即下单"照常可用。
   * 于是**不需要**在改数量/改地址的每个入口去手动清理状态（那种"记得清"的写法迟早漏一处）。</p>
   */
  _pendingOrderForCurrentCart() {
    const last = this.data.lastSubmittedOrder
    if (!last || !last.orderId) return null
    if ((Date.now() - (last.at || 0)) >= RECENT_ORDER_WINDOW_MS) return null
    if (last.fingerprint !== orderIntent.fingerprint(this._currentIntent())) return null
    return last
  },

  /** 把"已下单"态投影到页面（按钮文案与提示行都读它）。改内容/离开页面后自行失效。 */
  _syncPendingOrderState() {
    const pending = this._pendingOrderForCurrentCart()
    this.setData({
      pendingOrderId: pending ? pending.orderId : null,
      pendingOrderPaid: pending ? pending.paid === true : false
    })
  },

  /**
   * 从本地读回"刚刚下过的那一单"（进页面时调一次）。**过期的一律丢弃**。
   * 单独抽成方法是为了让流程测试能驱动它 —— 真机上它由 onLoad 调用，而测试不跑 onLoad。
   */
  _restorePendingOrderFromStorage() {
    try {
      const saved = wx.getStorageSync(RECENT_ORDER_KEY)
      if (saved && saved.orderId && saved.fingerprint
          && (Date.now() - (saved.at || 0)) < RECENT_ORDER_WINDOW_MS) {
        this.setData({ lastSubmittedOrder: saved })
      }
    } catch (e) {
      // 读不到 = 没有这层保护，不影响下单
    }
  },

  /** 「看订单详情 ›」：去那一单的结果页（那里有付款/取消等全部入口）。 */
  onViewPendingOrder() {
    const pending = this._pendingOrderForCurrentCart()
    if (!pending) return
    wx.setStorageSync('lastOrderId', pending.orderId)
    wx.redirectTo({ url: `/pages/order/success?id=${pending.orderId}&stationId=${this.data.stationId}` })
  },

  /** 记下"刚刚成功建出来的这一单"，供 _pendingOrderForCurrentCart 判定。 */
  _rememberSubmittedOrder(orderId, fingerprint, paid) {
    const snapshot = {
      orderId: orderId,
      fingerprint: fingerprint,
      at: Date.now(),
      // paid 决定按钮文案：还没结清 ⇒「继续支付」；已付/水票结清 ⇒「查看这笔订单」
      paid: paid === true
    }
    this.setData({ lastSubmittedOrder: snapshot })
    this._syncPendingOrderState()
    // 也写一份到本地：客户会在"跳结果页"与"回下单页"之间来回，页面实例会重建 ——
    // 只放内存里，"已下单"态在重进页面后就没了（而这正是实测那次"点 3 下成交 3 单"的场景）。
    // ⚠️ 读回时不另判客户：指纹第一段就是 customerId，换账号后指纹必然不同、状态自然不生效
    //    （与 utils/orderIntent.js「按客户隔离」同一纪律）。
    try {
      wx.setStorageSync(RECENT_ORDER_KEY, snapshot)
    } catch (e) {
      // 存不下就只保内存版：宁可少一层保护，也不阻断下单
    }
  },

  /** 这一次提交最终有没有把钱结清（决定"已下单"态显示「继续支付」还是「查看这笔订单」）。 */
  _markPendingOrderPaid(paid) {
    const last = this.data.lastSubmittedOrder
    if (!last || !last.orderId) return
    this._rememberSubmittedOrder(last.orderId, last.fingerprint, paid)
  },

  /**
   * 真正发建单请求。**只有这里会建单**。
   *
   * @param {boolean} confirmShortage 缺货弹窗里客户选了"同意等待安排"才为 true；
   *        它进的是同一个幂等键 + 同一份业务请求（服务端 requestDigest 不含这个标记，
   *        所以"同键带标记重提"天然可用）。
   */
  async _createOrder(confirmShortage) {
    // 防连点（契约：重复点击不重复下单）。闸门放在**这里**而不是只放在 onSubmit：
    // 押金确认、缺货同意、结果未知重试都会调本方法，任何一处被连点都会变成第二次建单请求。
    // 服务端还有幂等键兜底，但"根本别发出去"更省事、也不会让客户看到两次 loading。
    // ⚠️ 这道闸门只管"请求还没回来的那几秒" —— "成功之后又点一次"由下面的"已下单态"负责。
    if (this.data.submitting) {
      return
    }

    // ===== 购物车没动 = 这一单已经下好了：直接去那张单，**不再建单、也不弹任何确认框** =====
    // 业内主流形态：下单成功后页面进入"已下单"态（按钮变「继续支付/查看订单」），
    // 重复提交这个动作**根本没有入口**；只有改动了购物车内容才会回到「立即下单」。
    // 这里再判一次是因为"押金确认/缺货同意/结果未知重试"也走本方法，任何一条路都不许重复建单。
    const pending = this._pendingOrderForCurrentCart()
    if (pending) {
      wx.setStorageSync('lastOrderId', pending.orderId)
      wx.redirectTo({ url: `/pages/order/success?id=${pending.orderId}&stationId=${this.data.stationId}` })
      return
    }

    const idempotencyKey = orderIntent.keyFor(this._currentIntent())
    // 「提交结果未知」是个**要留在页面上**的状态：说明本次响应没带回订单号、可以重试。
    // 只有普通失败（网络异常）才把它归位，否则那条提示会被 finally 冲掉。
    let unknownResult = false
    this.setData({
      submitting: true,
      submitState: 'creating',
      showOfflineConfirm: false,
      showAssetConfirm: false,
      showShortageConfirm: false,
      idempotencyKey
    })

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

      const freshRequest = {
        items: items,
        addressId: this.data.address.id,
        specialNote: this.data.note,
        source: 3,
        paymentMethod: this.data.selectedMethod,
        stationId: this.data.stationId,
        extraDeposit: this.data.extraDepositAmount || 0,
        barrelPurchases: (this.data.barrelPurchases || []).map(l => ({ productId: l.productId, quantity: l.quantity, unitPrice: Number(l.unitPrice) })),
        idempotencyKey: idempotencyKey,
        // 只有客户明确同意等待才为 true；否则服务端返回 needConfirm（**不建单**）
        confirmShortage: confirmShortage === true
      }
      // 2026-10-03：报价失败会清明细，但未知建单重试必须重放原补购快照，不能变成另一请求。
      if (!this._originalOrderRequest || this._originalOrderRequest.body.idempotencyKey !== idempotencyKey) {
        if ((freshRequest.barrelPurchases || []).length && !this.data.barrelPurchaseConfirmed) { this.confirmBarrelPurchase(); return }
        this._originalOrderRequest = { body: JSON.parse(JSON.stringify(freshRequest)), session: captureSession() }
      }
      if (!isCurrentSession(this._originalOrderRequest.session)) return
      const request = JSON.parse(JSON.stringify(this._originalOrderRequest.body))
      if (confirmShortage === true) request.confirmShortage = true
      this._originalOrderRequest.body = request
      const orderRes = await createOrder(request)

      const data = (orderRes && orderRes.data) || null

      // ① 缺货：**还没有建单**（服务端在 INSERT 之前就返回了，见 OrderServiceImpl 的缺货分支）。
      //    绝不能当成功、不能付款、不能跳成功页 —— 幂等键也**保留**，等客户表态。
      if (data && data.needConfirm === true) {
        this._showShortageConfirm(data.shortages)
        return
      }

      // ② 建单成功：必须拿到一个**合法订单号**才算成功。
      //    旧代码写 `orderRes.data?.orderId || orderRes.data || null` —— 缺货时把整个响应对象
      //    当成了订单号，水票路径拿它去发支付请求（Long 反序列化失败 → 500），
      //    现金路径把它拼进 success 页 URL（订单根本不存在，页面却显示"等待配送"）。
      const orderId = pickOrderId(data)
      if (orderId == null) {
        // ③ 无法确认提交结果：**不支付、不跳成功页**，把判断权交回客户。
        //    服务端可能已经建单（响应丢了），所以**保留幂等键** —— 点"重试查一次"会用同一个键
        //    重新提交，服务端按幂等命中返回原单，不会产生第二张单。
        //    ⚠️ 这个状态**不能被下面的 finally 冲掉**（submitState 归位要判一下），
        //       否则页面上那条"结果未知 + 重试"的提示永远不会出现（流程测试抓过这一条）。
        unknownResult = true
        this.setData({
          submitState: 'unknown',
          unknownResultText: '没能确认这次提交的结果。点"重试查一次"会用同一次提交的标识重新查询，'
            + '不会重复下单；也可以先到「我的订单」看看有没有这张单。'
        })
        return
      }

      // 这一单确实建出来了 ⇒ 本次意图了结。
      // ⚠️ [2026-09-26 修正] 原来这里**立刻** orderIntent.clear()，于是同一页面上再点一次就是
      //   新键 ⇒ 新单（实测点 3 下成交 3 单）。现在键留着、并记下"刚下过这一单"，
      //   页面据此进入"已下单"态（按钮变「继续支付/查看订单」）；
      //   真正的"再下一单"是**改了购物车内容**（指纹一变，状态自动失效）。
      this._rememberSubmittedOrder(orderId, orderIntent.fingerprint(this._currentIntent()), false)
      // [2026-09-19] 记下这次用的支付方式（只存本地）。放在成功之后：失败/被拒不该污染"上次成功用过的"。
      payMethodStorage.set(this.data.selectedMethod)

      await this.proceedToPayment(orderId, data)
    } catch (error) {
      // 网络失败/超时：同样**可能已经建单**，所以不动幂等键，只是把状态说清楚
      console.error('[OrderCreate] 下单失败（可能未建单，也可能已建单但响应丢失）:', error)
      if (error && error.businessRejected) this._originalOrderRequest = null
      this.setData({ submitState: 'idle' })
      wx.showModal({
        title: '下单没提交成功',
        content: (error && error.message ? error.message + '\n\n' : '')
          + '如果刚才网络中断，这张单可能已经建出来了。点「重试」会沿用同一次提交的标识，'
          + '不会重复下单。',
        confirmText: '重试',
        cancelText: '先不提交',
        success: (r) => {
          if (r.confirm) this._createOrder(false)
        }
      })
    } finally {
      this.setData(unknownResult ? { submitting: false } : { submitting: false, submitState: 'idle' })
    }
  },

  /** 缺货确认弹窗（契约 A1）：说清"哪些商品、要多少、现在有多少、等货的含义"。 */
  _showShortageConfirm(shortages) {
    const list = Array.isArray(shortages) ? shortages : []
    const lines = list.map(s => {
      const name = s.productName || '商品'
      const need = s.requested == null ? '' : `要 ${s.requested} 桶`
      const stock = s.stock == null ? '当前缺货' : `现有 ${s.stock} 桶`
      return `${name}：${need}${need && stock ? '，' : ''}${stock}`
    })
    this.setData({
      showShortageConfirm: true,
      shortageItems: list,
      shortageText: lines.join('\n') || '部分商品当前库存不足',
      submitting: false,
      submitState: 'idle'
    })
  },

  /** 缺货后"返回调整"：关掉弹窗，页面仍可编辑；**没有建单、没有付款**（契约 A1）。 */
  onShortageBack() {
    this.setData({ showShortageConfirm: false })
    wx.showToast({ title: '已返回，可调整数量或换个商品', icon: 'none' })
  },

  /** 缺货后"同意等待安排"：同一幂等键 + confirmShortage=true 重提（只可能产生一张单）。 */
  onShortageAgree() {
    if (!this.data.quoteReady || this.data.quoteLoading || this.data.quoteError || this.data.blocked) {
      wx.showToast({ title: '请先核实最新报价', icon: 'none' }); return
    }
    this.setData({ showShortageConfirm: false })
    this._createOrder(true)
  },

  /** 「提交结果未知」时的重试：同一个键重提，服务端命中幂等就返回原单。 */
  onRetryUnknown() {
    this._createOrder(false)
  },

  async proceedToPayment(orderId, data) {
    // 下单响应里的 warnings（水站营业状态提示 / 欠桶提醒 / 缺货提示）**必须让客户看到**：
    // 后端一直在下发，前端从来没读过，等于白提醒。营业状态是"不阻断但要说清楚"的软状态。
    //
    // ⚠️ [2026-09-26 修] 这里传的必须是**已解包的 data**（本函数第二参数就是解包后的 data）：
    //   原实现两边形状不一致 —— 调用方传 `data`，而 showOrderWarnings 又去读 `orderRes.data.warnings`，
    //   等于读 `data.data.warnings`（恒 undefined），营业中/欠桶这类软提醒被**静默丢掉**。
    //   现在两侧统一为"解包后的 data"（形参已改名 orderData，免得再被当成响应体）。
    await this.showOrderWarnings(data)

    // ===== 3 = PayMethod.TICKET（水票支付）：下单即视同已付，补一条支付流水用于对账 =====
    // ===== 1 = PayMethod.WECHAT（微信）：**只有服务端确认走模拟渠道**时才发这一笔 =====
    //
    // [2026-09-26] 原来只有水票那一支会调 createPayment，微信建完单直接去结果页 ——
    //   于是"选微信 → 订单停在待收款(1) → 站长/配送员列表里看不见这张单"，
    //   而服务端的模拟渠道**只在 PaymentServiceImpl.createPayment 里成形**
    //   （方式=微信 且 app.payment.mock-wechat-pay=true → 服务端重算金额、写 PAID 流水、
    //   订单置 payment_status=2、入账押金），光建单永远不会触发它。
    //   现在两支走**同一条**同单付款路径：金额/流水/押金/库存预留全部沿用服务端原路径，
    //   前端不自己置已付、也不碰真实 wx.requestPayment。
    //
    // 判据来自服务端报价下发的 wechatPay.enabled（唯一实现在 PayMethod.payChannel），
    // 前端**不按 id===1 猜**渠道能力 —— 那等于把渠道开关复制到客户端。
    const payMethod = this.data.selectedMethod
    const wechatReady = !!(this.data.wechatPay && this.data.wechatPay.enabled)
    if (payMethod === 3 || (payMethod === 1 && wechatReady)) {
      const outcome = await this._paySameOrder(orderId, payMethod)
      // "已下单"态的按钮文案跟着真实结果走：结清了就显示「查看这笔订单」，没结清显示「继续支付」
      this._markPendingOrderPaid(outcome.ok === true)
      if (!outcome.ok) {
        // 失败/超时不等于"没下单"：**先回查原单的支付事实**（契约 A3），再决定怎么说。
        // 已扣票或已入账的情况绝不能再次扣款 —— _paySameOrder 只是回读，不做任何写动作。
        await new Promise((resolve) => {
          // paid：票/模拟渠道其实成功了，只是响应没回来；否则是**确实还没付**，
          // 必须给"再试一次"这条同单恢复路径，而不是把人丢到一个点不动的结果页。
          const title = outcome.state === 'paid' ? '已经付好了' : '订单已提交，但支付还没成功'
          const body = outcome.state === 'paid'
            ? '款项已经结清，可以放心等配送。'
            : ((outcome.error && outcome.error.message) || '网络异常')
              + '。订单已经建好了（订单号 ' + orderId + '），但这一笔支付没有完成。'
          wx.showModal({
            title,
            content: body,
            showCancel: outcome.state !== 'paid',
            confirmText: outcome.state === 'paid' ? '看订单' : '再试一次',
            cancelText: '看订单',
            success: (r) => {
              if (!r.confirm) {
                // 取消 = 去结果页（那里有后端下发的「去支付」，仍是同一张单）
                this.retryPaymentAfterFailure = false
                return
              }
              this.retryPaymentAfterFailure = outcome.state !== 'paid'
            },
            complete: () => resolve()
          })
        })
        if (this.retryPaymentAfterFailure) {
          this.retryPaymentAfterFailure = false
          // 同一个订单号重试：服务端一单一条活跃流水（uk_payment_active_order）兜底，
          // 已有待收款流水时 createPayment 会把它原样返回，不会落第二条、也不会双扣。
          const retry = await this._paySameOrder(orderId, payMethod)
          if (!retry.ok) {
            // 重试仍不成功：停在结果页（有「去支付」入口 + 后端下发的付款说明），不假报成功
            wx.showToast({
              title: retry.state === 'paid' ? '已经付好了' : '还没付成功，可在本页继续支付',
              icon: 'none'
            })
          }
        }
      }
    }

    wx.setStorageSync('lastOrderId', orderId)
    wx.redirectTo({ url: `/pages/order/success?id=${orderId}&stationId=${this.data.stationId}` })
  },

  /**
   * 对**同一张订单**发起付款（水票 / 模拟微信），并回读订单的真实支付事实。
   *
   * <p>失败时返回 {@code {ok:false, state}}，{@code state} 取值：
   * {@code 'paid'}（款项其实已结清，只是响应丢了）/ {@code 'unpaid'}（确实没付）/
   * {@code 'closed'}（已退款或已取消）/ {@code 'unknown'}（查不到，不猜）。</p>
   *
   * <p><b>⚠️ 只有服务端回读到的 {@code paymentStatus === 2} 才算成功</b>
   * （{@link #_verifyOrderPayment}）：后端对已存在的活跃流水是**幂等返回**，
   * 真渠道下那条是 {@code PENDING(1)} —— 若拿"请求成功了"当"钱收到了"，
   * 就是在没有真实渠道的部署里假报已付（本仓最忌讳的"界面说做了、账上没动"）。</p>
   */
  async _paySameOrder(orderId, paymentMethod) {
    try {
      await createPayment({
        orderId,
        customerId: getCustomerId(),
        amount: this.data.totalAmount,
        waterAmount: this.data.totalWaterCost,
        barrelDeposit: this.data.totalDeposit,
        extraDepositBuckets: this.data.extraDepositBuckets,
        extraDepositAmount: this.data.extraDepositAmount,
        paymentMethod: paymentMethod,
        ticketProductId: null,
        ticketQty: null
      })
      // 请求成功 ≠ 钱到账：一律回读订单的支付事实（服务端重算的金额与状态才是真相）
      return { ok: (await this._verifyOrderPayment(orderId)) === 'paid' }
    } catch (e) {
      console.error('[OrderCreate] 同单支付失败，先回查原单支付事实:', e)
      const state = await this._verifyOrderPayment(orderId)
      return { ok: state === 'paid', state, error: e }
    }
  },

  /** 回查原单的支付事实（只读；失败就说"查不到"，不猜）。 */
  async _verifyOrderPayment(orderId) {
    try {
      const res = await getOrderDetail(orderId)
      const ps = res && res.data ? Number(res.data.paymentStatus) : null
      if (ps === 2) return 'paid'
      if (ps === 3 || ps === 4) return 'closed'
      return 'unpaid'
    } catch (e) {
      console.warn('[OrderCreate] 回查订单支付状态失败:', e && e.message)
      return 'unknown'
    }
  },

  /**
   * 展示下单响应里的 warnings（非阻断）。
   * wx.showModal 是**回调式** API（本仓没有 promisify），所以这里包一层 Promise
   * 以便在跳转前把提示显示完 —— 直接 await wx.showModal(...) 会恒得 undefined。
   *
   * ⚠️ [2026-09-26 修] 入参是**已经解包的业务 data**（`_createOrder` 里的 `data`），
   *   不是整个响应体。原实现的形参叫 `orderRes` 且读 `orderRes.data.warnings`，
   *   而调用方传进来的就是 data ⇒ 实际读的是 `data.data.warnings`，恒 undefined，
   *   于是营业中/欠桶这类软提醒**静默消失**。形参与名字现在都对齐"解包后的 data"。
   */
  showOrderWarnings(orderData) {
    const warnings = (orderData && orderData.warnings) || []
    if (!Array.isArray(warnings) || !warnings.length) return Promise.resolve()
    return new Promise((resolve) => {
      wx.showModal({
        title: '下单成功，请注意',
        content: warnings.join('\n'),
        showCancel: false,
        confirmText: '知道了',
        complete: () => resolve()
      })
    })
  },

  // ===== 首次资产业务确认弹窗（契约 A2：**建单之前**）=====
  // 原来的"取消"发生在建单之后，只能告诉客户"订单已创建"；现在取消 = 一个写请求都不发。
  onAssetConfirmCancel() {
    this.setData({ showAssetConfirm: false, assetConfirmed: false })
    wx.showToast({ title: '没有提交订单，可以继续修改', icon: 'none' })
  },

  onAssetConfirmOk() {
    if (!this.data.quoteReady || this.data.quoteLoading || this.data.quoteError || this.data.blocked) {
      wx.showToast({ title: '请先核实最新报价', icon: 'none' }); return
    }
    // [2026-09-26] 说明弹窗里那个勾选是**真的闸门**，但**不能**做成"灰按钮点了没反应"：
    // 弹窗刚打开时勾选一定是 false（每次弹都重置），此时按钮若是死的，客户看到的就是
    // 一个点不动的按钮 —— 正是本仓 §8.30 那类最难排查的形态。
    // 所以这里改成**给出下一步**：说清为什么、并直接把他送到说明那一屏（两下就能继续）。
    if (!this.data.assetReadAgreed) {
      wx.showModal({
        title: '请先看使用说明',
        content: '下单前请先了解水桶与押金的使用说明，确认后即可继续下单。',
        confirmText: '看说明',
        cancelText: '再想想',
        success: (r) => {
          if (r.confirm) {
            this.onAssetDetailTap()
          }
        }
      })
      return
    }
    // 客户点了"确认下单"才建单（顺序就是契约要的那一条）
    this.setData({ assetConfirmed: true, showAssetConfirm: false })
    this._createOrder(false)
  },

  // ===== 企业身份申请（v50）=====
  //
  // 产品口径：「订水时检测到大额订单，会弹出确认是否是企业，可申请企业身份」——
  // **客户侧没有独立入口**，这一步就是全部入口。
  //
  // 三个设计取舍（改之前先读）：
  //   ① 每次进入页面**只弹一次**（this.enterprisePrompted 是挂在页面实例上的，不是 data）：
  //      报价在改数量/改支付方式时都会重算，若不加这个闸门，用户每点一下加号就被弹一次。
  //   ② 弹窗前先查一次"我在本站有没有待审的申请"：已经申请过的人不再骚扰
  //      （重复提交后端虽然幂等，但天天弹同一个窗很烦）。查询失败**不阻断** ——
  //      宁可多弹一次，也不能因为一次网络抖动就把"能申请"这件事静默丢掉。
  //   ③ 全程不阻断下单：无论用户点"暂不"、还是提交失败，都不影响本次下单流程。
  maybePromptEnterprise(hint) {
    if (!hint || this.enterprisePrompted) return
    // 先置位再弹：showModal 是异步回调，这里若不先置位，同一轮里的第二次报价会再弹一个
    this.enterprisePrompted = true
    this.checkPendingEnterpriseApply().then((hasPending) => {
      if (hasPending) return
      wx.showModal({
        title: '企业订水',
        content: hint,
        confirmText: '申请企业',
        cancelText: '暂不',
        success: (r) => {
          if (r.confirm) this.askEnterpriseName()
        }
      })
    })
  },

  /** 本站是否已有待审申请（拿不到就说"没有"，即允许弹窗）。 */
  async checkPendingEnterpriseApply() {
    try {
      const res = await getMyEnterpriseApplies(this.data.stationId)
      const list = (res && res.data) || []
      return list.some((a) => a.status === 'PENDING')
    } catch (e) {
      console.warn('[OrderCreate] 查询企业身份申请失败（按未申请处理）:', e.message)
      return false
    }
  },

  /**
   * 只问企业名称（后端必填项就这一个），用 wx.showModal 的 editable 形态 ——
   * 需求是"最小闭环、不新增页面"，为这一句话单开一个表单页不值当。
   * 联系人/电话/税号都可留空：站长审核时看得到是谁在下单（订单里有收货人）。
   */
  askEnterpriseName() {
    wx.showModal({
      title: '企业名称',
      editable: true,
      placeholderText: '请填写营业执照上的企业全称',
      success: (r) => {
        if (!r.confirm) return
        const companyName = (r.content || '').trim()
        if (!companyName) {
          wx.showToast({ title: '企业名称不能为空', icon: 'none' })
          return
        }
        this.submitEnterpriseApply(companyName)
      }
    })
  },

  async submitEnterpriseApply(companyName) {
    const address = this.data.address || {}
    try {
      await submitEnterpriseApply({
        stationId: this.data.stationId,
        companyName,
        // 电话可用就用收货人电话兜底：站长要打电话核实时不至于拿到一条没有任何联系方式的申请
        contactPhone: address.phone || undefined
      })
      wx.showModal({
        title: '申请已提交',
        content: '水站站长审核通过后，你会成为企业客户。本次下单不受影响，可以继续。',
        showCancel: false,
        confirmText: '继续下单'
      })
    } catch (e) {
      // 服务端开关关掉时这里收到的就是「企业身份功能当前未开启」，原样展示服务端文案
      wx.showToast({ title: e.message || '提交失败，请稍后重试', icon: 'none' })
    }
  },

  // ===== 桶与押金说明弹窗 =====
  onAssetDetailTap() {
    // 获取水站电话
    this.fetchStationPhone()
    this.setData({ showAssetDetail: true })
  },

  /**
   * 说明弹窗里的勾选（真的闸门）：勾上之后上一屏的「确认下单」才可点。
   * ⚠️ 每次重新弹**首次确认**时都要重置成 false（见 refreshQuote 里 setData 的 assetReadAgreed），
   * 否则"上次勾过"会让这次的新金额/新水站不再需要确认。
   */
  onAssetReadAgree() {
    this.setData({ assetReadAgreed: !this.data.assetReadAgreed })
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
      // [2026-09-26] 这里原来还弹一句「水站电话没取到，请稍后重试」的 toast ——
      // 而拉电话是**进页面时顺手做的**，客户此刻并没有在要打电话：一句自己没触发的失败提示
      // 只会让人以为下单出了问题。现在只记 console，真正的兜底在 onCallStation：
      // 取不到电话就带他去「客服」页（那里会区分"没查到"与"没登记"）。
      console.warn('[OrderCreate] 获取水站电话失败:', e && (e.message || e.errMsg))
    }
  },

  /**
   * 「桶和押金怎么算？」弹窗里的电话行：取到就拨号，取不到就跳「客服」页。
   * ⚠️ 不要在取不到时干点什么也不做 —— 那一行是客户问押金的唯一入口（同 §8.30 判据）。
   */
  onCallStation() {
    if (this.data.stationPhone) {
      wx.makePhoneCall({ phoneNumber: this.data.stationPhone })
      return
    }
    this.setData({ showAssetDetail: false })
    wx.navigateTo({ url: '/pages/service/index' })
  },

  // ===== 费用明细弹窗 =====
  onShowDetail() {
    this.setData({ showDetailPopup: true })
  },

  onDetailPopupClose() {
    this.setData({ showDetailPopup: false })
  }
})
