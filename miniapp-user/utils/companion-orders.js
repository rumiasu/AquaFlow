// /api/orders currently returns a paged array. Drain the two active status queries,
// rather than treating a first page of historical orders as the entire account.
async function readCompanionOrders(getOrders, stationId, current) {
  if (!Number.isSafeInteger(Number(stationId)) || Number(stationId) <= 0 || typeof current !== 'function')
    throw new Error('请先核对办理水站')
  const selected = new Map(), pageSize = 100
  for (const status of [1, 2]) {
    const seen = new Set()
    for (let page = 1; page <= 50; page++) {
      if (!current()) return null
      const response = await getOrders({ stationId, status, page, pageSize })
      if (!current()) return null
      const data = response && response.data, rows = Array.isArray(data) ? data : data && data.records
      if (!response || response.code != null && response.code !== 0 || !Array.isArray(rows)
          || rows.some(row => !row || row.id == null || row.stationId == null))
        throw new Error('送水订单暂未完整加载，请重试')
      let added = 0
      for (const row of rows) {
        const key = String(row.id)
        if (!seen.has(key)) { seen.add(key); added++ }
        if (String(row.stationId) === String(stationId) && [1, 2].includes(Number(row.status))) selected.set(key, row)
      }
      const more = !Array.isArray(data) && typeof data.hasMore === 'boolean' ? data.hasMore : rows.length >= pageSize
      if (!more) break
      if (!added || page === 50) throw new Error('送水订单尚未全部读取，请重试或选择到店、单独上门')
    }
  }
  return [...selected.values()]
}
module.exports = { readCompanionOrders }
