const { submitFeedback, getMyFeedback } = require('../../api/feedback')
const { stationStorage } = require('../../utils/storage')

Page({
  data: {
    serviceInfo: {
      phone: '',
      stationName: '',
      workTime: '08:00-20:00',
      wechat: 'aquaflow_service'
    },
    // 反馈表单
    category: 'bug',
    content: '',
    contact: '',
    // 历史
    history: [],
    submitting: false
  },

  onShow() {
    this.loadHistory()
    this.loadStation()
  },

  // 读取本地存储的水站信息
  async loadStation() {
    try {
      const station = stationStorage.get()
      if (station) {
        this.setData({
          serviceInfo: {
            phone: station.phone || '',
            stationName: station.name || '',
            workTime: '08:00-20:00',
            wechat: 'aquaflow_service'
          }
        })
      }
    } catch (err) {
      console.warn('[Service] 加载水站信息失败:', err.message)
    }
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

  onCopyWechat() {
    wx.setClipboardData({
      data: this.data.serviceInfo.wechat,
      success: () => {
        wx.showToast({ title: '微信号已复制', icon: 'success' })
      }
    })
  },

  onSelectCategory(e) {
    this.setData({ category: e.currentTarget.dataset.category })
  },

  onContentInput(e) {
    this.setData({ content: e.detail.value })
  },

  onContactInput(e) {
    this.setData({ contact: e.detail.value })
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

  async onSubmit() {
    const { category, content, contact } = this.data
    if (!content.trim()) {
      wx.showToast({ title: '请填写反馈内容', icon: 'none' })
      return
    }

    this.setData({ submitting: true })
    try {
      await submitFeedback({ category, content, contact })
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