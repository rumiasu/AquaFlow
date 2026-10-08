// 水站管理入口列表（站长）—— 只做路由，不做任何业务判断。
//
// [2026-09-26 IA 重排] 三列彩色宫格 → 行列表；五个分组（订单与客户 / 收款与工资 /
// 桶与库存 / 水站设置 / 经营数据）见 docs/design/29-两端体验与视觉改造方案.md §5。
//
// ⚠️ 本页是站长端所有管理功能的**唯一门**：wxml 里每行一个 `data-url`，少一个就是一个功能
// 彻底点不到，而 page_reach_audit.py / audit_wxml_handlers.py **都验不出来**（前者只验目标页
// 是否注册、后者只验处理函数是否存在）。改 wxml 前后必须逐个 data-url 对照。
// ⚠️ 本页有 tabBar 页做目标：「订单管理」→ pages/coordination/index
// （「水站资料」2026-09-19 起指向自己的 station-info 页，不再是 mine）。
// wx.navigateTo 对 tabBar 页会失败（fail 回调里只有一句 errMsg，界面**毫无反应**，
// 站长会以为"点了没反应=坏了"），必须换 wx.switchTab。
// 这里集中分流，不要在 wxml 里给某张卡单独绑另一个方法 —— 下一个加卡的人一定会漏。
const TAB_BAR_PAGES = ['/pages/coordination/index', '/pages/home/index', '/pages/mine/index']
const { get } = require('../../utils/request')
const { API } = require('../../config/api')
const navigation = require('../../utils/navigation')

Page({
  data: {
    // [2026-10-06] 关键入口待办角标：应收账款 / 业务待办与退出 / 退桶审批
    receivablesBadgeCount: null,
    businessWaitingBadgeCount: null,
    barrelReturnBadgeCount: null,
    badgeLoading: false,
    badgeError: ''
  },

  onShow() {
    navigation.show(this)
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this.loadBadgeCounts()
  },

  onHide() { navigation.hide(this) },
  onUnload() {
    navigation.dispose(this)
    this._badgeSeq = (this._badgeSeq || 0) + 1
  },

  /**
   * 加载关键入口的待办数量（角标）。
   * 静默失败是有意的：角标取不到就不显示，不阻断页面渲染。
   * 复用已有 API，不新增接口。
   */
  async loadBadgeCounts() {
    const seq = this._badgeSeq = (this._badgeSeq || 0) + 1
    this.setData({ badgeLoading: true, badgeError: '', receivablesBadgeCount: null,
      businessWaitingBadgeCount: null, barrelReturnBadgeCount: null })
    try {
      // 2026-10-06：收款流水、退款对象和 INFO 留痕曾被误作三类待办；只认分类汇总。
      const res = await get(API.MANAGER_PENDING_SUMMARY)
      if (seq !== this._badgeSeq) return
      const data = res.data || {}, items = Array.isArray(data.items) ? data.items : []
      const validCount = n => Number.isSafeInteger(n) && n >= 0 ? n : null
      const countOf = key => {
        const rows = items.filter(it => it.key === key)
        return rows.length === 1 && rows[0].available === true ? validCount(rows[0].count) : null
      }
      const receivables = countOf('overdueReceivable'), returns = countOf('barrelReturn')
      const business = validCount(data.businessWaitingTotal)
      this.setData({
        receivablesBadgeCount: receivables, businessWaitingBadgeCount: business,
        barrelReturnBadgeCount: returns,
        badgeError: [receivables, business, returns].includes(null) ? '部分待办数量未核对，点击重试' : ''
      })
    } catch (e) {
      if (seq === this._badgeSeq) this.setData({ badgeError: '待办数量未核对，点击重试' })
    } finally { if (seq === this._badgeSeq) this.setData({ badgeLoading: false }) }
  },

  onNavigate(e) {
    const url = e.currentTarget.dataset.url
    if (!url) return
    if (TAB_BAR_PAGES.indexOf(url) >= 0) {
      navigation.open(url, { owner: this })
      return
    }
    // ⚠️ 页面栈去重：宫格是**常驻入口**，反复进出同一页（尤其"水站资料"这种办完就回头的页）
    // 会把栈堆到 10 层上限，之后 navigateTo 直接失败、界面**毫无反应**。
    // 栈里已有就回退到它 —— 注意 delta 要**算出来**，不能写死 1：
    // 栈可能是 [宫格, 资料页, 营业状态, 宫格]，此时资料页在 2 层之前，退 1 层会落到"营业状态"。
    const route = url.split('?')[0].replace(/^\//, '')
    const pages = getCurrentPages()
    for (let i = pages.length - 1; i >= 0; i--) {
      if (!url.includes('?') && pages[i] && pages[i].route === route) {
        navigation.back(pages.length - 1 - i, { owner: this })
        return
      }
    }
    // 2026-10-06：栈去重看不到正在打开的页；统一保护连点并给超时重试入口。
    navigation.open(url, { owner: this })
  }
})
