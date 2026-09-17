const { post } = require('../../utils/request')
const { API, BINDING_STATUS } = require('../../config/api')
const { STORAGE_KEYS } = require('../../utils/storage-keys')

// V1: 选择身份后调用 /api/auth/select-role 创建对应 staff 记录
// - STATION_MANAGER: 创建 staff(station_id=null,bind_status=UNBOUND),继续到创建水站
// - DELIVERY: 创建 staff(station_id=null,bind_status=UNBOUND),继续到申请绑定水站
Page({
  data: {},

  /**
   * 身份**已生效**（已挂到水站上）时不应再停留在这里改选：后端 selectRole 会直接拒绝，
   * 让用户点两次才发现被拒是坏体验。生效前（如从 apply-bind / create-station 返回重选）
   * 则正常留在此页。
   */
  onShow() {
    const app = getApp()
    if (typeof app.isIdentityEffective === 'function' && app.isIdentityEffective()) {
      app.routeByRole(false)
    }
  },

  onSelectStationManager() {
    wx.showModal({
      title: '选择身份',
      content: '您将以「站长」身份使用，需要创建属于您的水站。是否继续？',
      confirmText: '确认站长',
      success: (res) => {
        if (res.confirm) this.selectRole('STATION_MANAGER')
      }
    })
  },

  onSelectDelivery() {
    wx.showModal({
      title: '选择身份',
      content: '您将以「配送员」身份使用，需填写资料并申请绑定水站，站长审批通过后生效。是否继续？',
      confirmText: '确认配送员',
      success: (res) => {
        if (res.confirm) this.selectRole('DELIVERY')
      }
    })
  },

  async selectRole(role) {
    wx.showLoading({ title: '处理中...' })
    try {
      const app = getApp()
      const prevUserInfo = (app.globalData && app.globalData.userInfo) || wx.getStorageSync(STORAGE_KEYS.USER_INFO) || {}
      // V1 规则：把登录阶段暂存的 pending openid 传给后端，绑定到新建的 staff
      const payload = {
        role,
        nickname: prevUserInfo.nickname || '',
        phone: prevUserInfo.phone || ''
      }
      if (prevUserInfo._pendingOpenid) {
        payload._pendingOpenid = prevUserInfo._pendingOpenid
      }
      const res = await post(API.SELECT_ROLE, payload)
      const data = res && res.data ? res.data : null
      if (!data) throw new Error('服务器未返回身份信息')

      const u = app.setLoginState(data)
      wx.hideLoading()

      // V1: 无论选什么，都不应有默认 station_id，严格按绑定状态走
      if (role === 'STATION_MANAGER') {
        // 站长必须先创建水站；如果 create-station 之前已经有 stationId(极少见)则进首页
        if (u.stationId) {
          wx.reLaunch({ url: '/pages/home/index' })
        } else {
          wx.redirectTo({ url: '/pages/station-mgmt/create-station/index' })
        }
        return
      }

      if (role === 'DELIVERY') {
        // DELIVERY 无默认 station_id → 必须去申请绑定水站
        if (u.bindStatus === BINDING_STATUS.BOUND && u.stationId) {
          wx.reLaunch({ url: '/pages/home/index' })
          return
        }
        if (u.bindStatus === BINDING_STATUS.PENDING || u.bindStatus === BINDING_STATUS.PENDING_UNBIND) {
          wx.redirectTo({ url: '/pages/bind-wait/index' })
          return
        }
        // UNBOUND / REJECTED → 申请绑定
        wx.redirectTo({ url: '/pages/station-mgmt/apply-bind/index' })
        return
      }

      wx.showToast({ title: '未知身份', icon: 'none' })
    } catch (err) {
      wx.hideLoading()
      wx.showToast({ title: err.message || '设置失败', icon: 'none', duration: 2500 })
    }
  }
})
