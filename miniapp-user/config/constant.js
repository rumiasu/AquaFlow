// 常量配置

// 订单状态
const ORDER_STATUS = {
  PENDING: 1,      // 待组批
  GROUPED: 4,      // 已组批待出发
  DELIVERING: 2,   // 配送中
  COMPLETED: 3     // 已完成
}

const ORDER_STATUS_TEXT = {
  [ORDER_STATUS.PENDING]: '待配送',
  [ORDER_STATUS.GROUPED]: '待配送',
  [ORDER_STATUS.DELIVERING]: '配送中',
  [ORDER_STATUS.COMPLETED]: '已完成'
}

// 订单来源
const ORDER_SOURCE = {
  PHONE: 1,        // 电话
  WECHAT: 2,       // 微信群
  MINIAPP: 3       // 小程序
}

// 付款状态
const PAYMENT_STATUS = {
  UNPAID: 1,       // 待付款
  PAID: 2          // 已付款
}

const PAYMENT_STATUS_TEXT = {
  [PAYMENT_STATUS.UNPAID]: '待付款',
  [PAYMENT_STATUS.PAID]: '已付款'
}

// 地址标签
const ADDRESS_TAGS = [
  { value: '小区', label: '小区' },
  { value: '工厂', label: '工厂' },
  { value: '写字楼', label: '写字楼' },
  { value: '商场', label: '商场' },
  { value: '其他', label: '其他' }
]

// 水票来源
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
