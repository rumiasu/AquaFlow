// 地址相关接口（后端: AddressController）
const { get, post, put, del } = require('../utils/request')
const { API, getCustomerId } = require('../config/api')

// GET /api/addresses?customerId=xxx
const getAddresses = (params = {}) => {
  return get(API.ADDRESSES, { customerId: getCustomerId(), ...params })
}

// GET /api/addresses/{id}
const getAddressDetail = (id) => {
  return get(`${API.ADDRESSES}/${id}`)
}

// POST /api/addresses
const createAddress = (data) => {
  return post(API.CREATE_ADDRESS, { customerId: getCustomerId(), ...data })
}

// PUT /api/addresses/{id}
const updateAddress = (id, data) => {
  return put(`${API.ADDRESSES}/${id}`, data)
}

// DELETE /api/addresses/{id}?customerId=xxx
const deleteAddress = (id) => {
  return del(`${API.ADDRESSES}/${id}`, { customerId: getCustomerId() })
}

// PUT /api/addresses/{id}/default?customerId=xxx
const setDefaultAddress = (id) => {
  return put(`${API.ADDRESSES}/${id}/default`, null, { customerId: getCustomerId() })
}

module.exports = { getAddresses, getAddressDetail, createAddress, updateAddress, deleteAddress, setDefaultAddress }
