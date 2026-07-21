// 水票相关接口（后端: TicketAccountController / TicketRecordController）
const { get } = require('../utils/request')
const { API, getCustomerId } = require('../config/api')

// GET /api/tickets?customerId=xxx
const getTicketAccounts = () => {
  return get(API.TICKETS, { customerId: getCustomerId() })
}

// GET /api/ticket-records?customerId=xxx
const getTicketRecords = () => {
  return get(API.TICKET_RECORDS, { customerId: getCustomerId() })
}

module.exports = { getTicketAccounts, getTicketRecords }
