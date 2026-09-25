// ⚠️ 直接用 utils/request，不引 api/station-mgmt.js 与 config/api.js：
// 那两个文件正被另一个工作流（商品图片库）改动，共用会让两边未提交的改动纠缠在一起。
// 路径常量写在本文件里，理由同上。
const { get, post, del } = require('../../../utils/request')

const PACKAGES_MANAGE = '/api/ticket-packages/manage'
const PACKAGES = '/api/ticket-packages'
const DISCOUNTS = '/api/ticket-discounts'
const DISCOUNT_PRESETS = '/api/ticket-discounts/presets'
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
 *
 * ============================ 站级「统一折扣」（v58，2026-09-20） ============================
 * 产品口径：「统一水票，**在站长端是特殊化的**，但在**用户端看起来没区别**，执行上
 * **也不是统一定价**，而是**对应水怎么统一打折、统一打几折**的区别，**不是专门卖统一水票**。」
 *
 * 所以本页的"统一水票"入口（橙色 chip）实际配的是**折扣**，不是价格：
 *   · 站长在这里配"买 N 张打几折"（如 10 张 9.5 折、30 张 9 折）—— 这就是"特殊化"的那部分；
 *   · **价格由后端按各款水自己的价现算**（农夫山泉按农夫山泉的价、娃哈哈按娃哈哈的价），
 *     所以这一页**没有"总价"输入框**，也不显示绝对金额；
 *   · 顾客端看不到"统一水票"这个商品，只是在买**某款水**的票时多出几个折扣档；
 *   · 该商品自己开了定制票（商品设置里的水票开关）时**以定制档位为准**（定制优先）。
 * 判据唯一实现在后端 `TicketTierService`，前端不重写。
 * ==========================================================================================
 */
