// 地址相关接口（后端: AddressController）
const { get, post, put, del } = require('../utils/request')
const { API } = require('../config/api')

// GET /api/addresses (customerId 从 JWT 获取)
const getAddresses = (params = {}) => {
  return get(API.ADDRESSES, params)
}

// GET /api/addresses/{id}
const getAddressDetail = (id) => {
  return get(`${API.ADDRESSES}/${id}`)
}

// POST /api/addresses
const createAddress = (data) => {
  return post(API.CREATE_ADDRESS, data)
}

// PUT /api/addresses/{id}
const updateAddress = (id, data) => {
  return put(`${API.ADDRESSES}/${id}`, data)
}

// DELETE /api/addresses/{id} (customerId 从 JWT 获取)
const deleteAddress = (id) => {
  return del(`${API.ADDRESSES}/${id}`)
}

// PUT /api/addresses/{id}/default (customerId 从 JWT 获取)
const setDefaultAddress = (id) => {
  return put(`${API.ADDRESSES}/${id}/default`, null)
}

module.exports = { getAddresses, getAddressDetail, createAddress, updateAddress, deleteAddress, setDefaultAddress }
