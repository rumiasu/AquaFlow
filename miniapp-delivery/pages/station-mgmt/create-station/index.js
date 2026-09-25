const { post } = require('../../../utils/request')
const { API } = require('../../../config/api')
// 与客户端收货地址共用同一套省市区拆分规则，避免同一串定位结果两端拆得不一样
const { parseRegion } = require('../../../utils/address')

// V1: 站长(尚未创建水站)使用 /api/auth/create-station 创建水站，
// 只需要水站名称 + 定位（可选），其他字段使用默认值
Page({
  data: {
    stationName: '',
    // [省, 市, 区] 固定 3 位（提交时按索引取值），直辖市已把重复的市置空
    region: [],
    // 展示用文案，由 region 去空后拼接（wxml 里不能调 filter/join）
    regionText: '',
    address: '',
    latitude: null,
    longitude: null,
    locationText: '',
    description: '',
    submitting: false
  },

  /**
   * 进页面先向服务器确认真实状态：可能本站已建好（例如在另一台设备上完成，
   * 或已被管理员加进某个水站），此时不该再让站长重复建站 —— 直接放行进入业务。
   * refreshIdentityAndRoute 只在「目标页 ≠ 当前页」时才跳，所以不会自我循环。
   */
  async onShow() {
    const app = getApp()
    await app.refreshIdentityAndRoute('pages/station-mgmt/create-station/index')
  },

  /**
   * 返回上一步：重新选择身份。
   * 用 reLaunch 而非 navigateBack —— 本页是 reLaunch 进来的，页面栈里没有上一页。
   * 依据：**选择身份不等于生效**（水站还没建），此时改选合理；生效后后端会拒绝改选。
   */
  onBackToRoleSelect() {
    const app = getApp()
    if (app.isIdentityEffective()) {
      wx.showToast({ title: '身份已生效，如需更换请联系管理员', icon: 'none' })
      return
    }
    wx.reLaunch({ url: '/pages/role-select/index' })
  },

  /**
   * 退出出口。本页是 reLaunch 进来的（页面栈无上一页），页面本身只有「创建水站」一条路，
   * 没有出口就只能在"必须建站"与"退不出"之间卡住。
   */
  onLogout() {
    wx.showModal({
      title: '退出登录',
      content: '退出后可用其他微信账号登录。',
      confirmText: '退出',
      success: (res) => {
        if (res.confirm) getApp().logout()
      }
    })
  },

  onStationNameInput(e) { this.setData({ stationName: e.detail.value }) },
  onPhoneInput(e) { this.setData({ phone: e.detail.value }) },
  onDescInput(e) { this.setData({ description: e.detail.value }) },

  onChooseLocation() {
    wx.chooseLocation({
      success: (res) => {
        // [2026-09-16] 原实现在这里只写地址文本，省市区要靠站长另外手点一个
        // 「补充省/市/区（可选）」级联选择器（`<picker mode="region">`）补齐，
        // 结果绝大多数水站的省市区都是空的。定位返回的 address 本身就带省市区，
        // 直接解析回填即可，手填项已删除。
        const fullAddr = (res.address || '') + (res.name || '')
        const parsed = parseRegion(fullAddr)
        const province = parsed.province
        // 直辖市 parsed.city === province，提交前去掉，否则拼出来是「北京市北京市朝阳区」
        const region = [province, parsed.city === province ? '' : parsed.city, parsed.district]
        // 后端 LoginController#createStationAndBind 拼 fullAddress 时是
        // 「province + city + district + address」，且**仅当 province 非空才拼** ——
        // 所以：解析出省 → 把省市区从地址串里剥掉交给 region 字段（否则库里存两遍）；
        // 没解析出省 → 整串留在 address 里（否则 city/district 会被后端静默丢掉）
        const streetAddress = province ? parsed.detail : (res.address || res.name || '')

        this.setData({
          latitude: res.latitude,
          longitude: res.longitude,
          locationText: res.name + (res.address ? ' · ' + res.address : ''),
          address: streetAddress,
          region: region,
          regionText: region.filter(function (p) { return !!p }).join(' · ')
        })
      },
      fail: (err) => {
        // 用户拒绝授权时，友好提示（不强制，定位可跳过）
        if (err && err.errMsg && err.errMsg.indexOf('auth') >= 0) {
          wx.showToast({ title: '可稍后在设置中开启定位', icon: 'none' })
        }
      }
    })
  },

  async onSubmit() {
    const { stationName, region, address, phone, locationText } = this.data
    const stationNameTrim = stationName.trim()

    // [2026-09-19] 三项必填，且**页面标了 * 就必须真的校验**（此前定位标了 * 却不校验，
    // 结果真实库两台水站的坐标都是空的 —— 而没坐标 = 算不出距离 = 配送范围整段失效）。
    if (!stationNameTrim) {
      wx.showToast({ title: '请输入水站名称', icon: 'none' })
      return
    }
    const phoneTrim = (phone || '').trim()
    if (!phoneTrim) {
      wx.showToast({ title: '请填写联系电话', icon: 'none' })
      return
    }
    if (!locationText) {
      wx.showToast({ title: '请选择水站位置（配送范围靠它判断）', icon: 'none' })
      return
    }

    this.setData({ submitting: true })
    wx.showLoading({ title: '创建中...' })
    try {
      const payload = {
        name: stationNameTrim,
        phone: phoneTrim,   // [2026-09-19] 必填，不再是写死的空串（旧注释：不强制填写，后端使用默认值）
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
