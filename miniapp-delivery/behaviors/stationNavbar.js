/**
 * 自绘导航栏 + 水站营业状态胶囊（页面级 Behavior，员工端「配送」页与「首页」共用）。
 *
 * 为什么这两页要自绘：站长/配送员要在**最上面、就在「首页 / 配送」字样左边**看到水站营业状态
 * （软状态：休息中 / 配送延迟…），而原生导航栏塞不进任何东西。所以两页各自
 * `navigationStyle: "custom"`，尺寸算法（状态栏高度 + 微信公众号胶囊算法）只有一处实现：
 * `utils/navbar.js`；结构在 `templates/station-navbar.wxml`；样式在 `styles/station-navbar.wxss`。
 * 三处合起来是本组件，原先在两页里各抄了一份（2026-09-18 抽出）。
 *
 * ⚠️ 营业状态是**软状态**（v32）：只提示、不阻断（见 `docs/design/13-营业状态与公告.md`）。
 * ⚠️ 文案一律来自后端（`statusText` / `note` / `customerHint`），**前端不写 1..4 映射表** ——
 *    拉不到就清空不显示，绝不编造"营业中"（与客户端同一口径）。
 * ⚠️ 走**公开接口**（不需要站长权限，配送员也要看得到"今天休息"）：路径直接写常量，
 *    不往 `api/station-mgmt.js` 里加 —— 那个文件正被另一个工作流改动。
 *
 * 用法（两个页面完全一致）：
 *   js:   const stationNavbar = require('../../behaviors/stationNavbar')
 *         Page({ behaviors: [stationNavbar], onLoad() { this.initNavMetrics() }, ... })
 *   wxml: <import src="../../templates/station-navbar.wxml" />
 *         <template is="stationNavbar" data="{{title: '配送', ...}}" />
 *   wxss: @import "../../styles/station-navbar.wxss";
 * ⚠️ `Page` 的 `behaviors` 需要基础库 2.9.2+（本端 project.config.json 的 libVersion 是 3.17.0）。
 * ⚠️ 尺寸初始化故意**不放进 behavior 的生命周期**（不写 onLoad/attached）：页面 behaviors 的
 *    生命周期合并顺序没有明确文档（页面自己的 onLoad 与 behavior 的 onLoad 谁先谁后未定义），
 *    而这两页各自都有 onLoad 要做别的事（角色校验、设置标题）。所以由页面显式调用
 *    `this.initNavMetrics()`，时机与抽出前的 `this.setData(navMetrics())` 逐字一致。
 */
const { get } = require('../utils/request')
const { navMetrics } = require('../utils/navbar')

// 水站营业状态（公开接口，配送员无站长权限也要能读）
const STATION_STATUS = '/api/stations'

module.exports = Behavior({
  data: {
    // 水站营业状态（软状态）：文案由后端下发，前端不做 1..4 映射
    stationStatusText: '',
    stationStatusNote: '',
    stationStatusHint: '',
    // 自绘导航栏尺寸（navigationStyle=custom）
    statusBarHeight: 44,
    navBarHeight: 44
  },

  methods: {
    /**
     * 自绘导航栏尺寸。页面 onLoad 里调（见文件头：生命周期不放 behavior）：
     * **先算好再渲染**，避免状态胶囊闪一下。
     */
    initNavMetrics() {
      this.setData(navMetrics())
    },

    /**
     * 拉水站营业状态（软状态）：只提示、不阻断。文案全部后端下发；
     * 拉不到就清空，不编造"营业中"。
     */
    async loadStationStatus(stationId) {
      if (!stationId) {
        this.setData({ stationStatusText: '', stationStatusNote: '', stationStatusHint: '' })
        return
      }
      try {
        const res = await get(STATION_STATUS + '/' + stationId + '/status')
        const d = (res && res.data) || {}
        this.setData({
          stationStatusText: d.statusText || '',
          stationStatusNote: d.note || '',
          stationStatusHint: d.customerHint || ''
        })
      } catch (e) {
        this.setData({ stationStatusText: '', stationStatusNote: '', stationStatusHint: '' })
      }
    },

    /** 点状态胶囊看详情（文案仍全部来自后端，弹窗只做拼接） */
    onStationStatusTap() {
      const { stationStatusText, stationStatusNote } = this.data
      if (!stationStatusText) return
      wx.showModal({
        title: '水站状态',
        content: stationStatusNote ? stationStatusText + '\n' + stationStatusNote : stationStatusText,
        showCancel: false,
        confirmText: '知道了'
      })
    }
  }
})
