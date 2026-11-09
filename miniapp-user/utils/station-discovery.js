// 2026-11-07: Discovery must never turn an absent/partial location into an unrestricted directory.
// Historical assets keep their separate public station source; this scope is only for finding a new station.
function discoveryScope(value) {
  if (!value || typeof value !== 'object') return null
  const hasLat = Object.prototype.hasOwnProperty.call(value, 'lat')
  const hasLng = Object.prototype.hasOwnProperty.call(value, 'lng')
  if (hasLat || hasLng) {
    if (typeof value.lat !== 'number' || !Number.isFinite(value.lat) || Math.abs(value.lat) > 90
        || typeof value.lng !== 'number' || !Number.isFinite(value.lng) || Math.abs(value.lng) > 180) return null
    const result = { lat: value.lat, lng: value.lng }
    const city = typeof value.city === 'string' ? value.city.trim() : ''
    const province = typeof value.province === 'string' ? value.province.trim() : ''
    if (city) { result.city = city; if (province) result.province = province }
    return result
  }
  const city = typeof value.city === 'string' ? value.city.trim() : ''
  const province = typeof value.province === 'string' ? value.province.trim() : ''
  if (!city) return null
  return province ? { province, city } : { city }
}

function discoveryKey(value) {
  return JSON.stringify(discoveryScope(value))
}

/** Use the actual active address, then the default. Missing coordinates do not borrow another address's point. */
function addressDiscovery(list, activeId) {
  if (!Array.isArray(list)) return null
  const active = activeId == null ? null : list.find(address => address && String(address.id) === String(activeId))
  const address = active || list.find(address => address && (address.isDefault === true || address.isDefault === 1))
  if (!address) return null
  const region = { province: address.province, city: address.city }
  // Addresses are decoded from JSON BigDecimals. Do not coerce empty/null coordinates to zero.
  const point = discoveryScope({ lat: address.lat, lng: address.lng, ...region })
  const scope = point || discoveryScope(region)
  return scope ? { scope, source: active ? 'active' : 'default', coordinates: !!point } : null
}

module.exports = { discoveryScope, discoveryKey, addressDiscovery }
