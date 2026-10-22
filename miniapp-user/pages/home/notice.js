const { getNoticeDetail } = require('../../api/notice')
const { formatTime } = require('../../utils/format')

Page({
  data: { notice: null, loading: true, error: '', canRetry: false, titleText: '', timeText: '', hasContent: false },

  onLoad(options) {
    this._unloaded = false
    const id = options && options.id
    this._noticeId = /^[1-9][0-9]*$/.test(String(id)) ? String(id) : null
    this.setData({ canRetry: !!this._noticeId })
    return this.loadNotice()
  },
  // 2026-10-22：真实点击防重；无事件的显式刷新仍可使旧请求失效。
  onRetry(event) { if (event && this.data.loading) return; return this.loadNotice() },
  onRetryTap() { if (!this.data.loading) return this.onRetry() },
  onReturnToList() {
    wx.redirectTo({ url: '/pages/notice/index', fail: () => wx.navigateBack({ delta: 1 }) })
  },
  // 2026-10-02：占位正文曾把失败冒充公告；只读取首次进入的编号，重试不能换公告。
  async loadNotice() {
    if (this._unloaded) return
    const version = this._requestVersion = (this._requestVersion || 0) + 1
    if (!this._noticeId) {
      this.setData({ notice: null, loading: false, error: '缺少有效公告编号，无法加载。请从公告列表重新打开。' })
      return
    }
    this.setData({ loading: true })
    try {
      const res = await getNoticeDetail(this._noticeId)
      if (this._unloaded || version !== this._requestVersion) return
      const notice = res && res.data
      if (!res || (res.code !== 0 && res.code !== 200) || !notice || typeof notice !== 'object' || Array.isArray(notice)
          || String(notice.id) !== this._noticeId || typeof notice.title !== 'string'
          || (notice.content != null && typeof notice.content !== 'string')) {
        throw new Error('公告数据不完整，请重试。')
      }
      const content = notice.content == null ? '' : notice.content
      const date = notice.createTime ? new Date(notice.createTime) : null
      this.setData({ notice: { ...notice, content }, error: '',
        titleText: notice.title.trim() ? notice.title : '公告', hasContent: !!content.trim(),
        timeText: date && Number.isFinite(date.getTime()) ? formatTime(notice.createTime) : '' })
    } catch (err) {
      if (this._unloaded || version !== this._requestVersion) return
      const reason = err && typeof err.message === 'string' && err.message.trim() ? err.message : '暂时无法加载，请重试。'
      // 2026-10-22：含中文的 HTTP 异常也不能直接展示；原异常留日志，中文业务拒绝保留。
      console.warn('[notice] 公告读取失败:', err)
      const readable = /[\u4e00-\u9fff]/.test(reason) && !/https?:|HTTP\s*\d{3}|request:fail|stack|TypeError|SyntaxError/i.test(reason)
        ? reason : '暂时无法加载，请重试。'
      this.setData({ error: (this.data.notice ? '刷新失败，显示上次成功加载的公告。' : '公告详情加载失败：') + readable })
    } finally {
      if (!this._unloaded && version === this._requestVersion) this.setData({ loading: false })
    }
  },
  async onPullDownRefresh() {
    const version = this._pullVersion = (this._pullVersion || 0) + 1
    this._pullRefreshing = true
    try { await this.loadNotice() } finally {
      if (version === this._pullVersion) { this._pullRefreshing = false; wx.stopPullDownRefresh() }
    }
  },
  onUnload() {
    this._unloaded = true
    this._requestVersion = (this._requestVersion || 0) + 1
    this._pullVersion = (this._pullVersion || 0) + 1
    if (this._pullRefreshing) { this._pullRefreshing = false; wx.stopPullDownRefresh() }
  }
})
