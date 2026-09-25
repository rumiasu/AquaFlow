// 客户通知接口（后端: CustomerNotificationController）
const { get, post } = require('../utils/request')
const { API } = require('../config/api')

// 获取未读通知列表
const getUnreadNotifications = () => {
  return get(API.CUSTOMER_NOTIFICATIONS_UNREAD, {})
}

// 获取通知列表
const getNotifications = (limit) => {
  return get(API.CUSTOMER_NOTIFICATIONS, { limit: limit || 50 })
}

// 获取未读数量
const getUnreadCount = () => {
  return get(API.CUSTOMER_NOTIFICATIONS_UNREAD_COUNT, {})
}

// 全部标记已读
const markAllRead = () => {
  return post(API.CUSTOMER_NOTIFICATIONS_READ_ALL, {})
}

module.exports = { getUnreadNotifications, getNotifications, getUnreadCount, markAllRead }
