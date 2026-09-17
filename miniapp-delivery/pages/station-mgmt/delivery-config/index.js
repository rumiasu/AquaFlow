// ⚠️ 这里直接用 utils/request 而不是 api/station-mgmt.js 的封装：
// 后者的接口清单当前正被另一个工作流（商品图片库）改动，共用文件会让两边未提交的改动纠缠在一起。
// 路径常量也写在本文件里，同样是为了避开共享的 config/api.js。
const { get, put } = require('../../../utils/request')

const DELIVERY_CONFIG_PATH = '/api/manager/delivery-config'

const getDeliveryConfig = () => get(DELIVERY_CONFIG_PATH)
const saveDeliveryConfig = (data) => put(DELIVERY_CONFIG_PATH, data)

/**
 * 配送计费配置（站长）：起送量 / 配送范围 / 运费 / 楼层费。规格见 docs/design/17。
 *
 * ⚠️ 三条口径，改这个页面时必须守住：
 *   1. 三个「处理方式」是三选一：WARN 仅提示 / REJECT 不接单 / FEE 加收费用。
 *      **默认必须是 WARN** —— 默认硬拦等于站长一建站就把客户挡在门外。
 *   2. 数值留空表示"不限"或"不收"，不要用 0 去表达"不限"（0 是合法的收费金额）。
 *   3. 费用会在下单时**快照进订单**，所以改这里的配置**不会**影响历史订单的金额。
 */

const LIMIT_MODES = [
  { value: 'WARN', name: '仅提示', desc: '照常接单，只在结算页提醒客户' },
  { value: 'REJECT', name: '不接单', desc: '直接拒绝下单（客户会看到原因）' },
  { value: 'FEE', name: '加收费用', desc: '照常接单，另加一笔费用' }
]

const FLOOR_MODES = [
  { value: 'PER_ORDER', name: '按单', desc: '每单加收一次，客户更容易接受（推荐）' },
  { value: 'PER_BUCKET', name: '按桶', desc: '无电梯 2 元/桶这类口头报价，多桶时金额增长快' }
]

Page({
  data: {
    loading: true,
    saving: false,
    configured: false,
    limitModes: LIMIT_MODES,
    floorModes: FLOOR_MODES,
    // 表单值一律用字符串存：留空表示"不限/不收"，用 '' 才能与数字 0 区分开
    form: {
      minOrderBuckets: '',
      minOrderAmount: '',
      minOrderMode: 'WARN',
      minOrderFee: '',
      deliveryRadiusM: '',
      overRadiusMode: 'WARN',
      remoteFee: '',
      baseDeliveryFee: '',
      freeDeliveryBuckets: '',
      freeDeliveryAmount: '',
      floorFreeLevel: '1',
      floorFeePerLevel: '',
      floorFeeMode: 'PER_ORDER'
    }
  },

  onShow() {
    const app = getApp()
    if (!app.canAccessStationBusiness()) {
      app.routeByRole(true)
      return
    }
    this.load()
  },

  async load() {
    this.setData({ loading: true })
    try {
      const res = await getDeliveryConfig()
      const d = res.data || {}
      const c = d.config || {}
      // 后端把 null 原样下发（表示"不限/不收"），这里统一转成 '' 便于输入框编辑
      const s = (v) => (v === null || v === undefined ? '' : String(v))
      this.setData({
        configured: d.configured === true,
        form: {
          minOrderBuckets: s(c.minOrderBuckets),
          minOrderAmount: s(c.minOrderAmount),
          minOrderMode: c.minOrderMode || 'WARN',
          minOrderFee: s(c.minOrderFee),
          deliveryRadiusM: s(c.deliveryRadiusM),
          overRadiusMode: c.overRadiusMode || 'WARN',
          remoteFee: s(c.remoteFee),
          baseDeliveryFee: s(c.baseDeliveryFee),
          freeDeliveryBuckets: s(c.freeDeliveryBuckets),
          freeDeliveryAmount: s(c.freeDeliveryAmount),
          floorFreeLevel: s(c.floorFreeLevel),
          floorFeePerLevel: s(c.floorFeePerLevel),
          floorFeeMode: c.floorFeeMode || 'PER_ORDER'
        }
      })
    } catch (err) {
      wx.showToast({ title: err.message || '配置加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  onInput(e) {
    const field = e.currentTarget.dataset.field
    this.setData({ ['form.' + field]: e.detail.value })
  },

  onPickMode(e) {
    const field = e.currentTarget.dataset.field
    const value = e.currentTarget.dataset.value
    this.setData({ ['form.' + field]: value })
  },

  /**
   * 提交。
   *
   * ⚠️ 空串必须转成 null 而不是 0：
   *   · minOrderBuckets 传 0 会被后端当成"起送量 0 桶"（等于没门槛，语义上恰好等价，但很含糊）；
   *   · floorFreeLevel 传 0 会变成"0 层以下免费"，含义全变。
   * 所以这里统一 ''→null，让"不限/不收"与"填 0"在数据上就是两件事。
   */
  async onSave() {
    if (this.data.saving) return
    const f = this.data.form
    const num = (v) => {
      if (v === '' || v === null || v === undefined) return null
      const n = Number(v)
      return isNaN(n) ? null : n
    }

    const payload = {
      minOrderBuckets: num(f.minOrderBuckets),
      minOrderAmount: num(f.minOrderAmount),
      minOrderMode: f.minOrderMode,
      minOrderFee: num(f.minOrderFee),
      deliveryRadiusM: num(f.deliveryRadiusM),
      overRadiusMode: f.overRadiusMode,
      remoteFee: num(f.remoteFee),
      baseDeliveryFee: num(f.baseDeliveryFee),
      freeDeliveryBuckets: num(f.freeDeliveryBuckets),
      freeDeliveryAmount: num(f.freeDeliveryAmount),
      floorFreeLevel: num(f.floorFreeLevel),
      floorFeePerLevel: num(f.floorFeePerLevel),
      floorFeeMode: f.floorFeeMode
    }

    // 只在"选了加收费用"时才提醒填金额 —— 选了 FEE 却不填金额，等于门槛白配了
    if (payload.minOrderMode === 'FEE' && !payload.minOrderFee) {
      wx.showToast({ title: '选「加收费用」请填写加收金额', icon: 'none' })
      return
    }
    if (payload.overRadiusMode === 'FEE' && !payload.remoteFee) {
      wx.showToast({ title: '选「加收费用」请填写远程费金额', icon: 'none' })
      return
    }

    this.setData({ saving: true })
    try {
      await saveDeliveryConfig(payload)
      wx.showToast({ title: '已保存', icon: 'success' })
      this.load()
    } catch (err) {
      wx.showToast({ title: err.message || '保存失败', icon: 'none' })
    } finally {
      this.setData({ saving: false })
    }
  }
})
