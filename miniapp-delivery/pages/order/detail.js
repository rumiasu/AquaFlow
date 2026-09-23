// 订单详情页
const { getOrderDetail, completeOrder, transferOrder, returnToStation, reportOrder, getStaffList, dispatchOrder, resolveOrder, requestCancel } = require('../../api/delivery')
// ⚠️ 楼层凭证（v43）用 utils/request 直接调：路径写常量、不往 api/ 或 config/api.js 加
// —— 那两个文件正被另一个工作流（商品图片库）改动。
const { get, put, post } = require('../../utils/request')
const ORDER_IMAGE_BY_ORDER = '/api/order-images/by-order'
// 支付流水（2026-09-18 接线）：GET 按订单查流水（后端 PaymentController.listByOrderId），
// PUT 单笔退款（后端 PaymentController.refund，站长专属）。
// 与上面的 ORDER_IMAGE_BY_ORDER 同一惯例：常量写在页面 js 顶部，不加进 config/api.js。
const PAYMENTS_BY_ORDER = '/api/payments/by-order'
const PAYMENT_REFUND = '/api/payments/'
// 拒付结案（v60 接线）：手工发起异常单 → 核销认损。
// 与上面同一惯例：常量写页面顶部。`?orderId=` 是**查询参数**（后端 @RequestParam）。
const MANAGER_EXCEPTIONS = '/api/manager/exceptions'
// 楼层/电梯文案：与配送任务列表共用同一份实现（口径只有一处）
const { buildFloorText } = require('../../utils/address')

// 纯展示用：订单状态数字 → 徽章 CSS class（仅控制颜色，不承载业务逻辑）
const STATUS_CLASS_MAP = {
  1: 'pending',     // 待配送
  2: 'delivering',  // 配送中
  3: 'delivered',   // 已送达
  4: 'completed',   // 已完成
  5: 'cancelled'    // 已取消
}

// ISO-8601 → 「MM-DD HH:mm」。
// ⚠️ 这是**纯展示切分**，不是时间计算，也绝不能写成 `new Date(str.replace(/-/g,'/'))`：
// 仓库明令禁止那种写法（AGENTS.md §8.7，时间一律按 ISO 解析）。
// 解析失败就原样返回，不编造时间。
function formatTime(v) {
  if (!v || typeof v !== 'string') return ''
  const parts = v.split('T')
  if (parts.length < 2) return v
  const time = parts[1].slice(0, 5)
  const date = parts[0].length >= 10 ? parts[0].slice(5, 10) : parts[0]
  return date + ' ' + time
}

// 支付状态「已付款」= 2（PaymentStatus.PAID）。
// 只做**一个等值判断**（决定要不要出现「退款」按钮），不是映射表 ——
// 文案一律渲染后端下发的 statusText / methodText，前端不维护状态字典。
const PAY_STATUS_PAID = 2

