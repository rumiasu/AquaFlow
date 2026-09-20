// 网络请求封装（JWT 双 Token + 自动续期）
const { getBaseUrl, API } = require('../config/api')
const { STORAGE_KEYS } = require('../utils/storage-keys')

let isRefreshing = false
let refreshQueue = []

const request = (options) => {
  return new Promise((resolve, reject) => {
    const app = getApp()
    const baseUrl = getBaseUrl()
    const accessToken = app.globalData.accessToken || wx.getStorageSync(STORAGE_KEYS.ACCESS_TOKEN)

    const header = {
      'Content-Type': 'application/json',
      ...options.header
    }

    if (accessToken) {
      header['Authorization'] = `Bearer ${accessToken}`
    }

    // 拼接 query 参数
    let url = baseUrl + options.url
    if (options.query) {
      const qs = Object.keys(options.query)
        .filter(k => options.query[k] !== undefined && options.query[k] !== null)
        .map(k => `${k}=${encodeURIComponent(options.query[k])}`)
        .join('&')
      if (qs) {
        url += (url.includes('?') ? '&' : '?') + qs
      }
    }

    wx.request({
      url,
      method: options.method || 'GET',
      data: options.data,
      header,
      timeout: 15000,
      success: (res) => {
        if (res.statusCode === 200) {
          if (res.data.code === 0 || res.data.code === 200) {
            resolve(res.data)
          } else if (res.data.code === 401) {
            // access_token 过期，尝试自动续期
            handle401(options, resolve, reject)
          } else {
            const msg = res.data.message || '请求失败'
            // [2026-09-14] 系统级错误（后端 code=500）用户既看不懂也做不了，
            // 主动询问是否上报给水站；业务错误（code=1）的文案本身已"能看懂、能处理"，
            // 不再弹窗打扰（见 offerErrorReport 的说明）。
            if (res.data.code === 500) offerErrorReport(msg, options)
            reject(new Error(msg))
          }
        } else if (res.statusCode === 401) {
          handle401(options, resolve, reject)
        } else {
          // [2026-09-20] 原来直接把状态码拼给用户看（「网络错误 500」）—— 顾客看不懂也没法处理。
          // 保留状态码在括号里，排查时仍能一眼看出是 4xx 还是 5xx。
          reject(new Error('服务暂时不可用（HTTP ' + res.statusCode + '），请稍后重试'))
        }
      },
      fail: (err) => {
        reject(toNetworkError(err))
      }
    })
  })
}

/**
 * 把 wx 的失败对象归一化成带可读 message 的 Error。
 *
 * <p>[2026-09-20 真机联调] 这不是美化文案，是修一个**系统性根因**：`wx.request` 的 `fail`
 * 回调拿到的是 `{errMsg: "request:fail timeout"}` 这种**没有 `message` 字段**的对象，
 * 原样 `reject` 出去后，全端约 150 处 `err.message || 'xxx失败'` 会**全部走兜底分支** ——
 * 于是真机弱网/后端没起时，用户看到的是「下单失败: 」（冒号后面什么都没有）、
 * 「保存失败: 」这类**没有原因**的提示，排查时也拿不到任何线索。</p>
 *
 * <p>⚠️ 别把这里改回 `reject(err)`：`err.message` 恒为 `undefined` 是 wx 的既定形状，
 * 不是偶发。要加新文案就在这里加分支，不要在调用点各写一套。</p>
 */
function toNetworkError(err) {
  const raw = (err && (err.errMsg || err.message)) || ''
  if (/timeout/i.test(raw)) {
    return new Error('网络超时，请确认手机与后端在同一网络后重试')
  }
  if (/fail/i.test(raw)) {
    return new Error('网络连接失败，请检查网络后重试')
  }
  return new Error(raw || '网络连接失败，请重试')
}

/* ==================== 系统级错误「一键上报给水站」 ==================== */

// 本次启动内已询问过的错误文案：防止同一问题反复弹窗（用户误触 / 一次操作多个请求同时失败）
const offeredReports = new Set()

