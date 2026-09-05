// 反馈相关接口
const { get, post } = require('../utils/request')
const { API } = require('../config/api')

const getMyFeedbacks = () => {
  return get(API.FEEDBACK_MY)
}

const submitFeedback = (data) => {
  return post(API.FEEDBACK, data)
}

module.exports = { getMyFeedbacks, submitFeedback }
