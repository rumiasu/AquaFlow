const { getTicketAccounts, getTicketRecords, getTicketPackages, purchaseTicket } = require('../../api/ticket')
const { getStationProducts } = require('../../api/product')
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
      paymentMethod: 1,
      // 选中的档位 id（null = 散买，按单张水票价）。**只用于定制档位**。
      packageId: null,
      // 选中的**统一折扣档张数**（null = 没选统一档）。与 packageId 互斥。
      // ⚠️ 只传张数、不传价：价格由后端按"这款水自己的价 × 该档折扣"现算
      // （产品口径「对应水怎么统一打折」），客户端算价就等于自己定价。
      unifiedQty: null,
      // 散买单张价。选了档位后 faceValue 会变成档位均价，用它才能退回散买口径
      looseFaceValue: 0
    },
    // 当前商品在本站能买的档位（定制档位 or 站级统一折扣折算出来的档位，见后端 TicketTierService）
    buyPackages: [],
    // [2026-09-26 产品口径] 购票是**自助预付**，不是「向水站申请、等水站同意」：
    // 客户自己下单、自己付钱，水站只负责「收到钱」这个事实。
    // 文案两处都要真：**不能**写「提交申请后由水站确认收款」（把收款确认说成审批，已改），
    // **也不能**写「无需水站确认」—— 真实微信渠道还没接入，当前部署里"到账"这一步确实由站长
    // 确认收到钱才完成。所以只描述渠道与结果，到没到账由提交后返回的真实 status 决定（见 onBuySubmit）。
    buyMethods: [
      { id: 1, name: '微信支付', desc: '微信收款，付款到账后水票即可使用' }
    ],
    submitting: false,
    // 在线购票幂等键：同一笔购买意图（含失败重试）复用同一个值，购买成功后才重新生成。
    // 后端 v33 起必传 —— 无订单支付在数据库层没有任何防重，缺了它连点两次「买票」
    // 会落两条待收款流水，站长两条都确认就会入账两次。
    purchaseIdempotencyKey: '',
    currentStationId: null,
    currentStation: null,
    showStationPicker: false,
    stationList: [],
    // ===== 失败标记（[2026-09-20 真机联调]，全部空串/ false = 一切正常）=====
    // 为什么需要：本页原来三处静默降级，失败时页面渲染成「可用水票 0 张 / 暂无水票 / 暂无记录」，
    // 与"这客户确实没有水票"完全无法区分（AGENTS §8.22）。水票是**花钱买来的资产**，
    // 显示成 0 张比直接报错更让人慌 —— 客户会以为票没了。
    loadError: '',          // 汇总提示（顶部提示条）
    accountsFailed: false,  // 水票余额没拉到 → 余额显示「—」而不是 0
    recordsFailed: false,   // 消费记录没拉到 → 空态改成"没加载出来"
    packagesError: '',      // 档位没拉到（不影响买散票，只在购票弹窗里说明）
  },

  onShow() {
    this.loadData()
  },

  // 生成一个购票幂等键（仅在"新的一次购买意图"时调用：进入页面 / 购买成功后）
  genPurchaseIdempotencyKey() {
    return 'ticket_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9)
  },

  async loadData() {
    // 优先读取本地存储的水站
    let stationId = stationStorage.getId()
    let station = stationStorage.get()

    this.setData({ currentStationId: stationId, currentStation: station })

    this.setData({ loading: true })
    try {
      // [2026-09-20 真机联调] 原来是「商品一个 `.catch(() => null)` + 余额/记录整体 try/catch 里
      // 只 console.error」：任一失败都被吞掉，页面照旧渲染「可用水票 0 张 / 暂无水票 / 暂无记录」，
      // 与"确实没有水票"无法区分（AGENTS §8.22）。现在逐个降级（成功的那部分照常展示），
      // 并把失败项汇总到顶部提示条 + 对应的「没加载出来」空态。
      const failed = []
      const softCatch = (tag) => (e) => {
        failed.push(tag)
        console.warn('[Ticket] ' + tag + ' 加载失败:', e && (e.message || e.errMsg))
        return null
      }
      let productsRes = null
      if (stationId) {
        // 2026-09-16：不再回退到 /api/products/on-sale —— 那是**全平台**在售列表，
        // 会把别站可售商品塞进"买水票"弹窗（跨站可见性漏洞）。没有选水站就只展示已持有水票。
        productsRes = await getStationProducts(stationId).catch(softCatch('可购商品'))
      }

      const [accountsRes, recordsRes] = await Promise.all([
        getTicketAccounts(stationId).catch(softCatch('水票余额')),
        getTicketRecords(stationId).catch(softCatch('消费记录'))
      ])

      if (failed.length) {
        this.setData({
          loadError: '有 ' + failed.length + ' 项没加载出来（' + failed.join('、')
            + '），下面显示的余票/记录可能不全，请退出重进本页重试'
        })
        wx.showToast({ title: '水票数据没加载全，请重试', icon: 'none' })
      } else {
        this.setData({ loadError: '' })
      }

      if (accountsRes && accountsRes.data) {
        const accounts = accountsRes.data
        const totalTickets = accounts.reduce((sum, a) => sum + (a.remainQuantity || 0), 0)
        const totalValue = accounts.reduce((sum, a) => sum + (a.remainQuantity || 0) * (a.effectiveTicketPrice || a.faceValue || a.price || 0), 0)
        this.setData({ accounts, totalTickets, totalValue, accountsFailed: false })
      } else if (!accountsRes) {
        // 拉失败：保留旧数据不动，并把余额显示成「—」—— 绝不能让它停在 0 张
        this.setData({ accountsFailed: true })
      }
      if (recordsRes && recordsRes.data) {
        this.setData({ records: recordsRes.data, recordsFailed: false })
      } else if (!recordsRes) {
        this.setData({ recordsFailed: true })
      }
      if (productsRes && productsRes.data) {
        // 列表里放两类商品，**都是真实商品**（2026-09-20 产品口径：「在用户端看起来没区别…
        // 不是专门卖统一水票」—— 所以这里**不会**出现"统一水票"这种商品）：
        //   ① 本站开了定制票的（ticketEnabled === 1）；
        //   ② 桶装水（category === 1）—— 站长配了站级统一折扣时，它也能按折扣买票。
        //      "到底能不能买"由点进去拉到的档位决定（后端 TicketTierService 一处判据），
        //      拉不到档位就提示一句，不在这儿猜。
        const buyProducts = productsRes.data.filter(p => p.ticketEnabled === 1 || p.category === 1)
        this.setData({ buyProducts, currentStationId: stationId })
      }
    } catch (error) {
      // 兜底分支：上面的每个请求都已各自 softCatch，走到这里只可能是本地代码出错
      console.error('[Ticket] Load ticket data error:', error)
      this.setData({ loadError: '水票数据没加载出来（' + ((error && error.message) || '本地异常') + '），请重试' })
    } finally {
      this.setData({ loading: false })
    }
  },

  async loadStationList() {
    // [2026-09-20 真机联调 · 出声] 原来是 `.catch(() => null)` + 外层只 console.error：
    // 失败时 stationList 为空数组，页面（如果有）就渲染成「暂无可用水站」—— 把"没查到"说成
    // "平台真的没有水站"。⚠️ 本页 wxml 目前**没有任何选站 UI**（onOpenStationPicker /
    // onSelectStation / onCloseStationPicker 三个 handler 在 wxml 里零引用 = 不可达），
    // 所以这里只留可查的痕迹 + 一次 toast，不新增只能在未来生效的 data 字段。
    try {
      const res = await getPublicStations()
      // 业务失败仍是 HTTP 200，一律判 body.code（AGENTS §8.1）
      if (!res || res.code !== 0 || !res.data) {
        console.warn('[Ticket] 水站列表返回非成功响应:', res && res.message)
        return
      }
      const activeStations = res.data.filter(s => s.status === 1)
      this.setData({ stationList: activeStations })
    } catch (e) {
      console.error('[Ticket] 加载水站列表失败:', e)
      wx.showToast({ title: '水站列表没加载出来，请重试', icon: 'none' })
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
          confirmText: '继续',
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
          cancelText: '继续提示',
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
    this.setData({ showPurchase: false, buyPackages: [], buyForm: { productId: null, productName: '', faceValue: 0, quantity: 1, totalPrice: 0, paymentMethod: 1, packageId: null, unifiedQty: null, looseFaceValue: 0 } })
  },

  /** 弹窗内容区吞掉点击，避免冒泡到遮罩触发关闭（wxml 用 catchtap 绑定） */
  stopPropagation() {},

  onBuyProductSelect(e) {
    const { id } = e.currentTarget.dataset
    // dataset 类型可能是 string/number，统一按字符串比较，避免 === 恒 false
    const product = this.data.buyProducts.find(p => String(p.id) === String(id))
    if (!product) return
    // 面值 = 后端下发的本站水票价（与 /api/tickets/purchase 的计费完全同源），不再用零售价。
    // ⚠️ 走统一折扣的商品没配水票价 → 这个值可能是 0，此时**只能按档位买**（页面不会显示散买）。
    const price = parseFloat(product.effectiveTicketPrice || product.price) || 0
    // 换商品必须清掉已选档位：档位是「本站 + 本商品」的，留着上一个商品的档位
    // 会被后端以"档位与本水站/本商品不匹配"拒绝
    this.setData({
      'buyForm.productId': product.id,
      'buyForm.productName': product.name,
      'buyForm.faceValue': price,
      'buyForm.looseFaceValue': price,
      'buyForm.packageId': null,
      'buyForm.unifiedQty': null,
      'buyForm.quantity': 1,
      'buyForm.totalPrice': price
    })
    this.loadBuyPackages(product.id)
  },

  /**
   * 拉该商品在本站能买的档位。
   *
   * <p>后端返回的每一项带 {@code source}：{@code CUSTOM} = 站长给这款水挂的定制档位；
   * {@code UNIFIED} = 站级统一折扣按**这款水自己的价**折算出来的档位。两者在界面上
   * **长得一样**（产品口径：「在用户端看起来没区别」），前端只需记住选了哪一个。</p>
   *
   * <p>没档位就返回空数组 —— 此时页面保持"散买"（按单张水票价）。但对于**走统一折扣的商品**，
   * 散买没有价可依（它没配水票价），所以后端会拒；页面对这种商品不显示散买入口。</p>
   */
  async loadBuyPackages(productId) {
    this.setData({ buyPackages: [], packagesError: '' })
    try {
      const res = await getTicketPackages(this.data.currentStationId, productId)
      this.setData({ buyPackages: res.data || [] })
    } catch (err) {
      // [2026-09-20 真机联调] 这里原来是**刻意的静默降级**（"档位拉不到不该挡住买票"）——
      // 不阻断买票这条判据仍然成立（下面照旧退回散买），但"静默"是错的：档位 = 越买越便宜的
      // 价目表，拉不到时客户看到的是"这款水没有优惠"，与"确实没配档位"完全无法区分
      //（AGENTS §8.22）。现在改成**出声但不阻断**：弹窗内留一条说明 + 一次轻提示。
      console.warn('[Ticket] load ticket packages failed:', err && err.message)
      this.setData({ packagesError: '档位没加载出来（' + ((err && err.message) || '网络异常') + '），现在只能按单张水票价买' })
      wx.showToast({ title: '优惠档位没加载出来', icon: 'none' })
    }
  },

  /**
   * 选档位。金额一律用**服务端下发的档位价**，前端不做 price/qty 的算术 ——
   * 均价是快照进水票批次的值，前端算一遍就会出现"界面一个价、批次另一个价"。
   * 张数必须等于档位张数（后端会校验）。
   *
   * <p>定制档回传 {@code packageId}；统一折扣档**只回传张数** {@code unifiedQty}
   * （价格由服务端按该款水的价 × 折扣现算，客户端传不了价也不该传）。</p>
   */
  onBuyPackageSelect(e) {
    const { id, source } = e.currentTarget.dataset
    // 统一档没有 packageId，只能按 qty 找；定制档按 id 找
    const pkg = this.data.buyPackages.find(p => source === 'UNIFIED'
      ? (p.source === 'UNIFIED' && String(p.qty) === String(id))
      : (p.source !== 'UNIFIED' && String(p.packageId || p.id) === String(id)))
    if (!pkg) return
    const custom = pkg.source !== 'UNIFIED'
    this.setData({
      'buyForm.packageId': custom ? (pkg.packageId || pkg.id) : null,
      'buyForm.unifiedQty': custom ? null : pkg.qty,
      'buyForm.quantity': pkg.qty,
      'buyForm.faceValue': pkg.unitPrice,
      'buyForm.totalPrice': pkg.price
    })
  },

  /** 退回散买：张数与单价都回到按单张水票价的口径。 */
  onBuyPackageClear() {
    const loose = this.data.buyForm.looseFaceValue || 0
    this.setData({
      'buyForm.packageId': null,
      'buyForm.unifiedQty': null,
      'buyForm.quantity': 1,
      'buyForm.faceValue': loose,
      'buyForm.totalPrice': loose
    })
  },

  /** 步进器 +/-（tap 事件，data-type=minus/add），数量下限 1 */
  onBuyQtyStep(e) {
    const { type } = e.currentTarget.dataset
    const cur = this.data.buyForm.quantity || 1
    const next = type === 'add' ? cur + 1 : Math.max(1, cur - 1)
    if (next === cur) return
    this.applyLooseQty(next)
  },

  /** 手动输入数量（input 事件） */
  onBuyQuantityInput(e) {
    this.applyLooseQty(Math.max(1, parseInt(e.detail.value) || 1))
  },

  /**
   * 改张数即放弃已选档位，回到散买口径。
   *
   * 档位价对应的是**固定张数**（后端会校验 quantity === pkg.qty）。张数一变，档位价就不再适用；
   * 若留着 packageId，提交会被后端以"购买张数与档位不一致，请重新选择"拒回 ——
   * 与其让客户撞一次错误，不如在这里直接退回散买并把单价换回单张水票价。
   */
  applyLooseQty(qty) {
    const loose = this.data.buyForm.looseFaceValue || 0
    this.setData({
      'buyForm.packageId': null,
      'buyForm.unifiedQty': null,
      'buyForm.quantity': qty,
      'buyForm.faceValue': loose,
      'buyForm.totalPrice': loose * qty
    })
  },

  /** 选择支付方式（tap 事件，data-id） */
  onBuyPaymentMethodSelect(e) {
    const { id } = e.currentTarget.dataset
    this.setData({ 'buyForm.paymentMethod': parseInt(id) || 1 })
  },

  async onBuySubmit() {
    // 连点保护：按钮上的 `disabled` 只改背景色，**拦不住 tap**（见 index.wxss 的 .modal-btn.disabled）。
    // 同一个幂等键虽然能兜住"库里落两条流水"，但第二次请求会再弹一次结果、再刷新一次页面。
    if (this.data.submitting) {
      return
    }
    const { productId, quantity, paymentMethod, packageId, unifiedQty } = this.data.buyForm
    if (!productId && productId !== 0) {
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

    // 幂等键：同一笔购买意图（含失败重试）必须复用同一个值，否则「连点两次」或
    // 「超时后重试」都会各落一条待收款流水，站长两条都确认就会入账两次。
    // 与 pages/order/create.js 的差别：那里失败后重新生成键（下一单是新意图），
    // 这里失败时**保留**原键 —— 购票只有「买成」与「没买成」两种结果，重试就是在重试同一件事。
    const idempotencyKey = this.data.purchaseIdempotencyKey || this.genPurchaseIdempotencyKey()
    this.setData({ submitting: true, purchaseIdempotencyKey: idempotencyKey })
    try {
      const res = await purchaseTicket({
        productId: productId,
        waterTypeId: productId, // 兼容旧字段
        quantity: quantity,
        paymentMethod: paymentMethod,
        stationId: this.data.currentStationId,
        idempotencyKey: idempotencyKey,
        // 定制档：张数与总价一律以服务端档位配置为准（客户端传的价格会被忽略）
        packageId: packageId || null,
        // 统一折扣档：**只传张数**，价格由服务端按"这款水自己的价 × 该档折扣"现算
        unifiedQty: unifiedQty || null
      })
      // 后端返回的是流水的**真实状态**，不要替它猜：
      //   2 = 已支付（票已入账，模拟微信渠道下当场就是这个）
      //   1 = 待收款（真实微信渠道未接入的部署里等水站确认收到钱 —— 那是收款确认，不是审批）
      const status = (res && res.data && res.data.status) != null ? res.data.status : 1
      this.onClosePurchase()
      // 本次购买意图已落库，换一个新键，避免「下一次购买」被当成重放而返回上一笔
      this.setData({ purchaseIdempotencyKey: this.genPurchaseIdempotencyKey() })
      await this.loadData()
      if (status === 2) {
        wx.showToast({ title: '购买成功，水票已到账', icon: 'success' })
      } else {
        wx.showModal({
          title: '等待到账',
          content: '水站还没确认收到这笔钱，确认后水票自动到账。如已付款较长时间仍未到账，请联系水站。',
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