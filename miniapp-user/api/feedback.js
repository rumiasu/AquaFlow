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

const appendRefundNote = data => post(`${API.FEEDBACK}/refund-notes`, data)
const getRefundNotes = (refundType, refundId) => get(`${API.FEEDBACK}/refund-notes`, { refundType, refundId })
const getRefundOptions = (page = 1) => get(`${API.FEEDBACK}/refund-options`, { page })
const getRefundDisputes = (page = 1) => get(`${API.FEEDBACK}/refund-disputes`, { page })
const openRefundDispute = data => post(`${API.FEEDBACK}/refund-disputes/open`, data)
module.exports = { submitFeedback, getMyFeedback, appendRefundNote, getRefundNotes, getRefundOptions, getRefundDisputes, openRefundDispute }
