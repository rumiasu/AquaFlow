const { validId } = require('./cart-storage')

function cartKey(cart) {
  if (!cart || typeof cart !== 'object' || Array.isArray(cart)) return null
  const rows = []
  for (const id of Object.keys(cart).sort()) {
    const value = cart[id], quantity = typeof value === 'number' || typeof value === 'string' ? Number(value) : NaN
    if (!validId(id) || !Number.isSafeInteger(quantity) || quantity <= 0) return null
    rows.push([String(id), quantity])
  }
  return rows.length ? JSON.stringify(rows) : null
}

// A result-page URL is never a cart receipt. Only a fresh cart-origin submission can capture one.
function captureSubmittedCart(app, stationId, items) {
  if (!app || typeof app.getCart !== 'function' || !validId(stationId) || !Array.isArray(items)) return null
  const expected = {}
  for (const item of items) {
    if (!item || !validId(item.productId) || !Number.isSafeInteger(item.quantity) || item.quantity <= 0) return null
    const id = String(item.productId)
    if (expected[id] != null) return null
    expected[id] = item.quantity
  }
  try {
    const key = cartKey(app.getCart(stationId))
    return key && key === cartKey(expected) ? { stationId, key, consumed: false } : null
  } catch (_) { return null }
}

function consumeSubmittedCart(app, proof, current) {
  try {
    if (!proof || proof.consumed || typeof current !== 'function' || !current()) return false
    if (cartKey(app.getCart(proof.stationId)) !== proof.key || typeof app.clearCart !== 'function') return false
    proof.consumed = true
    app.clearCart(proof.stationId)
    return true
  } catch (_) { return false } // Cart persistence must never change an already confirmed order result.
}
module.exports = { captureSubmittedCart, consumeSubmittedCart }
