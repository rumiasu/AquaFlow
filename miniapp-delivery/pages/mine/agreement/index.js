// One source of text and version IDs; the offline fallback can only display an explicitly unactivated draft.
const api = require('../../../api/agreements')
const { drafts, validDocument, validCatalog, find } = require('../../../utils/agreements')
Page({
  data: { doc: null, loading: false, error: '', fallbackNotice: '' },
  onLoad(options) {
    this._type = options && options.type === 'privacy' ? 'privacy' : 'user'
    this._version = options && options.versionId || ''
    const local = find(drafts, this._type)
    if (local && (!this._version || this._version === local.versionId)) this.setData({ doc: local })
    wx.setNavigationBarTitle({ title: this._type === 'privacy' ? '隐私政策' : '用户协议' })
    this.onRetry()
  },
  onUnload() { this._unloaded = true; this._readEpoch = (this._readEpoch || 0) + 1 },
  async onRetry() {
    if (this._unloaded || this.data.loading) return
    const epoch = this._readEpoch = (this._readEpoch || 0) + 1
    this.setData({ loading: true, error: '', fallbackNotice: '' })
    try {
      const res = this._version ? await api.document(this._version) : await api.current()
      if (this._unloaded || epoch !== this._readEpoch) return
      const value = res && res.data
      const doc = this._version ? value : validCatalog(value) && find(value, this._type)
      if (!validDocument(doc, this._type) || this._version && doc.versionId !== this._version) throw Error('协议版本暂时无法核实')
      this.setData({ doc, loading: false, error: '', fallbackNotice: '' })
    } catch (error) {
      if (this._unloaded || epoch !== this._readEpoch) return
      const local = find(drafts, this._type)
      const fallback = local && (!this._version || this._version === local.versionId)
      this.setData({ doc: fallback ? local : null, loading: false,
        error: '协议暂时无法加载，请重试。',
        fallbackNotice: fallback ? '以下为随应用提供的未启用草稿；当前无法核实最新正文。' : '' })
    }
  }
})
