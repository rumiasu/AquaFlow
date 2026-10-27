// Application confirmations; system permission APIs remain native.
function showBusinessModal(options = {}) {
  let page, dialog
  try {
    const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
    page = pages[pages.length - 1]
    dialog = page && typeof page.selectComponent === 'function' && page.selectComponent('#business-dialog')
  } catch (_) {}
  if (dialog && typeof dialog.open === 'function') return dialog.open(options)
  // Startup, unsupported containers and tests retain the native confirmation contract.
  return wx.showModal(options)
}
module.exports = { showBusinessModal }
