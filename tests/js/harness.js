/**
 * 小程序「流程测试」最小骨架（契约：实际执行页面处理函数 + 模拟 API/微信回调，
 * 断言请求次数、幂等键、跳转与页面状态；**不是** grep 某个字符串）。
 *
 * 为什么自己写而不用现成框架：本仓没有 npm 依赖、两端小程序都没有构建步骤，
 * 引一个测试框架就要连带引入 node_modules 与打包配置。这里只用 Node 内置能力：
 *   · 造一个 `wx` / `getApp` / `Page` 全局环境；
 *   · 拦掉 `require('../../api/xxx')` 这些**网络模块**，换成可编排的假实现；
 *   · `utils/**` 一律用**真模块**（幂等键、存储、地址格式化这些逻辑本身就要被测）。
 *
 * 用法见 tests/js/order-create-flow.test.js。
 */

const path = require('path')
const Module = require('module')

const ROOT = path.resolve(__dirname, '..', '..')

/** 造一个可编排的 wx 环境，并把每次调用记下来供断言。 */
function createWx() {
  const calls = { toast: [], modal: [], nav: [], loading: [], storageSet: [], request: [] }
  const storage = new Map()
  const wx = {
    __calls: calls,
    // 弹窗默认"用户点了确认"；测试可以改 modalAutoConfirm 或直接看 calls.modal
    __modalAutoConfirm: true,
    showToast(o) { calls.toast.push(o || {}) },
    hideToast() {},
    showLoading(o) { calls.loading.push(o || {}) },
    hideLoading() {},
    showModal(o) {
      const opt = o || {}
      // ===== 平台契约校验：弹窗按钮文案最多 4 个字符 =====
      // 微信 `wx.showModal` 的 confirmText / cancelText **最多 4 个字符**；超了既不弹窗、
      // 也不报错（线上同症状报告：confirmText/cancelText 超过四个字时"没有反应也没有任何错误"）。
      // 2026-09-26 真机反馈"点存为草稿毫无反应"就是这个原因 —— 当时写的是「下架为草稿」(5 字)，
      // 那次调用被平台直接丢弃，而页面既没有 fail 回调、也没有任何日志。
      // 这里**直接抛**：让流程测试红掉，而不是让这类问题在测试里静默通过。
      ['confirmText', 'cancelText'].forEach((k) => {
        const v = opt[k]
        if (typeof v === 'string' && [...v].length > 4) {
          throw new Error(`showModal 的 ${k}「${v}」有 ${[...v].length} 个字符，超过平台上限 4 —— 真机上会既不弹窗也不报错`)
        }
      })
      calls.modal.push(opt)
      if (opt.__manual) return
      const confirm = wx.__modalAutoConfirm
      if (typeof opt.success === 'function') opt.success({ confirm, cancel: !confirm })
      if (typeof opt.complete === 'function') opt.complete({ confirm, cancel: !confirm })
    },
    redirectTo(o) { calls.nav.push({ type: 'redirectTo', url: o && o.url }) },
    navigateTo(o) { calls.nav.push({ type: 'navigateTo', url: o && o.url }) },
    switchTab(o) { calls.nav.push({ type: 'switchTab', url: o && o.url }) },
    navigateBack(o) { calls.nav.push({ type: 'navigateBack', delta: o && o.delta }) },
    setStorageSync(k, v) { storage.set(k, v); calls.storageSet.push({ k, v }) },
    getStorageSync(k) { return storage.has(k) ? storage.get(k) : '' },
    removeStorageSync(k) { storage.delete(k) },
    clearStorageSync() { storage.clear() },
    __storage: storage,
    previewImage() {},
    chooseImage() {},
    getSystemInfoSync() { return { windowWidth: 375 } },
    createSelectorQuery() { return { select: () => ({ boundingClientRect: () => ({ exec: (cb) => cb && cb([{}]) }) }), exec: (cb) => cb && cb([]) } },
    request() {}
  }
  return wx
}

