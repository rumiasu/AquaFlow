const { getTemplates, saveTemplate, toggleTemplate, deleteTemplate } = require('../../api/template')
const { getProducts, getStationProducts } = require('../../api/product')
const { getAddresses } = require('../../api/address')
const { resolveStationId } = require('../../utils/station')
const { formatAddress } = require('../../utils/address')
const { captureSession, isCurrentSession } = require('../../utils/token')

// 只清理错误的展示文字；原对象的 code、resultUnknown、businessRejected 均不改动。
function viewError(error, fallback = '暂时无法完成，请稍后重试') {
  const text = error && typeof error.message === 'string' ? error.message.trim() : ''
  return /[\u3400-\u9fff]/.test(text)
    && !/(?:Exception|Error:|\bat\s+\S+\(|\bSQL\b|https?:\/\/|request:fail|errno|stack|[A-Z][A-Z_]{5,})/.test(text)
    ? text : fallback
}

Page({
  data: {
    loading: true, loaded: false, formLoading: false, formReady: false, submitting: false, saveError: '',
    templates: [],
    showEditModal: false,
    editingTemplate: null,
    isEditing: false,
    products: [],
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

  onLoad() {},

  onShow() {
    this._hidden = false
    if (this._session && !isCurrentSession(this._session)) this.setData({ templates: [], loaded: false, products: [], addresses: [], showEditModal: false })
    this._session = captureSession()
    return this.loadData()
  },
  onHide() { this._hidden = true; this._readSeq = (this._readSeq || 0) + 1; this._formSeq = (this._formSeq || 0) + 1 },
  onUnload() { this.onHide(); this._unloaded = true },
  async onPullDownRefresh() { try { await this.loadData() } finally { wx.stopPullDownRefresh() } },
  async loadData() {
    const session = captureSession(), seq = this._readSeq = (this._readSeq || 0) + 1
    const current = () => !this._hidden && !this._unloaded && seq === this._readSeq && isCurrentSession(session)
    this.setData({ loading: !this.data.loaded, loadError: '' })
    try {
      const sid = this.data.stationId || await resolveStationId()
      if (!current()) return
      if (!sid) throw new Error('请先选择水站，再查看本站常用订水配置')
      const res = await getTemplates(sid)
      if (!current()) return
      if (!res || res.code !== 0 || !Array.isArray(res.data)) throw new Error('常用订水配置暂未加载，请重试')
      this.setData({ templates: res.data, stationId: sid, loaded: true, loadError: '' })
    } catch (error) {
      if (!current()) return
      const denied = /无权|权限|登录/.test(error && error.message || '')
      if (denied) this.setData({ templates: [], loaded: false })
      this.setData({ loadError: (this.data.loaded ? '刷新失败，以下为上次读取的配置。' : '') + viewError(error, '常用订水配置暂未加载，请重试。') })
    } finally { if (current()) this.setData({ loading: false }) }
  },
  onRetry() { return this.loadData() },

  onAddTemplate() {
    if (this.data.submitting) return
    this.setData({
      showEditModal: true, saveError: '', formReady: false,
      isEditing: false,
      editingTemplate: null,
      form: {
        name: '',
        addressId: null,
        specialNote: '',
        items: [{ productId: null, quantity: 1 }]
      }
    })
    this.loadFormData()
  },

  onEditTemplate(e) {
    if (this.data.submitting || this.data.loadError) return
    const { template } = e.currentTarget.dataset
    const items = (template.items && template.items.length > 0)
      ? template.items.map(i => ({ productId: i.productId || i.waterTypeId, quantity: i.quantity || 1 }))
      : [{ productId: null, quantity: 1 }]
    this.setData({
      showEditModal: true, saveError: '', formReady: false,
      isEditing: true,
      editingTemplate: template,
      form: {
        name: template.name || '',
        addressId: template.addressId,
        specialNote: template.specialNote || '',
        items
      }
    })
    this.loadFormData()
  },

  async loadFormData() {
    if (this.data.formLoading || this.data.submitting) return
    const session = captureSession(), seq = this._formSeq = (this._formSeq || 0) + 1
    const current = () => !this._unloaded && !this._hidden && this.data.showEditModal && seq === this._formSeq && isCurrentSession(session)
    this.setData({ formLoading: true, formReady: false, formLoadError: '' })
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
      this.setData({ products: productRes.data, stationId: currentStationId })
    }
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

  onNameInput(e) {
    if (this.data.submitting) return
    this.setData({ 'form.name': e.detail.value })
  },

  onAddItem() {
    if (this.data.submitting) return
    const items = this.data.form.items.concat([{ productId: null, quantity: 1 }])
    this.setData({ 'form.items': items })
  },

  onRemoveItem(e) {
    if (this.data.submitting) return
    const { index } = e.currentTarget.dataset
    const items = this.data.form.items.filter((_, i) => i !== index)
    if (items.length === 0) items.push({ productId: null, quantity: 1 })
    this.setData({ 'form.items': items })
  },

  onSelectProduct(e) {
    if (this.data.submitting) return
    const { index, id } = e.currentTarget.dataset
    this.setData({ [`form.items[${index}].productId`]: parseInt(id) })
  },

  onQuantityChange(e) {
    if (this.data.submitting) return
    const { index, type } = e.currentTarget.dataset
    let items = this.data.form.items
    let qty = items[index].quantity || 1
    if (type === 'add') qty++
    else if (type === 'minus' && qty > 1) qty--
    this.setData({ [`form.items[${index}].quantity`]: qty })
  },

  onQuantityInput(e) {
    if (this.data.submitting) return
    const { index } = e.currentTarget.dataset
    const qty = parseInt(e.detail.value) || 1
    this.setData({ [`form.items[${index}].quantity`]: Math.max(1, qty) })
  },

  onSelectAddress(e) {
    if (this.data.submitting) return
    const { id } = e.currentTarget.dataset
    this.setData({ 'form.addressId': parseInt(id) })
  },

  onNoteInput(e) {
    if (this.data.submitting) return
    this.setData({ 'form.specialNote': e.detail.value })
  },

  onCloseEditModal() {
    if (this.data.submitting) return
    this._formSeq = (this._formSeq || 0) + 1
    this.setData({ showEditModal: false, formLoading: false })
  },

  async onSaveTemplate() {
    if (this.data.submitting || this._saveFlight) return
    const { form, isEditing, editingTemplate } = this.data
    const validItems = form.items.filter(i => i.productId)
    if (!validItems.length) { wx.showToast({ title: '请选择至少一种商品', icon: 'none' }); return }
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
      this.setData({ showEditModal: false })
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
    wx.showModal({
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
