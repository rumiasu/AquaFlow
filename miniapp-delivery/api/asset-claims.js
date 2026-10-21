const { get, post } = require('../utils/request')
const ROOT = '/api/manager/asset-claims'
module.exports = {
  pending: () => get(ROOT),
  detail: id => get(ROOT + '/' + id),
  review: (id, body) => post(ROOT + '/' + id + '/review', body)
}
