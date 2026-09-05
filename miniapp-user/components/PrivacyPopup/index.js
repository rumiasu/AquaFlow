Component({
  data: {
    show: false
  },

  lifetimes: {
    attached() {
      // 监听隐私协议需要授权
      if (wx.onNeedPrivacyAuthorization) {
        wx.onNeedPrivacyAuthorization((resolve, eventInfo) => {
          this.setData({ show: true })
          this._resolve = resolve
        })
      }
    }
  },

  methods: {
    onAgree() {
      this.setData({ show: false })
      if (this._resolve) {
        this._resolve({ buttonId: 'privacy-agree-btn' })
        this._resolve = null
      }
    },

    onReject() {
      this.setData({ show: false })
      if (this._resolve) {
        this._resolve({ buttonId: '' })
        this._resolve = null
      }
    }
  }
})
