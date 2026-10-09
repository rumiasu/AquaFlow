const { get, post } = require('../utils/request')
const current = () => get('/api/agreements/current', { audience: 'STAFF' })
const document = version => get('/api/agreements/documents/' + encodeURIComponent(version))
const acknowledge = data => post('/api/agreements/acknowledgements', data)
const mine = () => get('/api/agreements/acknowledgements/my')
module.exports = { current, document, acknowledge, mine }
