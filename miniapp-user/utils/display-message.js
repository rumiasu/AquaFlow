// 仅过滤展示文案；错误原文、状态码与业务拒绝标记必须另存，不能据展示兜底判断提交结果。
function displayMessage(value, fallback = '操作未完成，请稍后重试') {
  const text = typeof value === 'string' ? value.trim() : ''
  // 2026-10-24：顾客请求曾直接透传HTTP/GET/内部字段到toast；保留中文业务原因及容量、金额。
  const technical = /(?:Exception|Error:|\bat\s+[\w.$]+\(|\b(?:SQL|SELECT|INSERT|UPDATE|DELETE|GET|POST|PUT|PATCH|HEAD|OPTIONS)\b|\bHTTP\b|java\.|org\.|com\.example|\b[A-Z]+(?:_[A-Z]+)+\b|https?:\/\/|\/api\/|request:fail|errno|stack|\b(?:stationId|productId|customerId|orderId|staffId|clientToken|idempotencyKey)\b)/i
  return /[\u3400-\u9fff]/.test(text) && !technical.test(text) ? text : fallback
}

module.exports = { displayMessage }
