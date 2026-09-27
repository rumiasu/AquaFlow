const { completeOrder, getOrderDetail } = require('../../api/delivery')
const { get } = require('../../utils/request')

const REASON_OPTIONS = [
  { key: 'customer_kept', label: '客户留存' },
  { key: 'lost', label: '路上丢失' },
  { key: 'damaged', label: '破损' },
  { key: 'wrong', label: '送错' },
  { key: 'other', label: '其他' }
]

Page({
  /** 弹窗内容区吞掉点击（wxml 用 catchtap 绑定，此处为空实现，避免未定义方法告警） */
  stopPropagation() {},

  data: {
    orderId: null,
    from: 'detail',
    orderInfo: null,
    orderLoaded: false,
    /** 回桶核对行 —— **只含桶装水明细**（后端 barrelItem=true），瓶装水/饮水机不进这一块 */
    items: [],
    /** 本单有没有要核对回桶的明细；false 时整块不渲染（瓶装水单没有"回桶"这回事） */
    hasBarrelItems: false,
    /** 顶部副标题：没有回桶这件事时不能还说"核对回桶后确认完成" */
    successSubtitle: '核对回桶后确认完成',
    noteText: '',
    // 客户卡（[2026-09-26] 卡片式重排）：这两个值在 loadOrder 里算好，wxml 不做拼接/兜底
    contactPhone: '',
    addressText: '',
    // 送达凭证（原名「签收凭证」，[2026-09-26] 改名）：**客户不一定在现场签收**，
    // 所以不要求签字，照片只证明"水送到了"。上传 type=1（正常送达），与后端 order_image.type 一致。
    photos: [],
    uploading: false,
    // v43：楼层数（选填）+ 楼梯凭证照片（有争议时用；不影响向客户收的楼层费）
    reportedFloor: '',
    floorPhotos: [],
    floorUploading: false,
    /**
     * 楼层块是否展开（[2026-09-26] 产品口径：楼层一般不会"不清楚"，改成**有争议才展开**）。
     * 两种途径展开：① 载入时发现**地址没写清有没有电梯**（见 elevatorUnknown）；② 点「楼梯有争议」。
     */
    floorBlockOpen: false,
    /** 地址有楼层、但 `addressHasElevator` 是空（三态里的"没确认过"，见 utils/address.js） */
    elevatorUnknown: false,
    isCashOnDelivery: false,
    // 本单**是否还要现场收钱**（后端 needCollect 投影：现金单且未付）。
    // 与 isCashOnDelivery 分开：现金单付过款之后就不该再问一次"收了没"（契约 C1）。
    needCollect: false,
    // 现场是否已收款：**必须由人明确选择**（null = 还没选）。
    // 原来默认 false（未收款）且预先选中 —— 等于替配送员答了题；反过来默认 true 更糟
    //（把没收到的钱记成已收）。所以两边都不默认，提交前强制表态（契约 C1）。
    collected: null,
    collectedChosen: false,
    // 备货情况（后端 stockPrep 投影，契约 C4）
    stockPrep: null,
    stockPrepText: '',
    // 「更多（选填）」：备注 / 楼层与楼梯凭证（契约 C2：正常路径简短，异常与选填再展开）
    showMore: false,
    // 防连点：提交在途时不再发第二次（服务端有状态 CAS 兜底，但第二次会让已经成功的人看到报错）
    submittingComplete: false,
    showReasonPicker: false,
    currentReasonItemIdx: -1,
    reasonOptions: REASON_OPTIONS
  },

  onLoad(options) {
    if (options.id) {
      this.setData({ orderId: options.id, from: options.from || 'detail' })
      this.loadOrder(options.id)
    }
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
  },

  async loadOrder(id) {
    try {
      const res = await getOrderDetail(id)
      const order = res.data
      if (!order) return

      const isFirstBarrelOrder = order.firstBarrelOrder === true
      const orderItems = order.items || []
      // 回桶只对**桶装水**成立（判据由后端下发 `barrelItem`，来自 util/BarrelScope 这一个实现）。
      // 以前这里把每条明细都画成回桶行、默认值还等于送出数，两个后果：
      //   · 瓶装水 / 饮水机被提交成"回桶 N" → 桶账的物理上限（占用 0）直接拒掉整笔完成配送，
      //     报错是「回收空桶数(2)超过该客户当前持有数(0)」，配送员看不懂；
      //   · 混合单（桶装水 + 瓶装水）必踩 —— 只要不手动把瓶装水那行改成 0 就提交不了。
      const barrelItems = orderItems.filter(it => it.barrelItem === true)
      const items = barrelItems.map(item => {
        // 默认回桶数 = 后端算好的 `suggestedReturnQty`（= 客户手上**已有的旧桶**，
        // 上限「占用 = 权益 + over」且不超过送出桶数）。
        //
        // ⚠️ [2026-09-26 产品口径] **本单新买押金的桶不参与回收**：买桶是买桶、换水是换水，
        // 只有"旧桶换新水"那部分默认回（原话：「新付押金买的桶不需要计入回收，
        // 但是非本次订单产生押金的桶则默认计入回收」）。首单全是新买的押金桶 ⇒ 默认 0；
        // 混合单只默认回客户原本就有的那几个。这个减法**只在后端算**
        // （BarrelService#returnPlanOfOrder），前端不自己推。
        const suggested = item.suggestedReturnQty || 0
        return {
          id: item.id,
          productName: item.productNameSnapshot || item.productName || '未知商品',
          brand: item.brandSnapshot || '',
          spec: item.specSnapshot || '',
          /**
           * 本行**送出**桶数 —— 只用于显示「送出 N 桶」。
           * ⚠️ 不要拿它当 `expected`：那是「该回几桶」，本单新买的押金桶不该回，
           * 用送出数当 expected 会立刻触发"少回收 N 桶、请填异常原因"（首单必卡）。
           */
          sentQty: item.quantity || 0,
          /** 该回几桶（后端口径：客户手上的旧桶） */
          expected: suggested,
          /** 实际回桶数，默认 = 该回数；配送员可改（改小要填少桶原因） */
          actual: suggested,
          discrepancy: 0,
          reasons: [],
          reasonQtySum: 0
        }
      })

      // 收款口径一律取**后端投影**（Orders.getNeedCollect / getPayMethodText / getPayStateText）：
      // 前端此前自己按 1/2/3 重算（`pm !== 1 && ps !== 2`），水票未付会被显示成「货到付款」——
      // 与后端 needCollect（要求 payment_method = 2 现金）不是一回事，属"前端自带映射表"（AGENTS §6）。
      const needCollect = order.needCollect === true
      const prep = order.stockPrep || null

      // 楼层 / 电梯（[2026-09-26]）：`addressHasElevator` 是**三态**（null = 客户没确认过 /
      // 0 = 无电梯 / 1 = 有电梯，见 utils/address.js）。**只有"知道楼层但电梯未知"**才算
      // "客户没写清楚" —— 那种单最容易就楼层补贴扯皮，所以自动把楼层块露出来；
      // 有电梯 / 明确无电梯 / 连楼层都没填的普通单保持收起（产品口径：楼层一般没有不清楚的现象）。
      const floorRaw = order.addressFloor
      const floorKnown = floorRaw !== null && floorRaw !== undefined && floorRaw !== ''
      const liftRaw = order.addressHasElevator
      const liftKnown = liftRaw === 0 || liftRaw === '0' || liftRaw === 1 || liftRaw === '1'
      const elevatorUnknown = floorKnown && !liftKnown

      this.setData({
        orderInfo: order,
        orderLoaded: true,
        isFirstBarrelOrder,
        items,
        hasBarrelItems: items.length > 0,
        // 首单 / 纯瓶装水单都没有"核对回桶"这一步，副标题不能再说"核对回桶后确认完成"
        successSubtitle: (isFirstBarrelOrder || items.length === 0) ? '确认交付后完成' : '核对回桶后确认完成',
        isCashOnDelivery: needCollect,
        needCollect,
        collected: null,          // 交付事实由人确认，不预选
        collectedChosen: false,
        stockPrep: prep,
        stockPrepText: this._prepText(prep),
        // 客户卡上的两个可点动作（[2026-09-26]）：与订单详情页同一套口径 ——
        // 电话取 receiverPhone（订单快照）优先：跨站履约单的 customerPhone 是**刻意置空**的
        //（画像归归属站，见 util/CustomerProfileMask），快照里的收件人电话照常可用。
        contactPhone: order.receiverPhone || order.customerPhone || '',
        addressText: order.addressSnapshot || order.addressDetail || '',
        // 楼层数**默认带出地址里的楼层**（客户填过就省得配送员再输一遍）；
        // 地址没填就留空 —— 有楼层才填，没有就不填（空 = 沿用地址，两边都没有就不补）。
        reportedFloor: floorKnown ? String(floorRaw) : '',
        elevatorUnknown,
        // 电梯未知 → 直接展开楼层块（并让 wxml 说明为什么展开）；否则收起，等"楼梯有争议"再展开
        floorBlockOpen: elevatorUnknown,
        // ⚠️ 楼层块在「更多」里面：只置 floorBlockOpen 而不同时展开「更多」= **等于没展开**
        //    （站长/配送员根本看不到那一块）。只有"电梯未知"这一种自动展开，所以这里只跟着它走；
        //    普通单仍然保持"更多"收起 —— 正常送达只有商品 + 回桶 + 收钱（契约 C2）。
        showMore: elevatorUnknown
      })
    } catch (err) {
      this.setData({ orderLoaded: false })
      wx.showToast({ title: '加载订单失败', icon: 'none' })
    }
  },

  /**
   * 备货情况文案（后端下发，契约 C4）。
   * 口径是「实物 − 活跃预留」，**不是** inventory.quantity；这里只做展示，
   * 真正拦住"少扣一点先把单结了"的是后端出库前那次校验（提示可能过期）。
   */
  _prepText(prep) {
    if (!prep) return ''
    if (prep.ready === true) return '本单已备齐'
    const parts = []
    const items = prep.items || []
    items.forEach(it => {
      parts.push(`${it.productName || '商品'} 还缺 ${it.shortage} 桶`)
    })
    if (prep.itemsWithoutCredential > 0) {
      parts.push('有商品还没登记备货')
    }
    if (!parts.length) return ''
    return '还差：' + parts.join('、') + '（完成配送时系统会再核对一次）'
  },

  onToggleMore() {
    this.setData({ showMore: !this.data.showMore })
  },

  /* ==================== 客户卡上的两个动作（[2026-09-26]） ====================
   * 与订单详情页逐字同源：拨号用 order.receiverPhone || customerPhone，
   * 复制用地址快照优先（客户改了地址也不能导错/抄错）。
   * ⚠️ 在门口点了没反应比没有这个按钮更糟：拿不到值就**不出声地不动作**是不行的，
   *    这里给一句 toast（wxml 上这两行本来也是 wx:if 有值才渲染，正常不会走到）。
   */
  onCallPhone() {
    const phone = this.data.contactPhone
    if (!phone) {
      wx.showToast({ title: '这单没有可拨的电话', icon: 'none' })
      return
    }
    wx.makePhoneCall({ phoneNumber: phone, fail: () => {} })
  },

  onCopyAddress() {
    const address = this.data.addressText
    if (!address) {
      wx.showToast({ title: '这单没有地址', icon: 'none' })
      return
    }
    wx.setClipboardData({
      data: address,
      success: () => wx.showToast({ title: '地址已复制', icon: 'success' })
    })
  },

  /**
   * 站长从本单跳到既有的「商品与库存」补货（契约 C4）。
   * <p>刻意**不新建页面、不新建入口体系**：只是把人送到既有的那条路上
   * （入库/盘点在那页，权限本来就只开给站长）。普通配送员看不到这个按钮 ——
   * 他既没有入库权限，也不该被引导去做站长的事。</p>
   */
  onGoInventory() {
    const app = getApp()
    const role = app && app.globalData ? app.globalData.role : null
    if (role !== 'STATION_MANAGER') {
      wx.showToast({ title: '请让站长补货后再送', icon: 'none' })
      return
    }
    wx.navigateTo({ url: '/pages/station-mgmt/products/index' })
  },

  onActualChange(e) {
    const idx = parseInt(e.currentTarget.dataset.idx)
    const val = Math.max(0, parseInt(e.detail.value) || 0)
    this._updateItemActual(idx, val)
  },

  onActualDecrease(e) {
    const idx = parseInt(e.currentTarget.dataset.idx)
    const item = this.data.items[idx]
    this._updateItemActual(idx, Math.max(0, item.actual - 1))
  },

  onActualIncrease(e) {
    const idx = parseInt(e.currentTarget.dataset.idx)
    const item = this.data.items[idx]
    this._updateItemActual(idx, item.actual + 1)
  },

  _updateItemActual(idx, val) {
    const items = [...this.data.items]
    items[idx].actual = val
    items[idx].discrepancy = items[idx].expected - val
    this.setData({ items })
  },

  /** 少桶原因的**数量之和**——展示、闸门、校验三处必须用同一个数（契约 C2）。 */
  _reasonSum(item) {
    return (item.reasons || []).reduce((s, r) => s + (Number(r.qty) || 0), 0)
  },

  _updateReasonOptions() {
    const idx = this.data.currentReasonItemIdx
    if (idx < 0) return
    const item = this.data.items[idx]
    const missing = item.expected - item.actual
    const used = this._reasonSum(item)
    // 闸门按**数量之和**，不再按"原因条数"：原来 1 条原因 × 3 桶就被当成"3 桶配额用完了"，
    // 于是"少 3 桶只勾 1 条原因"在前端就通不过；反过来把一条改成 3 桶后又能再加两条。
    const reasonOptions = REASON_OPTIONS.map(r => ({
      ...r,
      checked: item.reasons.some(reason => reason.key === r.key),
      disabled: !item.reasons.some(reason => reason.key === r.key) && used >= missing
    }))
    this.setData({ reasonOptions })
  },

  onOpenReasonPicker(e) {
    const idx = parseInt(e.currentTarget.dataset.idx)
    this.setData({ showReasonPicker: true, currentReasonItemIdx: idx }, () => {
      this._updateReasonOptions()
    })
  },

  onCloseReasonPicker() {
    this.setData({ showReasonPicker: false, currentReasonItemIdx: -1 })
  },

  onSelectReason(e) {
    const reasonKey = e.currentTarget.dataset.key
    const idx = this.data.currentReasonItemIdx
    if (idx < 0) return

    const items = [...this.data.items]
    const item = items[idx]
    const missing = item.expected - item.actual

    const existingIdx = item.reasons.findIndex(r => r.key === reasonKey)
    if (existingIdx >= 0) {
      item.reasons.splice(existingIdx, 1)
    } else {
      const used = this._reasonSum(item)
      if (used < missing) {
        // 新选一项时**预填当前还没分配的缺口**（不是写死 1 桶）：少 3 桶选"客户留存"
        // 大多是 3 桶，让人再手改三次是白费功夫；数量仍可改（契约 C2）。
        item.reasons.push({ key: reasonKey, qty: missing - used })
      }
    }
    item.reasonQtySum = this._reasonSum(item)
    this.setData({ items }, () => {
      this._updateReasonOptions()
    })
  },

  onReasonQtyChange(e) {
    const { idx, ridx } = e.currentTarget.dataset
    const val = Math.max(0, parseInt(e.detail.value) || 0)
    const items = [...this.data.items]
    items[idx].reasons[ridx].qty = val
    items[idx].reasonQtySum = this._reasonSum(items[idx])
    this.setData({ items }, () => {
      this._updateReasonOptions()
    })
  },

  onRemoveReason(e) {
    const { idx, ridx } = e.currentTarget.dataset
    const items = [...this.data.items]
    items[idx].reasons.splice(ridx, 1)
    items[idx].reasonQtySum = this._reasonSum(items[idx])
    this.setData({ items })
  },

  onSelectCollected(e) {
    const val = e.currentTarget.dataset.value === 'true'
    this.setData({ collected: val, collectedChosen: true })
  },

  onNoteInput(e) {
    this.setData({ noteText: e.detail.value })
  },

  /* ==================== 楼层数 + 楼梯凭证（v43）====================
   * 为什么要有这两样：楼层补贴是给配送员的钱，只有他知道自己爬了几层 ——
   *   ① 楼层数**选填**：有楼层就填、没有就不填；不填时后端沿用客户地址里的楼层；
   *   ② 照片**不强制**（产品决定），但拍一张站得住脚 —— 与客户扯皮时（"你不是说 6 楼吗"）
   *      这是唯一的凭证，站长也可以事后补传。
   * ⚠️ 它不影响向客户收的楼层费 —— 那笔钱在下单时就按地址快照了。
   *
   * [2026-09-26 产品口径] 这一块**从"常显"改成"有争议才展开"**（原注释记的是旧判据
   * "藏进更多里人会跳过不填 = 少拿钱"）。产品原话：「楼层数藏在下面吧……不过楼层我觉得
   * 一般没有不清楚的现象，做成楼梯有争议时触发吧，楼梯凭证也一起」。
   * 兜底（防止"该填而没人提醒"）：地址**没写清有没有电梯**时载入即自动展开并说明原因，
   * 见 loadOrder 里的 elevatorUnknown。**改回常显前先回去读这句产品原话。**
   */
  onFloorInput(e) {
    this.setData({ reportedFloor: e.detail.value })
  },

  /** 「楼梯 / 楼层有争议」→ 展开楼层块（同时把楼梯凭证一起给出来） */
  onOpenFloorDispute() {
    this.setData({ floorBlockOpen: true })
  },

  onAddFloorPhoto() {
    if (this.data.floorPhotos.length >= 3 || this.data.floorUploading) return
    const { upload } = require('../../utils/upload')
    const { API } = require('../../config/api')
    wx.chooseImage({
      count: 3 - this.data.floorPhotos.length,
      sizeType: ['compressed'],
      success: async (res) => {
        this.setData({ floorUploading: true })
        const uploads = res.tempFilePaths.map(p => upload({
          filePath: p,
          url: API.ORDER_IMAGE_UPLOAD,
          name: 'file',
          // 3 = 楼梯凭证（1 正常送达 / 2 异常），后端 order_image.type 的注释里有
          formData: { orderId: this.data.orderId, type: 3 }
        }).then(r => r.data))
        try {
          const urls = await Promise.all(uploads)
          this.setData({ floorPhotos: this.data.floorPhotos.concat(urls.filter(Boolean)) })
        } catch (err) {
          wx.showToast({ title: err.message || '上传失败', icon: 'none' })
        } finally {
          this.setData({ floorUploading: false })
        }
      }
    })
  },

  onPreviewFloorPhoto(e) {
    const { index } = e.currentTarget.dataset
    wx.previewImage({ current: this.data.floorPhotos[index], urls: this.data.floorPhotos })
  },

  onRemoveFloorPhoto(e) {
    const { index } = e.currentTarget.dataset
    this.setData({ floorPhotos: this.data.floorPhotos.filter((_, i) => i !== index) })
  },

  onAddPhoto() {
    if (this.data.photos.length >= 3 || this.data.uploading) return
    const { upload } = require('../../utils/upload')
    const { API } = require('../../config/api')
    wx.chooseImage({
      count: 3 - this.data.photos.length,
      sizeType: ['compressed'],
      success: async (res) => {
        this.setData({ uploading: true })
        const uploads = res.tempFilePaths.map(p => upload({
          filePath: p,
          url: API.ORDER_IMAGE_UPLOAD,
          name: 'file',
          formData: { orderId: this.data.orderId, type: 1 }
        }).then(r => r.data))
        try {
          const urls = await Promise.all(uploads)
          this.setData({ photos: this.data.photos.concat(urls.filter(Boolean)) })
        } catch (err) {
          wx.showToast({ title: err.message || '上传失败', icon: 'none' })
        } finally {
          this.setData({ uploading: false })
        }
      }
    })
  },

  onPreviewPhoto(e) {
    const { index } = e.currentTarget.dataset
    wx.previewImage({ current: this.data.photos[index], urls: this.data.photos })
  },

  onRemovePhoto(e) {
    const { index } = e.currentTarget.dataset
    this.setData({ photos: this.data.photos.filter((_, i) => i !== index) })
  },

  _validate() {
    // 钱的事实必须先被确认（契约 C1）：本单还要收款时，"收了没"不许有默认值。
    if (this.data.needCollect && !this.data.collectedChosen) {
      wx.showToast({ title: '请先确认这单收到钱没有', icon: 'none' })
      return false
    }
    for (let i = 0; i < this.data.items.length; i++) {
      const item = this.data.items[i]
      const missing = item.expected - item.actual
      if (missing > 0) {
        // 按**数量之和**校验（与服务端同一条判据，见 OrderWorkflowServiceImpl.assertReturnReasonsMatchGap）
        const totalReasonQty = this._reasonSum(item)
        if (totalReasonQty !== missing) {
          wx.showToast({
            title: `${item.productName} 少 ${missing} 桶，异常原因合计 ${totalReasonQty} 桶，请补齐`,
            icon: 'none'
          })
          return false
        }
      }
    }
    return true
  },

  async onConfirmComplete() {
    if (!this.data.orderLoaded || !this.data.orderId || !this.data.orderInfo) {
      wx.showToast({ title: '订单未加载完成', icon: 'none' })
      return
    }
    if (!this._validate()) return

    const { items, isFirstBarrelOrder } = this.data
    const hasAnyReturn = items.some(it => it.actual > 0)

    // 首单（押金桶）本来就不回桶，不再问"全部为 0 是否确认"（契约 C1）：
    // 首单页面连步进器都不画，actual 恒为 0 ⇒ 每个首单都必弹一次这个窗，纯噪音。
    if (!isFirstBarrelOrder && !hasAnyReturn && items.length > 0) {
      wx.showModal({
        title: '确认回桶数',
        content: '本单桶装水的回桶数都是 0，是否确认无误？',
        confirmText: '确认无误',
        success: (res) => {
          if (res.confirm) this._showConfirmDialog()
        }
      })
      return
    }

    // 不再单独问一次"确认未收款"：收款在页面上已经要人明确选过（collectedChosen），
    // 下面那份摘要会把它写成"已送达，待收款"——同一件事问两遍正是契约 C2 要收敛掉的。
    this._showConfirmDialog()
  },

  _showConfirmDialog() {
    const { items, needCollect, collected, orderInfo } = this.data
    let s = ''
    items.forEach(it => {
      if (it.expected === 0 && it.actual === 0) return   // 首单押金桶：不占摘要
      s += `${it.productName}：回桶 ${it.actual}/${it.expected}`
      if (it.discrepancy !== 0) {
        s += `（少${Math.abs(it.discrepancy)}）`
      }
      s += '\n'
    })
    if (needCollect) {
      // 现金未收时**不许**写成"订单已结清"（契约 C2）：账户上这单还是待收款。
      s += collected ? '✓ 已收款' : '⚠ 已送达，待收款'
      s += '\n'
    } else if (orderInfo && orderInfo.payStateText) {
      s += `${orderInfo.payStateText}\n`
    }
    // [2026-09-20 预防层] 只列明细还不够：配送员要知道「点下去会发生什么、能不能撤」。
    // 「完成配送」是**不可逆**动作 —— 订单立刻闭环、计件工钱同时产生，事后没有系统内的回退通道
    // （订单类误操作只能线下联系客户协商，见 AGENTS §0.6 / design/20 §5.2）。
    s += '\n订单将立即结算为「已完成」，计件工钱同时产生，且不能撤回。'

    wx.showModal({
      title: '确认完成配送',
      content: s.trim(),
      confirmText: '确认完成',
      confirmColor: '#2E9E6B',
      success: async (res) => {
        if (!res.confirm) return
        this._doSubmit()
      }
    })
  },

  async _doSubmit() {
    // 防连点（契约：重复点击不重复出库/回桶/计件）。服务端还有状态 CAS 兜底
    // （第二次会拿到"该订单当前状态不可完成配送"），但那会让配送员在**已经成功**之后
    // 看到一个红色报错弹窗——所以闸门放在客户端这里。
    if (this.data.submittingComplete) {
      return
    }
    const { orderId, items, noteText, collected, needCollect, reportedFloor } = this.data
    this.setData({ submittingComplete: true })

    const itemReturns = items.map(it => ({
      orderItemId: it.id,
      productName: it.productName,
      expected: it.expected,
      actual: it.actual,
      reasons: it.reasons.map(r => ({ key: r.key, qty: Number(r.qty) || 0 }))
    }))

    wx.showLoading({ title: '提交中...' })
    try {
      await completeOrder(orderId, {
        itemReturns,
        note: noteText,
        // 非现金单在服务端本来就不看这个字段；仍显式给 true 是为了不把"未收款"误传给别的方式。
        // 现金单必须是人选过的值（_validate 已保证）。
        collected: needCollect ? collected : true,
        // v43：楼层数选填（有就填、没有不填）。填了才是楼层补贴的依据，
        // 与客户地址里填的不一致时后端会在收益明细里标记出来（防虚报）。
        reportedFloor: reportedFloor === '' || reportedFloor === null ? null : Number(reportedFloor)
      })
      wx.hideLoading()
      wx.showToast({ title: '配送完成！', icon: 'success' })
      setTimeout(() => {
        if (this.data.from === 'home') {
          wx.switchTab({ url: '/pages/home/index' })
        } else {
          wx.navigateBack({ delta: 2 })
        }
      }, 1500)
    } catch (err) {
      // 停在原页、保留现场填写的内容，让人按提示改（契约 C3：给能做的下一步，不吞异常）。
      // 服务端文案已按 C3 改成"一句事实 + 订单号"，内部术语只留在后端日志里。
      wx.hideLoading()
      wx.showModal({
        title: '没能完成配送',
        content: (err && err.message) || '提交失败，请检查网络后重试',
        showCancel: false,
        confirmText: '知道了'
      })
    } finally {
      // 成功/失败都要放开闸门，否则失败后连重试都点不动
      this.setData({ submittingComplete: false })
    }
  }
})
