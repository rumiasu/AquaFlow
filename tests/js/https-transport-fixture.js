'use strict'
// Explicit synthetic HTTPS seam for envelope/session callback tests. Dedicated cloud tests cover the production cloud route.
const Module = require('module'), path = require('path')
const root = path.resolve(__dirname, '../..')
function installHttpsTransportFixture() {
 const original = Module._load
 for (const app of ['miniapp-user', 'miniapp-delivery']) delete require.cache[path.join(root, app, 'utils/transport.js')]
 Module._load = function (id, parent) {
  const resolved = Module._resolveFilename(id, parent)
  const result = original.apply(this, arguments)
  if (['miniapp-user', 'miniapp-delivery'].some(app => resolved === path.join(root, app, 'config/api.js'))) {
   return { ...result, getTransportConfig: () => ({ mode: 'https', baseUrl: 'https://synthetic.invalid' }), getBaseUrl: () => 'https://synthetic.invalid' }
  }
  return result
 }
 return () => { Module._load = original }
}
function withHttpsTransportFixture(load) {
 const restore = installHttpsTransportFixture()
 try { return load() } finally { restore() }
}
module.exports = { withHttpsTransportFixture, installHttpsTransportFixture }