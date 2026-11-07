const { validId } = require('./cart-storage')

// Only the cart-origin request can capture a receipt. Each item is checked independently.
function captureSubmittedCart(app, stationId, items) {
  if (!app || typeof app.getCart !== 'function' || typeof app.getCartItemToken !== 'function'
    || typeof app.setCartQty !== 'function' || !validId(stationId) || !Array.isArray(items) || !items.length) return null
  try {
    const cart = app.getCart(stationId), rows = [], seen = new Set()
    for (const item of items) {
      if (!item || !validId(item.productId) || !Number.isSafeInteger(item.quantity) || item.quantity <= 0) return null
      const id = String(item.productId), before = Number(cart[id])
      if (seen.has(id)) return null
      seen.add(id)
      // An order may include a non-cart item; it cannot authorize removal here.
      if (!Number.isSafeInteger(before) || before < item.quantity) continue
      const token = app.getCartItemToken(stationId, id)
      if (!token) return null
      rows.push({ id, before, submitted: item.quantity, token })
    }
    return rows.length ? { stationId, rows, consumed: false } : null
  } catch (_) { return null }
}

function consumeSubmittedCart(app, proof, current) {
  try {
    if (!proof || proof.consumed || typeof current !== 'function' || !current()) return false
    const cart = app.getCart(proof.stationId)
    const eligible = proof.rows.filter(row => Number(cart[row.id]) === row.before
      && app.getCartItemToken(proof.stationId, row.id) === row.token)
    proof.consumed = true // A partial storage failure must not permit duplicate consumption.
    for (const row of eligible) app.setCartQty(proof.stationId, row.id, row.before - row.submitted)
    return eligible.length > 0
  } catch (_) { return false } // Cart persistence must never change an already confirmed order result.
}
module.exports = { captureSubmittedCart, consumeSubmittedCart }
