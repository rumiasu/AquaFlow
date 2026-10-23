// All upload requests, refreshes and the single authorized retry use request.js / transport.js.
const { getTransportConfig, API } = require('../config/api')
const { request, captureSession, currentSession } = require('./request')
const { snapshot } = require('./snapshot')
const { createMultipart } = require('./multipart-body')
const { fingerprint } = require('./upload-fingerprint')
const stateStore = require('./upload-state')

const MAX_FILE_BYTES = 5 * 1024 * 1024
const MAX_BODY_BYTES = MAX_FILE_BYTES + 64 * 1024
const intents = new Map()
const pathFlights = new Map()
const pickerBatches = new WeakMap()
let picking = false
let boundarySequence = 0

function changedSession() {
  return Object.assign(new Error('登录状态已变化，请重新操作'), { sessionChanged: true })
}
function assertSession(session) { if (!currentSession(session)) throw changedSession() }
function localError(message) { return Object.assign(new Error(message), { localUploadFailure: true }) }
function unknownError() {
  return Object.assign(new Error('图片提交结果待核实，请先查看已有图片，勿重复上传'), { uploadOutcomeUnknown: true })
}

// Keep the picker and its async upload callback single-flight; cancellation has no upload side effect.
function chooseImages(options) {
  if (picking) return
  picking = true
  const session = captureSession()
  const batch = []
  pickerBatches.set(session, batch)
  let selected = false
  const release = () => { pickerBatches.delete(session); picking = false }
  try {
    wx.chooseImage({ ...options,
      success: res => {
        selected = true
        if (!currentSession(session) || !res || !Array.isArray(res.tempFilePaths) || !res.tempFilePaths.length) { release(); return }
        Promise.resolve().then(() => {
          if (currentSession(session)) return options.success({ ...res, uploadSession: session })
        })
          .catch(error => {
            if (currentSession(session)) wx.showToast({ title: error.message || '选取图片失败', icon: 'none' })
          }).then(() => Promise.all(batch.map(promise => promise.then(() => null, () => null))))
          .then(release, release)
      },
      fail: error => {
        release()
        if (currentSession(session) && !/cancel/i.test(error && error.errMsg || '')) {
          wx.showToast({ title: '选取图片失败，请重试', icon: 'none' })
        }
      },
      complete: () => { if (!selected) release() }
    })
  } catch (_) { release(); throw localError('选取图片失败，请重试') }
}

function readImage(filePath, session) {
  return new Promise((resolve, reject) => {
    let fs
    try { fs = wx.getFileSystemManager() } catch (_) { reject(localError('无法读取图片，请重新选择')); return }
    if (!fs || typeof fs.stat !== 'function' || typeof fs.readFile !== 'function') {
      reject(localError('当前微信版本无法读取图片，请升级后重试')); return
    }
    fs.stat({ path: filePath,
      success: result => {
        try {
          assertSession(session)
          const size = result && result.stats && result.stats.size
          if (!Number.isInteger(size) || size <= 0) throw localError('图片为空或无法读取，请重新选择')
          if (size > MAX_FILE_BYTES) throw localError('图片不能超过 5MB，请压缩后重试')
          fs.readFile({ filePath,
            success: result => {
              try {
                assertSession(session)
                if (Object.prototype.toString.call(result.data) !== '[object ArrayBuffer]') throw localError('无法读取图片，请重新选择')
                const bytes = result.data.slice(0)
                if (bytes.byteLength !== size || bytes.byteLength > MAX_FILE_BYTES) throw localError('图片已变化，请重新选择')
                resolve(bytes)
              } catch (error) { reject(error) }
            },
            fail: () => reject(currentSession(session) ? localError('无法读取图片，请重新选择') : changedSession())
          })
        } catch (error) { reject(error) }
      },
      fail: () => reject(currentSession(session) ? localError('无法读取图片，请重新选择') : changedSession())
    })
  })
}

// Signature screening adds a local guard; it does not claim full image decoding or replace server checks.
function extension(bytes) {
  const b = new Uint8Array(bytes)
  if (b.length >= 8 && [137, 80, 78, 71, 13, 10, 26, 10].every((v, i) => b[i] === v)) return 'png'
  if (b.length >= 3 && b[0] === 255 && b[1] === 216 && b[2] === 255) return 'jpg'
  if (b.length >= 12 && [82, 73, 70, 70].every((v, i) => b[i] === v)
    && [87, 69, 66, 80].every((v, i) => b[i + 8] === v)) return 'webp'
  throw localError('请选择 JPG、PNG 或 WebP 图片')
}

