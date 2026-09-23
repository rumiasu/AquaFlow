// 企业资料接口（后端: CompanyInfoController）
const { get, post, put } = require('../utils/request')
const { API } = require('../config/api')

/** 获取当前客户企业资料 */
const getCompanyInfo = () => {
  return get(API.COMPANY_INFO)
}

/** 保存企业资料（员工用） */
const saveCompanyInfo = (data) => {
  return post(API.COMPANY_INFO, data)
}

/** 当前客户自助更新企业资料 */
const updateCompanyInfo = (data) => {
  return put(API.COMPANY_INFO, data)
}

module.exports = { getCompanyInfo, saveCompanyInfo, updateCompanyInfo }