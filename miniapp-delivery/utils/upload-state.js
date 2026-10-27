// Only nonsecret recovery metadata. Never store JWTs, image bytes, local paths or signed image URLs.
const KEY = 'aq_delivery_uploadRecovery_v1'
let sequence = 0
function failure() { return Object.assign(new Error('图片记录暂时无法保存，请先核实已提交图片'), { uploadStateFailure: true }) }
const kinds = ['product-image','customer-adjustment-evidence']
const object = value => value && typeof value === 'object' && !Array.isArray(value)
const positive = value => Number.isSafeInteger(value) && value > 0
const keys = (value, allowed) => object(value) && Object.keys(value).every(key => allowed.includes(key))
function validDraft(row) {
  return keys(row, ['id','kind','entityId','createdAt']) && typeof row.id === 'string' && /^draft-[a-z0-9-]{1,100}$/.test(row.id)
    && kinds.includes(row.kind) && (positive(row.entityId) || (row.kind === 'product-image' && row.entityId === 'new')) && positive(row.createdAt)
}
function validCase(row, b) {
  if (!keys(row, ['id','fingerprint','url','fields','draftId','kind','entityId','createdAt','phase','confirmedAt'])
    || typeof row.id !== 'string' || !/^upload-case-[a-z0-9-]{1,100}$/.test(row.id) || !/^[a-f0-9]{64}$/.test(row.fingerprint)
    || !positive(row.createdAt) || !['unknown','confirmed'].includes(row.phase)
    || (row.phase === 'confirmed' ? !positive(row.confirmedAt) : row.confirmedAt !== undefined)) return false
  if (row.url === '/api/order-images/upload') return row.kind === 'order-image' && row.draftId === null && row.entityId === null
    && keys(row.fields, ['orderId','type']) && positive(row.fields.orderId) && [1,2,3].includes(row.fields.type)
  const d = b.drafts[row.draftId]
  return row.url === '/api/common/upload' && object(row.fields) && Object.keys(row.fields).length === 0
    && d && d.kind === row.kind && d.entityId === row.entityId
}
function ownerStation(key) {
  try {
    const value = JSON.parse(key), route = value[0]
    const text = v => typeof v === 'string' && !!v.trim()
    if (!Array.isArray(value) || value.length !== 3 || !positive(value[1]) || !positive(value[2])
      || !Array.isArray(route) || JSON.stringify(value) !== key) throw failure()
    if (route[0] === 'cloudContainer' ? route.length !== 4 || !text(route[1]) || !text(route[2]) || typeof route[3] !== 'string'
      : route[0] !== 'https' || route.length !== 2 || !text(route[1])) throw failure()
    return value[2]
  } catch (_) { throw failure() }
}
function validIntent(key, ownerKey, stationId, b) {
  try {
    const value = JSON.parse(key), target = JSON.parse(value[1])
    if (!Array.isArray(value) || value.length !== 2 || value[0] !== ownerKey || JSON.stringify(value) !== key
      || !Array.isArray(target) || JSON.stringify(target) !== value[1]) return false
    if (target[0] === '/api/order-images/upload') return target.length === 3 && positive(target[1]) && [1,2,3].includes(target[2])
    const d = b.drafts[target[4]]
    return target[0] === '/api/common/upload' && target.length === 5 && target[2] === stationId
      && d && target[1] === d.kind && target[3] === d.entityId && target[4] === d.id
  } catch (_) { return false }
}
function validate(value) {
  if (!keys(value, ['version','owners']) || value.version !== 1 || !object(value.owners)) throw failure()
  const caseIds = new Set()
  for (const [ownerKey, b] of Object.entries(value.owners)) {
    const stationId = ownerStation(ownerKey)
    if (!keys(b, ['drafts','current','cases']) || !object(b.drafts) || !object(b.current) || !object(b.cases)) throw failure()
    // [2026-10-14] Valid rows under a damaged index previously bypassed unknown locks after restart.
    // Validate both directions of draft lookup and the canonical owner/target bucket before any write.
    for (const [draftId, d] of Object.entries(b.drafts)) if (!validDraft(d) || draftId !== d.id || !b.current[JSON.stringify([d.kind,d.entityId])]) throw failure()
    for (const [t,draftId] of Object.entries(b.current)) { const d = b.drafts[draftId]; if (!d || t !== JSON.stringify([d.kind,d.entityId])) throw failure() }
    for (const [intentKey, rows] of Object.entries(b.cases)) {
      if (!validIntent(intentKey, ownerKey, stationId, b) || !Array.isArray(rows)) throw failure()
      for (const row of rows) {
        if (!validCase(row,b) || caseIds.has(row.id)) throw failure()
        const target = row.url === '/api/order-images/upload' ? [row.url,row.fields.orderId,row.fields.type]
          : [row.url,row.kind,stationId,row.entityId,row.draftId]
        if (intentKey !== JSON.stringify([ownerKey,JSON.stringify(target)])) throw failure()
        caseIds.add(row.id)
      }
    }
  }
  return value
}
function load() {
  let raw
  try { raw = wx.getStorageSync(KEY) } catch (_) { throw failure() }
  if (raw === '' || raw === undefined || raw === null) return { version: 1, owners: {} }
  try {
    const value = typeof raw === 'string' ? JSON.parse(raw) : raw
    return validate(JSON.parse(JSON.stringify(value)))
  } catch (_) { throw failure() }
}
function save(value) {
  try {
    const serialized = JSON.stringify(validate(value))
    wx.setStorageSync(KEY, serialized)
    // An acknowledged write is insufficient if storage silently discarded or changed the pre-dispatch marker.
    if (JSON.stringify(load()) !== serialized) throw failure()
  } catch (_) { throw failure() }
}
function owner(config, session) {
  const user = session.app.globalData.userInfo || {}
  if (!session.app.globalData.isLogin || !Number.isSafeInteger(Number(user.staffId)) || Number(user.staffId) <= 0
    || !Number.isSafeInteger(Number(user.stationId)) || Number(user.stationId) <= 0) throw failure()
  const route = config.mode === 'cloudContainer' ? [config.mode, config.env, config.service, config.resourceAppid || ''] : [config.mode, config.baseUrl]
  return JSON.stringify([route, Number(user.staffId), Number(user.stationId)])
}
function branch(value, key) {
  if (!value.owners[key]) value.owners[key] = { drafts: {}, current: {}, cases: {} }
  const b = value.owners[key]
  if (!b.drafts || !b.current || !b.cases || [b.drafts,b.current,b.cases].some(x => typeof x !== 'object' || Array.isArray(x))) throw failure()
  return b
}
function id(prefix) { return prefix + '-' + Date.now().toString(36) + '-' + (++sequence) + '-' + Math.random().toString(36).slice(2,10) }
function draft(ownerKey, kind, entityId, fresh = false, selected) {
  if (!kinds.includes(kind) || (!positive(entityId) && !(kind === 'product-image' && entityId === 'new'))) throw failure()
  const value = load(), b = branch(value, ownerKey), target = JSON.stringify([kind, entityId])
  let selectedId = selected || (!fresh && b.current[target])
  if (selectedId) {
    const record = b.drafts[selectedId]
    if (!record || record.kind !== kind || record.entityId !== entityId) throw failure()
    if (b.current[target] !== selectedId) { b.current[target] = selectedId; save(value) }
    return selectedId
  }
  selectedId = id('draft')
  b.drafts[selectedId] = { id: selectedId, kind, entityId, createdAt: Date.now() }
  b.current[target] = selectedId; save(value)
  return selectedId
}
function checkDraft(ownerKey, draftId, kind, entityId) {
  const b = branch(load(), ownerKey), record = b.drafts[draftId]
  if (!record || record.kind !== kind || record.entityId !== entityId) throw failure()
}
function cases(ownerKey, intentKey) {
  const all = branch(load(), ownerKey).cases
  const entries = intentKey ? all[intentKey] || [] : Object.values(all).flat()
  if (!Array.isArray(entries)) throw failure()
  return entries.filter(row => row.phase === 'unknown').map(row => {
    if (!row || typeof row.id !== 'string' || !/^[a-f0-9]{64}$/.test(row.fingerprint)
      || !['/api/order-images/upload','/api/common/upload'].includes(row.url)) throw failure()
    return row
  })
}
function addCase(ownerKey, intentKey, record) {
  const value = load(), b = branch(value, ownerKey)
  const row = { id: record.id, fingerprint: record.fingerprint, url: record.url,
    fields: record.fields, draftId: record.draftId || null, kind: record.kind || 'order-image',
    entityId: record.entityId || null, createdAt: record.createdAt, phase: 'unknown' }
  const rows = b.cases[intentKey] || []
  if (rows.some(entry => entry.id === row.id)) throw failure()
  rows.push(row); b.cases[intentKey] = rows; save(value)
}
function removeCase(ownerKey, intentKey, caseId) {
  const value = load(), b = branch(value, ownerKey), rows = b.cases[intentKey] || []
  b.cases[intentKey] = rows.filter(row => row.id !== caseId)
  save(value)
}
// Called only after a validated upload receipt or explicit verified original-image GET.
// Keep the original fingerprint/target as audit evidence instead of deleting a successful row.
function confirmCase(ownerKey, intentKey, caseId) {
  const value = load(), b = branch(value, ownerKey), rows = b.cases[intentKey] || []
  const row = rows.find(row => row.id === caseId)
  if (!row) throw failure()
  if (row.phase === 'confirmed') return
  row.phase = 'confirmed'; row.confirmedAt = Date.now()
  save(value)
}
module.exports = { KEY, owner, draft, checkDraft, cases, addCase, removeCase, confirmCase, id }
