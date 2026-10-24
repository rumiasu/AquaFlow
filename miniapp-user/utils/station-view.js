// Display only. Keep source IDs and valid English names; never infer station identity or status.
const TECHNICAL = /^(?:request:fail|connect\s+(?:ECONN\w*|ENOTFOUND|ETIMEDOUT)|(?:Error|TypeError|ReferenceError|SyntaxError):|(?:java|org\.springframework|com\.example)\.)|\b(?:ECONNREFUSED|ENOTFOUND|ETIMEDOUT|HTTP\s*[45]\d\d)\b/i
const PLACEHOLDER = /^(?:undefined|null|NaN|\[object Object\]|stationName|stationId)$/
function stationText(value, fallback) {
  const text = typeof value === 'string' ? value.trim() : ''
  return !text || TECHNICAL.test(text) || PLACEHOLDER.test(text) ? fallback : text
}
const stationName = station => stationText(station && station.name, '水站名称暂未加载')
module.exports = { stationText, stationName }
