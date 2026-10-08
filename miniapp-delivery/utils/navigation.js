// 2026-10-06：同一在途导航只发一次；微信跳转不可取消，旧回调不得释放后来的锁。
// tab 重置先 reLaunch 到登录页清空缓存，再 switchTab；直接 reLaunch 到自绘 tab 曾白屏。
const TAB_PAGES = ['/pages/coordination/index', '/pages/home/index', '/pages/mine/index']
const TIMEOUT_MS = 12000

/** 原生导航事务；env 注入平台与时钟，回归测试可真实编排迟到/缺失回调。 */
function createNavigator(env) {
  let active = null, queuedGuard = null, failure = null, epoch = 0, lastReset = null
  const hidden = new WeakSet(), disposed = new WeakSet()
  const schedule = env.setTimer || setTimeout, unschedule = env.clearTimer || clearTimeout
  const pages = () => env.pages() || []
  const session = () => env.session ? env.session() : ''
  const top = () => { const stack = pages(); return stack[stack.length - 1] }
  const isObject = o => o && typeof o === 'object'
  const alive = o => !isObject(o) || (!disposed.has(o) && (!o.route || pages().includes(o)))
  const valid = job => job.epoch === epoch && job.session === session() && alive(job.owner)

  function drain() {
    const next = queuedGuard; queuedGuard = null
    if (!next) return false
    if (valid(next)) { start(next); return true }
    if (env.repairGuard) env.repairGuard()
    return false
  }
  function finish(job, error, cancelled) {
    if (active !== job) return
    unschedule(job.timer); active = null
    if (!error && !cancelled && valid(job) && job.mode === 'reset') {
      lastReset = { url: job.url, epoch, session: session() }
    }
    const feedbackAllowed = error && valid(job) && !cancelled
    if (drain() || !feedbackAllowed) return
    const f = { job }; failure = f
    const usable = () => failure === f && !active && valid(job)
    const message = /time\s*out/i.test((error && error.errMsg) || '')
      ? '页面打开超时，请重试或返回首页。' : '页面暂时无法打开，请重试或返回首页。'
    // 只由用户决定重试；不循环发送导航，也不把 12 秒解锁伪称为取消原生请求。
    if (env.feedback) env.feedback({ message,
      retry() { if (usable()) { failure = null; if (job.delta) back(job.delta, job.options); else open(job.url, job.options) } },
      home() { if (usable()) { failure = null; if (env.home) env.home() } }
    })
  }
  function issue(job, api, args, next) {
    const phase = ++job.phase
    const current = () => active === job && job.phase === phase
    const success = () => {
      if (!current()) {
        if (!job.repaired && !valid(job)) { job.repaired = true; if (env.repairGuard) env.repairGuard() }
        return
      }
      unschedule(job.timer)
      if (!valid(job)) { finish(job, null, true); return }
      if (next) next(); else finish(job)
    }
    const fail = e => { if (current()) finish(job, e || { errMsg: api + ':fail' }) }
    job.timer = schedule(() => fail({ errMsg: api + ':fail timeout' }), TIMEOUT_MS)
    if (job.timer && typeof job.timer.unref === 'function') job.timer.unref()
    try {
      env.wx[api]({ ...args, success, fail,
        complete(e) {
          if (!current()) return
          if (e && /:ok$/.test(e.errMsg || '')) success()
          else fail(e)
        }
      })
    } catch (e) { fail({ errMsg: api + ':fail' }) }
  }
  function start(job) {
    failure = null; active = job; job.phase = 0
    const route = job.url && job.url.split('?')[0]
    if (job.delta) { issue(job, 'navigateBack', { delta: job.delta }); return }
    if (TAB_PAGES.includes(route)) {
      const enter = () => issue(job, 'switchTab', { url: job.url })
      // switchTab 只关闭普通页，不销毁其他 tab 缓存。身份流程必须先清掉上一身份页面。
      if (job.mode === 'reset') issue(job, 'reLaunch', { url: '/pages/login/index' }, enter)
      else enter()
    } else {
      issue(job, job.mode === 'reset' ? 'reLaunch' : job.mode === 'replace' ? 'redirectTo' : 'navigateTo', { url: job.url })
    }
  }
  function submit(job) {
    // 首页等旧页面可能没有接生命周期钩子；源 Page 已离栈也不能把锁留给下一页。
    if (active && !alive(active.owner)) finish(active, null, true)
    if (active) {
      if (active.key === job.key && valid(active)) return true
      if (job.guard) { queuedGuard = job; return true }
      return false
    }
    start(job); return true
  }
  function open(url, options = {}) {
    if (typeof url !== 'string' || !url.startsWith('/pages/')) return false
    if (!alive(options.owner)) return false
    if (!options.guard && env.allowed && !env.allowed()) { if (env.repairGuard) env.repairGuard(); return false }
    const mode = options.mode || 'push', route = url.split('?')[0], p = top()
    if (!active && !url.includes('?') && p && '/' + p.route === route) {
      if (mode !== 'reset') return false
      if (pages().length === 1 && lastReset && lastReset.url === url && lastReset.epoch === epoch && lastReset.session === session()) return false
    }
    if (!active && options.reuse && !url.includes('?') && !TAB_PAGES.includes(route)) {
      const stack = pages()
      for (let i = stack.length - 1; i >= 0; i--) {
        if ('/' + stack[i].route === route) return back(stack.length - 1 - i, options)
      }
    }
    return submit({ url, mode, options, guard: !!options.guard, owner: options.owner,
      epoch, session: session(), key: mode + ':' + url })
  }
  function back(delta = 1, options = {}) {
    if (!Number.isInteger(delta) || delta < 1 || delta >= pages().length || !alive(options.owner)) return false
    return submit({ delta, options, owner: options.owner, guard: !!options.guard,
      epoch, session: session(), key: 'back:' + delta })
  }
  function dispose(owner) {
    if (isObject(owner)) disposed.add(owner)
    if (failure && (!owner || failure.job.owner === owner)) failure = null
    if (queuedGuard && (!owner || queuedGuard.owner === owner)) queuedGuard = null
    if (active && (!owner || active.owner === owner)) finish(active, null, true)
  }
  return { open, back, dispose,
    invalidate() { epoch++; failure = null; lastReset = null; queuedGuard = null },
    hide(owner) {
      if (isObject(owner)) hidden.add(owner)
      if (failure && failure.job.owner === owner) failure = null
    },
    show(owner) { if (isObject(owner) && hidden.has(owner)) { hidden.delete(owner); if (active && active.owner === owner) finish(active, null, true) } }
  }
}