Page({
  data: {
    orderId: null,
    order: {},
    loading: true,
    // 楼层凭证（v43）：站长与配送员都能看、都能补传；不强制，所以"没有也不拦"
    floorPhotos: [],
    floorUploading: false,
    // 支付流水（2026-09-18）：只对站长展示（后端端点本身也是 STATION_MANAGER 专属）
    payments: [],
    canRefundPayment: false,
    // 店员角色（决定要不要给「客户拒付」入口；后端端点本身是 STATION_MANAGER 专属，前端只是别画出来）
    isManager: false,
    refusalBusy: false
  },

  onLoad(options) {
    if (options.id) {
      this.setData({ orderId: options.id })
      this.loadOrderDetail(options.id)
    }
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    // 角色判定与其它站长页同源（coordination 的 checkRole）
    const role = (app.globalData.userInfo || {}).role || ''
    this.setData({ isManager: role === 'STATION_MANAGER' || role === 'manager' })
    // 从完成配送页返回时刷新
    if (this.data.orderId) {
      this.loadOrderDetail(this.data.orderId)
    }
  },

  /**
   * 【v60 接线】客户拒付 → 手工发起异常单 → 核销认损（**两步确认**）。
   *
   * <p>为什么在订单详情页做：拒付是**对着某一张单**发生的（已送达(3) + 待收款(1)），
   * 而「异常订单」页只能看到**已经存在**的异常 —— 它没法凭空知道你指的是哪张单。</p>
   *
   * <p>⚠️ 为什么分两步：第一步只**建单**（把事情记下来，可反悔），第二步才**核销**
   * ——核销会一次做完三件事（应收出账 / 撤销该单权益 / 等量记客户欠桶）且**不可撤销**。
   * 合成一步就是"点错一下钱和桶账一起动了"。这与响应里 `WRITE_OFF` 是终态、
   * 重复点会报错（而不是幂等跳过）是同一套口径。</p>
   */
  async onRefusalWriteOff() {
    if (this.data.refusalBusy) return
    const id = this.data.orderId

    const first = await new Promise((resolve) => {
      wx.showModal({
        title: '客户拒付',
        content: '第 1 步：先给这张单记一条「客户拒收」异常。\n\n记完还会再问一次 —— 真正动账（核销应收、撤桶权益、记欠桶）的是第 2 步。',
        confirmText: '记一条异常',
        success: resolve,
        fail: () => resolve({ confirm: false })
      })
    })
    if (!first || !first.confirm) return

    this.setData({ refusalBusy: true })
    let exId = null
    try {
      const res = await post(MANAGER_EXCEPTIONS + '?orderId=' + id,
        { category: 'CUSTOMER_REFUSE', staffNote: '客户拒付' })
      exId = res && res.data ? res.data.id : null
    } catch (err) {
      wx.showToast({ title: err.message || '发起异常失败', icon: 'none' })
      this.setData({ refusalBusy: false })
      return
    }

    if (!exId) {
      // 建单"成功"却没拿到 id：出声，别让站长以为记上了
      wx.showModal({ title: '异常单没有返回编号', content: '请到「异常订单」页确认这条异常是否记上了。', showCancel: false })
      this.setData({ refusalBusy: false })
      return
    }

    const second = await new Promise((resolve) => {
      wx.showModal({
        title: '核销认损（不可撤销）',
        content: '第 2 步会把三件事一次做完：\n\n1. 这笔应收出账，不再计入「待收款」\n2. 撤销这张单送出、客户尚未归还的桶权益\n3. 等量记成客户欠桶（方便继续追桶）\n\n确定认下这笔损失吗？',
        confirmText: '确认核销',
        confirmColor: '#FF3B30',
        cancelText: '先不核销',
        success: resolve,
        fail: () => resolve({ confirm: false })
      })
    })
    if (!second || !second.confirm) {
      wx.showToast({ title: '已记异常，未核销', icon: 'none' })
      this.setData({ refusalBusy: false })
      this.loadOrderDetail(id)
      return
    }

    try {
      await post(MANAGER_EXCEPTIONS + '/' + exId + '/write-off', { managerNote: '客户拒付，站长核销认损' })
      wx.showToast({ title: '已核销', icon: 'success' })
      this.loadOrderDetail(id)
    } catch (err) {
      wx.showToast({ title: err.message || '核销失败', icon: 'none' })
    } finally {
      this.setData({ refusalBusy: false })
    }
  },

  // 加载订单详情
  async loadOrderDetail(id) {
    this.setData({ loading: true })

    try {
      const res = await getOrderDetail(id)
      const order = res.data

      // 状态文案、支付方式文案、是否需现场收款、转单状态：全部由后端计算下发。
      // 注意：此前这里读取的 transferStatus / returnStatus / isTransferTarget 三个字段
      // 后端 Orders 根本不存在（与 collected 同类问题），导致「转单中/退回申请/待你确认」
      // 标签永远不显示。现改用后端 transferPending / transferText。
      let notes = []
      if (order.specialNote) {
        notes = order.specialNote.split('\n').filter(n => n.trim())
      }

      const labels = []
      if (order.needCollect) labels.push({ type: 'offline', text: '线下' })
      if (order.transferPending) labels.push({ type: 'transfer', text: order.transferText })

      this.setData({
        order: {
          ...order,
          statusText: order.statusText || '',
          statusClass: STATUS_CLASS_MAP[order.status] || 'default',
          notes,
          paymentMethodText: order.payMethodText || '',
          isOffline: !!order.needCollect,
          labels,
          isTransfer: !!order.transferPending,
          floorText: buildFloorText(order),
          // 楼层上报（v43）：显示成两行（配送员上报 / 地址里填的），不一致时打一个提示标。
          // ⚠️ 这只是**给人看的提示**；"标记"的权威记录在收益明细的 note 里（后端生成，见 docs/design/18 §4）。
          reportedFloorText: order.reportedFloor ? ('配送员上报 ' + order.reportedFloor + ' 层') : '',
          floorMismatch: !!(order.reportedFloor && order.addressFloor
            && Number(order.reportedFloor) !== Number(order.addressFloor))
        },
        loading: false
      })
      this.loadFloorPhotos(id)
      this.loadPayments(id)
    } catch (err) {
      console.error('加载订单详情失败:', err)
      this.setData({ loading: false })
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    }
  },

  // 拨打电话
  onCallPhone() {
    const phone = this.data.order.receiverPhone || this.data.order.customerPhone
    if (phone) {
      wx.makePhoneCall({ phoneNumber: phone })
    }
  },

  // 复制地址
  onCopyAddress() {
    const address = this.data.order.addressSnapshot || this.data.order.addressDetail
    if (address) {
      wx.setClipboardData({
        data: address,
        success: () => {
          wx.showToast({ title: '地址已复制', icon: 'success' })
        }
      })
    }
  },

  // 导航（优先使用下单时地址快照，避免客户改地址后导错）
  onNavigate() {
    const order = this.data.order
    const lat = Number(order.addressSnapshotLat)
    const lng = Number(order.addressSnapshotLng)
    const addressText = order.addressSnapshot || order.addressDetail || ''
    if (lat && lng) {
      wx.openLocation({
        latitude: lat,
        longitude: lng,
        name: addressText,
        address: addressText,
        scale: 18
      })
    } else {
      // 如果没有坐标，使用地址搜索
      wx.chooseLocation({
        success: (res) => {
          wx.openLocation({
            latitude: res.latitude,
            longitude: res.longitude,
            name: res.name,
            address: res.address,
            scale: 18
          })
        }
      })
    }
  },

  // 完成配送
  onComplete() {
    wx.navigateTo({
      url: `/pages/order/complete?id=${this.data.orderId}`
    })
  },

  // 异常反馈
  onReport() {
    wx.showActionSheet({
      itemList: ['客户不接电话', '地址找不到', '客户拒收', '水桶破损', '其他'],
      success: (res) => {
        const reasons = ['客户不接电话', '地址找不到', '客户拒收', '水桶破损', '其他']
        const reason = reasons[res.tapIndex]

        wx.showModal({
          title: '异常反馈',
          content: `反馈原因：${reason}`,
          confirmText: '确认反馈',
          success: async (modalRes) => {
            if (modalRes.confirm) {
              try {
                await reportOrder(this.data.orderId, { reason })
                wx.showToast({ title: '反馈已提交', icon: 'success' })
              } catch (err) {
                wx.showToast({ title: err.message || '反馈失败', icon: 'none' })
              }
            }
          }
        })
      }
    })
  },

  async onAcceptTransfer() {
    const id = this.data.orderId
    wx.showModal({
      title: '同意转单',
      content: '确定接手此转单？接手后你将成为该订单配送员。',
      confirmText: '同意',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' })
          try {
            const { post } = require('../../utils/request')
            const { API } = require('../../config/api')
            await post(`${API.DELIVERY_TRANSFER}/${id}/claim`)
            wx.hideLoading()
            wx.showToast({ title: '已接手', icon: 'success' })
            setTimeout(() => { wx.navigateBack() }, 1500)
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '操作失败', icon: 'none' })
          }
        }
      }
    })
  },

  async onRejectTransfer() {
    const id = this.data.orderId
    wx.showModal({
      title: '拒绝转单',
      content: '确定拒绝此转单申请？',
      confirmColor: '#FF3B30',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' })
          try {
            const { post } = require('../../utils/request')
            const { API } = require('../../config/api')
            await post(`${API.DELIVERY_TRANSFER}/${id}/reject`)
            wx.hideLoading()
            wx.showToast({ title: '已拒绝', icon: 'success' })
            setTimeout(() => { wx.navigateBack() }, 1500)
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '操作失败', icon: 'none' })
          }
        }
      }
    })
  },

  // 转给同事（直转本站配送员）
  onTransfer() {
    const app = getApp()
    const myId = (app.globalData.userInfo || {}).staffId
    const stationId = (app.globalData.userInfo || {}).stationId
    const that = this
    wx.showLoading({ title: '加载中...' })
    getStaffList(stationId).then(res => {
      wx.hideLoading()
      const staffs = res.data || []
      const colleagues = staffs.filter(s => String(s.id) !== String(myId))
      if (colleagues.length === 0) {
        wx.showModal({ title: '暂无同事', content: '本站暂无其他在职配送员可转让', showCancel: false })
        return
      }
      const itemList = colleagues.map(s => s.name || ('配送员' + s.id))
      wx.showActionSheet({
        itemList: itemList,
        success: (res2) => {
          const target = colleagues[res2.tapIndex]
          wx.showModal({
            title: '转给同事',
            content: `确认将订单转给 ${target.name}？`,
            confirmText: '确认',
            success: async (modalRes) => {
              if (modalRes.confirm) {
                wx.showLoading({ title: '转单中...' })
                try {
                  await transferOrder(that.data.orderId, { deliveryStaffId: target.id, reason: '配送员转让' })
                  wx.hideLoading()
                  wx.showToast({ title: '已转给 ' + target.name, icon: 'success' })
                  setTimeout(() => { wx.navigateBack() }, 1500)
                } catch (err) {
                  wx.hideLoading()
                  wx.showToast({ title: err.message || '转让失败', icon: 'none' })
                }
              }
            }
          })
        }
      })
    }).catch((e) => {
      // [2026-09-20 真机联调] 这个 catch 原来**丢掉了错误对象**（`.catch(() => ...)`），
      // 只把 loading 关掉并弹一句光秃秃的「加载配送员失败」—— 真机上分不出是超时、断网
      // 还是后端 500（utils/request.js 在 f9e1c09 起已把 fail 归一化成带可读 message 的 Error，
      // 这里直接用它）。转单是配送员的核心动作，失败必须说清原因。
      wx.hideLoading()
      console.error('[OrderDetail] 加载配送员名单失败:', e)
      wx.showToast({ title: '加载配送员失败：' + ((e && e.message) || '网络异常'), icon: 'none' })
    })
  },

  // 退回站长
  onReturnToStation() {
    const that = this
    wx.showModal({
      title: '退回站长',
      content: '确定要退回站长吗？退回后站长将重新分配此订单。',
      confirmText: '确认退回',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '退回中...' })
          try {
            await returnToStation(that.data.orderId, { reason: '配送员退回站长' })
            wx.hideLoading()
            wx.showToast({ title: '已退回站长', icon: 'success' })
            setTimeout(() => { wx.navigateBack() }, 1500)
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '退回失败', icon: 'none' })
          }
        }
      }
    })
  },

  // 外派订单：临时指派给其他水站配送
  async onDispatchOrder() {
    const id = this.data.orderId
    const app = getApp()
    const myStationId = (app.globalData.userInfo || {}).stationId

    // 获取其他水站列表
    const { get } = require('../../utils/request')
    const { API } = require('../../config/api')
    let stationList = []
    // [2026-09-20] 同 home/index.js 的 onMediateToColleague：原来失败只 console.error，
    // 然后把"没查到"说成「暂无其他营业中的水站可外派」—— 站长会以为真的没有可派的水站。
    let loadError = ''
    try {
      const res = await get(API.STATION_SEARCH, {})
      stationList = (res.data || []).filter(s => s.id !== myStationId && s.status === 1)
    } catch (e) {
      loadError = (e && e.message) || '网络异常'
      console.error('加载水站列表失败:', e)
    }

    if (loadError) {
      wx.showModal({
        title: '加载失败',
        content: '没能取到水站列表（' + loadError + '），请稍后重试',
        showCancel: false
      })
      return
    }
    if (stationList.length === 0) {
      wx.showModal({ title: '无可外派水站', content: '暂无其他营业中的水站可外派', showCancel: false })
      return
    }

    const itemList = stationList.map(s => s.name || ('水站' + s.id))
    wx.showActionSheet({
      itemList: itemList,
      success: async (res) => {
        const targetStation = stationList[res.tapIndex]
        // 二次确认：显示风险提醒
        const confirm = await new Promise(resolve => {
          wx.showModal({
            title: '⚠️ 外派确认',
            content: `将订单外派给「${targetStation.name}」配送。\n\n` +
              '重要提醒：\n' +
              '1. 本单归属仍在本站，客户资产（桶/水票/押金）不转移\n' +
              '2. 客户下次下单仍在本站，需手动切站才会用外派站资产\n' +
              '3. 外派站仅负责本次配送，不建立客户归属关系\n\n' +
              '确定外派吗？',
            confirmText: '确定外派',
            confirmColor: '#34C759',
            cancelText: '取消',
            success: (r) => resolve(r.confirm)
          })
        })
        if (!confirm) return

        wx.showLoading({ title: '外派中...' })
        try {
          await dispatchOrder(id, { targetStationId: targetStation.id, reason: '外派配送' })
          wx.hideLoading()
          wx.showToast({ title: '外派成功', icon: 'success' })
          setTimeout(() => { wx.navigateBack() }, 1500)
        } catch (err) {
          wx.hideLoading()
          wx.showToast({ title: err.message || '外派失败', icon: 'none' })
        }
      }
    })
  },

  // 申请取消订单（配送员）：**只有「配送中(2)」**能申请（wxml 的按钮条件与后端门槛一致），
  // 提交后订单状态不变，由站长审批；站长同意才走退款链。
  // ⚠️ 已送达(3) 申请不了（后端 isCancellable 排除，异常走「配送异常」）；
  //    待配送(1) 也不需要申请 —— 走「拒单」即可（不经审批）。
  async onRequestCancel() {
    const id = this.data.orderId
    const confirm = await new Promise(resolve => {
      wx.showModal({
        title: '申请取消订单',
        content: '该订单已被接单，需要站长同意才能取消。\n\n提交后订单保持当前状态，等待站长审批；站长同意后才会取消并退款。',
        confirmText: '提交申请',
        success: (r) => resolve(r.confirm)
      })
    })
    if (!confirm) return

    const reason = await new Promise(resolve => {
      wx.showModal({
        title: '取消原因',
        content: '请填写取消原因，将一并提交给站长：',
        editable: true,
        placeholderText: '如：客户临时取消、车辆故障',
        success: (r) => resolve(r.confirm ? r.content : ''),
        fail: () => resolve('')
      })
    })
    if (!reason || !reason.trim()) {
      wx.showToast({ title: '请填写取消原因', icon: 'none' })
      return
    }

    wx.showLoading({ title: '提交中...' })
    try {
      await requestCancel(id, { reason: reason.trim() })
      wx.hideLoading()
      wx.showToast({ title: '已提交，等待站长审批', icon: 'success' })
      setTimeout(() => { wx.navigateBack() }, 1500)
    } catch (err) {
      wx.hideLoading()
      wx.showToast({ title: err.message || '提交失败', icon: 'none' })
    }
  },

  // 解决订单：拒单并取消，触发退款
  async onResolveOrder() {
    const id = this.data.orderId
    // 严重警告：拒单会导致订单取消、退款、客户可能流失
    const confirm = await new Promise(resolve => {
      wx.showModal({
        title: '⚠️ 严重警告：解决/拒单',
        content: '此操作将：\n\n' +
          '1. 取消订单，状态变为「已取消」\n' +
          '2. 触发退款，款项原路退回（微信支付需几分钟到账）\n' +
          '3. 客户需重新下单，体验极差，极大概率导致客户流失\n\n' +
          '建议优先考虑：「外派」给其他水站，或内部协调配送。\n\n' +
          '确定要解决（拒单）吗？',
        confirmText: '确定解决',
        confirmColor: '#FF3B30',
        cancelText: '取消，去外派',
        success: (r) => resolve(r.confirm)
      })
    })
    if (!confirm) return

    // 必填拒单原因
    const reason = await new Promise(resolve => {
      wx.showModal({
        title: '拒单原因 (必填)',
        content: '请输入拒单原因，将记录在订单备注中：',
        editable: true,
        placeholderText: '如：地址偏远无法配送、暂时缺货',
        success: (r) => resolve(r.confirm ? r.content : ''),
        fail: () => resolve('')
      })
    })
    if (!reason || !reason.trim()) {
      wx.showToast({ title: '拒单原因不能为空', icon: 'none' })
      return
    }

    wx.showLoading({ title: '处理中...' })
    try {
      await resolveOrder(id, { reason: reason.trim() })
      wx.hideLoading()
      wx.showToast({ title: '已解决，订单取消并触发退款', icon: 'success' })
      setTimeout(() => { wx.navigateBack() }, 1500)
    } catch (err) {
      wx.hideLoading()
      wx.showToast({ title: err.message || '解决失败', icon: 'none' })
    }
  },

  /* ==================== 楼层凭证（v43）====================
   * 为什么站长也要能传：楼层补贴是给配送员的钱，与客户扯皮时（"你说的 6 楼呢"）
   * 这张照片是唯一的凭证；配送员当时没拍，站长可以事后补。
   * 照片**不强制**（产品决定），所以这里没有也不拦、只提示。
   */
  async loadFloorPhotos(orderId) {
    if (!orderId) return
    try {
      const res = await get(ORDER_IMAGE_BY_ORDER + '/' + orderId)
      const photos = (res.data || [])
        .filter(img => Number(img.type) === 3)
        .map(img => img.url || img.objectName)
        .filter(Boolean)
      this.setData({ floorPhotos: photos })
    } catch (e) {
      // 拉不到就不显示，不编造"没照片"
      this.setData({ floorPhotos: [] })
    }
  },

  onPreviewFloorPhoto(e) {
    const { index } = e.currentTarget.dataset
    wx.previewImage({ current: this.data.floorPhotos[index], urls: this.data.floorPhotos })
  },

  onAddFloorPhoto() {
    const { upload } = require('../../utils/upload')
    const { API } = require('../../config/api')
    wx.chooseImage({
      count: 3,
      sizeType: ['compressed'],
      success: async (res) => {
        this.setData({ floorUploading: true })
        const uploads = res.tempFilePaths.map(p => upload({
          filePath: p,
          url: API.ORDER_IMAGE_UPLOAD,
          name: 'file',
          // 3 = 楼层凭证（1 正常送达 / 2 异常）
          formData: { orderId: this.data.orderId, type: 3 }
        }).then(r => r.data))
        try {
          await Promise.all(uploads)
          await this.loadFloorPhotos(this.data.orderId)
          wx.showToast({ title: '已补传', icon: 'success' })
        } catch (err) {
          wx.showToast({ title: err.message || '上传失败', icon: 'none' })
        } finally {
          this.setData({ floorUploading: false })
        }
      }
    })
  },

  /* ==================== 支付流水与手工退款（2026-09-18 接线）====================
   * 为什么只给站长看：两个端点都是后端 @RequireRole({"STATION_MANAGER"}) 专属
   * （GET /api/payments/by-order、PUT /api/payments/{id}/refund），
   * 配送员点了只会拿到 code=1「权限不足」—— 那属于"让用户白点"。
   *
   * ⚠️ 展示口径：方式 / 状态**一律渲染后端下发的 methodText / statusText**，金额用后端给的
   * amount 原值，前端**不做任何金额加减**（包括"合计已退多少"这类派生数字一律不做）——
   * 金额口径的唯一真相源是 orders / payment_record，见 AGENTS.md §6。
   * 这里唯一用到的数字判断是"状态是不是 2（已付款）"，它只决定「退款」按钮出不出现。
   */
  async loadPayments(orderId) {
    if (!orderId) return
    const app = getApp()
    // 非站长直接跳过，连请求都不发（端点本身也会拒）
    if (!app.isStationManager || !app.isStationManager()) {
      this.setData({ payments: [], canRefundPayment: false })
      return
    }
    try {
      const res = await get(PAYMENTS_BY_ORDER, { orderId: orderId })
      const list = (res.data || []).map(p => ({
        id: p.id,
        methodText: p.methodText || '',
        statusText: p.statusText || '',
        amount: p.amount,
        amountText: p.amount === null || p.amount === undefined ? '' : ('¥' + p.amount),
        // 负数（退款冲正流水）标红，纯展示
        isRefund: Number(p.amount) < 0,
        // 2026-09-18：后端下的时间统一 ISO-8601，前端只做展示切分（见文件头 formatTime）
        timeText: formatTime(p.createTime),
        note: p.note || '',
        canRefund: Number(p.status) === PAY_STATUS_PAID
      }))
      this.setData({ payments: list, canRefundPayment: true })
    } catch (err) {
      // 拉不到就整块不显示，不编造"没有流水"（同 loadFloorPhotos 的处理）
      console.error('加载支付流水失败:', err)
      this.setData({ payments: [], canRefundPayment: false })
    }
  },

  /**
   * 手工退款（站长）：二次确认 → 调 PUT /api/payments/{id}/refund → 成功后刷新本区块。
   *
   * ⚠️ 失败必须把**后端 message 原样显示出来**（用 showModal 而不是一闪而过的 toast）：
   * 「微信支付渠道未接入，无法自动原路退回，请线下退款并登记」这类文案是站长唯一的操作指引，
   * 弹个 toast 就消失等于没告诉他下一步该干什么。
   */
  onRefundPayment(e) {
    const { id, index } = e.currentTarget.dataset
    const pay = this.data.payments[index]
    if (!pay) return

    wx.showModal({
      title: '退款确认',
      content: '确定为这笔支付退款吗？\n\n支付方式：' + pay.methodText
        + '\n退款金额：' + pay.amountText
        + '\n\n退款按原支付方式退回：水票支付的会把票原路补回客户账户；'
        + '现金由你当面退还客户；微信渠道未接入，无法自动退回。',
      confirmText: '确认退款',
      confirmColor: '#FF3B30',
      success: (res) => {
        if (!res.confirm) return
        this.doRefundPayment(id)
      }
    })
  },

  async doRefundPayment(paymentId) {
    wx.showLoading({ title: '退款中...' })
    try {
      await put(PAYMENT_REFUND + paymentId + '/refund', { note: '站长手工退款' })
      wx.hideLoading()
      wx.showToast({ title: '已退款', icon: 'success' })
      // 刷新支付流水区块（订单支付状态可能一起变了）
      await this.loadPayments(this.data.orderId)
      await this.loadOrderDetail(this.data.orderId)
    } catch (err) {
      wx.hideLoading()
      const msg = (err && err.message) ? err.message : '退款失败，请稍后重试'
      console.error('手工退款失败:', msg)
      wx.showModal({ title: '退款未成功', content: msg, showCancel: false, confirmText: '知道了' })
    }
  }
})