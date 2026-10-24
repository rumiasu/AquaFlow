const { API } = require('../config/api')
const { request, captureSession, currentSession } = require('./request')
const uploads = require('./upload')
function target(page, kind) {
  if (kind === 'order') return { url: API.ORDER_IMAGE_UPLOAD, orderId: Number(page.data.orderId) }
  if (kind === 'product') return { kind: 'product-image', id: page.data.isAdd ? 'new' : Number(page.data.editId) }
  return { kind: 'customer-adjustment-evidence', id: Number(page.data.customerId) }
}
function allowed() { const app = getApp(); return !app.canAccessStationBusiness || app.canAccessStationBusiness() }
function selectionCurrent(page, kind, s) { return s && currentSession(s.session) && target(page, kind).orderId === s.orderId }
function refresh(page, kind) {
  if (!page._uploadRecoveryActive) return
  try {
    if (!allowed()) throw Error('当前无法查看图片记录')
    const t = target(page, kind)
    const rows = uploads.uploadCases().filter(row => kind === 'order' ? row.url === API.ORDER_IMAGE_UPLOAD && row.fields.orderId === t.orderId
      : row.kind === t.kind && (kind === 'product' || row.entityId === t.id))
    const cases = rows.map(row => ({ ...row, label: row.kind === 'order-image' ? (row.fields.type === 3 ? '楼层图片待核实' : '送达图片待核实')
      : (row.kind === 'product-image' ? '商品图片草稿 ' : '调整证据草稿 ') + row.draftId.slice(-6) + ' 待核实' }))
    if (!selectionCurrent(page, kind, page._uploadRecoverySelection) || !cases.some(row => row.id === page._uploadRecoverySelection.caseId)) page._uploadRecoverySelection = null
    const s = page._uploadRecoverySelection
    page.setData({ uploadRecoveryCases: cases, uploadCaseIds: cases.map(row => row.id), uploadRecoveryReadFailed: false,
      uploadRecoverySelection: s ? { caseId: s.caseId, imageId: s.imageId, url: s.url } : null })
    if (kind === 'product' && typeof page.refreshProductImageState === 'function') page.refreshProductImageState()
  } catch (_) {
    page._uploadRecoverySelection = null; page.setData({ uploadRecoveryReadFailed: true, uploadRecoveryCases: [], uploadCaseIds: [], uploadRecoverySelection: null })
    if (kind === 'product' && typeof page.refreshProductImageState === 'function') page.refreshProductImageState()
  }
}
function wrapUploadRecovery(page, kind) {
  page.data = { ...page.data, uploadRecoveryCases: [], uploadCaseIds: [], uploadRecoverySelection: null, uploadRecoveryReadFailed: false, uploadRecovering: false,
    ...(kind === 'product' ? { uploadRecoveryExpanded: false } : {}) }
  page._uploadRecoveryActive = true; page._uploadRecoveryEpoch = 0
  page.refreshUploadRecovery = function () { refresh(this, kind) }
  if (kind === 'product') page.onToggleUploadRecovery = function () { this.setData({ uploadRecoveryExpanded: !this.data.uploadRecoveryExpanded }); refresh(this, kind) }
  function guard(instance, session) {
    const epoch = instance._uploadRecoveryEpoch, captured = JSON.stringify(target(instance, kind))
    return () => instance._uploadRecoveryActive && instance._uploadRecoveryEpoch === epoch && currentSession(session)
      && JSON.stringify(target(instance, kind)) === captured && allowed()
  }
  page.onUploadRecover = async function (event) {
    if (!this._uploadRecoveryActive || this.data.uploadRecovering) return
    refresh(this, kind)
    const entry = this.data.uploadRecoveryCases.find(row => row.id === event.currentTarget.dataset.caseid)
    if (!entry) return
    if (entry.url !== API.ORDER_IMAGE_UPLOAD) { wx.showModal({ confirmText: '知道了', title: '图片仍需人工核实', content: '原草稿记录已保留。无法确认时不要重传同一草稿。独立草稿可以继续，原记录不会被删除。', showCancel: false }); return }
    // [2026-10-14] A failed/empty/cancelled newer read must not leave an older image confirmable.
    this._uploadRecoverySelection = null
    this.setData({ uploadRecoverySelection: null })
    const session = captureSession(), current = guard(this, session)
    this.setData({ uploadRecovering: true })
    try {
      const result = await request({ url: '/api/order-images/by-order/' + entry.fields.orderId, method: 'GET' }, session)
      if (!current()) return
      const rows = Array.isArray(result.data) ? result.data.filter(row => row && ['number','string'].includes(typeof row.id) && Number.isSafeInteger(Number(row.id)) && Number(row.id) > 0
        && Number(row.orderId) === entry.fields.orderId && Number(row.type) === entry.fields.type
        && typeof row.objectName === 'string' && row.objectName && typeof row.url === 'string' && row.url.trim()) : []
      if (!rows.length) { wx.showToast({ title: '尚不能确认图片结果，原记录保留', icon: 'none' }); return }
      const selected = await new Promise(resolve => wx.showActionSheet({ itemList: rows.map(row => '图片 #' + row.id), success: result => resolve(rows[result.tapIndex]), fail: () => resolve(null) }))
      if (!selected || !current()) return
      // Inline selection keeps native preview/hide/show separate from explicit confirmation.
      this._uploadRecoverySelection = { caseId: entry.id, imageId: selected.id, objectName: selected.objectName, url: selected.url, orderId: entry.fields.orderId, fields: entry.fields, session }
      refresh(this, kind)
    } catch (error) { if (current()) wx.showToast({ title: error.message || '图片结果尚未核实', icon: 'none' }) }
    finally { if (current()) this.setData({ uploadRecovering: false }) }
  }
  page.onUploadPreviewSelected = function () {
    const s = this._uploadRecoverySelection
    if (this._uploadRecoveryActive && selectionCurrent(this, kind, s)) wx.previewImage({ current: s.url, urls: [s.url] })
  }
  page.onUploadConfirmSelected = async function () {
    if (!this._uploadRecoveryActive || this.data.uploadRecovering) return
    refresh(this, kind)
    const s = this._uploadRecoverySelection
    if (!selectionCurrent(this, kind, s)) return
    const session = captureSession(), current = guard(this, session)
    this.setData({ uploadRecovering: true })
    try {
      const confirmed = await new Promise(resolve => wx.showModal({ confirmText: '确认原图', title: '核实这张图片', content: '请确认刚查看的是原来提交的同一张图片。无法确认请取消。', success: result => resolve(result.confirm), fail: () => resolve(false) }))
      if (!confirmed || !current()) return
      // [2026-10-14] A final GET may fail while hidden, when its guarded catch cannot update the page.
      // Consume the visible choice before dispatch; keep only this request's proof in s.
      this._uploadRecoverySelection = null
      this.setData({ uploadRecoverySelection: null })
      const r = await uploads.recoverOrderUpload({ url: API.ORDER_IMAGE_UPLOAD, formData: s.fields },
        { caseId: s.caseId, resolution: 'present', confirmedSameImage: true, imageId: s.imageId, objectName: s.objectName, isCurrent: current })
      if (!current()) return
      if (this.data.floorPhotos && s.fields.type === 3) this.setData({ floorPhotos: Array.from(new Set(this.data.floorPhotos.concat(r.data.url))) })
      if (this.data.photos && s.fields.type === 1) this.setData({ photos: Array.from(new Set(this.data.photos.concat(r.data.url))) })
      if (typeof this.loadFloorPhotos === 'function' && s.fields.type === 3) await this.loadFloorPhotos(s.orderId)
      if (current()) { this._uploadRecoverySelection = null; wx.showToast({ title: '已核实原图片', icon: 'success' }) }
    } catch (error) {
      if (current()) {
        this._uploadRecoverySelection = null
        this.setData({ uploadRecoverySelection: null })
        wx.showToast({ title: error.message || '图片结果尚未核实', icon: 'none' })
      }
    }
    finally { if (current()) { this.setData({ uploadRecovering: false }); refresh(this, kind) } }
  }
  page.onUploadResumeDraft = function (event) {
    if (!this._uploadRecoveryActive) return
    if (kind === 'product' && (this.data.saving || this.data.uploading || this.data.productImageBusy)) { wx.showToast({ title: '图片或商品正在处理中，请稍候', icon: 'none' }); return }
    refresh(this, kind)
    const entry = this.data.uploadRecoveryCases.find(row => row.id === event.currentTarget.dataset.caseid)
    if (!entry || !entry.draftId) return
    try {
      if (kind === 'product' && typeof this.resumeProductImageDraft === 'function') {
        this.resumeProductImageDraft(entry)
        refresh(this, kind); wx.showToast({ title: '已继续原草稿，待核实记录保留', icon: 'none' }); return
      }
      const t = target(this, kind)
      if (kind === 'product' && entry.entityId === 'new') { this._uploadResumeDraftId = entry.draftId; try { this.openAdd() } finally { this._uploadResumeDraftId = null } }
      else if (t.id !== entry.entityId) { wx.showToast({ title: '请先打开对应商品或客户', icon: 'none' }); return }
      this._uploadDraftId = uploads.uploadDraft({ kind: entry.kind, id: entry.entityId }, false, entry.draftId)
      refresh(this, kind); wx.showToast({ title: '已继续原草稿，待核实记录保留', icon: 'none' })
    } catch (error) { refresh(this, kind); wx.showToast({ title: error.message || '原草稿暂时无法读取', icon: 'none' }) }
  }
  page.onUploadNewDraft = function () {
    if (kind === 'order' || !this._uploadRecoveryActive) return
    if (kind === 'product' && (this.data.saving || this.data.uploading || this.data.productImageBusy)) { wx.showToast({ title: '图片或商品正在处理中，请稍候', icon: 'none' }); return }
    const session = captureSession(), current = guard(this, session), t = target(this, kind)
    if (t.id !== 'new' && (!Number.isSafeInteger(t.id) || t.id <= 0)) { wx.showToast({ title: '请先打开对应商品或客户', icon: 'none' }); return }
    wx.showModal({ confirmText: '另开草稿', title: '另开图片草稿', content: '原草稿和待核实记录会保留。仅在开始一份独立草稿时继续。', success: result => {
      if (!result.confirm || !current()) return
      if (kind === 'product' && (this.data.saving || this.data.uploading || this.data.productImageBusy)) return
      try {
        if (kind === 'product' && typeof this.rememberProductImageDraft === 'function') this.rememberProductImageDraft()
        this._uploadDraftId = uploads.uploadDraft(t, true)
        if (kind === 'product') {
          this.setData({ 'editForm.imageUrl': '', 'editForm.imageObjectName': '', uploading: false })
          if (typeof this.onProductImageDraftChanged === 'function') this.onProductImageDraftChanged()
        } else { this.setData({ evidenceList: [], uploading: false }); if (this.resetPreview) this.resetPreview() }
      }
      catch (error) { wx.showToast({ title: error.message || '草稿暂时无法保存', icon: 'none' }) }
      refresh(this, kind)
    } })
  }
  for (const name of ['onLoad','onShow']) {
    const original = page[name]
    page[name] = function (...args) { this._uploadRecoveryActive = true; this._uploadRecoveryEpoch++; this.setData({ uploadRecovering: false }); const r = original && original.apply(this, args); refresh(this, kind); return r }
  }
  for (const name of ['onHide','onUnload']) {
    const original = page[name]
    page[name] = function (...args) { this._uploadRecoveryActive = false; this._uploadRecoveryEpoch++; this.setData({ uploadRecovering: false }); return original && original.apply(this, args) }
  }
  return page
}
module.exports = { wrapUploadRecovery }
