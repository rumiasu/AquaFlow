// 2026-10-08：分页未核对不能显示成零事项；游标只能沿当前站、当前筛选向旧编号推进。
function positiveId(value) { return /^[1-9]\d*$/.test(String(value || '')) && Number.isSafeInteger(Number(value)) }
function readPage(res, request, idKey, stationId) {
  const data = res && res.data, expectedScope = request.orderId ? 'LOOKUP' : request.scope
  const fail = () => { throw new Error(res && res.code !== 0 && res.message || '清单或分页未能核对，请重试') }
  if (!res || res.code !== 0 || !data || String(data.stationId) !== String(stationId) || data.scope !== expectedScope ||
      data.limit !== 50 || !Array.isArray(data.items) || data.items.length > 50 || !Object.prototype.hasOwnProperty.call(data, 'nextBeforeId')) fail()
  let previous = request.beforeId == null ? Infinity : Number(request.beforeId)
  for (const row of data.items) {
    if (!row || !positiveId(row[idKey]) || Number(row[idKey]) >= previous ||
        request.orderId && String(row[idKey]) !== String(request.orderId)) fail()
    previous = Number(row[idKey])
  }
  if (request.orderId && data.items.length !== 1) fail()
  if (data.nextBeforeId !== null && (!positiveId(data.nextBeforeId) || data.items.length !== 50 ||
      String(data.nextBeforeId) !== String(data.items[49][idKey]) || request.orderId)) fail()
  return data
}
module.exports = { positiveId, readPage }
