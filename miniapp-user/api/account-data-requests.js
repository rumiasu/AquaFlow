const { get, post } = require('../utils/request')
const options = () => get('/api/account/data-requests/options')
const submit = data => post('/api/account/data-requests', data)
const mine = beforeId => get('/api/account/data-requests/my', beforeId ? { beforeId } : {})
const detail = id => get('/api/account/data-requests/' + encodeURIComponent(id))
const closureCheck = () => get('/api/customer/account/closure-check')
module.exports = { options, submit, mine, detail, closureCheck }
