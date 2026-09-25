const { getTemplates, saveTemplate, toggleTemplate, deleteTemplate } = require('../../api/template')
const { getProducts, getStationProducts } = require('../../api/product')
const { getAddresses } = require('../../api/address')
const { resolveStationId } = require('../../utils/station')
const { formatAddress } = require('../../utils/address')

Page({
  data: {
    loading: true,
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
    this.loadData()
  },

  async loadData() {
    this.setData({ loading: true })
    try {
      const sid = this.data.stationId || await resolveStationId()
      // [2026-09-20 真机联调] 原来是 `.catch(() => null)`：模板列表拉不到时 templates 为空数组，
      // 页面渲染成「还没有常用订单」—— 与"这个客户确实没设过常用订单"完全无法区分（AGENTS §8.22），
      // 客户会以为自己的常用订单丢了，转头去重设一遍。
      let templates = []
      let templateError = ''
      if (sid) {
        try {
          const tplRes = await getTemplates(sid)
          if (tplRes && tplRes.code === 0 && tplRes.data) {
            templates = tplRes.data
          } else {
            templateError = (tplRes && tplRes.message) || '服务端返回异常'
          }
        } catch (e) {
          templateError = (e && e.message) || '网络异常'
        }
      }
      if (templateError) {
        console.warn('[Template] 常用订单列表加载失败:', templateError)
        // 失败时保留上一次的列表内容不动（不清空），只把失败说出来
        this.setData({ loadError: '常用订单没加载出来（' + templateError + '），下面是旧内容，请下拉刷新' })
        wx.showToast({ title: '常用订单没加载出来，请下拉刷新', icon: 'none' })
      } else {
        this.setData({ templates, loadError: '' })
      }
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
        items: [{ productId: null, quantity: 1 }]
      }
    })
    this.loadFormData()
  },

  onEditTemplate(e) {
    const { template } = e.currentTarget.dataset
    const items = (template.items && template.items.length > 0)
      ? template.items.map(i => ({ productId: i.productId || i.waterTypeId, quantity: i.quantity || 1 }))
      : [{ productId: null, quantity: 1 }]
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
        formLoadError = (e && e.message) || '网络异常'
        console.warn('[Template] 商品列表加载失败:', e && (e.message || e.errMsg))
      }
    }
    if (!productRes || !productRes.data) {
      // 没有选水站时的兜底：只取**通用库**商品（带 stationId 才能看到本站自定义商品；
      // 模板页只需要 id/name，不显示价格，所以这里不涉及站级价）。
      try {
        productRes = await getProducts(currentStationId ? { stationId: currentStationId } : {})
      } catch (e) {
        formLoadError = formLoadError || (e && e.message) || '网络异常'
        console.warn('[Template] 通用库商品加载失败:', e && (e.message || e.errMsg))
      }
    }
    let addressRes = null
    try {
      addressRes = await getAddresses()
    } catch (e) {
      formLoadError = formLoadError || (e && e.message) || '网络异常'
      console.warn('[Template] 收货地址加载失败:', e && (e.message || e.errMsg))
    }

    if (productRes && productRes.data) {
      this.setData({ products: productRes.data, stationId: currentStationId })
    }
    if (addressRes && addressRes.data) {
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
        ? '商品/地址没加载出来（' + formLoadError + '），下面可能是空的，请关掉弹窗重新打开'
        : ''
    })
  },

  onNameInput(e) {
    this.setData({ 'form.name': e.detail.value })
  },

  onAddItem() {
    const items = this.data.form.items.concat([{ productId: null, quantity: 1 }])
    this.setData({ 'form.items': items })
  },

  onRemoveItem(e) {
    const { index } = e.currentTarget.dataset
    const items = this.data.form.items.filter((_, i) => i !== index)
    if (items.length === 0) items.push({ productId: null, quantity: 1 })
    this.setData({ 'form.items': items })
  },

  onSelectProduct(e) {
    const { index, id } = e.currentTarget.dataset
    this.setData({ [`form.items[${index}].productId`]: parseInt(id) })
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
    const validItems = form.items.filter(i => i.productId)
    if (validItems.length === 0) {
      wx.showToast({ title: '请选择至少一种商品', icon: 'none' })
      return
    }
    // 水站是模板的归属维度（order_template 按站隔离），拿不到就必须挡住并说明原因，
    // 而不是把 station_id 静默存成空。
    const stationId = this.data.stationId || await resolveStationId()
    if (!stationId) {
      // 不逼用户当场去选站：下次下单时 order/success 页会按那次下单的水站把常用订单存好。
      wx.showModal({
        title: '还没确定水站',
        content: '常用订单是按水站分开保存的，现在还不知道该存到哪个水站。\n\n不用特意去选——你直接去下单，系统会按那次下单的水站自动把常用订单存好。',
        confirmText: '知道了',
        showCancel: false
      })
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
      await saveTemplate(payload, stationId)
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
      // [2026-09-20] 原来只有一句「操作失败」，原因是丢掉的 —— utils/request.js 起 e.message
      // 已是可读文案（超时/连接失败分开说），带上它才能判断是重试还是该找站长（AGENTS §8.15 同款判据）
      console.error('[Template] 启用/停用失败:', error)
      wx.showToast({ title: '操作失败：' + ((error && error.message) || '请重试'), icon: 'none' })
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
            // [2026-09-20] 同 onToggleTemplate：带上可读原因（见 utils/request.js 的 fail 归一化）
            console.error('[Template] 删除失败:', error)
            wx.showToast({ title: '删除失败：' + ((error && error.message) || '请重试'), icon: 'none' })
          }
        }
      }
    })
  }
})