/**
 * 系统级错误（后端 code=500）主动询问用户是否上报给水站。
 *
 * <p><b>为什么只对 500 弹、不对业务错误弹</b>：业务错误（code=1）的文案本身就是
 * "能看懂、能处理"的（如"水票余额不足，请先购买水票后再试"），再弹一次上报纯属打扰；
 * 而 500 意味着服务端异常，用户既看不懂也做不了，唯一有价值的动作就是把它交给站长。</p>
 *
 * <p>复用既有的反馈通道：提交走 {@code POST /api/feedback}，站长在
 * {@code miniapp-delivery/pages/station-mgmt/customer-feedback} 里接收
 * （{@code GET /api/feedback/customers}，只读）。
 * [2026-09-18 订正] 这里原写作 {@code miniapp-delivery/pages/feedback} —— 那是**员工自己的**
 * 「我的反馈」列表（{@code /api/feedback/my}），站长根本看不到客户提交的内容。
 * 一句指错地方的注释等于把下一个排查的人送到错页面（本仓 §6.1 注释契约）。</p>
 *
 * <p><b>[2026-09-18] 自动上报刻意保持实名（不传 {@code anonymous}，落库即 0）—— 这不是漏改</b>：
 * 自动上报与「客服页手动提反馈」是两件事。手动反馈是<b>意见</b>（可能针对水站本身，
 * 实名会让客户不敢开口，所以要给匿名开关）；自动上报是<b>报障</b>，它的唯一价值就是
 * 「站长能复现、能追问」—— 而反馈内容本身已经写明了页面与接口（下面 {@code content} 里那几行），
 * 匿名之后站长拿到一条"某客户在某页报了个错"却不知道找谁问，这条上报基本等于没用。
 * 客户若不想被认出来，可以到「客服/反馈」页手动提交（那边有匿名开关）。
 * ⚠️ 要改成匿名只是一个参数的事（{@code data} 里加 {@code anonymous: true}），
 * 属于**产品决定**，不要在排查问题时顺手改掉。</p>
 */
function offerErrorReport(message, options) {
  if (!message || offeredReports.has(message)) return
  offeredReports.add(message)

  wx.showModal({
    title: '遇到点问题',
    content: '系统好像开小差了。要把这个问题告诉水站吗？',
    confirmText: '上报',
    cancelText: '不用了',
    success: (r) => {
      if (r.confirm) submitErrorReport(message, options)
    }
  })
}

