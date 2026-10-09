// 我的 · Step6 重构（2026-09-12）
// 结构：用户行 → 押金/水票/桶三项卡 → 账单 / 我的信息 / 服务 三组菜单。
// 数字全部读后端派生口径（押金/水票张数/水桶），前端不做业务加减。
//
// [2026-09-27 走查 C05 修] **"没拉到"与"真的是 0"必须长得不一样**。
// 原来四格的初值是 0/-0，任一请求失败只是弹一句"显示的可能是 0"的 toast：
// toast 一消失，页面与"这个客户确实没有资产"完全一致，客户会以为自己押金/水票没了
//（AGENTS §8.22：界面空白与"确实没有"无法区分，是同一类缺陷）。
// 现在三个数各自带**加载状态**：`null` = 还没拿到（渲染成「暂未加载 · 重试」），
// 拿到才写数字（真的是 0 就显示 0）。渲染文案由 `assetsView` 统一算好，wxml 不做判断。
const { getBarrelSummary } = require('../../api/barrel')
const { getTicketAccounts } = require('../../api/ticket')
const { getCompanyInfo } = require('../../api/company')
const assetViewStation = require('../../utils/asset-view-station')
const { captureSession, isCurrentSession } = require('../../utils/token')
const app = getApp()

// 数字仅做展示校验；数值来源和计账规则仍由后端决定。
const assetNumber = raw => {
  if ((typeof raw !== 'number' && typeof raw !== 'string') || (typeof raw === 'string' && !raw.trim())) return null
  const value = Number(raw)
  return Number.isFinite(value) ? value : null
}
const assetCount = raw => {
  const value = assetNumber(raw)
  return value !== null && Number.isSafeInteger(value) && value >= 0 ? value : null
}

const fmtMoney = (n) => {
  const v = Number(n) || 0
  return (Math.round(v * 100) / 100).toFixed(v % 1 === 0 ? 0 : 2)
}

/** 「暂未加载」这一格的兜底文案（三项共用一份，改文案只改这里）。 */
const ASSET_UNLOADED = '暂未加载'

/**
 * 三个数字 → 三项展示态。[2026-09-27 走查 C05]
 *
 * @param {{deposit:?number, ticket:?number, barrel:?number}} v  null = 该项没拿到
 * @returns {{depositText:string, ticketText:string, barrelText:string,
 *            depositLoaded:boolean, ticketLoaded:boolean, barrelLoaded:boolean,
 *            unloadedCount:number}}
 *
 * 判据：**只有拿到值才写数字**；没拿到写「暂未加载」并且 `*Loaded=false`
 *（wxml 据此把这一格画成未加载样式，而不是把它当成 0）。
 */
function assetsView(v) {
  const loaded = {
    deposit: v.deposit !== null && v.deposit !== undefined,
    ticket: v.ticket !== null && v.ticket !== undefined,
    barrel: v.barrel !== null && v.barrel !== undefined
  }
  return {
    depositLoaded: loaded.deposit,
    ticketLoaded: loaded.ticket,
    barrelLoaded: loaded.barrel,
    depositText: loaded.deposit ? fmtMoney(v.deposit) : ASSET_UNLOADED,
    ticketText: loaded.ticket ? String(v.ticket) : ASSET_UNLOADED,
    barrelText: loaded.barrel ? String(v.barrel) : ASSET_UNLOADED,
    unloadedCount: Object.keys(loaded).filter(k => !loaded[k]).length
  }
}

