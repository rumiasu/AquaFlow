const { getTemplates, saveTemplate, toggleTemplate, deleteTemplate } = require('../../api/template')
const { getWaterTypes } = require('../../api/product')
const { getAddresses } = require('../../api/address')

Page({
  data: {
    loading: true,
    templates: [],
    showEditModal: false,
    editingTemplate: null,
    isEditing: false,
    products: [],
    addresses: [],
    form: {
      name: '',
      addressId: null,
      specialNote: '',
      items: []
    }
  },

  onLoad() {},

  onShow() {
    this.loadData()
  },

  async loadData() {
    this.setData({ loading: true })
    try {
      const tplRes = await getTemplates().catch(() => null)
      let templates = []
      if (tplRes && tplRes.data) {
        templates = tplRes.data
      }
      this.setData({ templates })
    } finally {
      this.setData({ loading: false })
    }
  },

  onAddTemplate() {
    this.setData({
      showEditModal: true,
      isEditing: false,
      editingTemplate: null,
      form: {
        name: '',
        addressId: null,
        specialNote: '',
        items: [{ waterTypeId: null, quantity: 1 }]
      }
    })
    this.loadFormData()
  },

  onEditTemplate(e) {
    const { template } = e.currentTarget.dataset
    const items = (template.items && template.items.length > 0)
      ? template.items.map(i => ({ waterTypeId: i.waterTypeId, quantity: i.quantity || 1 }))
      : [{ waterTypeId: null, quantity: 1 }]
    this.setData({
      showEditModal: true,
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
    const [productRes, addressRes] = await Promise.all([
      getWaterTypes().catch(() => null),
      getAddresses().catch(() => null)
    ])
    if (productRes && productRes.data) {
      this.setData({ products: productRes.data })
    }
    if (addressRes && addressRes.data) {
      const addresses = addressRes.data
      const defaultAddr = addresses.find(a => a.isDefault) || addresses[0]
      this.setData({
        addresses,
        'form.addressId': this.data.form.addressId || (defaultAddr ? defaultAddr.id : null)
      })
    }
  },

  onNameInput(e) {
    this.setData({ 'form.name': e.detail.value })
  },

  onAddItem() {
    const items = this.data.form.items.concat([{ waterTypeId: null, quantity: 1 }])
    this.setData({ 'form.items': items })
  },

  onRemoveItem(e) {
    const { index } = e.currentTarget.dataset
    const items = this.data.form.items.filter((_, i) => i !== index)
    if (items.length === 0) items.push({ waterTypeId: null, quantity: 1 })
    this.setData({ 'form.items': items })
  },

  onSelectProduct(e) {
    const { index, id } = e.currentTarget.dataset
    this.setData({ [`form.items[${index}].waterTypeId`]: parseInt(id) })
  },

  onQuantityChange(e) {
    const { index, type } = e.currentTarget.dataset
    let items = this.data.form.items
    let qty = items[index].quantity || 1
    if (type === 'add') qty++
    else if (type === 'minus' && qty > 1) qty--
    this.setData({ [`form.items[${index}].quantity`]: qty })
  },

  onQuantityInput(e) {
    const { index } = e.currentTarget.dataset
    const qty = parseInt(e.detail.value) || 1
    this.setData({ [`form.items[${index}].quantity`]: Math.max(1, qty) })
  },

  onSelectAddress(e) {
    const { id } = e.currentTarget.dataset
    this.setData({ 'form.addressId': parseInt(id) })
  },

  onNoteInput(e) {
    this.setData({ 'form.specialNote': e.detail.value })
  },

  onCloseEditModal() {
    this.setData({ showEditModal: false })
  },

  async onSaveTemplate() {
    const { form, isEditing, editingTemplate } = this.data
    const validItems = form.items.filter(i => i.waterTypeId)
    if (validItems.length === 0) {
      wx.showToast({ title: '请选择至少一种水', icon: 'none' })
      return
    }

    const payload = {
      ...(isEditing ? { id: editingTemplate.id } : {}),
      name: form.name || '常用订单',
      addressId: form.addressId,
      specialNote: form.specialNote,
      items: validItems
    }

    try {
      await saveTemplate(payload)
      wx.showToast({ title: '保存成功', icon: 'success' })
      this.setData({ showEditModal: false })
      this.loadData()
    } catch (error) {
      console.error('[Template] 保存失败:', error)
      wx.showToast({ title: '保存失败: ' + (error.message || ''), icon: 'none', duration: 3000 })
    }
  },

  async onToggleTemplate(e) {
    const { id, enabled } = e.currentTarget.dataset
    const newEnabled = enabled === 1 ? 0 : 1
    try {
      await toggleTemplate(id, newEnabled)
      this.loadData()
    } catch (error) {
      wx.showToast({ title: '操作失败', icon: 'none' })
    }
  },

  onDeleteTemplate(e) {
    const { id } = e.currentTarget.dataset
    wx.showModal({
      title: '确认删除',
      content: '确定要删除这个常用订单吗？',
      success: async (res) => {
        if (res.confirm) {
          try {
            await deleteTemplate(id)
            wx.showToast({ title: '删除成功', icon: 'success' })
            this.loadData()
          } catch (error) {
            wx.showToast({ title: '删除失败', icon: 'none' })
          }
        }
      }
    })
  }
})
