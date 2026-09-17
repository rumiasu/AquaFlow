// 水票相关接口（后端: TicketAccountController / TicketRecordController）
const { get, post } = require('../utils/request')
const { API } = require('../config/api')

// GET /api/tickets (customerId 从 JWT 获取)
const getTicketAccounts = (stationId) => {
  return get(API.TICKETS, stationId ? { stationId } : {})
}

// GET /api/ticket-records (customerId 从 JWT 获取)
const getTicketRecords = (stationId) => {
  return get(API.TICKET_RECORDS, stationId ? { stationId } : {})
}

// POST /api/tickets/purchase { productId, quantity, paymentMethod, stationId, idempotencyKey }
// 支付方式: 1=微信 2=现金 3=水票（客户入口暂支持 1/2 为待支付，3 直接入票）
// ⚠️ idempotencyKey 必传（后端 v33 起强制）：无订单支付在数据库层没有任何防重，
//    缺了它连点两次「买票」会落两条待收款流水，站长两条都确认就会入账两次。
const purchaseTicket = (data) => {
  return post(`${API.TICKETS}/purchase`, data)
}

module.exports = { getTicketAccounts, getTicketRecords, purchaseTicket }
