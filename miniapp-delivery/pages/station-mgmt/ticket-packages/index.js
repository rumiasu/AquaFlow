// ⚠️ 直接用 utils/request，不引 api/station-mgmt.js 与 config/api.js：
// 那两个文件正被另一个工作流（商品图片库）改动，共用会让两边未提交的改动纠缠在一起。
// 路径常量写在本文件里，理由同上。
const { get, post, del } = require('../../../utils/request')

const PACKAGES_MANAGE = '/api/ticket-packages/manage'
const PACKAGES = '/api/ticket-packages'
const PRESETS = '/api/ticket-packages/presets'
const PRODUCTS = '/api/products/sale-by-station'

/**
 * 统一水票（站级通用票）的伪商品 id —— **必须与后端 util/TicketScope.UNIFIED_PRODUCT_ID 一致**。
 *
 * 它在 product 表里没有对应行：统一票是"本站桶装水通用"的一张票，
 * 不挂在任何具体商品上；后端也是靠 `product_id = 0` 表达站级通用账户/档位。
 */
const UNIFIED_PRODUCT_ID = 0

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
 *
 * [v54 统一水票] 商品选择里多了一个**伪商品**「统一水票（站级通用）」（id = 0）：
 * 给本站挂统一票档位（如 10 张 9.5 折、30 张 9 折）。它就是统一票的**站级开关** ——
 * 上架了档位 = 开通，全部下架/删除 = 关闭（后端没有另设开关列，避免两处真值打架）。
 * 抵扣顺序由后端裁定：客户有该商品的定制票就用定制，没有才落到统一票
 * （见 docs/design/26 §26.0）。
 */
Page({
  data: {
    stationId: null,
    loading: true,
    products: [],
    productId: '',
    productName: '',
    /** 当前选中的是不是「统一水票（站级通用）」伪商品 —— 只影响文案，不影响提交 */
    isUnified: false,
    packages: [],
    // 平台预设档（10 / 20 / 100 张三档，折扣与总价一律由后端算好下发）。
    // 它只是「一键填入」的草稿 —— 点了只填表单，不自动保存；站长能改数字再存。
    presets: [],
    presetBasePrice: null,
    presetBasis: '',
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
    this.loadPresets()
  },

  /**
   * 平台预设档（统一水票专用）。只读接口，**不会**替本站建档位 ——
   * "有上架的统一票档位"就是统一水票的站级开关，系统不替站长做这个决定。
   *
   * 拉不到就静默降级为空（站长仍能自己填价），不弹错误 ——
   * 这只是一个方便填写的草稿，不该挡住主流程。
   */
  async loadPresets() {
    try {
      const res = await get(PRESETS)
      const d = (res && res.data) || {}
      this.setData({
        presets: d.presets || [],
        presetBasePrice: d.baseUnitPrice != null ? d.baseUnitPrice : null,
        presetBasis: d.basePriceBasis || ''
      })
    } catch (err) {
      console.warn('[ticket-package presets] load failed:', err && err.message)
      this.setData({ presets: [], presetBasePrice: null, presetBasis: '' })
    }
  },

  /**
   * 一键填入预设档：**只填表单、不保存**。
   *
   * 张数/总价/名称全部用服务端下发的值，前端不做 price/qty 的算术 ——
   * 均价是快照进水票批次的值，前端算一遍就会出现"界面一个价、批次另一个价"。
   * 填完站长还能改，确认无误再点「保存档位」。
   */
  onApplyPreset(e) {
    const id = Number(e.currentTarget.dataset.id)
    const p = this.data.presets.find(x => Number(x.qty) === id)
    if (!p) return
    this.setData({
      'form.qty': String(p.qty),
      'form.price': String(p.price),
      'form.title': p.title || ''
    })
    wx.showToast({ title: '已填入，可修改后保存', icon: 'none' })
  },

  async loadProducts() {
    this.setData({ loading: true })
    try {
      const res = await get(PRODUCTS + '?stationId=' + this.data.stationId)
      // [v54] 统一水票放在最前面：它是站级设置，不是"某个商品的档位"。
      // 后端按 productId=0 收档位，所以这里只要给个 id=0 的伪条目，其余流程完全复用。
      const unified = { id: UNIFIED_PRODUCT_ID, name: '统一水票（站级通用）', unified: true }
      this.setData({ products: [unified].concat(res.data || []) })
    } catch (err) {
      wx.showToast({ title: err.message || '商品加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  async onPickProduct(e) {
    const id = Number(e.currentTarget.dataset.id)
    const p = this.data.products.find(x => x.id === id)
    this.setData({
      productId: String(id),
      productName: p ? p.name : '',
      isUnified: id === UNIFIED_PRODUCT_ID
    })
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
        '顾客在小程序里买票时会看到这些档位；没挂档位就还是按单张水票价散买（统一水票除外，' +
        '它只能按档位买）。\n\n' +
        '【统一水票（站级通用）】一张票抵一桶，本站桶装水通用，不挑品牌。' +
        '客户手里有某个商品的定制票时先用定制票，没有才用统一票。' +
        '挂上档位就等于开通，全部下架就等于关闭。',
      showCancel: false
    })
  }
})
