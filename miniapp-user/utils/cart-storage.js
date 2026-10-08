const { STORAGE_KEYS } = require('./storage-keys')

const validId = value => /^[1-9]\d*$/.test(String(value || ''))

// Saved drafts contain only station/product IDs and positive whole quantities.
function cleanStations(stations) {
  const result = {}
  if (!stations || typeof stations !== 'object' || Array.isArray(stations)) return result
  Object.keys(stations).forEach(sid => {
    const cart = stations[sid]
    if (!validId(sid) || !cart || typeof cart !== 'object' || Array.isArray(cart)) return
    const items = {}
    Object.keys(cart).forEach(pid => {
      const raw = cart[pid]
      const qty = (typeof raw === 'number' || typeof raw === 'string') ? Number(raw) : NaN
      if (validId(pid) && Number.isSafeInteger(qty) && qty > 0) items[pid] = qty
    })
    if (Object.keys(items).length) result[sid] = items
  })
  return result
}

function readCart(owner) {
  try {
    const value = wx.getStorageSync(STORAGE_KEYS.CART_PREFIX + owner)
    return value && value.version === 1 && value.owner === owner ? cleanStations(value.stations) : {}
  } catch (_) {
    console.warn('[cart] 本地清单读取失败')
    return {}
  }
}

function saveCart(owner, stations) {
  if (!owner) return false
  try {
    wx.setStorageSync(STORAGE_KEYS.CART_PREFIX + owner, { version: 1, owner, stations: cleanStations(stations) })
    return true
  } catch (_) {
    console.warn('[cart] 本地清单保存失败，当前页面仍可使用')
    return false
  }
}

module.exports = { validId, readCart, saveCart }
