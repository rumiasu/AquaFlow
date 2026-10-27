const { showBusinessModal } = require('../utils/business-dialog')
/**
 * 自绘导航栏 + 水站营业状态胶囊（页面级 Behavior，员工端「配送」页与「首页」共用）。
 *
 * 为什么这两页要自绘：站长/配送员要在**最上面、就在「首页 / 配送」字样左边**看到水站营业状态
 * （软状态：休息中 / 待上线…），而原生导航栏塞不进任何东西。所以两页各自
 * `navigationStyle: "custom"`，尺寸算法（状态栏高度 + 微信公众号胶囊算法）只有一处实现：
 * `utils/navbar.js`；结构在 `templates/station-navbar.wxml`；样式在 `styles/station-navbar.wxss`。
 * 三处合起来是本组件，原先在两页里各抄了一份（2026-09-18 抽出）。
 *
 * ⚠️ 营业状态是**软状态**（v32）：只提示、不阻断（见 `docs/design/13-营业状态与公告.md`）。
 * ⚠️ 文案一律来自后端（`statusText` / `note` / `customerHint`），**前端不写 1..4 映射表** ——
 *    拉不到就显示待核实，绝不编造"营业中"。
 * ⚠️ 走**公开接口**（不需要站长权限，配送员也要看得到"今天休息"）：路径直接写常量，
 *    不塞进 `api/station-mgmt.js` 那一族站长接口里，免得被误当成"配送员也能调"。
 *    （**待填项**是站长专属的 `api/station-mgmt.js#getSetupGuide`，但它现在只由
 *     `utils/setup-reminder.js` require —— 本文件**不再**自己拉，见文件顶部那段 require 注释。
 *     2026-09-24 改：原来本文件与 setup-reminder 各拉一次，两份快照会打架。）
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
// 待填项：**只有** `utils/setup-reminder.js` 里 require 了 getSetupGuide ——
// ⚠️ 本文件**不要**再直接 require `getSetupGuide` 自己拉一次。两路各拉各的 = 两份快照，
//    会出现"胶囊数字更新了、小框列表还是旧的"（2026-09-24 踩过）。
const { checkSetupReminder } = require('../utils/setup-reminder')

// 水站营业状态（**公开**接口，配送员无站长权限也要能读）：路径直接写常量，
// 不走 api/station-mgmt.js —— 那一族是站长接口，混在一起容易让人以为配送员也能调。
const STATION_STATUS = '/api/stations'

const { groupSetupItems, progressPatch } = require('../utils/setup-progress')
const navigation = require('../utils/navigation')

module.exports = Behavior({
  data: {
    // 水站营业状态（软状态）：文案由后端下发，前端不做 1..4 映射
    stationStatusText: '', stationOperatingStatus: null,
    setupProgressKnown: false, setupDoneCount: null, setupTotalCount: null, setupRequiredPending: null,
    stationStatusNote: '',
    stationStatusHint: '', stationStatusError: '',
    // 自绘导航栏尺寸（navigationStyle=custom）
    statusBarHeight: 44,
    navBarHeight: 44,

    // 营业状态小框（2026-09-23）：点胶囊弹出，里面同时有「待填项」与「设置营业状态」入口
    stationPanelShow: false,
    stationPanelLoading: false,
    stationPanelItems: [],
    // 未完成 / 已完成两份源数据（切页签时在 js 里合成 stationPanelItems，见 switchPanelTab）
    stationPanelPending: [],
    stationPanelDone: [],
    stationPanelShowAll: false,
    stationPanelSummary: '',
    // 「下一步」那句话（后端下发，三种站况三句 —— 见 StationSetupGuideService#onlineHintOf）。
    // 单独一个字段而不是并进 summaryText：summaryText 是"还差几项"的计数，这句是"接下来做什么"。
    stationPanelOnlineHint: '',
    // 拉待填项失败时的提示。没有它，catch 之后 items 为空 → 走"全配好"分支 → 告诉站长
    // "待填项都配好了" —— 而真相是**根本没拉到**，这会让他以为万事大吉（静默失效）。
    stationPanelError: '', stationPanelReadReady: false, stationPanelRefreshing: false,
    // 待填项**总数**（2026-09-24）：胶囊上的「待填 N 项」徽标。数字来自后端
    // （setup-guide 的 pendingCount），前端不自己加总；拉不到时**保持原值**不清零。
    stationPendingCount: 0
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
     * 拉不到时清除旧状态并显示待核实，不编造"营业中"。
     */
    async loadStationStatus(stationId) {
      const seq = this._stationStatusSeq = (this._stationStatusSeq || 0) + 1
      const app = getApp(), generation = app._loginGeneration || 0
      const identity = () => { const g = getApp().globalData || {}, u = g.userInfo || {}; return JSON.stringify([!!g.isLogin, u.staffId, u.role, u.stationId, u.bindStatus]) }
      const owner = identity()
      const current = () => seq === this._stationStatusSeq && getApp() === app && generation === (app._loginGeneration || 0) && identity() === owner
      if (!stationId) {
        this.setData({ stationOperatingStatus: null, stationStatusText: '', stationStatusNote: '', stationStatusHint: '', stationStatusError: '' })
        return
      }
      this.setData({ stationOperatingStatus: null, stationStatusText: '', stationStatusNote: '', stationStatusHint: '', stationStatusError: '' })
      try {
        const res = await get(STATION_STATUS + '/' + stationId + '/status')
        if (!current()) return
        const d = (res && res.data) || {}
        if (typeof d.statusText !== 'string' || !d.statusText.trim()) throw new Error('营业状态尚未核实')
        this.setData({ stationOperatingStatus: Number.isInteger(d.operatingStatus) ? d.operatingStatus : null, stationStatusText: d.statusText, stationStatusNote: d.note || '', stationStatusHint: d.customerHint || '', stationStatusError: '' })
        return res
      } catch (e) {
        if (current()) this.setData({ stationOperatingStatus: null, stationStatusText: '', stationStatusNote: '', stationStatusHint: '', stationStatusError: '营业状态尚未读取，请重新核实' })
      }
    },

    /**
     * 拉一次完善度清单，**同时**刷新：胶囊徽标、小框里的待填项列表、汇总文案、"下一步"，
     * 并顺手做一次「新出现待填项」提醒。
     *
     * 为什么四件事捆一起：读的是同一个接口（`/api/manager/setup-guide`）。**分开刷 = 两份快照** ——
     * 2026-09-24 产品报的"刚填完的项还显示着要填"就是因为这条链路只刷了徽标、没刷列表。
     * 提醒逻辑与"哪些算新出现"的判据在 `utils/setup-reminder.js`。
     *
     * ⚠️ **只有站长能调**：该接口带 `@RequireRole("STATION_MANAGER")`，配送员会被直接拒。
     *    `isManager` 由使用本 behavior 的页面算好放进 data（pages/home 与 pages/coordination 都已算）。
     * ⚠️ 拉不到（返回 null）时：**保留旧列表与旧徽标**（不清零，否则站长会以为已经配好了），
     *    但必须收掉 loading，并标明本次尚未核实 —— 详见方法内注释。
     */
    async loadStationPending() {
      if (!this.data.isManager) return
      const seq = this._stationPendingSeq = (this._stationPendingSeq || 0) + 1
      const app = getApp(), generation = app._loginGeneration || 0
      const identity = () => { const g = getApp().globalData || {}, u = g.userInfo || {}; return JSON.stringify([!!g.isLogin, u.staffId, u.role, u.stationId, u.bindStatus]) }
      const owner = identity()
      let r = null
      try { r = await checkSetupReminder() } catch (e) { /* 读取或本地提醒记录失败，均不能确认配置齐全 */ }
      if (seq !== this._stationPendingSeq || getApp() !== app || generation !== (app._loginGeneration || 0) || identity() !== owner) return
      if (!r) {
        // ⚠️ 拉不到**必须收掉 loading 并说出来**，两条都不能省：
        //    ① 不收 loading → 小框永远停在"正在检查待填项…"转圈；
        //    ② 有列表却把 stationPanelError 清掉 → 回到"刚填完的项还显示着要填"那个 bug 的
        //       另一种形态（拿旧快照当新的）。所以：**有旧列表就保留它，但绝不假装它是新的**。
        this.setData({
          stationPanelLoading: false, stationPanelRefreshing: false, stationPanelReadReady: false, setupProgressKnown: false,
          stationPanelError: (this.data.stationPanelItems || []).length
            ? '本次待填项未完成核对，以下为上次读取记录'
            : '待填项尚未读取，请重新核对'
        })
        return
      }
      // ⚠️ 一处 setData 把**四件事**一起写（见 setupPatchOf 的说明）。
      //    只写 stationPendingCount 是不够的 —— 那样胶囊数字会变、小框里的列表还是旧的，
      //    于是"刚填完的项还显示着要填"（2026-09-24 产品报的 bug，就是这么来的）。
      this.setData(this.setupPatchOf(r))
    },

    onRetryStationPending() {
      this.setData({ stationPanelRefreshing: true })
      this.loadStationPending()
    },

    /**
     * 把 `checkSetupReminder()` 的返回值翻成一组 data 补丁。**唯一**的翻译点 ——
     * 徽标 / 列表 / 汇总 / 下一步 四处都从这里出，保证它们永远是**同一份快照**。
     *
     * ⚠️ **攒成一个 patch 再 setData**：不要在中间插 `setData`。原来这里写成
     *    `try { setData(结果) } finally { setData(loading:false) }` —— `finally` 在 try 的
     *    setData **之后同步**跑，同一个同步块里连调两次，实测触发
     *    「recursive update detected」，后面还跟一个渲染层 `Cannot read properties of undefined (reading '0')`。
     */
    setupPatchOf(r) {
      const pending = r.pending || []
      const done = (r.all || []).filter(it => it && it.done)
      return {
        ...progressPatch(r),
        stationPendingCount: Number.isSafeInteger(r.p0PendingCount) ? r.p0PendingCount : null,
        stationPanelPending: pending,
        stationPanelDone: done,
        // 渲染用的那一份：**跟着当前页签走**（见 switchPanelTab）。
        // ⚠️ 在 js 里拼好、wxml 只渲染 —— 别写成 `wx:for="{{showAll ? a : b}}"`。
        stationPanelItems: groupSetupItems(this.data.stationPanelShowAll ? pending.concat(done) : pending),
        stationPanelSummary: pending.length ? (r.summaryText || '') : '',
        stationPanelOnlineHint: r.onlineHint || '',
        stationPanelError: '', stationPanelReadReady: true, stationPanelRefreshing: false,
        stationPanelLoading: false
      }
    },

    /**
     * 小框顶部「待填 / 已配好」两个页签。
     *
     * [2026-09-24 产品反馈]「待上线点击后，没法看到已完成的项 —— 我填错了，想回去重新填却没了，
     * 只能回去设置里找」。原因有两个，这次一起修：
     *   ① 上一版的开关在**列表下面**（summary 与"下一步"之下）的一句灰字里，实测发现不了 → 挪到列表上方做成页签；
     *   ② 已配好的行右侧写的是「已配好」这个**状态标签**，看着不可点 → 改成「去修改 ›」。
     */
    onPanelShowPending() { this.switchPanelTab(false) },
    onPanelShowDone() { this.switchPanelTab(true) },

    switchPanelTab(showAll) {
      if (showAll === this.data.stationPanelShowAll) return
      const pending = this.data.stationPanelPending || []
      const done = this.data.stationPanelDone || []
      // ⚠️ 一次 setData 把开关与列表一起改（见 setupPatchOf 的 ⚠️），中间不要插第二次
      this.setData({
        stationPanelShowAll: showAll,
        stationPanelItems: groupSetupItems(showAll ? pending.concat(done) : pending)
      })
    },

    /**
     * 点状态胶囊 → **弹营业状态小框**（2026-09-23 定稿）。
     *
     * 小框里三块：① 当前状态 + 留言；② **待填项**清单（点一条直达对应页面）；
     * ③ 「设置营业状态」入口 → `pages/station-mgmt/station-status/index`。
     *
     * ⚠️ 为什么不做成"直接跳设置页"：站长在首页点一下的意图往往是"我这站现在什么情况"，
     *    直接跳走会把人从他正在看的东西上拽走；而"待填项"是**待上线的原因**，必须与状态同屏。
     * ⚠️ 配送员保持原样只弹一句话：他们没有站长权限，`/api/manager/setup-guide` 会直接被拒。
     * `isManager` 由使用本 behavior 的页面算好放进 data（pages/home 与 pages/coordination 都已算）。
     */
    onStationStatusTap() {
      const { stationStatusText, stationStatusNote, stationStatusError, isManager } = this.data
      if (stationStatusError) {
        showBusinessModal({ confirmColor: '#1264D6', cancelColor: '#637083',  title: '营业状态待核实', content: stationStatusError, confirmText: '重新读取', cancelText: '稍后再看', success: result => { if (result.confirm) this.loadStationStatus((getApp().globalData.userInfo || {}).stationId) } })
        return
      }
      if (!stationStatusText) return
      if (isManager) {
        this.openStationPanel()
        return
      }
      showBusinessModal({ confirmColor: '#1264D6', cancelColor: '#637083',
        title: '水站状态',
        content: stationStatusNote ? stationStatusText + '\n' + stationStatusNote : stationStatusText,
        showCancel: false,
        confirmText: '知道了'
      })
    },

    /**
     * 打开营业状态小框。
     *
     * ⚠️ **每次打开都重新拉**（2026-09-24 改）。原来写的是"首次打开才拉"
     * （`needLoad = !loading && !items.length`），后果是：站长从「去填写」补完一项回来，
     * 小框里**那一项还在**，等于让他再填一遍（产品实际报的问题）。
     * 现在：**先拿缓存列表把框显示出来**（不闪、不等），再后台拉一份新的替换掉。
     */
    openStationPanel() {
      const hasCache = (this.data.stationPanelItems || []).length > 0
      // ⚠️ **一次 setData 把几个字段都设上**。不要写成 setData(show) 之后再调一个内部会
      //    setData(loading) 的方法 —— 同一个同步块里连调两次 setData，实测触发
      //    「recursive update detected: a data update is applying during another data update」，
      //    紧接着渲染层抛 `Cannot read properties of undefined (reading '0')`，小框根本出不来。
      //    （刷新是 async 的，它的 setData 在 await 之后，属于另一个同步块，安全。）
      this.setData({
        stationPanelShow: true,
        // 有缓存就先显示缓存（loading 只用来告诉用户"还没拉到过"）；没缓存才显示"正在检查…"
        stationPanelLoading: !hasCache,
        stationPanelRefreshing: true,
        stationPanelError: ''
      })
      this.loadStationPending()
    },

    closeStationPanel() {
      this.setData({ stationPanelShow: false })
    },

    /** 小框里点一条待填项 → 先关框再跳（不关的话返回时框还盖着，像"没反应"） */
    onPanelGoFill(e) {
      const route = e.currentTarget.dataset.route
      if (!route) {
        wx.showToast({ title: '这一项还没有直达页面，请看上面的说明', icon: 'none' })
        return
      }
      this.setData({ stationPanelShow: false })
      navigation.open(route, { owner: this })
    },

    /** 小框底部「设置营业状态」→ 那个页面（原本点胶囊的落点） */
    goStationStatus() {
      this.setData({ stationPanelShow: false })
      navigation.open('/pages/station-mgmt/station-status/index', { owner: this })
    },

    /** 小框遮罩上吞掉 touchmove，防止滚动穿透（wxml 用 catchtouchmove） */
    preventMove() {}
  }
})
