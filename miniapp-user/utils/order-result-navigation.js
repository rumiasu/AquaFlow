// 2026-10-10：结果页裸 redirectTo 超时后无恢复；导航只处理原单页面，不能重走建单/付款。
const TIMEOUT_MS = 12000

/** 确认页局部导航；注入时钟供回归编排缺失/迟到回调，不包含身份重置或自动重试。 */
function createOrderResultNavigator(env) {
  let active = null, failure = null
  const schedule = env.setTimer || setTimeout, unschedule = env.clearTimer || clearTimeout
  const valid = proof => { try { return env.valid(proof) } catch (e) { return false } }
  const update = state => { try { env.update(state) } catch (e) { /* 展示失败不能回到建单失败分支。 */ } }

  function invalidate() {
    if (active) unschedule(active.timer)
    active = null; failure = null
    update({ busy: false, error: '' })
  }
  function open(proof, list = false) {
    if (!valid(proof)) return false
    if (active && !valid(active.proof)) invalidate()
    if (active) return false
    const api = list ? 'switchTab' : 'redirectTo'
    const url = list ? '/pages/order/list'
      : '/pages/order/success?id=' + encodeURIComponent(proof.orderId) + '&stationId=' + encodeURIComponent(proof.stationId)
    const job = { proof, api, url }; active = job; failure = null
    update({ busy: true, error: '' })
    function finish(error) {
      // fail 与 complete 可各回一次；超时后原回调也可能到达，不能覆盖下一次尝试。
      if (active !== job) return
      unschedule(job.timer); active = null
      if (!valid(proof)) return
      if (error) {
        failure = job
        const fact = proof.paid === true ? '这笔订单已确认付款。' : '订单已经提交，付款状态请在原订单中核实。'
        const reason = /time\s*out/i.test(error.errMsg || '') ? '页面打开超时。' : '页面暂时无法打开。'
        update({ busy: false, error: '订单号 ' + proof.orderId + '：' + fact + reason + '可重试打开，或到我的订单查看。' })
      } else update({ busy: false, error: '' })
    }
    job.timer = schedule(() => finish({ errMsg: api + ':fail timeout' }), TIMEOUT_MS)
    if (job.timer && typeof job.timer.unref === 'function') job.timer.unref()
    try {
      env.wx[api]({ url, success: () => finish(), fail: error => finish(error || { errMsg: api + ':fail' }),
        complete: result => {
          if (result && /:ok$/.test(result.errMsg || '')) finish()
          else finish(result || { errMsg: api + ':fail' })
        }
      })
    } catch (e) { finish({ errMsg: api + ':fail' }) }
    return true
  }
  function recover(list) {
    if (!failure || active) return false
    const proof = failure.proof
    if (!valid(proof)) { invalidate(); return false }
    return open(proof, list)
  }
  return { open, invalidate, retry: () => recover(false), orders: () => recover(true) }
}

module.exports = { createOrderResultNavigator, TIMEOUT_MS }
