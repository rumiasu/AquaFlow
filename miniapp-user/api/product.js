const { get } = require('../utils/request')
const { API } = require('../config/api')

const getProducts = (params) => {
  return get(API.PRODUCTS, params)
}

const getOnSaleProducts = (params) => {
  return get(API.PRODUCTS_SALE, params)
}

const getProductDetail = (id) => {
  return get(`${API.PRODUCTS}/${id}`)
}

const getMyProducts = () => {
  return get(API.MY_PRODUCTS)
}

const getStationProducts = (stationId) => {
  return get(API.PRODUCTS_SALE_BY_STATION, { stationId })
}

module.exports = { getProducts, getOnSaleProducts, getProductDetail, getMyProducts, getStationProducts }
