/**
 * 自绘导航栏的尺寸（员工端「首页」「配送」两页用）。
 *
 * 为什么这两页要自绘：站长/配送员要在**最上面、就在「首页 / 配送」字样左边**看到水站营业状态
 * （软状态：休息中 / 配送延迟…），而原生导航栏塞不进任何东西。客户端首页本来就是
 * `navigationStyle: custom`，所以那边不需要这套。
 *
 * ⚠️ 口径与客户端首页保持一致：优先 `wx.getWindowInfo`，回落 `getSystemInfoSync`；
 * 胶囊取不到时（老基础库 / 开发者工具早期版本）用微信推荐的默认值，**不要让页面因为拿不到尺寸而白屏**。
 * ⚠️ `navBarHeight` 的算法是微信官方推荐的那条：`(胶囊上边距 − 状态栏高度) × 2 + 胶囊高度`。
 * 直接写死 44px 在刘海屏/大屏上会与胶囊按钮重叠。
 */
function navMetrics() {
  let statusBarHeight = 44
  try {
    const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
    statusBarHeight = (info && info.statusBarHeight) || 44
  } catch (e) { /* 拿不到就用默认值，不抛 */ }

  let navBarHeight = 44
  try {
    const cap = wx.getMenuButtonBoundingClientRect ? wx.getMenuButtonBoundingClientRect() : null
    if (cap && cap.height && cap.top) {
      navBarHeight = (cap.top - statusBarHeight) * 2 + cap.height
    }
  } catch (e) { /* 同上 */ }

  return { statusBarHeight, navBarHeight }
}

module.exports = { navMetrics }
