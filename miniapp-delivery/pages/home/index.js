const { getPendingOrders, getDeliveringOrders, getCompletedToday, acceptOrder, getDeliveredUnpaid, confirmCollection, transferOrder, returnToStation, getStaffList, respondTransfer, getTransferList, getAssignedToMe } = require('../../api/delivery')
// 楼层/电梯文案与订单详情页共用同一份实现（口径只有一处）
const { buildFloorText } = require('../../utils/address')
// 自绘导航栏 + 水站营业状态胶囊（本页 navigationStyle=custom）：与「首页」共用一份实现
// —— 结构与样式见 templates/station-navbar.wxml、styles/station-navbar.wxss
const stationNavbar = require('../../behaviors/stationNavbar')
// 自绘底栏（tabBar.custom=true）：本页是 tab 页，onShow 必须同步一次（见 utils/tabbar.js）
const { syncTabBar } = require('../../utils/tabbar')
// 配送异常上报（原因文案与上报实现与订单详情页共用一份）
const { reportDeliveryProblem } = require('../../utils/delivery-problem')

Page({
  behaviors: [stationNavbar],

  data: {
    activeTab: 'assigned',
    isManager: false,
    assignedOrders: [],
    deliveringOrders: [],
    completedOrders: [],
    deliveredUnpaidOrders: [],
    incomingTransfers: [],
    staffList: [],
    loading: false,
    // true = 配送员视角（只看派给自己的单）：空态文案据此说"等站长派单"，而不是"今天没单"
    onlyAssigned: false,
    // 部分列表接口失败时的提示文案（空串 = 全部正常）。见 loadData 里的说明。
    loadError: '',
    showMediateModal: false,
    currentOrderId: null
  },

  onLoad() {
    // 自绘导航栏尺寸先算好再渲染，避免状态胶囊闪一下（实现来自 behaviors/stationNavbar.js）
    this.initNavMetrics()
  },

  onShow() {
    const app = getApp()
    // 自绘底栏：配送员只有 配送/我的 两项（本页是配送员的主页）
    syncTabBar(this, '/pages/home/index')
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    const userInfo = app.globalData.userInfo || {}
    const role = userInfo.role || ''
    // #46: 匹配normalized后的角色值
    const isManager = role === 'STATION_MANAGER' || role === 'manager' || role === 'MANAGER'
    this.setData({ isManager })
    // 营业状态跟着首页刷新：站长刚改成"休息中"，配送员回到这页就该看到
    // （软状态 v32：只提示不阻断；实现与「首页」共用，见 behaviors/stationNavbar.js）
    this.loadStationStatus(userInfo.stationId)
    // 待填项徽标 + 「条件项刚成立」提醒（见 behaviors/stationNavbar.js；
    // 非站长会直接 return，不发请求 —— setup-guide 是站长专属接口）
    this.loadStationPending()
    this.loadData(isManager)
  },

  onPullDownRefresh() {
    this.loadData(this.data.isManager).then(() => { wx.stopPullDownRefresh() })
  },

  /**
   * @param {boolean} isManager **必须显式传入**，不要在函数里回头读 `this.data.isManager`：
   *   依赖"setData 已同步写回 data"这种时序，一旦不成立就会走错分支 —— 配送员去请求
   *   站长专属的"未分配列表"拿到权限错误，或者站长少看一屏还没派出去的单。
   */
  async loadData(isManager) {
    const manager = isManager === true
    this.setData({ loading: true, onlyAssigned: !manager })
    try {
      // ⚠️ [2026-09-19 删除] 这里原先还调 `getTodayStats()`（/api/delivery/stats/today）并把结果写进
      // `data.stats` —— 而本页 wxml **从来没有读过 `stats`**（上面看板用的是三个列表的 .length）。
      // 也就是说每次进「配送」页都白发一次请求。删掉它，页面上的数字一个都不会变。
      // 证据见 docs/audit/2026-09-16-死端点评估.md「删除登记表」#10。
      //
      // ⚠️ [2026-09-26 产品裁定] `getPendingOrders()`（本站**未分配**的待配送单）**只有站长拉**：
      //    产品原话「如果是未分配的订单，不应该直接显示给配送员吧 —— 现在站长还没分配，
      //    刚同意入站就能看见订单了，就能接单了」。配送员那一侧只有「派给我的」。
      //    后端也收紧了：/orders/pending 已是站长专属，acceptOrder 对配送员要求"必须先被分配"。
      const requests = [
        getAssignedToMe(),
        getDeliveringOrders(),
        getCompletedToday(),
        getDeliveredUnpaid(),
        // [2026-09-27] 待我确认的同事转单。**必须与上面四个一起拉**：它决定"待配送"页签顶部
        // 那张卡出不出现，而那张卡是一张**要我决定**的卡（同意/不接受）——漏了它，
        // 发起方那边会一直停在"已转出、等对方同意"，接收方却什么都看不到。
        getTransferList()
      ]
      if (manager) requests.push(getPendingOrders())
      const results = await Promise.allSettled(requests)

      const unwrap = (r) => r.status === 'fulfilled' ? r.value : { data: [] }
      // [2026-09-20 真机联调] 原来 unwrap 把「失败」静默折成「空列表」，于是外层 catch
      // **永远不会触发** —— 后端没起 / 手机换了网时，配送员看到的是 4 个空白列表，
      // 与"今天确实没有单"完全无法区分（正是 AGENTS §8.22 描述的形状）。
      // 现在把失败项数记下来，由 wxml 显式提示；列表照常渲染（部分成功仍然有用）。
      const failedCount = results.filter(r => r.status === 'rejected').length
      if (failedCount) {
        console.error('[home] 有 ' + failedCount + ' 个列表接口失败：',
          results.filter(r => r.status === 'rejected').map(r => r.reason))
      }
      const assignedRes = unwrap(results[0])
      const deliveringRes = unwrap(results[1])
      const completedRes = unwrap(results[2])
      const unpaidRes = unwrap(results[3])
      // ⚠️ 下标跟着 requests 数组走：转单列表固定在第 5 位（索引 4），
      //    站长的「本站未分配」在它之后（索引 5）。**插入/删除 requests 项时必须同步改这里**，
      //    否则会静默串位（拿转单当未分配单渲染，页面看着"有单"但按钮全是错的）。
      const transferRes = unwrap(results[4])
      const pendingRes = manager ? unwrap(results[5]) : { data: [] }

      // 商品摘要（§4「混合商品必须准确」）—— 三张页签的列表 SQL 都下发了 itemSummary；
      // 「待收款」那条（OrderMapper.listByStationIdAndStatus）同样下发。
      //   ① 有 `itemSummary` → **直接用**（服务端逐明细拼好，单位也是它判的：桶/瓶/台/件）；
      //   ② 为空（老数据）→ 回落 `firstProductName`，但 ⚠️ **绝不再拿
      //      `quantity` 去配「桶」**：`quantity` 是**全单总件数**（含瓶装水、饮水机），
      //      配上「第一条明细的名字」正是要修掉的那个错（实测 3 桶水 + 1 瓶水
      //      显示成「纯净水 × 4 桶」）。数量只认后端记的 `deliveryBucketQty`（本单桶装水桶数），
      //      连它都没有才退到 `quantity` 并说「件」（不硬写单位）。
      //   ③ ⚠️ **不要再把摘要降级成「共 N 种商品 · 查看」**（2026-09-27 产品反馈：
      //      「怎么还是隐藏的」）。原先 `summary.length > 20 && kindCount > 1` 时整段换成
      //      「共 N 种商品 · 查看」，配送员在列表上**一个商品名都看不到**，还得先点进详情才知道送什么 ——
      //      而列表正是他决定接不接这一单的地方。现在一律显示完整摘要（CSS 允许换行，见 .product-name）。
      //      多出来的那行「共 N 种商品」只作辅助信息（kindCount > 1 时）。
      //   ④ ⚠️ 有完整摘要时**不再另外显示「共 N 种」**（2026-09-27 真机）：它本来就含在摘要里
      //      （「…1桶，…2桶」一眼就是两种），挂成第二个 flex 子项还会把"共 2 种"挤到下一行，
      //      看着像排版坏了。**只有回落分支**（没有 itemSummary、只知道总数）才用它报数量。
      const summaryOf = (o) => {
        const summary = (o.itemSummary || '').trim()
        if (summary) return { text: summary, meta: '' }
        const barrelQty = Number(o.deliveryBucketQty || 0)
        const pieces = Number(o.quantity || 0)
        return {
          text: o.firstProductName || '商品明细待确认',
          meta: barrelQty > 0 ? `等 ${barrelQty} 桶` : (pieces > 0 ? `等 ${pieces} 件` : '')
        }
      }

      // 金额一律取后端 totalAmount。此前按 quantity * (waterTypePrice || productPrice)
      // 前端自算，而这两个单价字段后端从不返回，导致金额恒为 ¥0.00。
      const enrichOrder = (o) => {
        const summary = summaryOf(o)
        return {
          ...o,
          amountText: `¥${Number(o.totalAmount || 0).toFixed(2)}`,
          // 「已付 / 需收 ¥X」：判据只用后端投影 —— needCollect（现金且还没收到钱）
          // 与 payState（钱到底到没到账），**不按 payment_status 的 1/2/3 自己写映射表**（AGENTS §6）。
          payLabel: o.needCollect ? '需收' : (o.payState === 'PAID' ? '已付' : '未收'),
          itemSummaryText: summary.text,
          itemMetaText: summary.meta,
          // 是否需现场收款、是否已收款：均由后端按 payment_status / payment_method 判定，
          // 前端不再各写一套（此前三处 isOffline 口径互不一致）。
          isOffline: !!o.needCollect,
          isUnpaid: o.payState !== 'PAID',
          // 楼层/电梯：配送员出车前要知道这一单要不要上楼。
          // 文案口径与订单详情页共用 utils/address.buildFloorText（只有一处实现，三态由它处理）；
          // 后端下发了这两个字段的有：派给我的 / 全站未分配 / 配送中 / 今日完成 ——
          // 「待收款」那条（listByStationIdAndStatus）**没有**，于是它是空串、整行不显示，
          // 这里不替它硬凑（卡 §1.2）。
          floorText: buildFloorText(o)
        }
      }

      const unpaidOrders = (unpaidRes.data || []).map(enrichOrder)

      // 「待配送」页签 = status 1（后端状态名就叫待配送）：
      //   ① 站长已分配给我、我还没接单的；② **站长**额外看到本站还没派出去的单。
      // [2026-09-26 产品裁定] 第 ② 支只给站长：未分配的单不该出现在配送员面前，
      // 更不该让他接走（后端 acceptOrder 同步加了闸门：配送员只能接派给自己的单）。
      const assignedSet = new Set()
      const mergedAssigned = [
        ...(assignedRes.data || []),
        ...(pendingRes.data || [])
      ].filter(o => {
        if (assignedSet.has(o.id)) return false
        assignedSet.add(o.id)
        return true
      }).map(enrichOrder)

      this.setData({
        assignedOrders: mergedAssigned,
        deliveringOrders: (deliveringRes.data || []).map(enrichOrder),
        completedOrders: (completedRes.data || []).map(enrichOrder),
        deliveredUnpaidOrders: unpaidOrders,
        // 待我确认的同事转单（后端只返回「待确认且指向我」的那些，见 listIncomingTransfers）。
        // 走同一个 enrichOrder：金额/收款口径、商品摘要、楼层文案全部同一套映射，不另写一份。
        incomingTransfers: (transferRes.data || []).map(enrichOrder),
        loadError: failedCount
          ? '有 ' + failedCount + ' 项没加载出来（网络或后端异常），下面列表可能不完整'
          : '',
        loading: false
      })
    } catch (err) {
      console.error('加载数据失败:', err)
      this.setData({ loading: false })
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    }
  },

  switchTab(e) {
    this.setData({ activeTab: e.currentTarget.dataset.tab })
  },

  onOrderTap(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: `/pages/order/detail?id=${id}` })
  },

  /**
   * 卡上的「联系」动作（§4 的字段顺序里排在主动作之前）：号码取订单快照。
   * 与订单详情页 / 完成页同一口径 —— `receiverPhone` 优先，因为跨站履约单的 `customerPhone`
   * 是**刻意置空**的（画像归归属站，见 util/CustomerProfileMask），快照里的收件人电话照常可用。
   * ⚠️ wxml 上这行本来就有值才渲染；真拿到空值也要出声（点了没反应比没有这个按钮更糟）。
   */
  onCallCustomer(e) {
    const phone = e.currentTarget.dataset.phone
    if (!phone) {
      wx.showToast({ title: '这单没有可拨的电话', icon: 'none' })
      return
    }
    wx.makePhoneCall({ phoneNumber: phone, fail: () => {} })
  },

  async onAcceptOrder(e) {
    const id = e.currentTarget.dataset.id
    // #47: 防重复点击
    if (this._accepting) return
    this._accepting = true
    wx.showModal({
      title: '确认接单',
      content: '确定接受此配送任务？',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '接单中...' })
          try {
            await acceptOrder(id)
            wx.hideLoading()
            wx.showToast({ title: '接单成功', icon: 'success' })
            // ⚠️ 必须显式传 isManager（loadData 的第一句就是 setData({onlyAssigned: !manager})）：
            // 漏传 → manager=undefined → 站长点完接单就被切成"配送员视角"
            // （onlyAssigned=true、空态文案也变成"等站长分配"），而他明明还是站长。
            this.loadData(this.data.isManager)
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '接单失败', icon: 'none' })
          }
        }
        this._accepting = false
      },
      fail: () => { this._accepting = false }
    })
  },

  /**
   * 同意同事转给我的单（[2026-09-27] 双方同意的第二半）。
   *
   * 后端在这一步才把 delivery_staff_id 改到我名下（CAS：要求订单仍挂在发起人名下），
   * 所以成功之后这单会从「待我确认」那张卡变成下面的普通待配送卡 —— 再点一次「接单配送」
   * 才进入配送中（与站长派单过来的单完全同一条路，不另开分支）。
   */
  async onAcceptTransfer(e) {
    const id = e.currentTarget.dataset.id
    if (this._transferBusy) return
    this._transferBusy = true
    wx.showModal({
      title: '确认接收',
      content: '同意后这单归你配送，确定吗？',
      confirmText: '同意',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '确认中...' })
          try {
            await respondTransfer(id, { action: 'claim' })
            wx.hideLoading()
            wx.showToast({ title: '已接收，请尽快配送', icon: 'success' })
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '接收失败', icon: 'none' })
          }
          // 成功失败都重拉：失败多半是"这条转单已被处理/订单已被改派"（后端 CAS 拒绝），
          // 重拉一次列表就能自愈，而不是让配送员对着一条已经不存在的待确认卡反复点。
          this.loadData(this.data.isManager)
        }
        this._transferBusy = false
      },
      fail: () => { this._transferBusy = false }
    })
  },

  /** 不接受同事转来的单：订单原地留在原配送员名下（待确认期间归属从没改过），我这边卡片消失。 */
  async onRejectTransfer(e) {
    const id = e.currentTarget.dataset.id
    if (this._transferBusy) return
    this._transferBusy = true
    wx.showModal({
      title: '不接受转单',
      content: '这单会留在对方名下，确定不接受吗？',
      confirmText: '不接受',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '处理中...' })
          try {
            await respondTransfer(id, { action: 'reject' })
            wx.hideLoading()
            wx.showToast({ title: '已回绝，这单仍归对方', icon: 'none' })
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '操作失败', icon: 'none' })
          }
          this.loadData(this.data.isManager)
        }
        this._transferBusy = false
      },
      fail: () => { this._transferBusy = false }
    })
  },

  onCompleteOrder(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: `/pages/order/complete?id=${id}&from=home` })
  },

  async onConfirmCollection(e) {
    const id = e.currentTarget.dataset.id
    wx.showModal({
      title: '确认收款',
      content: '确认已收到此订单款项？',
      confirmText: '确认收款',
      confirmColor: '#2E9E6B',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '收款确认中...' })
          try {
            await confirmCollection(id)
            wx.hideLoading()
            wx.showToast({ title: '收款成功', icon: 'success' })
            this.loadData(this.data.isManager)
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '收款失败', icon: 'none' })
          }
        }
      }
    })
  },

  onMediateOrder(e) {
    const id = e.currentTarget.dataset.id
    this.setData({ showMediateModal: true, currentOrderId: id })
  },

  onCloseMediateModal() {
    this.setData({ showMediateModal: false, currentOrderId: null })
  },

  /**
   * 配送遇到问题的**主项**：上报异常（[2026-09-27] 产品口径「配送遇到问题不应该是异常单处理吗，
   * 为什么会是转让处理，改一下，转让可以做成里面的一小部分」）。
   *
   * 原先这个入口点开只有两个动作，而且**第一个就是转单** —— 配送员把"送不到"当成"换个人送"，
   * 现场问题反而没有留痕。现在异常上报排在最前、说明也最直白；
   * 转单与退回站长降为"这单我确实送不了"的后续安排，排在后面。
   *
   * 上报只落备注 + 审计（不生成异常单），订单仍在我名下 —— 所以上报完不刷新列表（没有任何字段变），
   * 只给一句"站长看得到"。实现与原因文案见 utils/delivery-problem.js（与详情页共用一份）。
   */
  onReportProblem() {
    const id = this.data.currentOrderId
    this.setData({ showMediateModal: false })
    reportDeliveryProblem(id)
  },

  async onMediateToColleague() {
    const id = this.data.currentOrderId
    this.setData({ showMediateModal: false })
    const app = getApp()
    const myId = (app.globalData.userInfo || {}).staffId
    let staffList = []
    // [2026-09-20] 原来失败只 console.error，随后照旧拿空列表往下走 —— 于是"接口挂了/断网"
    // 被显示成「本站暂无其他在职配送员可转单」，把人往错误方向带（AGENTS §8.17 的判据：
    // 「用户以为做成了、账上没动」与「用户以为没数据、其实没查到」都算缺陷，宁可失败出声）。
    let loadError = ''
    try {
      const staffRes = await getStaffList((app.globalData.userInfo || {}).stationId)
      staffList = (staffRes.data || []).filter(s => String(s.id) !== String(myId))
    } catch (e) {
      loadError = (e && e.message) || '网络异常'
      console.error('加载配送员失败:', e)
    }
    if (loadError) {
      wx.showModal({
        title: '加载失败',
        content: '没能取到同事名单（' + loadError + '），请稍后重试',
        showCancel: false
      })
      return
    }
    if (staffList.length === 0) {
      wx.showModal({ title: '暂无同事', content: '本站暂无其他在职配送员可转单', showCancel: false })
      return
    }
    const itemList = staffList.map(s => s.name || ('配送员' + s.id))
    wx.showActionSheet({
      itemList: itemList,
      success: async (res) => {
        const target = staffList[res.tapIndex]
        wx.showModal({
          title: '转单确认',
          content: `确认将订单转给 ${target.name || '同事'}？需对方确认后生效。`,
          confirmText: '申请转单',
          success: async (modalRes) => {
            if (modalRes.confirm) {
              wx.showLoading({ title: '转单中...' })
              try {
                await transferOrder(id, { deliveryStaffId: target.id, reason: '配送员调解转单' })
                wx.hideLoading()
                wx.showToast({ title: '已申请转单，等待对方确认', icon: 'success' })
                this.loadData(this.data.isManager)
              } catch (err) {
                wx.hideLoading()
                wx.showToast({ title: err.message || '转单失败', icon: 'none' })
              }
            }
          }
        })
      }
    })
  },

  async onMediateToStation() {
    const id = this.data.currentOrderId
    this.setData({ showMediateModal: false })
    wx.showModal({
      title: '退回站长',
      content: '确定申请退回站长吗？退回后需站长确认，订单将重新分配。',
      confirmText: '申请退回',
      success: async (res) => {
        if (res.confirm) {
          wx.showLoading({ title: '退回中...' })
          try {
            await returnToStation(id, { reason: '配送员调解退回' })
            wx.hideLoading()
            wx.showToast({ title: '已申请退回，等待站长确认', icon: 'success' })
            this.loadData(this.data.isManager)
          } catch (err) {
            wx.hideLoading()
            wx.showToast({ title: err.message || '退回失败', icon: 'none' })
          }
        }
      }
    })
  },

  stopPropagation() {}
})
