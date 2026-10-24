// Mirrored byte-for-byte in both independent miniapp packages; no cross-package runtime require.
// Official wx types declare callContainer as Promise/void, not an abortable RequestTask.
const { getTransportConfig } = require('../config/api')
let initialization = null

function cloudFailure(error, phase, dispatched) {
  return {
    errMsg: phase === 'init' ? '云连接初始化未成功，请核实环境关联后重试' : '云连接失败，请核实环境关联与服务访问权限后重试',
    requestMayHaveOccurred: dispatched, transportPhase: phase,
    cloudErrorCode: error && Number.isSafeInteger(error.errCode) ? error.errCode : null
  }
}

function queryPath(path, query) {
  if (!query) return path
  const qs = Object.keys(query).filter(k => query[k] !== undefined && query[k] !== null)
    .map(k => `${k}=${encodeURIComponent(query[k])}`).join('&')
  return qs ? path + (path.includes('?') ? '&' : '?') + qs : path
}

function readyCloud(config) {
  const sdk = wx.cloud
  if (!sdk || typeof sdk.Cloud !== 'function') throw new Error('当前微信版本缺少云连接能力，请升级后重试')
  const key = JSON.stringify([config.env, config.resourceAppid])
  if (initialization && initialization.sdk === sdk && initialization.key === key) return initialization.promise
  const resource = { resourceEnv: config.env }
  if (config.resourceAppid) resource.resourceAppid = config.resourceAppid
  const client = new sdk.Cloud(resource)
  if (!client || typeof client.init !== 'function' || typeof client.callContainer !== 'function') {
    throw new Error('当前微信版本缺少云连接能力，请升级后重试')
  }
  const state = { sdk, key, promise: null }
  // Initialize once per package/configuration. A rejected initialization is explicitly retryable.
  state.promise = Promise.resolve().then(() => client.init()).then(() => client)
  initialization = state
  state.promise.catch(() => { if (initialization === state) initialization = null })
  return state.promise
}

/** JSON or binary transport: JWT/envelope/session checks and write recovery stay with callers. */
function send(options) {
  let config, path
  try {
    config = getTransportConfig()
    path = queryPath(options.path, options.query)
  } catch (error) {
    const failure = { errMsg: error.message, transportConfiguration: true }
    try { if (options.fail) options.fail(failure) } finally { if (options.complete) options.complete(failure) }
    return
  }
  if (config.mode === 'https') {
    const request = { ...options, url: config.baseUrl + path }
    delete request.path
    delete request.query
    delete request.beforeDispatch
    if (options.filePath) {
      // wx.uploadFile owns the multipart boundary in HTTPS mode. JWT/retry/session handling stays in request.js.
      delete request.data
      request.header = { ...request.header }
      Object.keys(request.header).forEach(key => { if (key.toLowerCase() === 'content-type') delete request.header[key] })
      request.success = result => {
        let data = result.data
        if (typeof data === 'string') { try { data = JSON.parse(data) } catch (_) { data = null } }
        options.success({ ...result, data })
      }
      return wx.uploadFile(request)
    }
    return wx.request(request)
  }
  let settled = false, dispatched = false
  const timeout = options.timeout === undefined ? 15000 : options.timeout
  const finish = (kind, result) => {
    if (settled) return
    settled = true
    clearTimeout(timer)
    try { if (options[kind]) options[kind](result) } finally { if (options.complete) options.complete(result) }
  }
  // 2026-10-13: SDK timeout is not proof of rollback. Bound initialization + invocation locally,
  // ignore late results, and never automatically replay a cloud failure through another route.
  const timer = setTimeout(() => finish('fail', { errMsg: 'request:fail timeout', requestMayHaveOccurred: dispatched, transportPhase: 'timeout' }), timeout)
  const task = { abort: () => finish('fail', {
    errMsg: '网络请求已停止等待，结果仍需核实', localOnly: true, requestMayHaveOccurred: dispatched
  }) }
  let ready
  try { ready = readyCloud(config) } catch (error) {
    finish('fail', { ...cloudFailure(error, 'init', false), errMsg: error.message, transportConfiguration: true })
    return task
  }
  ready.then(client => {
    if (settled) return
    if (options.beforeDispatch && !options.beforeDispatch()) {
      finish('fail', { errMsg: '登录状态已变化，请重新操作', requestMayHaveOccurred: false })
      return
    }
    let call
    try {
      dispatched = true
      call = client.callContainer({ path, method: options.method || 'GET', data: options.data,
        header: { ...options.header, 'X-WX-SERVICE': config.service }, dataType: 'json', timeout })
    } catch (error) {
      finish('fail', cloudFailure(error, 'call', true))
      return
    }
    if (!call || typeof call.then !== 'function') {
      finish('fail', { errMsg: '云连接返回方式不受支持，提交结果仍需核实', requestMayHaveOccurred: true })
      return
    }
    call.then(result => {
      // Keep HTTP status and project envelope intact, including malformed responses for existing guards.
      finish('success', result && typeof result === 'object'
        ? { statusCode: result.statusCode, data: result.data, header: result.header } : result)
    }, error => finish('fail', cloudFailure(error, 'call', true)))
  }, error => finish('fail', cloudFailure(error, 'init', false)))
  return task
}

module.exports = { send }
