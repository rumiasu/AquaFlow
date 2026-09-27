/**
 * 自绘底部导航（custom tabBar）。
 *
 * [2026-09-26 为什么改成自绘] 产品原话：「首页 tab 在普通配送员那里直接不显示」。
 * 微信**没有**"隐藏原生 tabBar 某一项"的 API —— `wx.setTabBarItem` 的参数只有
 * index / text / iconPath / selectedIconPath（官方文档，基础库 1.9.0+），**没有 visible**；
 * `wx.hideTabBar` 是整条隐藏。要让"首页"只对站长存在，只有自绘这一条路
 * （`app.json` 的 `tabBar.custom = true` + 本组件，路径与目录名 `custom-tab-bar` 是微信写死的）。
 *
 * ⚠️ **`app.json` 的 tabBar.list 必须留着**：它仍是 `wx.switchTab` 的合法目标表，
 * 同时也是低版本基础库拿不到本组件时的回退（那时会渲染原生三项，配送员会看到"首页"，
 * 但点进去是「您无权限 → 自动回配送」，不会卡住）。
 *
 * ⚠️ **红点**：`wx.showTabBarRedDot` 在自绘模式下无效（没有原生条目可点），
 * 所以站长的 P0 待办红点改由本组件画（见 utils/pending-reminder.js 的 syncTabBarDot）。
 * 两条路都保留：原生 API 照旧调（回退模式用得上），自绘这条路走 storage 标记 + setDot。
 *
 * ⚠️ 每个 tab 页都要在自己的 onShow 里 `this.getTabBar().sync('<本页路径>')`：
 * 组件实例是**按页**创建的（每个 tab 页一份），不 sync 就会出现"切过去高亮还停在上一个页签"，
 * 而且角色是登录后才确定的 —— 冷启动时组件先按"未登录"渲染（2 项），登录/换身份后必须重算。
 */
const { readDotFlag } = require('../utils/pending-reminder')

// 与 app.json 的 tabBar.list 一一对应（图标路径照抄，别另起一份命名）。
const TAB_COORDINATION = {
  pagePath: '/pages/coordination/index',
  text: '首页',
  iconPath: '/static/tabbar/coordination.png',
  selectedIconPath: '/static/tabbar/coordination-active.png'
}
const TAB_HOME = {
  pagePath: '/pages/home/index',
  text: '配送',
  iconPath: '/static/tabbar/delivery.png',
  selectedIconPath: '/static/tabbar/delivery-active.png'
}
const TAB_MINE = {
  pagePath: '/pages/mine/index',
  text: '我的',
  iconPath: '/static/tabbar/mine.png',
  selectedIconPath: '/static/tabbar/mine-active.png'
}

/** 站长：首页（站长首页）+ 配送 + 我的；配送员 / 未登录：只有配送 + 我的。 */
const TABS_MANAGER = [TAB_COORDINATION, TAB_HOME, TAB_MINE]
const TABS_DELIVERY = [TAB_HOME, TAB_MINE]

Component({
  data: {
    tabs: TABS_DELIVERY,
    selectedPath: TAB_HOME.pagePath,
    showDot: false
  },

  lifetimes: {
    /**
     * 首帧就按当前身份渲染（[2026-09-26]）。
     *
     * ⚠️ 不做这一步的话，站长冷启动会**先闪一下"只有两项"**：组件的 attached 早于页面的 onShow，
     * 而角色是在页面 onShow 里才同步过来的。attached 里 globalData 已经有身份了
     * （`app.onShow` → `checkLoginState` 在读 storage，早于任何页面渲染），所以这里能算对。
     * 高亮位置仍由页面的 `syncTabBar(this, 路径)` 覆盖 —— 这里只知道"有哪几项"，不知道"当前在哪页"。
     */
    attached() {
      this.sync(this.data.selectedPath)
    }
  },

  methods: {
    /**
     * 页面 onShow 调：按当前身份重算要显示哪几项，并把高亮挪到本页。
     *
     * ⚠️ 角色从这里现读（`getApp().globalData.userInfo`），不缓存在 data 里：
     * 换身份（站长 ↔ 配送员）走的是同一个页面实例，缓存会导致"退出后还显示首页"。
     */
    sync(selectedPath) {
      const app = getApp()
      const u = (app && app.globalData && app.globalData.userInfo) || {}
      const role = u.role || ''
      const isManager = role === 'STATION_MANAGER' || role === 'manager' || role === 'MANAGER'
      const tabs = isManager ? TABS_MANAGER : TABS_DELIVERY
      // 未登录 / 还没选身份时也可能落在 tab 页上：此时 tabs 只有两项，
      // selectedPath 若指向"首页"就高亮不上任何一项 —— 保持"配送"高亮即可（下面兜一下）。
      let selected = selectedPath || ''
      if (!tabs.some(t => t.pagePath === selected)) selected = tabs[0].pagePath
      this.setData({ tabs, selectedPath: selected })
      this.refreshDot()
    },

    /** 从 storage 读待办红点标记并画出来（真正的"要不要亮"由 utils/pending-reminder 决定）。 */
    refreshDot() {
      this.setData({ showDot: readDotFlag() })
    },

    /** 给 utils/pending-reminder 直接推状态用（它拿不到这个组件实例时也会写 storage）。 */
    setDot(on) {
      this.setData({ showDot: !!on })
    },

    onTap(e) {
      const url = e.currentTarget.dataset.path
      if (!url || url === this.data.selectedPath) return
      // tabBar 页只能 switchTab（navigateTo 必然失败且界面毫无反应）
      wx.switchTab({
        url,
        fail: () => wx.showToast({ title: '打开失败，请重试', icon: 'none' })
      })
    }
  }
})