let runtime = null, runtimeWx = null, runtimeApp = null
function appNow() { return typeof getApp === 'function' ? getApp() : null }
/** 只用于内存比较，绝不输出凭据或将指纹写入页面/日志。 */
function sessionKey() {
  const a = appNow(), g = a && a.globalData || {}, u = g.userInfo || {}
  return JSON.stringify([g.accessToken || '', g.isLogin, u.staffId, u.role, u.stationId, u.bindStatus])
}
function identityKey() {
  const a = appNow(), g = a && a.globalData || {}, u = g.userInfo || {}
  // 续期只替换凭据，不等于重新登录；真正的 setLoginState/clearLoginState 另行 invalidate。
  return JSON.stringify([!!g.accessToken, g.isLogin, u.staffId, u.role, u.stationId, u.bindStatus])
}
function current() {
  const a = appNow()
  if (!runtime || runtimeWx !== wx || runtimeApp !== a) {
    if (runtime) runtime.dispose()
    runtimeWx = wx; runtimeApp = a
    runtime = createNavigator({ wx, pages: () => getCurrentPages(), session: identityKey,
      allowed: () => { const app = appNow(); return !app || ((!app.globalData || app.globalData.isLogin !== false) && (typeof app.canAccessStationBusiness !== 'function' || app.canAccessStationBusiness())) },
      repairGuard: () => { const app = appNow(); if (app && typeof app.checkLoginState === 'function') app.checkLoginState(); else if (app && typeof app.routeByRole === 'function') app.routeByRole(true) },
      home: () => { const app = appNow(); if (app && app.globalData && app.globalData.isLogin && typeof app.routeByRole === 'function') app.routeByRole(false); else current().open('/pages/login/index', { mode: 'reset', guard: true }) },
      feedback: ({ message, retry, home }) => {
        const fallback = () => wx.showToast({ title: '页面未打开，请返回后重试', icon: 'none' })
        try { wx.showModal({ title: '页面未打开', content: message, confirmText: '重试', cancelText: '返回首页', success: r => { if (r.confirm) retry(); else if (r.cancel) home() }, fail: fallback }) }
        catch (e) { fallback() }
      }
    })
  }
  return runtime
}
module.exports = { TAB_PAGES, createNavigator, sessionKey,
  open: (url, options) => current().open(url, options), back: (delta, options) => current().back(delta, options),
  invalidate: () => current().invalidate(), dispose: owner => current().dispose(owner),
  hide: owner => current().hide(owner), show: owner => current().show(owner)
}
