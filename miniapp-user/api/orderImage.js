// 订单图片接口（后端: OrderImageController）
const { get } = require('../utils/request')
const { API } = require('../config/api')

/** 查询订单图片 */
const getOrderImages = (orderId) => {
  return get(`${API.ORDER_IMAGES}/by-order/${orderId}`)
}

module.exports = { getOrderImages }