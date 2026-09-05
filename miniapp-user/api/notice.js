// 公告相关接口（后端: NoticeController）
const { get } = require('../utils/request')
const { API } = require('../config/api')

/** 客户/员工可见：已发布公告列表 */
const getNotices = () => {
  return get(API.NOTICES)
}

/** 公告详情 */
const getNoticeDetail = (id) => {
  return get(`${API.NOTICES}/${id}`)
}

module.exports = { getNotices, getNoticeDetail }