/** 造一个 getApp 返回值（页面只用到 globalData 与几个方法）。 */
function createApp(extra) {
  return Object.assign({
    globalData: { isLogin: true, customerId: 7, tempStationId: null, stationId: null },
    canAccessStationBusiness: () => true,
    routeByRole: () => {},
    getCart: () => ({}),
    clearCart: () => {}
  }, extra || {})
}

/**
 * 把页面模块加载起来，返回一个可调用的"页面实例"。
 *
 * @param {string} relPagePath 相对仓库根的页面 js 路径，如 'miniapp-user/pages/order/create.js'
 * @param {object} opts { stubs: {模块路径后缀: 假实现}, wx, app }
 */
function loadPage(relPagePath, opts) {
  const options = opts || {}
  const wx = options.wx || createWx()
  const app = options.app || createApp()
  const pagePath = path.resolve(ROOT, relPagePath)

  let captured = null
  // ⚠️ 全局只装一次、**不还原**：小程序运行时里 `wx` / `getApp` 全程都在，
  // 而页面里有些调用（如 utils/token.getCustomerId）发生在 loadPage 之后 ——
  // 还原成 undefined 会让这些**延迟调用**在测试里抛错，掩盖真正的断言结果。
  global.wx = wx
  global.getApp = () => app
  global.Page = (cfg) => { captured = cfg }
  global.getCurrentPages = () => [{}]

  // 拦掉网络模块：只按"请求路径的后半段"匹配，避免写死相对层级
  const stubs = options.stubs || {}
  const originalLoad = Module._load
  Module._load = function (request, parent, isMain) {
    const keys = Object.keys(stubs)
    for (const k of keys) {
      if (request === k || request.endsWith(k)) {
        return stubs[k]
      }
    }
    return originalLoad.apply(this, arguments)
  }

  try {
    delete require.cache[require.resolve(pagePath)]
    require(pagePath)
  } finally {
    Module._load = originalLoad
  }

  if (!captured) {
    throw new Error('页面没有调用 Page()：' + relPagePath)
  }

  // 实例：data 深拷贝一份，方法直接绑上（与小程序运行时足够像）
  const instance = Object.assign({}, captured)
  instance.data = JSON.parse(JSON.stringify(captured.data || {}))
  instance.__wx = wx
  instance.__app = app
  instance.setData = function (patch, cb) {
    Object.assign(instance.data, patch || {})
    if (typeof cb === 'function') cb()
  }
  // 页面上挂的非 data 字段（如 this.enterprisePrompted）
  Object.keys(captured).forEach((k) => {
    if (typeof captured[k] === 'function') instance[k] = captured[k].bind(instance)
  })
  return instance
}

/**
 * 「卡死即失败」看门狗：套件开头 `const done = armWatchdog()`，结尾 `done()`。
 *
 * <p><b>为什么必须有</b>：用例 await 的 Promise 若永远不 resolve（假后端忘了 resolve、
 * 页面的分支根本没走到），Node 的事件循环会空转然后<b>静默退出、退出码 0</b> ——
 * 输出停在半截，CI 判绿。这不是假设：2026-09-26 给「连点只发一次请求」做反向验证时实测到了
 * （把保护摘掉后第一个请求的 resolver 被第二次调用覆盖，套件打印到第 4 条就退出、退出码 0）。
 * 假绿比红贵得多，所以宁可让整个套件超时失败。</p>
 *
 * <p>定时器**故意保持事件循环存活**（不 unref）：卡住时它才有机会开火。
 * {@code tests/js/run-all.js} 另有外部超时（spawnSync）兜底。</p>
 */
function armWatchdog(ms) {
  const timer = setTimeout(() => {
    console.error('\n套件超时未结束：有用例卡住了（多数是假后端没 resolve，或页面分支没走到）。判为失败。')
    process.exit(1)
  }, ms || 120000)
  return () => clearTimeout(timer)
}

module.exports = { loadPage, createWx, createApp, armWatchdog, ROOT }
