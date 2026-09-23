/**
 * 用户隐私保护弹窗（自实现版本）。
 *
 * <p>⚠️ **本组件必须在用到隐私接口的页面里真的渲染出来，否则等于没有。**</p>
 *
 * <p>[2026-09-20 真机事故] 全仓此前 `<privacy-popup />` **只出现在本组件自己的 wxml 里**，
 * 员工端 0 个页面渲染它 —— 而监听器是在 `attached` 生命周期里注册的：
 * 没挂载 ⇒ `wx.onNeedPrivacyAuthorization` 从未注册 ⇒ 真机上 `wx.chooseLocation` /
 * `wx.chooseImage` 被微信隐私授权拦下。更糟的是那 6 个调用点**都没有 `fail` 回调**，
 * 于是用户点了「导航到客户地址」「上传送达照片」**什么都不发生、也没有任何提示**。
 * 开发者工具里看不出来：模拟器由工具自己提供隐私授权，不走这个回调。</p>
 *
 * <p>因此新增了使用隐私接口的页面后，**必须在该页 wxml 的根节点下加一行 `<privacy-popup />`**
 * （不能在 `wx:if` 里 —— 条件为假时 `attached` 不执行，等于没挂）。
 * 组件已在 `app.json` 的全局 `usingComponents` 注册，页面无需再声明。
 * 当前已挂载的页面见 `pages/**` 里 6 处（order/complete、order/detail、
 * station-mgmt/{create-station,products,station-info}、station-mgmt/customers/adjust/edit）。
 * 顾客端的对照实现：`miniapp-user/pages/address/edit.wxml:2` + `address/edit.js:104-153`（含权限被拒兜底）。</p>
 */
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
    },

    preventMove() {}
  }
})
