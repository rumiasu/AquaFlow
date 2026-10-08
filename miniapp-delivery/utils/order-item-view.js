// Mirrored in both independent miniapp packages; keep the two files identical.
function itemUnit(item) {
  const row = item || {}
  const category = typeof row.category === 'number' || typeof row.category === 'string' ? Number(row.category) : NaN
  if (category === 1) return '桶'
  if (category === 2) return '瓶'
  if (category === 3) return '台'
  return row.barrelItem === true ? '桶' : '件'
}

function withItemUnits(items) {
  return (Array.isArray(items) ? items : []).filter(row => row && typeof row === 'object')
    .map(row => ({ ...row, quantityUnit: itemUnit(row) }))
}

function orderSummary(order, fallback = '商品明细待确认') {
  const row = order || {}
  const supplied = typeof row.itemSummary === 'string' ? row.itemSummary.trim() : ''
  if (supplied) return { text: supplied, meta: '' }
  const items = withItemUnits(row.items).filter(item => Number.isFinite(Number(item.quantity)) && Number(item.quantity) > 0)
  if (items.length) return {
    text: items.map(item => `${item.productNameSnapshot || item.productName || item.waterTypeName || '商品'} ${Number(item.quantity)}${item.quantityUnit}`).join('，'),
    meta: ''
  }
  const pieces = Number(row.quantity), barrels = Number(row.deliveryBucketQty)
  return {
    text: row.firstProductName || row.productName || row.waterTypeName || fallback,
    meta: Number.isFinite(pieces) && pieces > 0 ? `等 ${pieces} 件`
      : Number.isFinite(barrels) && barrels > 0 ? `含 ${barrels} 桶` : ''
  }
}

module.exports = { itemUnit, withItemUnits, orderSummary }
