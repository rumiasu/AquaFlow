// 网络请求封装（JWT 双 Token + 自动续期）
const { API } = require('../config/api')
const { send } = require('./transport')
const { snapshot } = require('./snapshot')
const { displayMessage } = require('./display-message')
const { STORAGE_KEYS } = require('../utils/storage-keys')
const { captureSession, isCurrentSession, isCurrentCredentials, acceptRefreshedTokens, beginSession } = require('./token')

const refreshFlights = new Map()

const request = (options) => {
  // 2026-10-02：旧 401 曾用新客户 refreshToken 重发原购票。第一次发送时固定周期和请求体。
  const session = captureSession()
  const original = Object.assign({}, options, { header: Object.assign({}, options.header),
    query: options.query && Object.assign({}, options.query),
    data: snapshot(options.data) })
  return sendRequest(original, session, session.accessToken, false)
}

function sessionChangedError() {
  const error = new Error('登录身份已变化，请重新进入后操作')
  error.sessionChanged = true
  return error
}

function sendRequest(options, session, accessToken, retried) {
  return new Promise((resolve, reject) => {
    if (!isCurrentSession(session)) { reject(sessionChangedError()); return }

    const header = {
      'Content-Type': 'application/json',
      ...options.header
    }

    if (accessToken) {
      header['Authorization'] = `Bearer ${accessToken}`
    }


    send({
      path: options.url, query: options.query,
      beforeDispatch: () => isCurrentSession(session),
      method: options.method || 'GET',
      data: snapshot(options.data),
      header,
      timeout: 15000,
      success: (res) => {
        if (!isCurrentSession(session)) { reject(sessionChangedError()); return }
        const error = responseError(res)
        if (error) { reject(responseFailure(error, res)); return }
        if (res.statusCode === 200) {
          if (res.data.code === 0 || res.data.code === 200) {
            resolve(res.data)
          } else if (res.data.code === 401) {
            // access_token 过期，尝试自动续期
            if (retried) reject(new Error('登录已过期'))
            else handle401(options, session).then(resolve, reject)
          } else {
            const msg = responseMessage(res.data)
            // [2026-09-14] 系统级错误（后端 code=500）用户既看不懂也做不了，
            // 主动询问是否上报给水站；业务错误（code=1）的文案本身已"能看懂、能处理"，
            // 不再弹窗打扰（见 offerErrorReport 的说明）。
            if (res.data.code === 500) offerErrorReport(typeof res.data.message === 'string' ? res.data.message : msg, options, session)
            const failure = new Error(msg)
            if (res.data.code === 1) failure.businessRejected = true
            reject(responseFailure(failure, res))
          }
        } else if (res.statusCode === 401) {
          if (retried) reject(new Error('登录已过期'))
          else handle401(options, session).then(resolve, reject)
        } else {
          // 2026-10-24：状态码只留诊断元数据，顾客看到可执行的恢复提示。
          reject(responseFailure(new Error('服务暂时不可用，请稍后重试'), res))
        }
      },
      fail: (err) => {
        reject(isCurrentSession(session) ? toNetworkError(err) : sessionChangedError())
      }
    })
  })
}

/**
 * 把 wx 的失败对象归一化成带可读 message 的 Error。
 *
 * <p>[2026-09-20 真机联调] 这不是美化文案，是修一个**系统性根因**：`wx.request` 的 `fail`
 * 回调拿到的是 `{errMsg: "request:fail fail:time out", errno: 5}` 这种**没有 `message` 字段**的对象，
 * 原样 `reject` 出去后，全端约 150 处 `err.message || 'xxx失败'` 会**全部走兜底分支** ——
 * 于是真机弱网/后端没起时，用户看到的是「下单失败: 」（冒号后面什么都没有）、
 * 「保存失败: 」这类**没有原因**的提示，排查时也拿不到任何线索。</p>
 *
 * <p>⚠️ 别把这里改回 `reject(err)`：`err.message` 恒为 `undefined` 是 wx 的既定形状，
 * 不是偶发。要加新文案就在这里加分支，不要在调用点各写一套。</p>
 *
 * <p>⚠️⚠️ **[2026-09-22 真机实测] 超时判据必须同时匹配 `timeout` 与 `time out`（两个词）。**
 * 微信实际给的是 `"request:fail fail:time out"` —— 原来的 `/timeout/i` **匹配不上**，
 * 于是**超时被误报成「网络连接失败」**，把最有用的那句"请确认手机与后端在同一网络"吞掉了；
 * 而"超时"恰恰是"手机路由不到后端"最典型的症状（跨网段时对方不回 RST，只会静默丢包）。</p>
 */
