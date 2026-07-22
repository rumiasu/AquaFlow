// 水票相关接口（后端: TicketAccountController / TicketRecordController）
const { get } = require('../utils/request')
const { API } = require('../config/api')

// GET /api/tickets (customerId 从 JWT 获取)
const getTicketAccounts = () => {
  return get(API.TICKETS)
}

// GET /api/ticket-records (customerId 从 JWT 获取)
const getTicketRecords = () => {
  return get(API.TICKET_RECORDS)
}

module.exports = { getTicketAccounts, getTicketRecords }
