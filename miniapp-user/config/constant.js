export const ORDER_STATUS = {
  PENDING: 1,
  DELIVERING: 2,
  COMPLETED: 3,
  GROUPED: 4,
  CANCELLED: 5
}

export const ORDER_STATUS_TEXT = {
  1: '待配送',
  2: '配送中',
  3: '已完成',
  4: '待配送',
  5: '已取消'
}

export const ORDER_SOURCE = {
  PHONE: 1,
  WECHAT: 2,
  MINIAPP: 3
}

export const PAYMENT_STATUS = {
  UNPAID: 0,
  PENDING: 1,
  PAID: 2,
  REFUNDED: 3,
  CANCELLED: 4
}

export const PAYMENT_STATUS_TEXT = {
  0: '未付款',
  1: '待确认',
  2: '已付款',
  3: '已退款',
  4: '已取消'
}

export const ADDRESS_TAGS = [
  { value: '小区', label: '小区' },
  { value: '工厂', label: '工厂' },
  { value: '写字楼', label: '写字楼' },
  { value: '学校', label: '学校' },
  { value: '商场', label: '商场' },
  { value: '其他', label: '其他' }
]

export const TICKET_SOURCE = {
  PURCHASE: '购买',
  GIFT: '赠送',
  CONSUME: '消费'
}