function toNetworkError(err) {
  const raw = (err && (err.errMsg || err.message)) || ''
  const message = /time\s*out/i.test(raw) ? '网络超时，请检查手机网络后重试'
    : /fail/i.test(raw) ? '网络连接失败，请检查网络后重试' : displayMessage(raw, '网络连接失败，请重试')
  return Object.assign(new Error(message), err, { message, originalMessage: raw })
}

/* ==================== 系统级错误「一键上报给水站」 ==================== */

// 同一登录周期内正在询问/上报或已完成/取消的错误；失败释放，换会话独立去重。
const offeredReports = new Set()
let reportSessionEpoch = null

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
function offerErrorReport(message, options, session) {
  if (!isCurrentSession(session)) return
  if (reportSessionEpoch !== session.epoch) {
    offeredReports.clear()
    reportSessionEpoch = session.epoch
  }
  if (!message || offeredReports.has(message)) return
  offeredReports.add(message)

  wx.showModal({
    title: '遇到点问题',
    content: '系统好像开小差了。要把这个问题告诉水站吗？',
    confirmText: '上报',
    cancelText: '不用了',
    success: (r) => {
      if (r.confirm && isCurrentSession(session)) submitErrorReport(message, options, session)
    },
    fail: () => { if (isCurrentSession(session)) offeredReports.delete(message) }
  })
}

