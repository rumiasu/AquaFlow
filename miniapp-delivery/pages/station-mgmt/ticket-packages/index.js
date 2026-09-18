// ⚠️ 直接用 utils/request，不引 api/station-mgmt.js 与 config/api.js：
// 那两个文件正被另一个工作流（商品图片库）改动，共用会让两边未提交的改动纠缠在一起。
// 路径常量写在本文件里，理由同上。
const { get, post, del } = require('../../../utils/request')

const PACKAGES_MANAGE = '/api/ticket-packages/manage'
const PACKAGES = '/api/ticket-packages'
const PRODUCTS = '/api/products/sale-by-station'

/**
 * 站长端「水票档位」：给本站商品挂一份价目表（10 张 / 20 张 / 100 张，越买越便宜）。
 * 规格见 docs/design/19；产品决定见 docs/design/16 §1（"水票只提供档位"）。
 *
 * 三条口径（改这个页面时必须守住）：
 *   1. **档位是定价结构，不是促销活动** —— 永远可买、不叠加、不互斥。所以这里只有
 *      最朴素的增删改，没有活动、优先级、有效期。
 *   2. **均价（unitPrice）由服务端算**（price / qty），前端不传也不算。
 *      它是**快照进水票批次的值**，必须"展示与快照同源"；前端自己算就会出现
 *      "界面显示 8.00、快照存 8.33"。所以本页在保存**之后**才显示均价（用服务端返回值）。
 *   3. **删档位不影响已售出的票** —— 客户账户里的票由 ticket_lot 记着单价快照，
 *      删价目表不改它们的价值与退票口径。别在这里做"删档位要退差价"。
 *
 * ⚠️ 档位是**站级 + 商品级**的：切换商品才能看到该商品的档位；同一张数重复保存即更新（upsert）。
 */
Page({
  data: {
    stationId: null,
    loading: true,
    products: [],
    productId: '',
    productName: '',
    packages: [],
    form: { qty: '', price: '', title: '', sort: '' },
    saving: false
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    const stationId = (app.globalData.userInfo || {}).stationId
    if (!stationId) {
      wx.showToast({ title: '未识别到所属水站', icon: 'none' })
      return
    }
    this.setData({ stationId })
    this.loadProducts()
  },

  async loadProducts() {
    this.setData({ loading: true })
    try {
      const res = await get(PRODUCTS + '?stationId=' + this.data.stationId)
      this.setData({ products: res.data || [] })
    } catch (err) {
      wx.showToast({ title: err.message || '商品加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  async onPickProduct(e) {
    const id = Number(e.currentTarget.dataset.id)
    const p = this.data.products.find(x => x.id === id)
    this.setData({ productId: String(id), productName: p ? p.name : '' })
    await this.loadPackages()
  },

  async loadPackages() {
    this.setData({ loading: true })
    try {
      const res = await get(PACKAGES_MANAGE + '?productId=' + this.data.productId)
      const p = this.data.products.find(x => String(x.id) === this.data.productId)
      // 散买单价（站级水票价）只用于算"参考节省"这个展示值；
      // 档位自己的 price / unitPrice 一律用服务端返回值，不由前端推导。
      const looseUnit = p && p.ticketPrice != null ? Number(p.ticketPrice) : null
      const packages = (res.data || []).map(x => {
        const unit = x.unitPrice != null ? Number(x.unitPrice) : null
        let saveText = ''
        if (looseUnit !== null && unit !== null && looseUnit > 0) {
          const save = (looseUnit - unit) * x.qty
          // toFixed 只影响展示；权威金额仍是服务端的 price
          saveText = save > 0 ? '比散买省 ¥' + save.toFixed(2) : '与散买同价'
        }
        return Object.assign({}, x, {
          statusText: x.status === 1 ? '上架' : '已下架',
          saveText
        })
      })
      this.setData({ packages })
    } catch (err) {
      wx.showToast({ title: err.message || '档位加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  onInput(e) {
    this.setData({ ['form.' + e.currentTarget.dataset.field]: e.detail.value })
  },

  async onSave() {
    if (this.data.saving) return
    if (!this.data.productId) {
      wx.showToast({ title: '请先选择商品', icon: 'none' })
      return
    }
    const f = this.data.form
    const qty = Number(f.qty)
    const price = Number(f.price)
    if (!qty || qty <= 0) {
      wx.showToast({ title: '请填档位张数', icon: 'none' })
      return
    }
    if (!price || price <= 0) {
      wx.showToast({ title: '请填档位总价', icon: 'none' })
      return
    }

    this.setData({ saving: true })
    try {
      // 不传 unitPrice：服务端按 price / qty 算并快照
      const res = await post(PACKAGES, {
        productId: Number(this.data.productId),
        qty,
        price,
        title: f.title || null,
        sort: f.sort === '' ? 0 : Number(f.sort),
        status: 1
      })
      const saved = res.data || {}
      wx.showToast({ title: '已保存（均价 ¥' + saved.unitPrice + '）', icon: 'none' })
      this.setData({ form: { qty: '', price: '', title: '', sort: '' } })
      await this.loadPackages()
    } catch (err) {
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ saving: false })
    }
  },

  /** 上架/下架：没有单独的状态接口，按 (站, 商品, 张数) upsert 重存一次即可。 */
  async onToggleStatus(e) {
    const id = Number(e.currentTarget.dataset.id)
    const p = this.data.packages.find(x => x.id === id)
    if (!p) return
    try {
      await post(PACKAGES, {
        productId: p.productId,
        qty: p.qty,
        price: p.price,
        title: p.title,
        sort: p.sort,
        status: p.status === 1 ? 0 : 1
      })
      wx.showToast({ title: p.status === 1 ? '已下架' : '已上架', icon: 'success' })
      await this.loadPackages()
    } catch (err) {
      wx.showToast({ title: err.message || '操作失败', icon: 'none' })
    }
  },

  onDelete(e) {
    const id = Number(e.currentTarget.dataset.id)
    wx.showModal({
      title: '删除这个档位？',
      content: '只删价目表这一行。已经卖出去的水票不受影响 —— 它们的单价在批次里另存了快照。',
      success: async (r) => {
        if (!r.confirm) return
        try {
          await del(PACKAGES + '/' + id)
          wx.showToast({ title: '已删除', icon: 'success' })
          await this.loadPackages()
        } catch (err) {
          wx.showToast({ title: err.message || '删除失败', icon: 'none' })
        }
      }
    })
  },

  onHelp() {
    wx.showModal({
      title: '档位怎么定',
      content: '档位就是价目表：挂"10 张 / 20 张 / 100 张"各卖多少钱，越买越便宜。' +
        '它是定价结构不是促销 —— 永远可买、不叠加、不互斥。\n\n' +
        '均价由系统按「总价 ÷ 张数」算并写进客户买票时的批次快照，' +
        '所以界面上显示的均价就是将来退票的依据，不会出现"按新价退旧票"。\n\n' +
        '顾客在小程序里买票时会看到这些档位；没挂档位就还是按单张水票价散买。',
      showCancel: false
    })
  }
})
