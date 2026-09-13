const {
  getManagerProducts,
  createManagerProduct,
  updateManagerProduct,
  deleteManagerProduct
} = require('../../../api/station-mgmt')
const { upload } = require('../../../utils/upload')
const { API } = require('../../../config/api')

const CATEGORY_MAP = [
  { value: 1, name: '桶装水' },
  { value: 2, name: '瓶装水' },
  { value: 3, name: '饮水器' }
]
const CATEGORY_NAMES = CATEGORY_MAP.map(c => c.name)

Page({
  data: {
    list: [],
    filterStatus: null,
    showEdit: false,
    isAdd: false,
    editId: null,
    editForm: {},
    categoryNames: CATEGORY_NAMES,
    saving: false,
    deleting: false,
    uploading: false
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this.loadData()
  },

  onPullDownRefresh() {
    this.loadData().then(() => wx.stopPullDownRefresh())
  },

  async loadData() {
    try {
      const res = await getManagerProducts()
      let list = res.data || []
      // 前端过滤
      if (this.data.filterStatus !== null) {
        list = list.filter(i => i.status === this.data.filterStatus)
      }
      this.setData({ list })
    } catch (err) {
      wx.showToast({ title: err.message || '加载失败', icon: 'none' })
    }
  },

  onFilter(e) {
    const raw = e.currentTarget.dataset.status
    const status = raw === '' || raw === undefined ? null : Number(raw)
    this.setData({ filterStatus: status }, () => this.loadData())
  },

  async onTogglePriority(e) {
    const { id, value } = e.currentTarget.dataset
    const newValue = value === 1 ? 0 : 1
    try {
      const { post } = require('../../../utils/request')
      await post(`/api/manager/products/${id}/priority?enabled=${newValue}`)
      const list = this.data.list.map(item => {
        if (item.id === id) return { ...item, priorityDisplay: newValue }
        return item
      })
      this.setData({ list })
      wx.showToast({ title: newValue === 1 ? '已设为优先展示' : '已取消优先展示', icon: 'success' })
    } catch (err) {
      wx.showToast({ title: err.message || '操作失败', icon: 'none' })
    }
  },

  // ===== 新增 =====
  openAdd() {
    this.setData({
      isAdd: true,
      editId: null,
      editForm: {
        name: '',
        brand: '',
        spec: '',
        category: 1,
        categoryIndex: 0,
        price: '',
        deposit: '',
        quantity: '',
        salePrice: '',
        enabled: 1,
        ticketEnabled: 0,
        ticketPrice: '',
        imageUrl: ''
      },
      showEdit: true
    })
  },

  // ===== 编辑 =====
  openEdit(e) {
    const item = e.currentTarget.dataset.item
    const catIdx = CATEGORY_MAP.findIndex(c => c.value === item.category)
    this.setData({
      isAdd: false,
      editId: item.id,
      editForm: {
        name: item.name || '',
        brand: item.brand || '',
        spec: item.spec || '',
        category: item.category || 1,
        categoryIndex: catIdx >= 0 ? catIdx : 0,
        price: item.price != null ? String(item.price) : '',
        deposit: item.deposit != null ? String(item.deposit) : '',
        quantity: item.quantity != null ? String(item.quantity) : '0',
        salePrice: item.salePrice != null ? String(item.salePrice) : '',
        enabled: item.enabled != null ? item.enabled : 0,
        ticketEnabled: item.ticketEnabled != null ? item.ticketEnabled : 0,
        ticketPrice: item.ticketPrice != null ? String(item.ticketPrice) : '',
        imageUrl: item.imageUrl || ''
      },
      showEdit: true
    })
  },

  closeEdit() {
    this.setData({ showEdit: false })
  },

  stopPropagation() {},

  /** 弹窗遮罩上吞掉 touchmove，防止滚动穿透到页面（wxml 用 catchtouchmove） */
  preventMove() {},

  onFieldChange(e) {
    const { field } = e.currentTarget.dataset
    this.setData({ ['editForm.' + field]: e.detail.value })
  },

  onCategoryChange(e) {
    const idx = Number(e.detail.value)
    const newCategory = CATEGORY_MAP[idx].value
    const updates = {
      'editForm.categoryIndex': idx,
      'editForm.category': newCategory
    }
    if (newCategory !== 1) {
      updates['editForm.deposit'] = ''
    }
    if (newCategory === 3) {
      updates['editForm.ticketEnabled'] = 0
      updates['editForm.ticketPrice'] = ''
    }
    this.setData(updates)
  },

  onSwitchChange(e) {
    const { field } = e.currentTarget.dataset
    this.setData({ ['editForm.' + field]: e.detail.value ? 1 : 0 })
  },

  // ===== 保存 (新增/编辑) =====
  async onSave() {
    const { isAdd, editId, editForm } = this.data
    if (!editForm.name || !editForm.name.trim()) {
      wx.showToast({ title: '商品名称不能为空', icon: 'none' })
      return
    }
    if (!editForm.price || isNaN(parseFloat(editForm.price))) {
      wx.showToast({ title: '请输入正确的售价', icon: 'none' })
      return
    }

    const payload = {
      name: editForm.name.trim(),
      brand: editForm.brand || '',
      spec: editForm.spec || '',
      category: editForm.category || 1,
      price: parseFloat(editForm.price) || 0,
      deposit: parseFloat(editForm.deposit) || 0,
      quantity: parseInt(editForm.quantity) || 0,
      enabled: editForm.enabled || 0,
      ticketEnabled: editForm.ticketEnabled || 0,
      imageUrl: editForm.imageUrl || ''
    }
    // 本站售价 (有值才传)
    if (editForm.salePrice && !isNaN(parseFloat(editForm.salePrice))) {
      payload.salePrice = parseFloat(editForm.salePrice)
    }
    // 水票价格 (推出水票时才传)
    if (editForm.ticketEnabled === 1 && editForm.ticketPrice && !isNaN(parseFloat(editForm.ticketPrice))) {
      payload.ticketPrice = parseFloat(editForm.ticketPrice)
    }

    this.setData({ saving: true })
    try {
      if (isAdd) {
        await createManagerProduct(payload)
        wx.showToast({ title: '创建成功', icon: 'success' })
      } else {
        await updateManagerProduct(editId, payload)
        wx.showToast({ title: '保存成功', icon: 'success' })
      }
      this.closeEdit()
      this.loadData()
    } catch (err) {
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ saving: false })
    }
  },

  // ===== 停用 (软删除) =====
  // 注意：wx.showModal 是**回调式** API，不是 Promise。写成 `await wx.showModal(...)` 解构 confirm
  // 会恒得 undefined（其余 45 处调用都是 success 回调式，本项目没有做 promisify），
  // 表现为「点了停用没反应」。这里统一回回调式。
  onDelete() {
    const { editId } = this.data
    if (!editId) return
    wx.showModal({
      title: '停用商品',
      content: '停用后客户将无法看到该商品，历史订单不受影响。确认停用？',
      confirmText: '停用',
      confirmColor: '#f44336',
      success: (res) => {
        if (res.confirm) this._doDelete(editId)
      }
    })
  },

  async _doDelete(editId) {
    this.setData({ deleting: true })
    try {
      await deleteManagerProduct(editId)
      wx.showToast({ title: '已停用', icon: 'success' })
      this.closeEdit()
      this.loadData()
    } catch (err) {
      wx.showToast({ title: err.message || '操作失败', icon: 'none' })
    } finally {
      this.setData({ deleting: false })
    }
  },

  // ===== 上传图片 =====
  async uploadImage() {
    wx.chooseImage({
      count: 1,
      success: async (res) => {
        this.setData({ uploading: true })
        try {
          const tempFile = res.tempFilePaths[0]
          const uploadRes = await upload({
            filePath: tempFile,
            url: API.GENERAL_UPLOAD,
            name: 'file'
          })
          if (uploadRes.code === 0) {
            this.setData({ 'editForm.imageUrl': uploadRes.data })
            wx.showToast({ title: '图片上传成功', icon: 'success' })
          } else {
            wx.showToast({ title: uploadRes.message || '上传失败', icon: 'none' })
          }
        } catch (err) {
          wx.showToast({ title: err.message || '上传失败', icon: 'none' })
        } finally {
          this.setData({ uploading: false })
        }
      }
    })
  }
})
