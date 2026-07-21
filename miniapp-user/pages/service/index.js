Page({
  data: {
    serviceInfo: {
      phone: '400-123-4567',
      workTime: '08:00-20:00',
      wechat: 'aquaflow_service'
    }
  },

  onCallPhone() {
    wx.makePhoneCall({
      phoneNumber: this.data.serviceInfo.phone
    })
  },

  onCopyWechat() {
    wx.setClipboardData({
      data: this.data.serviceInfo.wechat,
      success: () => {
        wx.showToast({
          title: '微信号已复制',
          icon: 'success'
        })
      }
    })
  },

  onFeedback() {
    wx.showModal({
      title: '意见反馈',
      content: '请拨打客服电话或添加微信号反馈问题',
      showCancel: false
    })
  }
})
