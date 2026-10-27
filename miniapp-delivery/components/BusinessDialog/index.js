function parts(text) {
  return String(text == null ? '' : text).split(/([¥￥]?\s*\d+(?:\.\d+)?(?:\s*(?:元|桶|张|份|%))?)/g)
    .filter(Boolean).map(text => ({ text, number: /\d/.test(text) }))
}
Component({
  data: { visible: false, title: '', parts: [], showCancel: true, editable: false, input: '', dark: false },
  lifetimes: {
    attached() {
      this._queue = []; this._closed = false
      this._theme = event => this.setData({ dark: event.theme === 'dark' })
      try { const info = wx.getAppBaseInfo ? wx.getAppBaseInfo() : wx.getSystemInfoSync(); this._theme(info) } catch (_) {}
      if (wx.onThemeChange) wx.onThemeChange(this._theme)
    },
    detached() { this._closed = true; this.cancelAll(); if (wx.offThemeChange) wx.offThemeChange(this._theme) }
  },
  pageLifetimes: { show() { this._hidden = false }, hide() { this._hidden = true; this.cancelAll() } },
  methods: {
    open(options) {
      return new Promise(resolve => {
        if (this._closed || this._hidden) {
          const result = { confirm: false, cancel: true, errMsg: 'showModal:ok' }
          try { if (options.success) options.success(result) } finally { if (options.complete) options.complete(result); resolve(result) }
          return
        }
        this._queue.push({ options, resolve }); this.next()
      })
    },
    next() {
      if (this._active || this._closed || !this._queue.length) return
      const entry = this._active = this._queue.shift(), o = entry.options
      this.setData({ visible: true, title: o.title || '', parts: o.editable ? [] : parts(o.content), showCancel: o.showCancel !== false,
        confirmText: o.confirmText || '确定', cancelText: o.cancelText || '取消', danger: /^#(?:bd3328|dc2626|e64340|ff0000)$/i.test(o.confirmColor || ''),
        editable: !!o.editable, input: o.editable ? o.content || '' : '', placeholder: o.placeholderText || '' })
    },
    onInput(event) { this.setData({ input: event.detail.value }) },
    stop() {},
    onConfirm() { this.finish(true) },
    onCancel() { this.finish(false) },
    finish(confirm) {
      const entry = this._active
      if (!entry) return
      this._active = null
      const result = { confirm, cancel: !confirm, errMsg: 'showModal:ok', ...(entry.options.editable ? { content: this.data.input } : {}) }
      if (wx.hideKeyboard) wx.hideKeyboard()
      this.setData({ visible: false })
      try { if (entry.options.success) entry.options.success(result) }
      finally { try { if (entry.options.complete) entry.options.complete(result) } finally { entry.resolve(result); this.next() } }
    },
    cancelAll() {
      const waiting = this._queue || []; this._queue = []
      this.finish(false)
      for (const entry of waiting) {
        const result = { confirm: false, cancel: true, errMsg: 'showModal:ok' }
        try { if (entry.options.success) entry.options.success(result) }
        finally { try { if (entry.options.complete) entry.options.complete(result) } finally { entry.resolve(result) } }
      }
    }
  }
})