Page({
  data: {
    isLogin: false,
    userInfo: null,
    // ⚠️ 这三项的 `null` 是有意的：见 assetsView 的说明。**不要**改回 0 当占位。
    deposit: null,       // 押金账户余额；本次可退金额须在退桶办理中预览
    ticket: null,        // 水票张数 = Σ remainQuantity
    barrel: null,        // 我的桶：新口径 occupiedBuckets，历史 heldBuckets；不以权益量替代
    depositText: ASSET_UNLOADED,
    ticketText: ASSET_UNLOADED,
    barrelText: ASSET_UNLOADED,
    barrelHint: '',
    depositLoaded: false,
    ticketLoaded: false,
    barrelLoaded: false,
    unloadedCount: 0,
    stationName: '',
    isCompany: false
  },

  onShow() {
    this._assetUnloaded = false
    const session = captureSession()
    const { isLogin, userInfo } = app.globalData
    assetViewStation.activate(this)
    const stationId = assetViewStation.id(this)
    // [2026-09-27 走查 C05 修] 换站必须**先清空**上一站的数字：
    // 原来 onShow 只更新站名、数字等 loadAssets 回来才覆盖 —— 客户在水站 A 看到"押金 ¥450"，
    // 切到水站 B 的那一瞬间这 450 还挂在屏幕上，而资产卡下面写的已经是 B 站的名字。
    // 桶/押金/水票都是按站隔离的账，"旧站数字 + 新站标题"比空着更危险。
    const prevStationId = this._assetStationId
    if (prevStationId !== stationId || !this._assetSession || !isCurrentSession(this._assetSession)) {
      this._assetStationId = stationId
      this._assetSession = session
      this.setData({
        deposit: null, ticket: null, barrel: null,
        ...assetsView({ deposit: null, ticket: null, barrel: null }),
        barrelHint: '',
        isCompany: false
      })
    }
    this.setData({
      isLogin,
      userInfo
    })
    if (isLogin) {
      return this.loadAssets()
    }
  },

  async loadAssets() {
    const context = assetViewStation.beginRead(this)
    const stationId = context.stationId
    const session = captureSession()
    const sequence = this._assetReadSeq = (this._assetReadSeq || 0) + 1
    this.setData({ deposit: null, ticket: null, barrel: null,
      ...assetsView({ deposit: null, ticket: null, barrel: null }), barrelHint: '' })
    // [2026-09-20 真机联调] 旧版本多个请求原来各自 `.catch(() => null)` —— 失败被吞成 null，
    // 页面照常显示「押金 ¥0 / 水票 0 张」，与"这个客户确实没有资产"完全无法区分
    // （AGENTS §8.22）。降级保留（部分成功仍然显示），但**失败的那一格必须显式标出来**：
    // 见 assetsView（未加载 = 文案「暂未加载」，不是 0）。
    //
    // ⚠️ 必须逐项记「哪一项失败了」，不能只记一个计数：失败项要写回 null 覆盖掉旧值，
    //    否则一次失败的刷新会把**上一次（甚至上一站）的数字**留在屏幕上冒充当前值。
    let summaryRes = null, ticketsRes = null, companyRes = null
    // ⚠️ `failed` 必须先声明再用（caught 是箭头函数，但它一被调用就读这个变量）
    const failed = []
    const caught = (tag) => (e) => {
      failed.push(tag)
      console.warn('[mine] ' + tag + ' 加载失败:', e && e.message)
      return null
    }
    ;[summaryRes, ticketsRes, companyRes] = await Promise.all([
      getBarrelSummary(stationId).catch(caught('桶与押金')),
      getTicketAccounts(stationId).catch(caught('水票')),
      getCompanyInfo().catch(caught('企业资料'))
    ])

    if (this._assetUnloaded || sequence !== this._assetReadSeq || !isCurrentSession(session)
      || !assetViewStation.current(this, context)) return
    const v = { deposit: null, ticket: null, barrel: null }
    let barrelHint = ''

    if (summaryRes && summaryRes.code === 0 && summaryRes.data && typeof summaryRes.data === 'object' && !Array.isArray(summaryRes.data)) {
      const summary = summaryRes.data
      // 2026-10-07 #10：只展示接口真正提供的有限数值；缺字段/畸形值不能冒充真实 0。
      v.deposit = assetNumber(summary.depositBalance)
      v.barrel = assetCount(summary.independentRights ? summary.occupiedBuckets : summaryRes.data.heldBuckets)
      // 新路径 H=E+over，不含数量占用 R；旧 held 含遗留配送中桶，故两者均保留中性「我的桶」。
      barrelHint = summary.independentRights ? '账面桶数，非实物盘点' : '账面汇总，含遗留配送中'
    }

    if (ticketsRes && ticketsRes.code === 0 && Array.isArray(ticketsRes.data)) {
      const counts = ticketsRes.data.map(row => assetCount(row && row.remainQuantity))
      // 空账户列表是真实 0；有行却缺数量则是未加载，不能用 ||0 吞掉。
      if (counts.every(q => q !== null)) v.ticket = counts.reduce((sum, q) => sum + q, 0)
    }

    // 企业资料：仅月结/企业客户显示这行，个人用户不占行
    const isCompany = !!(companyRes && companyRes.code === 0 && companyRes.data
      && (companyRes.data.id || companyRes.data.companyName))

    const view = assetsView(v)
    this.setData({
      deposit: v.deposit,
      ticket: v.ticket,
      barrel: v.barrel,
      barrelHint,
      isCompany,
      ...view
    })

    // 失败出声：toast 说明**哪几格**没加载出来（不再说"显示的可能是 0"——
    // 那正是这次要修掉的误导：没加载出来的格子已经不显示 0 了）
    if (view.unloadedCount > 0) {
      wx.showToast({
        title: '有 ' + view.unloadedCount + ' 项没加载出来，已标「' + ASSET_UNLOADED + '」，不是 0',
        icon: 'none',
        duration: 3000
      })
    }
  },

  /** [2026-09-27 走查 C05 修] 未加载的那几格点一下重新拉一次（不跳转、不刷新整页） */
  onRetryAssets() {
    this.setData({ deposit: null, ticket: null, barrel: null,
      ...assetsView({ deposit: null, ticket: null, barrel: null }), barrelHint: '' })
    return this.loadAssets()
  },

  onUnload() {
    assetViewStation.suspend(this)
    this._assetUnloaded = true
    this._assetReadSeq = (this._assetReadSeq || 0) + 1
  },

  onHide() { assetViewStation.suspend(this) },

  onAssetStationChange(e) {
    if (assetViewStation.select(this, e.detail)) return this.onRetryAssets()
  },

  onLogin() {
    wx.navigateTo({ url: '/pages/login/index' })
  },

  onEditProfile() {
    wx.navigateTo({ url: '/pages/mine/edit' })
  },

  onMenuTap(e) {
    let url = e.currentTarget.dataset.url
    if (['/pages/barrel/index', '/pages/ticket/index', '/pages/deposit/records/index', '/pages/barrel/purchase'].includes(url)) {
      url = assetViewStation.url(this, url)
    }
    // tabBar 页面必须用 switchTab 跳转，否则会被微信拦截
    const tabPages = ['/pages/home/index', '/pages/order/list', '/pages/mine/index']
    if (tabPages.indexOf(url) >= 0) {
      wx.switchTab({ url })
    } else {
      wx.navigateTo({ url })
    }
  },

  onLogout() {
    wx.showModal({
      title: '提示',
      content: '确定退出登录吗？',
      success: (res) => {
        if (res.confirm) {
          app.clearLoginInfo()
          this.setData({
            isLogin: false,
            userInfo: null,
            deposit: null,
            ticket: null,
            barrel: null,
            ...assetsView({ deposit: null, ticket: null, barrel: null }),
            barrelHint: '',
            isCompany: false
          })
          wx.showToast({ title: '已退出', icon: 'success' })
        }
      }
    })
  }
})