function checkReceipt(result, url, kind) {
  const value = url === API.ORDER_IMAGE_UPLOAD ? result && result.data && result.data.url : result && result.data
  const objectName = result && result.objectName
  const validProductKey = typeof objectName === 'string' && objectName === objectName.trim()
    && !/[:?#\s\\]/.test(objectName) && objectName.split('/').every(part => part && part !== '.' && part !== '..')
  if (typeof value !== 'string' || !value.trim() || (kind === 'product-image' && !validProductKey)) {
    throw Object.assign(new Error('图片提交结果待核实，请先查看已有图片，勿重复上传'), { uploadOutcomeUnknown: true })
  }
  return result
}

function integer(value) {
  if (typeof value !== 'number' && (typeof value !== 'string' || !/^[0-9]+$/.test(value.trim()))) throw localError('图片上传参数不正确')
  const number = Number(value)
  if (!Number.isSafeInteger(number) || number <= 0) throw localError('图片上传参数不正确')
  return number
}
function uploadContext(options, session) {
  const ownerKey = stateStore.owner(getTransportConfig(), session)
  if (!options || ![API.ORDER_IMAGE_UPLOAD, API.GENERAL_UPLOAD].includes(options.url)) throw localError('图片上传位置不正确')
  const supplied = snapshot(options.formData || {})
  if (!supplied || typeof supplied !== 'object' || Array.isArray(supplied)) throw localError('图片上传参数不正确')
  let fields, intentKey, draftId, kind, entityId
  if (options.url === API.ORDER_IMAGE_UPLOAD) {
    if (Object.keys(supplied).some(key => !['orderId', 'type'].includes(key))) throw localError('图片上传参数不正确')
    fields = { orderId: integer(supplied.orderId), type: integer(supplied.type === undefined ? 1 : supplied.type) }
    if (![1, 2, 3].includes(fields.type)) throw localError('图片上传参数不正确')
    intentKey = JSON.stringify([options.url, fields.orderId, fields.type])
  } else {
    if (Object.keys(supplied).length) throw localError('图片上传参数不正确')
    fields = {}
    const intent = options.intent || {}
    if (!['product-image', 'customer-adjustment-evidence'].includes(intent.kind)) throw localError('图片上传参数不正确')
    const stationId = integer(session.app.globalData.userInfo && session.app.globalData.userInfo.stationId)
    entityId = intent.kind === 'product-image' && intent.id === 'new' ? 'new' : integer(intent.id)
    kind = intent.kind
    draftId = intent.draftId
    stateStore.checkDraft(ownerKey, draftId, kind, entityId)
    intentKey = JSON.stringify([options.url, kind, stationId, entityId, draftId])
  }
  return { fields, intentKey: JSON.stringify([ownerKey, intentKey]), ownerKey, draftId, kind, entityId }
}
function getIntent(context) {
  const key = context.intentKey
  let state = intents.get(key)
  if (!state) { state = { pending: new Set(), content: new Map(), completed: new Map(), unknown: new Map() }; intents.set(key, state) }
  stateStore.cases(context.ownerKey, key).forEach(record => {
    if (!Array.from(state.pending).some(operation => operation.caseId === record.id)) state.unknown.set(record.id, record)
  })
  return state
}
function blockedError(state) {
  return Object.assign(unknownError(), { uploadCaseIds: Array.from(state.unknown.keys()) })
}
function pendingOtherSession(state) {
  return Array.from(state.pending).some(item => !currentSession(item.session))
}
function upload(options) {
  let config, session, context, state, pathKey
  try {
    session = options && options.session || captureSession()
    assertSession(session)
    if (!options || typeof options.filePath !== 'string' || !options.filePath.trim()) throw localError('请先选择图片')
    if (options.name && options.name !== 'file') throw localError('图片上传参数不正确')
    context = uploadContext(options, session)
    state = getIntent(context)
    // An unresolved write belongs to the business target, never to a replaceable temporary path/login generation.
    if (state.unknown.size) throw blockedError(state)
    if (pendingOtherSession(state)) throw localError('该图片仍在处理中，请稍后核实')
    config = getTransportConfig()
    pathKey = JSON.stringify([context.intentKey, session.generation, session.identity, options.filePath])
    const existing = pathFlights.get(pathKey)
    if (existing && existing.session.app === session.app) return existing.promise
  } catch (error) { return Promise.reject(error) }
  const original = { url: options.url, filePath: options.filePath, name: 'file', formData: context.fields, header: snapshot(options.header) }
  const operation = { session, fingerprint: null, attempted: false, promise: null }
  const work = async () => {
    // Read both routes so a new picker path cannot create a second concurrent upload of the same bytes.
    const bytes = await readImage(original.filePath, session)
    const ext = extension(bytes)
    assertSession(session)
    if (state.unknown.size) throw blockedError(state)
    operation.fingerprint = fingerprint(bytes)
    const saved = state.completed.get(operation.fingerprint)
    if (saved && currentSession(saved.session)) return saved.result
    const existing = state.content.get(operation.fingerprint)
    if (existing) return existing.promise
    state.content.set(operation.fingerprint, operation)
    let requestOptions
    if (config.mode === 'cloudContainer') {
      let multipart
      try {
        multipart = createMultipart({ fields: context.fields, bytes, filename: 'upload.' + ext,
          boundary: 'AquaFlow_' + Date.now() + '_' + (++boundarySequence), maxBodyBytes: MAX_BODY_BYTES })
      } catch (_) { throw localError('图片或上传参数不符合要求，请重新选择') }
      requestOptions = { url: original.url, method: 'POST', data: multipart.body,
        header: { ...original.header, 'Content-Type': multipart.contentType } }
    } else requestOptions = { ...original, method: 'POST' }
    assertSession(session)
    if (state.unknown.size) throw blockedError(state)
    operation.caseId = stateStore.id('upload-case')
    const durableCase = { id: operation.caseId, fingerprint: operation.fingerprint, url: original.url, fields: context.fields, draftId: context.draftId, kind: context.kind, entityId: context.entityId, createdAt: Date.now() }
    stateStore.addCase(context.ownerKey, context.intentKey, durableCase)
    operation.attempted = true
    const result = checkReceipt(await request(requestOptions, session), original.url, context.kind)
    stateStore.removeCase(context.ownerKey, context.intentKey, operation.caseId)
    state.completed.set(operation.fingerprint, { session, result })
    return result
  }
  operation.promise = Promise.resolve().then(work).then(result => {
    finishOperation()
    return result
  }, error => {
    const ambiguous = operation.attempted && ((error.sessionChanged && error.requestMayHaveOccurred !== false) || error.requestMayHaveOccurred
      || error.malformedResponse || error.statusCode >= 500 || error.backendCode === 500
      || error.backendCode === 1 || error.uploadOutcomeUnknown || error.uploadStateFailure)
    if (ambiguous) {
      const caseId = operation.caseId
      state.unknown.set(caseId, { id: caseId, fingerprint: operation.fingerprint, url: original.url, fields: context.fields, draftId: context.draftId, kind: context.kind, entityId: context.entityId, createdAt: Date.now() })
      error.uploadOutcomeUnknown = true
      error.uploadCaseIds = Array.from(state.unknown.keys())
      error.message = '图片提交结果待核实，请先查看已有图片，勿重复上传'
    }
    if (!ambiguous && operation.caseId) {
      try { stateStore.removeCase(context.ownerKey, context.intentKey, operation.caseId) } catch (_) { error.uploadOutcomeUnknown = true }
    }
    finishOperation()
    throw error
  })
  function finishOperation() {
    pathFlights.delete(pathKey)
    state.pending.delete(operation)
    if (state.content.get(operation.fingerprint) === operation) state.content.delete(operation.fingerprint)
  }
  pathFlights.set(pathKey, operation)
  state.pending.add(operation)
  const batch = pickerBatches.get(session)
  if (batch) batch.push(operation.promise)
  return operation.promise
}

// Explicit reconciliation only: the user must identify the same image, then the existing authorized read verifies it.
// Empty lists, absent-result claims, timers, file changes and relogin never release unknown state.
async function recoverOrderUpload(options, proof) {
  const session = captureSession(), context = uploadContext(options, session)
  const state = getIntent(context)
  if (!state || !proof || proof.resolution !== 'present' || proof.confirmedSameImage !== true
    || typeof proof.caseId !== 'string' || !state.unknown.has(proof.caseId) || state.pending.size) throw localError('图片结果尚未核实')
  const record = state.unknown.get(proof.caseId)
  if (record.url !== API.ORDER_IMAGE_UPLOAD) throw localError('图片结果尚未核实')
  const imageId = integer(proof.imageId)
  const result = await request({ url: '/api/order-images/by-order/' + context.fields.orderId, method: 'GET' }, session)
  assertSession(session)
  const rows = result && result.data
  const row = Array.isArray(rows) && rows.find(item => item && Number(item.id) === imageId
    && Number(item.orderId) === context.fields.orderId && Number(item.type) === context.fields.type
    && typeof item.objectName === 'string' && !!item.objectName && typeof item.url === 'string' && !!item.url.trim())
  if (!row || state.pending.size || state.unknown.get(proof.caseId) !== record
    || (proof.objectName && row.objectName !== proof.objectName) || (proof.isCurrent && !proof.isCurrent())) throw localError('图片结果尚未核实')
  stateStore.removeCase(context.ownerKey, context.intentKey, proof.caseId)
  state.completed.set(record.fingerprint, { session, result: { code: 0, data: row } })
  state.unknown.delete(proof.caseId)
  return { code: 0, data: row, remainingUnknown: state.unknown.size }
}

function uploadDraft(intent, fresh = false, selected) {
  if (!intent || !['product-image','customer-adjustment-evidence'].includes(intent.kind)) throw localError('图片草稿不正确')
  const session = captureSession(), ownerKey = stateStore.owner(getTransportConfig(), session)
  const entityId = intent.kind === 'product-image' && intent.id === 'new' ? 'new' : integer(intent.id)
  return stateStore.draft(ownerKey, intent.kind, entityId, fresh, selected)
}
function uploadCases() {
  const session = captureSession(), ownerKey = stateStore.owner(getTransportConfig(), session)
  return stateStore.cases(ownerKey).filter(record => !Array.from(intents.values())
    .some(state => Array.from(state.pending).some(operation => operation.caseId === record.id)))
}
module.exports = { upload, chooseImages, recoverOrderUpload, uploadDraft, uploadCases, isUploadSessionCurrent: currentSession }
