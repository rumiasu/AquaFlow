'use strict'
const { getCatalog, getMyStation, updateCatalogSetting } = require('../../../api/station-mgmt')
const amount = value => /^\d{1,8}(?:\.\d{1,2})?$/.test(String(value).trim()) && Number(value) > 0
function session() {
  const app = getApp(), g = app.globalData || {}, u = g.userInfo || {}
  return { app, key: JSON.stringify([g.isLogin, u.staffId, u.stationId, u.role, u.bindStatus, app._loginGeneration || 0]), station: u.stationId }
}
function current(page, proof) {
  const now = session()
  return proof && page._promotionOwner === proof && now.app === proof.app && now.key === proof.key
    && !page._productDestroyed && now.app.canAccessStationBusiness()
}
function draft(row) {
  return { id: row.id, name: row.name, spec: row.spec || '', current: row.effectivePrice,
    regular: row.regularPrice, value: '', promoActive: row.promotionActive,
    wasActive: row.promotionActive, promoValue: row.promotionActive ? String(row.promotionPrice) : '',
    savedPromo: row.promotionPrice, checkValue: null }
}
function changes(d) {
  const next = {}, value = String(d.value).trim()
  if (value) { if (!amount(value)) throw Error('日常价须大于零，最多两位小数。'); next.salePrice = Number(value) }
  if (d.promoActive) {
    const regular = value ? Number(value) : Number(d.regular)
    if (!amount(d.promoValue) || Number(d.promoValue) >= regular) throw Error('促销价须大于零、低于日常价，最多两位小数。')
    if (!d.wasActive || Number(d.promoValue) !== Number(d.savedPromo)) {
      next.promotionActive = true; next.promotionPrice = Number(d.promoValue)
    }
  } else if (d.wasActive) next.promotionActive = false
  return next
}
function confirmed(row, change) {
  return (change.salePrice == null || Number(row.salePrice) === change.salePrice && Number(row.regularPrice) === change.salePrice)
    && (change.promotionActive == null || row.promotionActive === change.promotionActive)
    && (!change.promotionActive || Number(row.promotionPrice) === change.promotionPrice)
}
async function read(page, owner, id) {
  const result = await getCatalog()
  if (!current(page, owner)) return null
  const row = result && result.code === 0 && Array.isArray(result.data)
    && result.data.find(r => Number(r.id) === Number(id) && r.selected && r.status === 1)
  if (!row || row.ownerStationId && Number(row.ownerStationId) !== Number(owner.station)) throw Error('商品已变化，请返回目录核对。')
  if (typeof row.promotionActive !== 'boolean' || !amount(row.regularPrice) || !amount(row.effectivePrice)
    || Number(row.effectivePrice) !== Number(row.promotionActive ? row.promotionPrice : row.regularPrice)
    || row.promotionActive && (!amount(row.promotionPrice) || Number(row.promotionPrice) >= Number(row.regularPrice))) {
    throw Error('本站售价尚未核实，请稍后重新读取。')
  }
  return row
}
module.exports = {
  syncPromotionSession() {
    if (this._promotionOwner && !current(this, this._promotionOwner)) {
      this._promotionOwner = null
      this.setData({ promotionVisible: false, promotionDraft: null, posterProduct: null, promotionError: '' })
    }
  },
  hidePromotion() {
    this._promotionView = null
    this.setData({ promotionVisible: false, posterProduct: null, posterStationName: '' })
  },
  closePromotion() { this.hidePromotion() },
  closePromotionPoster() { this.setData({ posterProduct: null, posterStationName: '' }) },
  async openPromotion(event) {
    if (this.productWriteLocked() || this._productHidden || this._productDestroyed || !getApp().canAccessStationBusiness()
      || this.data.showEdit || this.data.showSetting || this.data.showInventory) return
    const id = Number(event.currentTarget.dataset.id)
    if (!Number.isSafeInteger(id) || id <= 0) return
    if (this.data.promotionDraft && this.data.promotionDraft.checkValue !== null && current(this, this._promotionOwner)) {
      // An uncertain write must be reconciled before opening another product.
      this.setData({ promotionVisible: true, posterProduct: null })
      return
    }
    this._promotionOwner = session(); this._promotionId = id
    this.setData({ promotionVisible: true, promotionDraft: null, posterProduct: null, promotionError: '' })
    return this.retryPromotionRead()
  },
  async retryPromotionRead() {
    if (this._promotionFlight || this.data.promotionBusy || !current(this, this._promotionOwner)) return
    const owner = this._promotionOwner, view = this._promotionView = {}
    this.setData({ promotionBusy: true, promotionError: '' })
    try {
      const row = await read(this, owner, this._promotionId)
      if (row && this._promotionView === view && this.data.promotionVisible) this.setData({ promotionDraft: draft(row) })
    } catch (e) {
      if (current(this, owner) && this._promotionView === view) this.setData({ promotionError: e.message || '读取失败，请重试。' })
    } finally { this.setData({ promotionBusy: false }) }
  },
  editPromotion(event) {
    const d = this.data.promotionDraft, field = event.currentTarget.dataset.field
    if (!d || d.checkValue !== null || this.productWriteLocked() || !current(this, this._promotionOwner) || !['value','promoValue'].includes(field)) return
    this.setData({ promotionDraft: { ...d, [field]: event.detail.value }, posterProduct: null, promotionError: '' })
  },
  switchPromotion(event) {
    const d = this.data.promotionDraft
    if (!d || d.checkValue !== null || this.productWriteLocked() || !current(this, this._promotionOwner)) return
    this.setData({ promotionDraft: { ...d, promoActive: event.detail.value === true }, posterProduct: null, promotionError: '' })
  },
  promotionPoster() { return this.savePromotion(true) },
  async savePromotion(wantPoster) {
    const d = this.data.promotionDraft, owner = this._promotionOwner
    if (!d || this.productWriteLocked() || !current(this, owner) || this._productHidden || !this.data.promotionVisible) return
    let change
    try { change = d.checkValue || changes(d) } catch (e) { this.setData({ promotionError: e.message }); return }
    if (!Object.keys(change).length && wantPoster !== true) { this.setData({ promotionError: '请先修改售价或促销。' }); return }
    const flight = this._promotionFlight = {}, view = this._promotionView = {}
    const visible = () => current(this, owner) && this._promotionView === view && this.data.promotionVisible && !this._productHidden
    let uncertain = d.checkValue !== null, accepted = false
    this.setData({ promotionBusy: true, promotionError: '', posterProduct: null })
    try {
      if (d.checkValue === null && Object.keys(change).length) {
        uncertain = true
        const response = await updateCatalogSetting(d.id, change)
        if (!response || response.code !== 0) throw Error('售价保存结果尚未确认。')
        accepted = true
      }
      const row = await read(this, owner, d.id)
      if (!row) return
      if (!confirmed(row, change)) {
        this.setData({ promotionDraft: draft(row), promotionError: '当前售价与提交值不同，已读回最新价格，请核对后再操作。' })
        return
      }
      this.setData({ promotionDraft: draft(row), promotionError: Object.keys(change).length ? '售价已核对。' : '' })
      if (visible()) this.loadData()
      if (wantPoster === true && visible()) {
        const station = await getMyStation()
        if (!visible()) return
        if (!station || station.code !== 0 || !station.data || Number(station.data.id) !== Number(owner.station)) throw Error('水站资料尚未核实，请重试。')
        // Never infer brand imagery: use the saved product photo, or the existing neutral illustration.
        this.setData({ posterProduct: { ...row, imageUrl: row.imageUrl || '/assets/product/barrel-neutral-template.webp' },
          posterStationName: station.data.name || '' })
      }
    } catch (e) {
      if (current(this, owner)) {
        const rejected = !accepted && (e.businessRejected === true || e.requestMayHaveOccurred === false)
        this.setData({ promotionDraft: uncertain && !rejected ? { ...d, checkValue: change } : d,
          promotionError: uncertain && !rejected ? '保存结果待核对；重试只读取最新价格，不重复提交。' : e.message || '暂未完成，请重试。' })
      }
    } finally {
      if (this._promotionFlight === flight) { this._promotionFlight = null; this.setData({ promotionBusy: false }) }
    }
  }
}
