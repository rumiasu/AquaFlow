const uiScene = require('../../utils/ui-scene')
const { showBusinessModal } = require('../../utils/business-dialog')
const { getTemplates, saveTemplate, toggleTemplate, deleteTemplate } = require('../../api/template')
const { getProducts, getStationProducts } = require('../../api/product')
const { getAddresses } = require('../../api/address')
const { resolveStationId } = require('../../utils/station')
const { formatAddress } = require('../../utils/address')
const { captureSession, isCurrentSession } = require('../../utils/token')
const { createDraftGuard } = require('../../utils/draft-guard')

// 只清理错误的展示文字；原对象的 code、resultUnknown、businessRejected 均不改动。
function viewError(error, fallback = '暂时无法完成，请稍后重试') {
  const text = error && typeof error.message === 'string' ? error.message.trim() : ''
  return /[\u3400-\u9fff]/.test(text)
    && !/(?:Exception|Error:|\bat\s+\S+\(|\bSQL\b|https?:\/\/|request:fail|errno|stack|[A-Z][A-Z_]{5,})/.test(text)
    ? text : fallback
}

// 2026-10-24：嵌套商品循环曾把商品序号当成表单行号；拒绝失效/越界事件，避免写出额外行。
function formRowIndex(event, items) {
  const dataset = event && event.currentTarget && event.currentTarget.dataset || {}
  const raw = dataset.index
  if (typeof raw !== 'number' && !(typeof raw === 'string' && /^\d+$/.test(raw))) return -1
  const index = Number(raw)
  return Number.isSafeInteger(index) && index >= 0 && Array.isArray(items) && index < items.length && items[index]
    && typeof items[index].rowKey === 'string' && dataset.rowKey === items[index].rowKey
    ? index : -1
}

function validFormDraft(form) {
  if (!form || typeof form.name !== 'string' || typeof form.specialNote !== 'string' || !Array.isArray(form.items)) return false
  if (form.addressId != null && (!Number.isSafeInteger(Number(form.addressId)) || Number(form.addressId) <= 0)) return false
  return form.items.every(row => row && typeof row === 'object' && !Array.isArray(row)
    && (row.productId == null || Number.isSafeInteger(Number(row.productId)) && Number(row.productId) > 0)
    && (typeof row.quantity === 'string' || typeof row.quantity === 'number')
    && (row.productName == null || typeof row.productName === 'string'))
}

function formItem(page, productId = null, quantity = 1) {
  page._itemSeq = (page._itemSeq || 0) + 1
  return { rowKey: 'template-row-' + page._itemSeq, productId, quantity }
}

Page({
  data: {
    sceneImageUrl: uiScene.LOCAL,
    loading: true, loaded: false, needLogin: false, accessDenied: false, formLoading: false, formReady: false, submitting: false, saveError: '',
    templates: [], emptyTemplateImageFailed: false,
    showEditModal: false, formDirty: false, storedDraft: false, draftNotice: '',
    editingTemplate: null,
    isEditing: false,
    products: [], selectorProducts: [], selectingRowKey: '',
    addresses: [],
    stationId: null,
    // ===== 失败标记（[2026-09-20 真机联调]，空串 = 一切正常）=====
    // 为什么需要：列表与弹窗里的商品/地址原来都是 `.catch(() => null)`，失败时页面渲染成
    // 「还没有常用订单」、弹窗里商品/地址一片空白 —— 把"没查到"说成"你确实没有"（AGENTS §8.22），
    // 后者更糟：用户会在空列表里反复点"添加"却永远选不出商品来（保存时还被拦"请选择至少一种商品"）。
    loadError: '',
    formLoadError: '',
    form: {
      name: '',
      addressId: null,
      specialNote: '',
      items: []
    }
  },

  onEmptyTemplateImageError(e) { uiScene.imageError(this, e, 'emptyTemplateImageFailed') },

  onLoad() {},

  onShow() {
    uiScene.show(this, 'emptyTemplateImageFailed')
    this._hidden = false
    if (this.data.emptyTemplateImageFailed) this.setData({ emptyTemplateImageFailed: false })
    if (this._session && !isCurrentSession(this._session)) {
      this.setData({ templates: [], loaded: false, products: [], selectorProducts: [], addresses: [], stationId: null, showEditModal: false, formDirty: false, form: { name: '', addressId: null, specialNote: '', items: [] }, formLoading: false, formReady: false })
      if (wx.disableAlertBeforeUnload) wx.disableAlertBeforeUnload()
    }
    this._session = captureSession()
    return Promise.all([this.loadData(), this.data.showEditModal && !this.data.formReady ? this.loadFormData() : null])
  },
  onHide() {
    uiScene.hide(this)
    this._hidden = true; this._readSeq = (this._readSeq || 0) + 1; this._formSeq = (this._formSeq || 0) + 1
    // 2026-10-22：旧回调失效时释放对应锁；返回后只重读选项，不清表单输入。
    if (this.data.formLoading) this.setData({ formLoading: false, formReady: false, formLoadError: '读取已暂停，返回后将重新核实商品和地址。' })
  },
  onUnload() { this.onHide(); this._unloaded = true },
  async onPullDownRefresh() { try { await this.loadData() } finally { wx.stopPullDownRefresh() } },
  async loadData() {
    const session = captureSession(), seq = this._readSeq = (this._readSeq || 0) + 1
    const current = () => !this._hidden && !this._unloaded && seq === this._readSeq && isCurrentSession(session)
    this.setData({ needLogin: !session.loggedIn || !session.customerId })
    if (!session.loggedIn || !session.customerId) { if (wx.disableAlertBeforeUnload) wx.disableAlertBeforeUnload(); this.setData({ loading: false, loaded: false, accessDenied: false,
      showEditModal: false, formDirty: false, form: { name: '', addressId: null, specialNote: '', items: [] }, templates: [], loadError: '请先登录后查看本人常用订水配置。' }); return }
    this.setData({ loading: !this.data.loaded, loadError: '' })
    try {
      const sid = this.data.stationId || await resolveStationId()
      if (!current()) return
      if (!sid) throw new Error('请先选择水站，再查看本站常用订水配置')
      const res = await getTemplates(sid)
      if (!current()) return
      if (!res || res.code !== 0 || !Array.isArray(res.data)) throw new Error('常用订水配置暂未加载，请重试')
      this.setData({ templates: res.data, stationId: sid, loaded: true, accessDenied: false, loadError: '' })
    } catch (error) {
      if (!current()) return
      const denied = /无权|权限|登录/.test(error && error.message || '')
      if (denied) { if (wx.disableAlertBeforeUnload) wx.disableAlertBeforeUnload(); this.setData({ templates: [], loaded: false, accessDenied: true, showEditModal: false, formDirty: false, form: { name: '', addressId: null, specialNote: '', items: [] },
        needLogin: /登录/.test(error && error.message || '') }) }
      this.setData({ loadError: (this.data.loaded ? '刷新失败，以下为上次读取的配置。' : '') + viewError(error, '常用订水配置暂未加载，请重试。') })
    } finally { if (current()) this.setData({ loading: false }) }
  },
  onRetry() { return this.loadData() },
  onLogin() { wx.navigateTo({ url: '/pages/login/index' }) },

  onAddTemplate() {
    if (this.data.formDirty && !this.data.showEditModal) { wx.showToast({ title: '请先处理未保存的配置', icon: 'none' }); return }
    if (this._hidden || this._unloaded || this.data.submitting || this.data.needLogin || this.data.accessDenied) return
    const session = captureSession()
    if (!session.loggedIn || !session.customerId) { this.loadData(); return }
    this.setData({
      showEditModal: true, formDirty: false, saveError: '', formReady: false,
      isEditing: false,
      editingTemplate: null,
      form: {
        name: '',
        addressId: null,
        specialNote: '',
        items: [formItem(this)]
      }
    })
    this._draftEntity = 'new'; this.beginFormDraft()
    this.loadFormData()
  },

  onEditTemplate(e) {
    if (this.data.formDirty && !this.data.showEditModal) { wx.showToast({ title: '请先处理未保存的配置', icon: 'none' }); return }
    if (this._hidden || this._unloaded || this.data.submitting || this.data.loadError) return
    const { template } = e.currentTarget.dataset
    const items = (template.items && template.items.length > 0)
      ? template.items.map(i => ({ ...formItem(this, i.productId || i.waterTypeId, i.quantity || 1), productName: i.productName || i.waterTypeName || '' }))
      : [formItem(this)]
    this.setData({
      showEditModal: true, formDirty: false, saveError: '', formReady: false,
      isEditing: true,
      editingTemplate: template,
      form: {
        name: template.name || '',
        addressId: template.addressId,
        specialNote: template.specialNote || '',
        items
      }
    })
    this._draftEntity = 'template:' + String(template.id); this.beginFormDraft()
    this.loadFormData()
  },

  async loadFormData() {
    if (this.data.formLoading || this.data.submitting) return
    const session = captureSession(), seq = this._formSeq = (this._formSeq || 0) + 1
    const current = () => !this._unloaded && !this._hidden && this.data.showEditModal && seq === this._formSeq && isCurrentSession(session)
    this.setData({ formLoading: true, formReady: false, formLoadError: '', products: [], selectorProducts: [] })
    try {
    // [2026-09-14 修正] 原实现调 /api/stations/mine —— 那是**员工**接口
    // （@RequireRole STATION_MANAGER/DELIVERY），顾客 token 必然 403；异常又被空 catch 吞掉，
    // 导致 stationId 恒为 null：模板列表恒空、保存时 station_id 落空。是静默失败，不是「缺字段」。
    // 现改为与首页 checkStation 同一套来源：stationStorage 优先，回退 my-station。
    const currentStationId = await resolveStationId()

    // [2026-09-20 真机联调] 商品与地址原来是各自的 `.catch(() => null)`：拉不到时弹窗里
    // 商品/地址选项**一片空白**，用户点"+"也好、点保存也好都只会撞上"请选择至少一种商品"——
    // 把"没查到"说成了"你没选"（AGENTS §8.22）。现在仍然不阻断（拿到什么用什么），
    // 但把失败原因摆在弹窗里，用户才知道该重试而不是干瞪眼。
    let formLoadError = ''
    let productRes = null
    if (currentStationId) {
      try {
        productRes = await getStationProducts(currentStationId)
      } catch (e) {
        formLoadError = viewError(e, '网络异常')
        console.warn('[Template] 商品列表加载失败:', e && (e.message || e.errMsg))
      }
    }
    if (!productRes || !productRes.data) {
      // 没有选水站时的兜底：只取**通用库**商品（带 stationId 才能看到本站自定义商品；
      // 模板页只需要 id/name，不显示价格，所以这里不涉及站级价）。
      try {
        productRes = await getProducts(currentStationId ? { stationId: currentStationId } : {})
      } catch (e) {
        formLoadError = formLoadError || viewError(e, '网络异常')
        console.warn('[Template] 通用库商品加载失败:', e && (e.message || e.errMsg))
      }
    }
    let addressRes = null
    try {
      addressRes = await getAddresses()
    } catch (e) {
      formLoadError = formLoadError || viewError(e, '网络异常')
      console.warn('[Template] 收货地址加载失败:', e && (e.message || e.errMsg))
    }

    if (!current()) return
    if (productRes && Array.isArray(productRes.data)) {
      this.setData({ products: productRes.data, selectorProducts: productRes.data.map(p => ({ id: p.id, name: p.name, spec: p.spec || '', image: p.imageUrl || '', priceText: '价格待确认' })), stationId: currentStationId })
    }
    if (!this._draftToken && this.data.stationId) this.beginFormDraft()
    if (addressRes && Array.isArray(addressRes.data)) {
      const addresses = addressRes.data.map(function (a) {
        return Object.assign({}, a, { addressText: formatAddress(a) })
      })
      const defaultAddr = addresses.find(a => a.isDefault) || addresses[0]
      this.setData({
        addresses,
        'form.addressId': this.data.form.addressId || (defaultAddr ? defaultAddr.id : null)
      })
    }
    this.setData({
      formLoadError: formLoadError
        ? '商品或地址未能完整读取。' + formLoadError + '输入已保留，可在此重新加载。'
        : ''
    })
    this.setData({ formReady: !formLoadError })
    } catch (error) { if (current()) this.setData({ formLoadError: viewError(error, '商品或地址暂未加载，输入已保留，请重试。') }) }
    finally { if (current()) this.setData({ formLoading: false }) }
  },

  beginFormDraft() {
    this._draftGuard = createDraftGuard(() => {
      const session = captureSession()
      return { app: 'customer', actor: session.loggedIn && session.customerId ? 'customer:' + session.customerId : '',
        station: this.data.stationId || '', route: 'pages/template/index', entity: this._draftEntity || '', generation: session.epoch }
    })
    this._draftToken = this._draftGuard.begin(); this._draftBlocked = true; this._draftSnapshot = null
    if (!this._draftToken) { this.setData({ storedDraft: false, draftNotice: '草稿尚未确认保存，请先选择水站并登录。' }); return }
    const result = this._draftGuard.read(this._draftToken)
    if (result.state === 'saved' && result.draft && validFormDraft(result.draft.form)) {
      this.setData({ storedDraft: true, draftNotice: '有上次未提交的输入，可选择恢复或放弃。' }); return
    }
    if (result.state !== 'empty') { this.setData({ storedDraft: false, draftNotice: '草稿暂时无法核实，请重试读取。' }); return }
    this._draftBlocked = false; this.setData({ storedDraft: false, draftNotice: '' })
    if (this.data.formDirty) this.saveFormDraft()
  },
  saveFormDraft() {
    if (this._draftBlocked || !this._draftGuard || !this._draftToken) {
      this.setData({ draftNotice: '草稿尚未确认保存，请先处理上次的输入。' }); return
    }
    const current = this._draftGuard.read(this._draftToken)
    if ((this._draftSnapshot == null && current.state !== 'empty') || (this._draftSnapshot != null && (current.state !== 'saved' || JSON.stringify(current.draft) !== this._draftSnapshot))) {
      this._draftBlocked = true; this.setData({ storedDraft: current.state === 'saved', draftNotice: '草稿已变化或暂无法读取，当前输入尚未确认保存。' }); return
    }
    const draft = { form: this.data.form }, result = this._draftGuard.save(this._draftToken, draft)
    if (result.state === 'saved') this._draftSnapshot = JSON.stringify(draft)
    this.setData({ draftNotice: result.state === 'saved' ? '' : '草稿尚未确认保存，请重试或记下填写内容。' })
  },
  onRetryFormDraft() {
    if (this._draftCleanupPending) { if (this.clearFormDirty()) { this._draftCleanupPending = false; this.setData({ showEditModal: false, saveError: '' }) } return }
    this.beginFormDraft()
  },
  onRestoreStoredDraft() {
    const token = this._draftToken, revision = this._formRevision || 0, formSeq = this._formSeq || 0
    const offered = this._draftGuard && this._draftGuard.read(token)
    const restore = () => {
      const result = this._draftGuard && this._draftGuard.read(token), draft = result && result.draft
      if (!result || result.state !== 'saved' || !offered || offered.state !== 'saved' || JSON.stringify(draft) !== JSON.stringify(offered.draft) || revision !== (this._formRevision || 0) || formSeq !== (this._formSeq || 0) || this._hidden || this._unloaded || !draft || !validFormDraft(draft.form)) {
        this.setData({ draftNotice: '草稿或当前输入已变化，请重新核对。' }); return
      }
      const form = draft.form
      const items = form.items.map(r => ({ ...formItem(this, r.productId, r.quantity), productName: r.productName || '' }))
      this._draftBlocked = false; this._draftSnapshot = JSON.stringify(draft); this._formRevision = revision + 1
      this.setData({ form: { name: form.name, addressId: form.addressId, specialNote: form.specialNote, items }, formDirty: true, storedDraft: false, draftNotice: '' })
      if (wx.enableAlertBeforeUnload) wx.enableAlertBeforeUnload({ message: '常用订水配置尚未保存，确定离开吗？' })
    }
    if (this.data.formDirty) return showBusinessModal({ title: '恢复上次输入？', content: '恢复会替换当前未保存的输入。', confirmText: '恢复', cancelText: '继续填写', success: res => { if (res.confirm) restore() } })
    restore()
  },
  onDiscardStoredDraft() {
    const session = captureSession(), token = this._draftToken, revision = this._formRevision || 0, formSeq = this._formSeq || 0
    const offered = this._draftGuard && this._draftGuard.read(token)
    showBusinessModal({ title: '放弃上次输入？', content: '放弃后无法恢复这份草稿。', confirmText: '放弃', cancelText: '保留', success: res => {
      if (!res.confirm || this._hidden || this._unloaded || formSeq !== (this._formSeq || 0) || !isCurrentSession(session) || revision !== (this._formRevision || 0)) return
      const current = this._draftGuard && this._draftGuard.read(token)
      if (!offered || offered.state !== 'saved' || !current || current.state !== 'saved' || JSON.stringify(current.draft) !== JSON.stringify(offered.draft)) { this.setData({ draftNotice: '草稿已变化或暂无法读取，请重试。' }); return }
      const result = this._draftGuard.clear(token)
      if (!result || result.state !== 'empty') { this.setData({ draftNotice: '草稿尚未确认清除，请重试。' }); return }
      this._draftBlocked = false; this.setData({ storedDraft: false, draftNotice: '' }); if (this.data.formDirty) this.saveFormDraft()
    } })
  },
  markFormDirty() {
    this._formRevision = (this._formRevision || 0) + 1
    this.setData({ formDirty: true }); this.saveFormDraft()
    if (wx.enableAlertBeforeUnload) wx.enableAlertBeforeUnload({ message: '常用订水配置尚未保存，确定离开吗？' })
  },
  clearFormDirty() {
    if (!this._draftBlocked && this._draftGuard && this._draftToken && this._draftSnapshot != null) {
      const current = this._draftGuard.read(this._draftToken)
      if (current.state === 'saved' && JSON.stringify(current.draft) === this._draftSnapshot) {
        const result = this._draftGuard.clear(this._draftToken)
        if (result.state !== 'empty') { this.setData({ draftNotice: '草稿尚未确认清除，请重试。' }); return false }
      } else if (current.state !== 'empty') { this.setData({ draftNotice: '草稿已变化或暂无法读取，请稍后核对。' }); return false }
    }
    this._draftSnapshot = null; this.setData({ formDirty: false, draftNotice: '' })
    if (wx.disableAlertBeforeUnload) wx.disableAlertBeforeUnload()
    return true
  },
  onNameInput(e) {
    if (this._hidden || this._unloaded || this.data.submitting) return
    this.setData({ 'form.name': e.detail.value })
    this.markFormDirty()
  },

  onAddItem() {
    if (this._hidden || this._unloaded || this.data.submitting) return
    const items = this.data.form.items.concat([formItem(this)])
    this.setData({ 'form.items': items })
    this.markFormDirty()
  },

  onRemoveItem(e) {
    if (this._hidden || this._unloaded || this.data.submitting) return
    const index = formRowIndex(e, this.data.form.items)
    if (index < 0) return
    const items = this.data.form.items.filter((_, i) => i !== index)
    if (items.length === 0) items.push(formItem(this))
    this.setData({ 'form.items': items })
    this.markFormDirty()
  },

  onOpenProduct(e) {
    const index = formRowIndex(e, this.data.form.items)
    if (index < 0 || this._hidden || this._unloaded || this.data.submitting || !this.data.formReady) return
    if (wx.hideKeyboard) wx.hideKeyboard({})
    const key = this.data.form.items[index].rowKey
    this.setData({ selectingRowKey: this.data.selectingRowKey === key ? '' : key })
  },
  onSelectProduct(e) {
    if (this._hidden || this._unloaded || this.data.submitting) return
    const index = formRowIndex(e, this.data.form.items)
    const id = Number(e && e.detail && e.detail.id != null ? e.detail.id : e && e.currentTarget && e.currentTarget.dataset && e.currentTarget.dataset.id)
    if (index < 0 || !Number.isSafeInteger(id) || id <= 0 || !this.data.products.some(product => Number(product.id) === id)) return
    const product = this.data.products.find(p => Number(p.id) === id)
    this.setData({ [`form.items[${index}].productId`]: id, [`form.items[${index}].productName`]: product.name || '商品 #' + id, selectingRowKey: '' })
    this.markFormDirty()
  },

  onQuantityChange(e) {
    if (this._hidden || this._unloaded || this.data.submitting) return
    const index = formRowIndex(e, this.data.form.items)
    if (index < 0) return
    const { type } = e.currentTarget.dataset
    const raw = this.data.form.items[index].quantity
    let qty = Number(raw)
    // 2026-10-28：输入中允许空或非法值，不再把它静默改成 1。
    if (String(raw).trim() === '' || !Number.isSafeInteger(qty) || qty < 1) return
    if (type === 'add' && Number.isSafeInteger(qty + 1)) qty++
    else if (type === 'minus' && qty > 1) qty--
    else return
    this.setData({ [`form.items[${index}].quantity`]: qty })
    this.markFormDirty()
  },

  onQuantityInput(e) {
    if (this._hidden || this._unloaded || this.data.submitting) return
    const index = formRowIndex(e, this.data.form.items)
    if (index < 0) return
    const raw = String(e.detail.value)
    const qty = /^\d+$/.test(raw) && Number.isSafeInteger(Number(raw)) ? Number(raw) : raw
    this.setData({ [`form.items[${index}].quantity`]: qty })
    this.markFormDirty()
  },

  onSelectAddress(e) {
    if (this._hidden || this._unloaded || this.data.submitting) return
    const { id } = e.currentTarget.dataset
    this.setData({ 'form.addressId': parseInt(id) })
    this.markFormDirty()
  },

  onNoteInput(e) {
    if (this._hidden || this._unloaded || this.data.submitting) return
    this.setData({ 'form.specialNote': e.detail.value })
    this.markFormDirty()
  },

  onTemplateBeforeLeave() {
    if (!this.data.showEditModal) return
    this._formSeq = (this._formSeq || 0) + 1
    // 2026-10-28：系统返回收起表单时保留未保存内容，并提供继续编辑入口。
    this.setData({ showEditModal: false, formLoading: false })
  },
  onResumeDraft() { if (!this._hidden && !this._unloaded && this.data.formDirty && !this.data.submitting && isCurrentSession(this._session)) { this.setData({ showEditModal: true }); if (!this.data.formReady) this.loadFormData() } },
  onDiscardDraft() {
    if (!this.data.formDirty || this.data.submitting) return
    const session = captureSession(), formSeq = this._formSeq || 0
    showBusinessModal({ title: '放弃未保存的配置？', content: '放弃后本次填写不会保留。', confirmText: '放弃', cancelText: '继续填写',
      success: res => { if (res.confirm && !this._hidden && !this._unloaded && formSeq === (this._formSeq || 0) && isCurrentSession(session) && !this.data.submitting && this.clearFormDirty()) { this._draftCleanupPending = false; this.setData({ showEditModal: false, formLoading: false }) } } })
  },
  onCloseEditModal() {
    if (this.data.submitting) return
    if (this.data.formDirty) { this.onDiscardDraft(); return }
    this._formSeq = (this._formSeq || 0) + 1
    this.setData({ showEditModal: false, formLoading: false })
  },

  async onSaveTemplate() {
    if (this._hidden || this._unloaded || this.data.submitting || this._saveFlight || this._draftCleanupPending) return
    const { form, isEditing, editingTemplate } = this.data
    // rowKey 只用于本地行身份；接口仍只收商品和数量。
    const selectedItems = form.items.filter(i => i.productId)
    if (!selectedItems.length) { wx.showToast({ title: '请选择至少一种商品', icon: 'none' }); return }
    if (selectedItems.some(i => String(i.quantity).trim() === '' || !Number.isSafeInteger(Number(i.quantity)) || Number(i.quantity) < 1)) {
      this.setData({ saveError: '请填写大于零的整数数量。' }); return
    }
    const validItems = selectedItems.map(i => ({ productId: i.productId, quantity: Number(i.quantity) }))
    const session = captureSession()
    this._saveFlight = true
    this.setData({ submitting: true, saveError: '' })
    try {
      const stationId = this.data.stationId || await resolveStationId()
      if (!isCurrentSession(session) || this._unloaded) return
      if (!stationId) throw new Error('请先到订水页选择水站，再返回保存常用配置。保存配置不会下单。')
      await saveTemplate({ ...(isEditing ? { id: editingTemplate.id } : {}), name: form.name || '常用订单',
        addressId: form.addressId, specialNote: form.specialNote, items: validItems }, stationId)
      if (!isCurrentSession(session) || this._unloaded) return
      this._draftCleanupPending = true
      if (this.clearFormDirty()) { this._draftCleanupPending = false; this.setData({ showEditModal: false }) }
      else this.setData({ saveError: '配置已保存，但草稿尚未确认清除，请重试。' })
      wx.showToast({ title: '配置已保存', icon: 'success' })
      await this.loadData()
    } catch (error) {
      if (isCurrentSession(session) && !this._unloaded) this.setData({ saveError: viewError(error, '配置未能保存，输入已保留，请重试。') })
    } finally { this._saveFlight = false; if (!this._unloaded) this.setData({ submitting: false }) }
  },

  async onToggleTemplate(e) {
    if (this._mutation || this.data.loadError) return
    this._mutation = true
    const { id, enabled } = e.currentTarget.dataset
    const newEnabled = enabled === 1 ? 0 : 1
    try {
      await toggleTemplate(id, newEnabled)
      this.loadData()
    } catch (error) {
      // [2026-09-20] 原来只有一句「操作失败」，原因是丢掉的 —— utils/request.js 起 e.message
      // 已是可读文案（超时/连接失败分开说），带上它才能判断是重试还是该找站长（AGENTS §8.15 同款判据）
      console.error('[Template] 启用/停用失败:', error)
      wx.showToast({ title: '操作失败：' + viewError(error, '请重试'), icon: 'none' })
    } finally { this._mutation = false }
  },

  onDeleteTemplate(e) {
    if (this._mutation || this.data.loadError) return
    this._mutation = true
    const release = () => { this._mutation = false }
    const { id } = e.currentTarget.dataset
    showBusinessModal({ confirmColor: '#BD3328', cancelColor: '#637083',
      title: '确认删除',
      content: '删除后此配置不再保留；已有订单不受影响。', fail: release,
      success: async (res) => {
        if (!res.confirm) { release(); return }
        if (res.confirm) {
          try {
            await deleteTemplate(id)
            wx.showToast({ title: '删除成功', icon: 'success' })
            this.loadData()
          } catch (error) {
            // [2026-09-20] 同 onToggleTemplate：带上可读原因（见 utils/request.js 的 fail 归一化）
            console.error('[Template] 删除失败:', error)
            wx.showToast({ title: '删除失败：' + viewError(error, '请重试'), icon: 'none' })
          } finally { release() }
        }
      }
    })
  }
})
