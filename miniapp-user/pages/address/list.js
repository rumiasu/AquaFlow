const { showBusinessModal } = require('../../utils/business-dialog')
const { captureSession, isCurrentSession } = require('../../utils/token')
const { getAddresses, deleteAddress, setDefaultAddress } = require('../../api/address')
const { storage } = require('../../utils/storage')
const { formatAddress } = require('../../utils/address')

// 只生成展示文案，不改写错误对象及其业务判断元数据。
function failureView(error) {
  const message = String(error && error.message || '')
  const code = error && (error.code || error.statusCode)
  const denied = String(code) === '403' || /权限|无权|客户账号|客户身份|HTTP\s*403|FORBIDDEN/i.test(message)
  const login = String(code) === '401' || /登录|身份已变化|HTTP\s*401|UNAUTHORIZED/i.test(message)
  return { denied: denied || login, text: denied ? '当前账号无权查看或修改收货地址，请使用客户账号登录。'
    : login ? '登录状态已失效，请重新登录后查看地址。' : '地址未能读取，请检查网络后重试。' }
}
Page({
  data: { loading: true, loaded: false, loadError: '', accessDenied: false, addresses: [], from: '',
    mutationPending: false, actionMessage: '', actionUnknown: false },
  onLoad(options) { this.setData({ from: options && options.from || '' }) },
  onShow() { this._disposed = false; this.loadAddresses() },
  onHide() { this._readId = (this._readId || 0) + 1; this.setData({ loading: false }) },
  onUnload() { this._disposed = true; this._readId = (this._readId || 0) + 1 },
  onPullDownRefresh() { return this.loadAddresses().finally(() => wx.stopPullDownRefresh()) },
  onRetry() { return this.loadAddresses() },
  onLogin() { wx.navigateTo({ url: '/pages/login/index' }) },
  ensurePageSession() {
    if (!this._pageSession) { this._pageSession = captureSession(); return true }
    if (isCurrentSession(this._pageSession)) return true
    this._readId = (this._readId || 0) + 1
    const patch = { loading: false, accessDenied: true, loadError: '登录身份已变化，请退出本页后重新进入。' }
    if (this.data.mutationPending) patch.actionUnknown = false; patch.actionMessage = this.data.mutationPending ? '登录身份已变化，请重新登录后继续。' : this.data.actionMessage
    this.setData(patch)
    return false
  },
  async loadAddresses() {
    if (!this.ensurePageSession()) return
    const readId = this._readId = (this._readId || 0) + 1
    this.setData({ loading: true, loadError: '' })
    try {
      const res = await getAddresses()
      if (readId !== this._readId || this._disposed || !this.ensurePageSession()) return
      if (!res || !Array.isArray(res.data)) throw new Error('地址数据不完整')
      const addresses = res.data.map(a => Object.assign({}, a, { addressText: formatAddress(a) }))
      this.setData({ addresses, loaded: true, accessDenied: false, actionUnknown: false,
        actionMessage: this.data.actionUnknown ? '地址列表已更新，请核对刚才的操作结果。' : this.data.actionMessage })
    } catch (error) {
      if (readId !== this._readId || this._disposed || !this.ensurePageSession()) return
      console.error('Load addresses error:', error)
      const view = failureView(error)
      this.setData({ loadError: view.text, accessDenied: view.denied })
    } finally {
      if (readId === this._readId && !this._disposed) this.setData({ loading: false })
    }
  },
  onSelectAddress(e) {
    if (!this.ensurePageSession()) return
    if (this.data.loading || this.data.loadError || this.data.mutationPending || this.data.actionUnknown) return
    if (this.data.from === 'home' || this.data.from === 'order') {
      storage.set('selectedAddress', e.currentTarget.dataset.item)
      wx.navigateBack()
    }
  },
  onAddAddress() { if (!this.data.mutationPending) wx.navigateTo({ url: '/pages/address/edit' }) },
  onEditAddress(e) {
    if (this.data.mutationPending || this.data.actionUnknown || this.data.accessDenied) return
    wx.navigateTo({ url: '/pages/address/edit?id=' + e.currentTarget.dataset.id })
  },
  onDeleteAddress(e) {
    if (this.data.mutationPending || this.data.actionUnknown || this.data.accessDenied || this.data.loading || this.data.loadError) return
    const { id } = e.currentTarget.dataset
    showBusinessModal({ cancelColor: '#637083',  title: '删除收货地址', content: '删除后将不能再选择此地址，确认删除吗？',
      confirmText: '删除', confirmColor: '#BD3328',
      success: res => { if (res.confirm) this.changeAddress(id, 'delete') } })
  },
  onSetDefault(e) { return this.changeAddress(e.currentTarget.dataset.id, 'default') },
  async changeAddress(id, action) {
    if (!this.ensurePageSession()) return
    if (this.data.mutationPending || this.data.actionUnknown || this.data.accessDenied || this.data.loading || this.data.loadError) return
    // 使写入前开始的读取失效，避免旧列表覆盖操作后的刷新。
    this._readId = (this._readId || 0) + 1
    this.setData({ mutationPending: true, loading: false, actionMessage: action === 'delete' ? '正在删除地址…' : '正在设置默认地址…' })
    try {
      await (action === 'delete' ? deleteAddress(id) : setDefaultAddress(id))
      if (this._disposed || !this.ensurePageSession()) return
      this.setData({ actionMessage: action === 'delete' ? '地址已删除。请以刷新后的列表为准。' : '默认地址已设置。请以刷新后的列表为准。' })
      await this.loadAddresses()
    } catch (error) {
      if (this._disposed || !this.ensurePageSession()) return
      console.error('Change address error:', error)
      const view = failureView(error)
      const known = error && (error.businessRejected === true || String(error.code || error.statusCode) === '401' || String(error.code || error.statusCode) === '403')
      this.setData({ accessDenied: view.denied, actionUnknown: false,
        actionMessage: view.denied ? view.text : known ? '操作未通过，请重试。' : '连接中断，正在更新地址列表…' })
      if (!view.denied && !known) {
        await this.loadAddresses()
        if (!this._disposed && this.ensurePageSession()) this.setData({ actionMessage: this.data.loadError
          ? '地址列表未能更新，请重新读取后继续。' : '地址列表已更新。' })
      }
    } finally {
      if (!this._disposed) this.setData({ mutationPending: false })
    }
  }
})
