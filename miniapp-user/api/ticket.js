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

// POST /api/tickets/purchase { productId, quantity, paymentMethod, stationId }
// 支付方式: 1=微信 2=现金 3=水票（客户入口暂支持 1/2 为待支付，3 直接入票）
const purchaseTicket = (data) => {
  return post(`${API.TICKETS}/purchase`, data)
}

module.exports = { getTicketAccounts, getTicketRecords, purchaseTicket }
