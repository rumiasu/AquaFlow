Component({
  data: {
    show: false
  },

  lifetimes: {
    attached() {
      this._privacyDetached = false
      this._privacyResolvers = []
      this.registerPrivacyListener()
    },
    detached() {
      this._privacyDetached = true
      this.cancelPendingPrivacyOperations()
    }
  },

  pageLifetimes: {
    show() { this.registerPrivacyListener() }
  },

  methods: {
    registerPrivacyListener() {
      if (this._privacyDetached || !wx.onNeedPrivacyAuthorization) return
      // 2026-10-24：返回旧页面时重新接管监听；已卸载组件的旧监听不得再更新界面。
      if (!this._privacyListener) this._privacyListener = resolve => {
        if (typeof resolve !== 'function') return
        if (this._privacyDetached) { try { resolve({ event: 'disagree' }) } catch (_) {} return }
        this._privacyResolvers.push(resolve)
        this.setData({ show: true })
      }
      wx.onNeedPrivacyAuthorization(this._privacyListener)
    },

    cancelPendingPrivacyOperations() {
      // 离开原页面只能取消请求，不能留下等待授权的选图锁，更不能当成用户同意。
      const pending = this._privacyResolvers || []
      this._privacyResolvers = []
      for (const resolve of pending) { try { resolve({ event: 'disagree' }) } catch (_) {} }
    },

    resolvePrivacyAuthorization(result) {
      if (this._privacyDetached) return
      // 同时触发的隐私接口都保留各自回调，只在一次明确选择后逐个回应。
      const pending = this._privacyResolvers || []
      this._privacyResolvers = []
      for (const resolve of pending) {
        try { resolve(result) } catch (_) { console.warn('[PrivacyPopup] 隐私授权回调未完成') }
      }
      if (!this._privacyDetached) this.setData({ show: this._privacyResolvers.length > 0 })
    },

    onAgree() {
      // 2026-10-24：仅由原生 agreePrivacyAuthorization 事件进入；普通 tap 不能代替隐私同意。
      this.resolvePrivacyAuthorization({ event: 'agree', buttonId: 'privacy-agree-btn' })
    },

    onReject() {
      this.resolvePrivacyAuthorization({ event: 'disagree' })
    }
  }
})