function submitErrorReport(message, options) {
  const pages = (typeof getCurrentPages === 'function') ? getCurrentPages() : []
  const route = pages.length ? (pages[pages.length - 1].route || '未知页面') : '未知页面'
  const api = (options && options.url) ? options.url : '未知接口'

  const d = new Date()
  const p2 = (n) => (n < 10 ? '0' + n : '' + n)
  const ts = `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())} `
    + `${p2(d.getHours())}:${p2(d.getMinutes())}`

  // 后端 FeedbackCreateDTO 限制 content ≤ 1000 字，超长会被拒 → 先截断
  const content = `【自动上报·系统错误】\n错误：${message}\n页面：${route}\n接口：${api}\n时间：${ts}`
    .slice(0, 1000)

  const app = getApp()
  const token = (app && app.globalData && app.globalData.accessToken)
    || wx.getStorageSync(STORAGE_KEYS.ACCESS_TOKEN)

  // 刻意不复用本模块的 request()：上报自身若再失败，会再次触发上报询问（递归弹窗）。
  wx.request({
    url: getBaseUrl() + API.FEEDBACK,
    method: 'POST',
    header: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`
    },
    // 不带 anonymous：自动上报**刻意保持实名** —— 它的价值就是站长能复现/追问，
    // 理由见 offerErrorReport 的 javadoc。要改成匿名是产品决定，别顺手加。
    data: { category: '错误报告', content },
    success: () => wx.showToast({ title: '已上报，水站会尽快处理', icon: 'none' }),
    fail: () => wx.showToast({ title: '上报失败，请稍后再试', icon: 'none' })
  })
}

// 401 自动续期处理
function handle401(originalOptions, resolve, reject) {
  // 如果是刷新或登录请求，不重试
  if (originalOptions.url.includes('/auth/refresh') ||
      originalOptions.url.includes('/auth/login') ||
      originalOptions.url.includes('/auth/wx-login') ||
      originalOptions.url.includes('/auth/dev-login')) {
    clearAndRedirect()
    reject(new Error('登录已过期'))
    return
  }

  if (isRefreshing) {
    // 正在刷新，排队等待
    refreshQueue.push({ resolve, reject, options: originalOptions })
    return
  }

  isRefreshing = true
  const refreshToken = wx.getStorageSync(STORAGE_KEYS.REFRESH_TOKEN)

  if (!refreshToken) {
    clearAndRedirect()
    reject(new Error('登录已过期'))
    isRefreshing = false
    return
  }

  const { getBaseUrl, API } = require('../config/api')
  wx.request({
    // 统一走 API.REFRESH。勿硬编码 '/api/auth/refresh'——本项目出现过
    // "常量定义了没人用、路径却散落硬编码在四处"的不一致（2026-09-14 已统一）。
    url: getBaseUrl() + API.REFRESH,
    method: 'POST',
    data: { refreshToken },
    // [2026-09-20] 必须带 timeout：refresh 请求原本没有任何超时，真机切网/弱网时可能
    // 既不 success 也不 fail → refreshQueue 里的 promise 永不 settle → 按钮一直转圈。
    // 有了超时会走 fail 分支，processQueue 才会把排队的请求放掉。
    timeout: 15000,
    success: (res) => {
      if (res.statusCode === 200 && res.data && res.data.code === 0) {
        const { accessToken, refreshToken: newRefreshToken } = res.data.data
        const app = getApp()
        app.globalData.accessToken = accessToken
        if (newRefreshToken) app.globalData.refreshToken = newRefreshToken
        wx.setStorageSync(STORAGE_KEYS.ACCESS_TOKEN, accessToken)
        if (newRefreshToken) wx.setStorageSync(STORAGE_KEYS.REFRESH_TOKEN, newRefreshToken)

        // 重试原始请求
        retryRequest(originalOptions, accessToken).then(resolve).catch(reject)
        // 处理排队中的请求
        processQueue(null, accessToken)
      } else {
        clearAndRedirect()
        reject(new Error('登录已过期'))
        processQueue(new Error('refresh failed'))
      }
    },
    fail: (err) => {
      clearAndRedirect()
      reject(new Error('网络错误'))
      // 排队的请求也要拿到**可读**的错误：原来是原样透传 wx 的 `{errMsg}` 对象，
      // 那些请求的 catch 里 `err.message` 同样是 undefined。
      processQueue(toNetworkError(err))
    },
    complete: () => {
      isRefreshing = false
    }
  })
}

// 重试请求（用新 token）
function retryRequest(options, newToken) {
  return new Promise((resolve, reject) => {
    const app = getApp()
    const baseUrl = getBaseUrl()
    const header = {
      'Content-Type': 'application/json',
      ...options.header,
      'Authorization': `Bearer ${newToken}`
    }

    let url = baseUrl + options.url
    if (options.query) {
      const qs = Object.keys(options.query)
        .filter(k => options.query[k] !== undefined && options.query[k] !== null)
        .map(k => `${k}=${encodeURIComponent(options.query[k])}`)
        .join('&')
      if (qs) url += (url.includes('?') ? '&' : '?') + qs
    }

    wx.request({
      url,
      method: options.method || 'GET',
      data: options.data,
      header,
      // [2026-09-20] 与首次请求（上面的 `timeout: 15000`）保持一致。
      // 原实现漏了这一个，续期后的重试会走系统默认超时（60s），弱网下表现为长时间卡死。
      timeout: 15000,
      success: (res) => {
        if (res.statusCode === 200 && (res.data.code === 0 || res.data.code === 200)) {
          resolve(res.data)
        } else {
          reject(new Error(res.data.message || '请求失败'))
        }
      },
      fail: (err) => reject(toNetworkError(err))
    })
  })
}

// 处理排队中的请求
function processQueue(error, token) {
  refreshQueue.forEach(item => {
    if (error) {
      item.reject(error)
    } else {
      retryRequest(item.options, token).then(item.resolve).catch(item.reject)
    }
  })
  refreshQueue = []
}

function clearAndRedirect() {
  wx.removeStorageSync(STORAGE_KEYS.ACCESS_TOKEN)
  wx.removeStorageSync(STORAGE_KEYS.REFRESH_TOKEN)
  wx.removeStorageSync(STORAGE_KEYS.USER_INFO)
  const app = getApp()
  if (app) {
    app.globalData.accessToken = null
    app.globalData.refreshToken = null
    app.globalData.userInfo = null
    app.globalData.isLogin = false
  }
  wx.redirectTo({ url: '/pages/login/index' })
}

const get = (url, query) => request({ url, method: 'GET', query })
const post = (url, data, query) => request({ url, method: 'POST', data, query })
const put = (url, data, query) => request({ url, method: 'PUT', data, query })
const del = (url, query) => request({ url, method: 'DELETE', query })

module.exports = { request, get, post, put, del }