function submitErrorReport(message, options, session) {
  if (!isCurrentSession(session)) return
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

  const token = captureSession().accessToken
  const failed = (error, view) => {
    if (!isCurrentSession(session)) return
    offeredReports.delete(message)
    // 只生成展示文案；原异常、响应、会话与请求结果不作改写。
    const diagnostic = error && view !== 'network' && typeof error.originalMessage === 'string' ? error.originalMessage : error && error.message
    const raw = typeof diagnostic === 'string' ? diagnostic.trim() : ''
    const reason = /[\u4e00-\u9fff]/.test(raw) && !/[A-Za-z\r\n]/.test(raw) ? raw : ''
    const unknown = '上报结果未确认，请稍后核对'
    const titles = {
      rejected: reason ? '上报未被接受：' + reason : '上报未被接受，请稍后核对',
      expired: '登录已过期，请重新登录后上报',
      http: '服务暂时不可用，' + unknown,
      malformed: '收到的数据不完整，' + unknown,
      network: (/网络超时/.test(reason) ? '网络超时，' : '网络连接异常，') + unknown,
      unknown: reason ? reason + '，' + unknown : unknown
    }
    wx.showToast({ title: titles[view] || unknown, icon: 'none' })
  }

  // 刻意不复用本模块的 request()：上报自身若再失败，会再次触发上报询问（递归弹窗）。
  send({
    path: API.FEEDBACK,
    method: 'POST',
    header: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`
    },
    // 不带 anonymous：自动上报**刻意保持实名** —— 它的价值就是站长能复现/追问，
    // 理由见 offerErrorReport 的 javadoc。要改成匿名是产品决定，别顺手加。
    data: { category: '错误报告', content },
    timeout: 15000,
    success: res => {
      if (!isCurrentSession(session)) return
      const error = responseError(res)
      if (error) { failed(error, 'malformed'); return }
      if (res.statusCode === 200 && (res.data.code === 0 || res.data.code === 200)) {
        wx.showToast({ title: '已上报，水站会尽快处理', icon: 'none' })
      } else if (res.statusCode === 401 || (res.statusCode === 200 && res.data.code === 401)) {
        failed(new Error('登录已过期，请重新登录后上报'), 'expired')
      } else {
        failed(responseFailure(new Error(res.statusCode === 200 ? responseMessage(res.data)
          : '服务暂时不可用，请稍后重试'), res),
          res.statusCode === 200 ? (res.data.code === 1 ? 'rejected' : 'unknown') : 'http')
      }
    },
    fail: err => failed(toNetworkError(err), 'network')
  })
}

// 启动与页面共用续期；新会话不进入旧队列，同周期旧凭据也不能清理新令牌。
function handle401(options, session) {
  if (!isCurrentSession(session)) return Promise.reject(sessionChangedError())
  if (['/auth/refresh', '/auth/login', '/auth/wx-login', '/auth/dev-login'].some(path => options.url.includes(path))) {
    clearAndRedirect(session)
    return Promise.reject(new Error('登录已过期'))
  }
  return refreshLogin(session).then(token => sendRequest(options, session, token, true))
}

// 2026-10-02：平台 success 已离开 Promise executor；空 body 直接读 code 曾抛错并让页面永久加载。
// 仅守响应外壳/code，错误元数据 message 不得成为合法成功的新门槛。
function responseError(res) {
  if (!res || typeof res !== 'object' || Array.isArray(res) || !Number.isInteger(res.statusCode)) return malformedResponseError()
  if (res.statusCode !== 200) return null // 真 HTTP401 无 body 也走原续期；会话检查仍先执行。
  const body = res.data
  return !body || typeof body !== 'object' || Array.isArray(body) || !Number.isInteger(body.code) ? malformedResponseError() : null
}

function malformedResponseError() { return new Error('收到的数据不完整，请重试') }

// 原始诊断独立保存；调用方仍按状态码/拒绝标记/原文守恢复边界，不比较展示兜底。
function responseFailure(error, res) {
  return Object.assign(error, { statusCode: res && res.statusCode,
    backendCode: res && res.data && res.data.code, originalMessage: res && res.data && res.data.message })
}

function responseMessage(body, fallback = '请求失败') {
  return displayMessage(body && body.message, fallback)
}

function validRefreshData(data) {
  return data && typeof data === 'object' && !Array.isArray(data)
    && typeof data.accessToken === 'string' && !!data.accessToken.trim()
    && (data.refreshToken == null || typeof data.refreshToken === 'string')
}

function refreshLogin(session = captureSession()) {
  if (!isCurrentSession(session)) return Promise.reject(sessionChangedError())
  // 同周期已有成功续期：迟到401直接复用当前令牌，即使 access 文本没有变化。
  if (!isCurrentCredentials(session)) return Promise.resolve(captureSession().accessToken)
  const key = session.epoch + ':' + session.credentialVersion
  let flight = refreshFlights.get(key)
  if (!flight) {
    flight = refreshSession(session)
    refreshFlights.set(key, flight)
    const done = () => { if (refreshFlights.get(key) === flight) refreshFlights.delete(key) }
    flight.then(done, done)
  }
  return flight
}

function refreshSession(session) {
  return new Promise((resolve, reject) => {
    const superseded = () => {
      if (!isCurrentSession(session)) { reject(sessionChangedError()); return true }
      if (!isCurrentCredentials(session)) { resolve(captureSession().accessToken); return true }
      return false
    }
    if (superseded()) return
    if (!session.refreshToken) {
      clearAndRedirect(session); reject(new Error('登录已过期')); return
    }
    send({
      path: API.REFRESH, method: 'POST', data: { refreshToken: session.refreshToken },
      beforeDispatch: () => isCurrentCredentials(session),
      timeout: 15000,
      success: res => {
        if (superseded()) return
        const error = responseError(res)
        if (error) { clearAndRedirect(session); reject(error); return }
        if (res.statusCode === 200 && res.data.code === 0) {
          if (!validRefreshData(res.data.data)) {
            if (!res.data.data) clearAndRedirect(session) // 保留原来缺少 payload 时的失效清理。
            reject(malformedResponseError()); return
          }
          const { accessToken, refreshToken } = res.data.data
          try {
            if (!acceptRefreshedTokens(session, accessToken, refreshToken)) {
              reject(new Error('登录续期未成功，请重新登录')); return
            }
            resolve(accessToken)
          } catch (error) { reject(new Error('登录信息保存失败，请重新登录')) }
        } else {
          clearAndRedirect(session); reject(new Error('登录已过期'))
        }
      },
      fail: err => {
        if (superseded()) return
        clearAndRedirect(session); reject(toNetworkError(err))
      }
    })
  })
}

function clearAndRedirect(session) {
  if (!isCurrentCredentials(session)) return
  beginSession()
  const app = getApp()
  if (app) {
    app.globalData.accessToken = null
    app.globalData.refreshToken = null
    app.globalData.userInfo = null
    app.globalData.isLogin = false
    app.globalData.customerId = null
  }
  // 清理存储失败也必须让所有等待者 settle；已结束的内存会话不回退成旧缓存令牌。
  for (const key of [STORAGE_KEYS.ACCESS_TOKEN, STORAGE_KEYS.REFRESH_TOKEN, STORAGE_KEYS.USER_INFO, STORAGE_KEYS.CUSTOMER_ID]) {
    try { wx.removeStorageSync(key) } catch (error) { /* 已结束会话；登录页可重新覆盖缓存 */ }
  }
  wx.redirectTo({ url: '/pages/login/index' })
}

const get = (url, query) => request({ url, method: 'GET', query })
const post = (url, data, query) => request({ url, method: 'POST', data, query })
const put = (url, data, query) => request({ url, method: 'PUT', data, query })
const del = (url, query) => request({ url, method: 'DELETE', query })

module.exports = { request, get, post, put, del, refreshLogin }
