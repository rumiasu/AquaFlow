/**
 * 自绘底栏（custom tabBar）与页面之间的唯一衔接点。
 *
 * 背景：本端 `app.json` 的 `tabBar.custom = true`（[2026-09-26] 为了让「首页」只对站长显示 ——
 * 微信没有"隐藏原生 tabBar 某一项"的 API）。自绘组件见 `custom-tab-bar/index.js`。
 *
 * ⚠️ 三条必须知道的：
 *   1. **每个 tab 页的 onShow 都要调一次** `syncTabBar(this, '/pages/xxx/index')`。
 *      组件实例是**按页各一份**的：不调就会出现"切过去了，高亮还停在上一个页签"；
 *      而且角色是登录后才确定的，冷启动时组件先按未登录渲染（两项），登录/换身份后必须重算。
 *   2. **不能只在 app.onShow 里调**：`app.onShow` 在"从别的页切回 tab 页"时不会触发。
 *   3. 非 tab 页调它是**无害的空操作**（`getTabBar` 拿不到，直接返回）—— 所以页面可以无脑调，
 *      不必先判断自己是不是 tab 页。
 */

/**
 * @param {Object} page 页面实例（传 `this`）
 * @param {string} selectedPath 本页路径，如 `/pages/home/index`
 */
function syncTabBar(page, selectedPath) {
  try {
    if (!page || typeof page.getTabBar !== 'function') return
    const bar = page.getTabBar()
    if (bar && typeof bar.sync === 'function') bar.sync(selectedPath)
  } catch (e) {
    // 自绘底栏只是导航，出问题不该连累页面（真出问题在开发者工具里会看到组件报错）
    console.warn('[tabbar] 同步自绘底栏失败:', e && e.message)
  }
}

module.exports = { syncTabBar }
