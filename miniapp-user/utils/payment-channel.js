const { get } = require('./request')
const { API, API_CONFIG, getTransportConfig, isDevelopEnv, isReleaseEnv } = require('../config/api')
const { captureSession, isCurrentSession } = require('./token')

const bindingKeys = ['environmentId', 'serviceId', 'bindingId']
const text = value => typeof value === 'string' && value.trim() === value && !!value && !/[\s*<>]/.test(value)
const target = () => JSON.stringify({ transport: getTransportConfig(), sandbox: API_CONFIG.paymentSandbox || null })

// 2026-10-27：trial 标签不是授权；只接受本人渠道能力及与当前连接一致的独立测试绑定。
const simulationAllowed = (channel, session = captureSession()) => {
  if (!channel || channel.simulated !== true) return true
  if (isReleaseEnv()) return false
  const proof = channel.simulationAuthorization
  if (!proof || proof.version !== 1 || !session.customerId || String(proof.customerId) !== String(session.customerId)) return false
  // 只采信当前已认证连接返回的服务端测试能力，无需客户端维护服务/顾客许可清单。
  if (proof.scope === 'SERVER_TEST') return true
  if (proof.scope === 'LOCAL_TEST') return isDevelopEnv()
  if (proof.scope !== 'PAYMENT_SANDBOX') return false
  const expected = API_CONFIG.paymentSandbox || {}
  if (!bindingKeys.every(key => text(expected[key]) && expected[key] === proof[key])) return false
  try {
    const route = getTransportConfig()
    if (route.mode === 'cloudContainer') return route.env === proof.environmentId && route.service === proof.serviceId
    return text(expected.apiBaseUrl) && expected.apiBaseUrl === route.baseUrl
  } catch (_) { return false }
}

/** Common preflight for order, tickets and independent rights. No client field can mark money as received. */
const withOnlinePayment = (body, send, hint = {}) => {
  if (Number(body.paymentMethod) !== 1) return Promise.resolve(send())
  if (hint.simulatedWechatPay === true && isReleaseEnv()) return Promise.reject(new Error('当前版本不可使用模拟付款'))
  return readOnlineChannel().then(channel => {
    if (hint.simulatedWechatPay === true && !channel.simulated) throw new Error('付款方式已变化，请重新确认')
    return send()
  })
}

const readOnlineChannel = () => {
  let session, originalTarget
  try { session = captureSession(); originalTarget = target() } catch (error) { return Promise.reject(error) }
  return Promise.resolve().then(() => get(`${API.PAYMENTS}/capabilities`)).then(result => {
    if (!isCurrentSession(session) || originalTarget !== target()) throw new Error('身份或付款位置已变化，请重新确认')
    const channel = result && result.code === 0 && result.data && result.data.wechatPay
    if (!channel || channel.method !== 1 || channel.enabled !== true || typeof channel.simulated !== 'boolean'
        || typeof channel.label !== 'string' || !channel.label.trim() || !simulationAllowed(channel, session)) throw new Error('当前付款方式未获授权，请重新确认')
    return channel
  })
}

module.exports = { simulationAllowed, withOnlinePayment, readOnlineChannel }
