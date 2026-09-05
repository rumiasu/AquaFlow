const ORDER_STATUS = {
  PENDING: 1,
  DELIVERING: 3,
  DELIVERED: 4,
  COMPLETED: 5,
  CANCELLED: 6,
  DELIVERED_PENDING_PAYMENT: 4,
  REJECTED: 7
}

const ORDER_STATUS_TEXT = {
  1: '待配送',
  3: '配送中',
  4: '已送达',
  5: '已完成',
  6: '已取消',
  7: '待水站认领'
}

const ORDER_SOURCE = {
  PHONE: 1,
  WECHAT: 2,
  MINIAPP: 3
}

const PAYMENT_STATUS = {
  UNPAID: 0,
  PENDING: 1,
  PAID: 2,
  REFUNDED: 3,
  CANCELLED: 4
}

const PAYMENT_STATUS_TEXT = {
  0: '未付款',
  1: '待收款',
  2: '已付款',
  3: '已退款',
  4: '已取消'
}

const ADDRESS_TAGS = [
  { value: '小区', label: '小区' },
  { value: '工厂', label: '工厂' },
  { value: '写字楼', label: '写字楼' },
  { value: '学校', label: '学校' },
  { value: '商场', label: '商场' },
  { value: '其他', label: '其他' }
]

const TICKET_SOURCE = {
  PURCHASE: '购买',
  GIFT: '赠送',
  CONSUME: '消费'
}

module.exports = {
  ORDER_STATUS,
  ORDER_STATUS_TEXT,
  ORDER_SOURCE,
  PAYMENT_STATUS,
  PAYMENT_STATUS_TEXT,
  ADDRESS_TAGS,
  TICKET_SOURCE
}
