const { post, get } = require('../utils/request')
const { API } = require('../config/api')

/** 服务端支付试算（金额/新增桶押金以服务端为准）
 *  返回 methods[] + allowOfflinePayment，前端禁止自带 1/2/3 支付方式映射表。 */
const getQuote = (data) => {
  return post(API.PAYMENT_QUOTE, data)
}

/** 查询客户支付记录 (customerId 从 JWT 获取) */
const getPaymentsByCustomer = () => {
  return get(`${API.PAYMENTS}/by-customer`)
}

// 说明 1：这里原先导出 getPayConfig()，调用 GET /api/payments/config 并写死 stationId: 0。
//   该接口是站长端的（@RequireRole STATION_MANAGER），且 stationId 入参会被后端用登录态覆盖，
//   客户端调用必然失败；该函数也从未被任何页面使用，已删除。
//
// 说明 2（2026-09-12 清理）：confirmPayment / getPaymentsByOrder 指向站长专属路由
//   （PUT /api/payments/{id}/confirm、GET /api/payments/by-order），顾客 token 必然 403，
//   且全小程序无任何调用点，属误导性死导出，已删除。
//   另：createPayment 在 api/order.js 中已有同名实现（页面实际用的那一个），
//   本模块里的重复定义同样无人调用，一并删除，避免两个入口行为分叉。
//   顾客端真正用到的只有 getQuote（试算）与 getPaymentsByCustomer（支付记录）。

module.exports = { getQuote, getPaymentsByCustomer }
