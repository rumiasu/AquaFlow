// 反馈相关接口
const { get, post } = require('../utils/request')
const { API } = require('../config/api')

const getMyFeedbacks = () => {
  return get(API.FEEDBACK_MY)
}

/**
 * 站长看「落到本站的客户反馈」（后端 `FeedbackController.customerFeedback`）。
 *
 * ⚠️ 与「我的反馈」(`getMyFeedbacks`) 是**两个不同的人群**：本函数只返回
 * `customer_id is not null` 的记录，其中不含员工自己提的反馈。
 */
const getCustomerFeedbacks = () => {
  return get(API.FEEDBACK_CUSTOMERS)
}

const submitFeedback = (data) => {
  return post(API.FEEDBACK, data)
}

module.exports = { getMyFeedbacks, getCustomerFeedbacks, submitFeedback }
