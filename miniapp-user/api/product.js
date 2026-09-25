const { get } = require('../utils/request')
const { API } = require('../config/api')

const getProducts = (params) => {
  return get(API.PRODUCTS, params)
}

const getOnSaleProducts = (params) => {
  return get(API.PRODUCTS_SALE, params)
}

// 站级归属（2026-09-16 商品与库存重构）：水站的自定义商品只有该站能读，
// 所以详情接口必须带上 stationId；不传时后端只返回**通用库**商品。
const getProductDetail = (id, stationId) => {
  return get(`${API.PRODUCTS}/${id}`, stationId ? { stationId } : {})
}

const getStationProducts = (stationId) => {
  return get(API.PRODUCTS_SALE_BY_STATION, { stationId })
}

// 注意（2026-09-16 商品与库存重构）：
//   · GET /api/products/my 恒返回空数组（"已有商品"标签从来点不亮），已按设计删除，不要加回来；
//   · /api/products/sale-by-station 与 /api/products/{id} 现在下发的是**本站有效价**
//     （effectivePrice / effectiveDeposit / effectiveTicketPrice），前端展示必须用它，
//     不要自己拿 price/salePrice 拼 —— 站级价一上线，自己拼就会出现"列表价 ≠ 结算价"。

module.exports = { getProducts, getOnSaleProducts, getProductDetail, getStationProducts }
