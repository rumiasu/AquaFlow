const { submitFeedback, getMyFeedback } = require('../../api/feedback')
const { stationStorage } = require('../../utils/storage')
const { getStationPublicPhone } = require('../../api/station')

Page({
  data: {
    serviceInfo: {
      phone: '',
      stationName: ''
    },
    // 反馈表单
    category: 'bug',
    content: '',
    contact: '',
    // 匿名提交开关（v46，产品裁定 2026-09-18）：**默认 false = 实名**。
    // ⚠️ 这里只是把用户的意愿原样传给后端；**身份脱敏是后端做在查询 SQL 上的**
    // （FeedbackMapper.listCustomerFeedbackByStation 对 anonymous=1 的记录把 customer_id
    //   与姓名都置 NULL）。前端**不得**自己拼"匿名顾客 / 匿名用户"之类的身份文案 ——
    // 前端能隐藏的东西，前端也能一眼看穿（改个 setData、翻个接口就没了）。
    anonymous: false,
    // 历史
    history: [],
    submitting: false
  },

  onShow() {
    this.loadHistory()
    this.loadStation()
  },

  /**
   * 水站联系方式。
   *
   * ⚠️ 电话**优先走公开接口** `GET /api/stations/{id}/public-phone`（免登录、只回 id/name/phone），
   * 本地缓存只作兜底：`selectedStation` 是选站时存下的快照，**不保证带 phone**，
   * 只读缓存会让"拨打"按钮在部分客户那儿永远提示"暂未获取到客服电话"——
   * api/station.js 的注释里记着同类事故（误调站长路由导致电话恒定取不到，排查成本很高）。
   */
  async loadStation() {
    const cached = stationStorage.get() || {}
    const info = { phone: cached.phone || '', stationName: cached.name || '' }
    const stationId = cached.id || cached.stationId
    if (stationId) {
      try {
        const res = await getStationPublicPhone(stationId)
        const phone = res && res.data && res.data.phone
        if (phone) info.phone = phone
      } catch (err) {
        // 拿不到就退回缓存值；两者都没有时按钮会给出可读提示（不当成页面故障）
        console.warn('[Service] 读取水站公开电话失败:', err && err.message)
      }
    }
    this.setData({ serviceInfo: info })
  },

  onCallPhone() {
    const phone = this.data.serviceInfo.phone
    if (!phone) {
      wx.showToast({ title: '暂未获取到客服电话，请稍后再试', icon: 'none' })
      return
    }
    wx.makePhoneCall({
      phoneNumber: phone
    })
  },

  // [2026-09-17 删除] onCopyWechat 与 data.wechat：那个微信号 'aquaflow_service' 是**编造的**，
  // 后端 Station 实体根本没有微信字段，客户复制到的是一个不存在的账号。
  // 同理删掉了硬编码的 workTime '08:00-20:00' —— 也没有数据来源。
  // **判据：界面上不放假数据。没有数据源就不展示，而不是填一个看起来合理的值**
  //（真要展示营业时间，先给 Station 加字段并让站长可配）。

  onSelectCategory(e) {
    this.setData({ category: e.currentTarget.dataset.category })
  },

  onContentInput(e) {
    this.setData({ content: e.detail.value })
  },

  onContactInput(e) {
    this.setData({ contact: e.detail.value })
  },

  /**
   * 匿名开关。只改本地状态，提交时才随 `anonymous` 一起发给后端。
   *
   * <p>⚠️ 别把这个开关做成"前端隐藏姓名"：姓名从来就不是前端藏起来的 ——
   * 站长端拿到的响应里**根本没有** customerId / customerName（后端 SQL 已置 NULL）。
   * 前端只需如实上报用户的选择。</p>
   */
  onAnonymousChange(e) {
    this.setData({ anonymous: !!e.detail.value })
  },

  async loadHistory() {
    try {
      const res = await getMyFeedback()
      if (res.data) {
        this.setData({ history: res.data })
      }
    } catch (err) {
      // 未登录或加载失败时不强提示
      console.warn('[Feedback] 加载历史失败:', err.message)
    }
  },

  /**
   * 提交反馈。
   *
   * <p><b>匿名（v46，产品裁定 2026-09-18）</b>：随请求体带 `anonymous`，由后端落 `feedback.anonymous`。
   * 提交后**不重置开关**：用户选了匿名就一直是匿名，免得第二次提交在他没注意时变回实名
   * （对隐私而言，悄悄变回实名是更差的方向；而开关就在屏幕上，状态是看得见的）。</p>
   *
   * <p>⚠️ 只有本页（用户手动提交）带 `anonymous`。顾客端的**自动错误上报**
   * （`utils/request.js` 的 `offerErrorReport`）**保持实名不变**，原因写在那边的注释里。</p>
   */
  async onSubmit() {
    const { category, content, contact, anonymous } = this.data
    if (!content.trim()) {
      wx.showToast({ title: '请填写反馈内容', icon: 'none' })
      return
    }

    this.setData({ submitting: true })
    try {
      await submitFeedback({ category, content, contact, anonymous })
      wx.showToast({ title: '反馈已提交，感谢！', icon: 'success' })
      this.setData({ content: '', contact: '' })
      this.loadHistory()
    } catch (error) {
      wx.showToast({ title: error.message || '提交失败', icon: 'none' })
    } finally {
      this.setData({ submitting: false })
    }
  }
})