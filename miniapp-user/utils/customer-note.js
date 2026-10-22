// Whitespace formatting is kept consistent across the two independent miniapp packages.
/** 2026-10-22：只预览独立客户留言；specialNote 混有处理记录，不能回退或按标记猜测。 */
function customerNotePreview(order) {
  const note = order && order.customerNote
  return typeof note === 'string' ? note.replace(/\s+/g, ' ').trim() : ''
}

module.exports = { customerNotePreview }
