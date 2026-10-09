const { get } = require('../utils/request')

// 仅本人关联站，包括停用站和历史零余额；不用公开营业站冒充完整资产范围。
const listAssetStations = (afterStationId = 0, limit = 20) =>
  get('/api/customer-assets/stations', { afterStationId, limit })
const getAssetStation = id => get('/api/customer-assets/stations/' + id)
module.exports = { listAssetStations, getAssetStation }

