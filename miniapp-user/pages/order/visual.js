// 仅供订单页面展示使用；不修改原错误对象，code/resultUnknown 仍由原流程判断。
const { displayMessage } = require('../../utils/display-message')
function errorText(error, fallback = '暂时无法完成，请稍后重试') {
  if (error && error.resultUnknown) return '结果尚未确认，请先核对原订单，勿重复提交新请求'
  return displayMessage(error && error.message, fallback)
}

// 缺值与合法 0 必须分开；不承担计价，只格式化已经读到的金额。
function money(value) {
  return ['number', 'string'].includes(typeof value) && String(value).trim() !== '' && Number.isFinite(Number(value))
    ? Number(value).toFixed(2) : '待核实'
}

module.exports = { errorText, money }
