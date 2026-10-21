const { get, post } = require('../utils/request')
const ROOT = '/api/asset-claims'
module.exports = {
  mine: stationId => get(ROOT, { stationId }),
  products: stationId => get(ROOT + '/products', { stationId }),
  detail: id => get(ROOT + '/' + id),
  submit: body => post(ROOT, body)
}
