// Application confirmations; system permission APIs remain native.
function showBusinessModal(options = {}) {
  let page, dialog
  try {
    const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
    page = pages[pages.length - 1]
    dialog = page && typeof page.selectComponent === 'function' && page.selectComponent('#business-dialog')
  } catch (_) {}
  if (dialog && typeof dialog.open === 'function') return dialog.open(options)
  // Preserve the legacy native return value and callback identity; wx owns native one-shot dispatch.
  if (!['description', 'initialValue', 'details'].some(key => Object.prototype.hasOwnProperty.call(options, key))) return wx.showModal(options)
  // 2026-10-28: native editable content is the initial value, not a separate description.
  // If that presentation cannot preserve the new contract, cancel without turning instructions into a write.
  const textOf = value => value == null ? '' : String(value)
  const details = (Array.isArray(options.details) ? options.details : []).map(item => textOf(item && item.label) + '：' + textOf(item && item.value))
  const { description, initialValue, details: ignoredDetails, ...native } = options
  return new Promise(resolve => {
    let settled = false
    const finish = (result, failed) => {
      if (settled) return
      settled = true
      try { const callback = failed ? options.fail : options.success; if (callback) return callback(result) }
      finally { try { if (options.complete) options.complete(result) } finally { resolve(result) } }
    }
    if (options.editable && (textOf(description) || details.length)) {
      finish({ confirm: false, cancel: true, errMsg: 'showModal:ok', reason: 'unsupported-editable-fallback' })
      return
    }
    if (options.editable) {
      native.content = textOf(Object.prototype.hasOwnProperty.call(options, 'initialValue') ? initialValue : options.content)
    } else {
      native.content = [textOf(options.content), textOf(description), ...details].filter(Boolean).join('\n\n')
    }
    native.success = result => finish(result, false)
    native.fail = error => finish(error, true)
    // Native complete can follow success/fail; the adapter dispatches the caller's complete once.
    native.complete = () => {}
    try { wx.showModal(native) } catch (error) { finish(error, true) }
  })
}
module.exports = { showBusinessModal }
