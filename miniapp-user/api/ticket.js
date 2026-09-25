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

// GET /api/ticket-packages?stationId=&productId=
// 某站某商品**在售**的水票档位（价目表）。两个参数都必传：档位是站级的，
// 全局档位会让 A 站买的票在 B 站有价差。没挂档位时返回空数组（此时照旧按单张水票价散买）。
const getTicketPackages = (stationId, productId) => {
  return get(API.TICKET_PACKAGES, { stationId, productId })
}

// POST /api/tickets/purchase { productId, quantity, paymentMethod, stationId, idempotencyKey }
// 支付方式: 1=微信 2=现金 3=水票（客户入口暂支持 1/2 为待支付，3 直接入票）
// 按档位买时额外传 packageId（后端 v36）：张数与总价一律以**服务端档位配置**为准，
//   且必须满足 quantity === 档位的 qty，否则后端会以"购买张数与档位不一致"拒绝。
// ⚠️ idempotencyKey 必传（后端 v33 起强制）：无订单支付在数据库层没有任何防重，
//    缺了它连点两次「买票」会落两条待收款流水，站长两条都确认就会入账两次。
const purchaseTicket = (data) => {
  return post(`${API.TICKETS}/purchase`, data)
}

module.exports = { getTicketAccounts, getTicketRecords, getTicketPackages, purchaseTicket }
