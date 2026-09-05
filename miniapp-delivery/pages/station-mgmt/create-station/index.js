const { post } = require('../../../utils/request')
const { API } = require('../../../config/api')

// V1: 站长(尚未创建水站)使用 /api/auth/create-station 创建水站，
// 只需要水站名称 + 定位（可选），其他字段使用默认值
Page({
  data: {
    stationName: '',
    region: [],
    address: '',
    latitude: null,
    longitude: null,
    locationText: '',
    description: '',
    submitting: false
  },

  onStationNameInput(e) { this.setData({ stationName: e.detail.value }) },
  onRegionChange(e) { this.setData({ region: e.detail.value }) },
  onDescInput(e) { this.setData({ description: e.detail.value }) },

  onChooseLocation() {
    wx.chooseLocation({
      success: (res) => {
        this.setData({
          latitude: res.latitude,
          longitude: res.longitude,
          locationText: res.name + (res.address ? ' · ' + res.address : ''),
          address: res.address || res.name || ''
        })
      },
      fail: (err) => {
        // 用户拒绝授权时，友好提示（不强制，省市区选填）
        if (err && err.errMsg && err.errMsg.indexOf('auth') >= 0) {
          wx.showToast({ title: '可稍后在设置中开启定位', icon: 'none' })
        }
      }
    })
  },

  async onSubmit() {
    const { stationName, region, address } = this.data
    const stationNameTrim = stationName.trim()

    // V1 极简：只需要水站名称，其他全部可选（含定位）
    if (!stationNameTrim) {
      wx.showToast({ title: '请输入水站名称', icon: 'none' })
      return
    }

    this.setData({ submitting: true })
    wx.showLoading({ title: '创建中...' })
    try {
      const payload = {
        name: stationNameTrim,
        phone: '', // 不强制填写，后端使用默认值
        province: region[0] || '',
        city: region[1] || '',
        district: region[2] || '',
        address: address.trim(),
        latitude: this.data.latitude,
        longitude: this.data.longitude,
        description: this.data.description.trim()
      }
      const res = await post(API.AUTH_CREATE_STATION, payload)
      const data = res && res.data ? res.data : null
      if (!data) throw new Error('服务器未返回创建结果')

      const app = getApp()
      app.setLoginState(data)

      wx.hideLoading()
      wx.showToast({ title: '水站创建成功', icon: 'success' })
      setTimeout(() => {
        wx.switchTab({ url: '/pages/home/index' })
      }, 1500)
    } catch (err) {
      wx.hideLoading()
      this.setData({ submitting: false })
      wx.showToast({ title: err.message || '创建失败', icon: 'none', duration: 2500 })
    }
  }
})