Page({
  data: {
    stationId: null,
    loading: true,
    products: [],
    productId: '',
    productName: '',
    /** 当前选中的是不是站级「统一折扣」入口 —— 决定表单与列表渲染哪一套 */
    isUnified: false,
    packages: [],
    /** 站级统一折扣档（含已下架）：{qty, discountPerMille, discountText, title, statusText, sort} */
    discounts: [],
    // 平台预设档（10 / 20 / 100 张三档的**折扣**，由后端下发）。
    // 它只是「一键填入」的草稿 —— 点了只填表单，不自动保存；站长能改数字再存。
    presets: [],
    form: { qty: '', price: '', discount: '', title: '', sort: '' },
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
      const res = await get(DISCOUNT_PRESETS)
      const d = (res && res.data) || {}
      this.setData({ presets: d.presets || [] })
    } catch (err) {
      console.warn('[ticket-discount presets] load failed:', err && err.message)
      this.setData({ presets: [] })
    }
  },

  /**
   * 一键填入预设档：**只填表单、不保存**。
   *
   * 填的是**折扣**（千分比 950 → 界面上的「9.5」折）与张数/名称，全部用服务端下发的值。
   * ⚠️ 这里**没有总价**：价格要按各款水自己的价现算，存不下来也不该由前端算。
   */
  onApplyPreset(e) {
    const id = Number(e.currentTarget.dataset.id)
    const p = this.data.presets.find(x => Number(x.qty) === id)
    if (!p) return
    this.setData({
      'form.qty': String(p.qty),
      // 千分比 → 界面上的"折"：950 → 9.5。反着算回千分比时乘 100 再取整（见 onSave）
      'form.discount': String((Number(p.discountPerMille) || 0) / 100),
      'form.title': p.title || ''
    })
    wx.showToast({ title: '已填入，可修改后保存', icon: 'none' })
  },

  async loadProducts() {
    this.setData({ loading: true })
    try {
      const res = await get(PRODUCTS + '?stationId=' + this.data.stationId)
      // 站级「统一折扣」放在最前面：它是站级设置，不是"某个商品的档位"
      // （产品口径：「统一水票在站长端是特殊化的」）。它不参与具体商品的保存流程。
      const unified = { id: 'UNIFIED', name: '统一折扣（站级）', unified: true }
      this.setData({ products: [unified].concat(res.data || []) })
    } catch (err) {
      wx.showToast({ title: err.message || '商品加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  async onPickProduct(e) {
    const raw = e.currentTarget.dataset.id
    const unified = String(raw) === 'UNIFIED'
    const id = unified ? null : Number(raw)
    const p = this.data.products.find(x => (unified ? x.unified : x.id === id))
    this.setData({
      productId: unified ? 'UNIFIED' : String(id),
      productName: p ? p.name : '',
      isUnified: unified
    })
    await this.loadPackages()
  },

  async loadPackages() {
    this.setData({ loading: true })
    try {
      if (this.data.isUnified) {
        // 站级统一折扣：只有张数 + 折扣，**没有金额**（价格按各款水现算）
        const res = await get(DISCOUNTS)
        const discounts = (res.data || []).map(x => Object.assign({}, x, {
          statusText: x.status === 1 ? '生效中' : '已停用',
          discountText: (Number(x.discountPerMille) || 0) / 100 + ' 折'
        }))
        this.setData({ discounts, packages: [] })
        return
      }
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
      this.setData({ packages, discounts: [] })
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
    if (!qty || qty <= 0) {
      wx.showToast({ title: '请填档位张数', icon: 'none' })
      return
    }

    this.setData({ saving: true })
    try {
      if (this.data.isUnified) {
        // ===== 站级统一折扣：填的是**折扣**，不是价格 =====
        const zhe = Number(f.discount)
        if (!zhe || zhe <= 0 || zhe > 10) {
          wx.showToast({ title: '请填折扣（如 9.5 折填 9.5）', icon: 'none' })
          this.setData({ saving: false })
          return
        }
        // 界面上的"折" → 后端要的千分比：9.5 → 950。四舍五入到整数千分比，
        // 避免 9.55 这类输入进来变成浮点数（后端只收整数）。
        const perMille = Math.round(zhe * 100)
        const res = await post(DISCOUNTS, {
          qty,
          discountPerMille: perMille,
          title: f.title || null,
          sort: f.sort === '' ? 0 : Number(f.sort),
          status: 1
        })
        const saved = res.data || {}
        wx.showToast({ title: '已保存（' + qty + ' 张 ' + (saved.discountPerMille / 100) + ' 折）', icon: 'none' })
        this.setData({ form: { qty: '', price: '', discount: '', title: '', sort: '' } })
        await this.loadPackages()
        return
      }

      const price = Number(f.price)
      if (!price || price <= 0) {
        wx.showToast({ title: '请填档位总价', icon: 'none' })
        return
      }
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
      this.setData({ form: { qty: '', price: '', discount: '', title: '', sort: '' } })
      await this.loadPackages()
    } catch (err) {
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ saving: false })
    }
  },

  /** 上架/下架：没有单独的状态接口，按唯一键 upsert 重存一次即可（定制档按站+商品+张数，统一折扣按站+张数）。 */
  async onToggleStatus(e) {
    const id = Number(e.currentTarget.dataset.id)
    try {
      if (this.data.isUnified) {
        const d = this.data.discounts.find(x => x.id === id)
        if (!d) return
        await post(DISCOUNTS, {
          qty: d.qty,
          discountPerMille: d.discountPerMille,
          title: d.title,
          sort: d.sort,
          status: d.status === 1 ? 0 : 1
        })
        wx.showToast({ title: d.status === 1 ? '已停用' : '已生效', icon: 'success' })
        await this.loadPackages()
        return
      }
      const p = this.data.packages.find(x => x.id === id)
      if (!p) return
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
      title: this.data.isUnified ? '删除这个折扣档？' : '删除这个档位？',
      content: '只删这一行配置。已经卖出去的水票不受影响 —— 它们的单价在批次里另存了快照。',
      success: async (r) => {
        if (!r.confirm) return
        try {
          await del((this.data.isUnified ? DISCOUNTS : PACKAGES) + '/' + id)
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
      content: '【某款水的档位】挂"10 张 / 20 张 / 100 张"各卖多少钱，越买越便宜。' +
        '它是定价结构不是促销 —— 永远可买、不叠加、不互斥。\n\n' +
        '均价由系统按「总价 ÷ 张数」算并写进客户买票时的批次快照，' +
        '所以界面上显示的均价就是将来退票的依据，不会出现"按新价退旧票"。\n\n' +
        '【统一折扣（站级）】这里配的是**买多少张打几折**（如 10 张 9.5 折、30 张 9 折），' +
        '不是价格 —— 价格由系统按**每款水自己的水票价**现算：农夫山泉按农夫山泉的价打这个折、' +
        '娃哈哈按娃哈哈的价打这个折。\n\n' +
        '顾客端看不到"统一水票"这个东西，就是在买某款水的票时多出这几个折扣档；' +
        '某款水自己在「商品」里开了水票（挂了自己的档位）时，以它自己的档位为准。\n\n' +
        '配了上架的折扣档 = 统一折扣生效；全部停用/删除 = 关闭。',
      showCancel: false
    })
  }
})
