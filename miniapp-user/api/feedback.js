// 意见反馈接口（后端: FeedbackController）
const { get, post } = require('../utils/request')
const { API } = require('../config/api')

/** 客户提交反馈 */
const submitFeedback = (data) => {
  return post(API.FEEDBACK, data)
}

/** 当前登录客户/员工的反馈记录 */
const getMyFeedback = () => {
  return get(API.FEEDBACK_MY)
}

module.exports = { submitFeedback, getMyFeedback }