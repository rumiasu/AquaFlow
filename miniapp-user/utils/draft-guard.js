
const sessionDrafts = new WeakMap()
const sessionEpochs = new WeakMap()
const tokenRuntimes = new WeakMap()
function runtimeContext() {
  try { const app = getApp(), g = app.globalData || {}, u = g.userInfo || {}; return { app, epoch: sessionEpochs.get(app) || 0, identity: JSON.stringify([!!g.isLogin, String(g.customerId || u.customerId || ''), String(u.staffId || ''), String(u.stationId || ''), u.role || null, u.bindStatus || null]) } } catch (_) { return null }
}
function sameRuntime(before, now) { return before && now ? before.app === now.app && before.epoch === now.epoch && before.identity === now.identity : !before && !now }
const uiDraftPrefix = 'aquaflow:ui-draft:v1:'
function draftSession(app) {
  if (!app || typeof app !== 'object') return null
  if (!sessionDrafts.has(app)) sessionDrafts.set(app, { records: [], callbacks: new Set() })
  return sessionDrafts.get(app)
}
function rememberDraft(token, storage) {
  let app
  try { app = getApp() } catch (_) { return }
  const session = draftSession(app)
  if (session && !session.records.some(item => item.key === token.key && item.storage === storage)) session.records.push({ key: token.key, storage })
}
function registerSessionDraftCleanup(app, clear) {
  const session = draftSession(app)
  if (!session || typeof clear !== 'function') return () => {}
  session.callbacks.add(clear)
  return () => session.callbacks.delete(clear)
}
// 2026-10-28: page unload alone cannot clean drafts when logout happens from another page.
// This runs before identity mutation; only UI-draft keys and registered memory caches are eligible.
function endSessionDrafts(app, storage) {
  sessionEpochs.set(app, (sessionEpochs.get(app) || 0) + 1)
  const session = sessionDrafts.get(app)
  sessionDrafts.delete(app)
  let unknown = false, removed = 0
  const remove = (store, key) => {
    try { store.removeStorageSync(key); const value = store.getStorageSync(key); if (value != null && value !== '') unknown = true; else removed++ }
    catch (_) { unknown = true }
  }
  if (session) {
    for (const clear of session.callbacks) { try { clear() } catch (_) { unknown = true } }
    for (const item of session.records) remove(item.storage, item.key)
    session.callbacks.clear(); session.records.length = 0
  }
  const data = app && app.globalData || {}, user = data.userInfo || {}
  const staff = user.staffId != null, actor = staff ? user.staffId : (data.customerId || user.customerId)
  const aliases = staff ? ['miniapp-delivery', 'staff', 'delivery'] : ['miniapp-user', 'customer', 'user']
  if (data.isLogin && actor != null && /^\d+$/.test(String(actor))) {
    if (!storage || typeof storage.getStorageInfoSync !== 'function') unknown = true
    else try {
      for (const key of storage.getStorageInfoSync().keys || []) {
        if (!key.startsWith(uiDraftPrefix)) continue
        let scope
        try { scope = JSON.parse(decodeURIComponent(key.slice(uiDraftPrefix.length))) } catch (_) { unknown = true; continue }
        if (!Array.isArray(scope) || !aliases.includes(scope[0]) || String(scope[1]).replace(/^(?:customer|staff):/, '') !== String(actor)) continue
        const value = storage.getStorageSync(key)
        if (value && value.version === 1 && value.scopeKey === key) remove(storage, key)
        else if (value != null && value !== '') unknown = true
      }
    } catch (_) { unknown = true }
  }
  return { state: unknown ? 'unknown' : 'cleared', removed }
}

// UI text/selection drafts only. Original business intent, request IDs, receipts and photos stay in their owning modules.
function createDraftGuard(readScope, storage = wx) {
  const prefix = 'aquaflow:ui-draft:v1:'
  const context = () => {
    let value
    try { value = readScope() || {} } catch (_) { return null }
    const fields = ['app', 'actor', 'station', 'route', 'entity']
    if (fields.some(key => value[key] == null || String(value[key]) === '')) return null
    const scope = fields.map(key => String(value[key]))
    const token = Object.freeze({ key: prefix + encodeURIComponent(JSON.stringify(scope)), identity: JSON.stringify([...scope, value.generation == null ? 0 : value.generation]) })
    const runtime = runtimeContext(); if (runtime) tokenRuntimes.set(token, runtime)
    return token
  }
  const current = token => { const now = context(); return !!token && !!now && token.key === now.key && token.identity === now.identity && sameRuntime(tokenRuntimes.get(token), tokenRuntimes.get(now)) }
  const read = token => {
    if (!current(token)) return { state: 'stale' }
    rememberDraft(token, storage)
    try {
      const value = storage.getStorageSync(token.key)
      if (value == null || value === '') return { state: 'empty' }
      if (!value || value.version !== 1 || value.scopeKey !== token.key || !Object.prototype.hasOwnProperty.call(value, 'draft')) return { state: 'unknown' }
      return { state: 'saved', draft: JSON.parse(JSON.stringify(value.draft)) }
    } catch (_) { return { state: 'unknown' } }
  }
  return {
    begin: context,
    read,
    save(token, draft) {
      if (!current(token)) return { state: 'stale' }
      rememberDraft(token, storage)
      try {
        const value = { version: 1, scopeKey: token.key, draft: JSON.parse(JSON.stringify(draft)) }
        storage.setStorageSync(token.key, value)
        const actual = storage.getStorageSync(token.key)
        if (!current(token)) return { state: 'stale' }
        return JSON.stringify(actual) === JSON.stringify(value) ? { state: 'saved' } : { state: 'unknown' }
      } catch (_) { return { state: 'unknown' } }
    },
    clear(token) {
      if (!current(token)) return { state: 'stale' }
      rememberDraft(token, storage)
      try {
        storage.removeStorageSync(token.key)
        return read(token).state === 'empty' ? { state: 'empty' } : { state: 'unknown' }
      } catch (_) { return { state: 'unknown' } }
    }
  }
}
module.exports = { createDraftGuard, registerSessionDraftCleanup, endSessionDrafts }
