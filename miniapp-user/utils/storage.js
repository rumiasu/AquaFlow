// 本地存储工具
const { captureSession, isCurrentSession } = require('./token')

const storage = {
  get: (key) => {
    try {
      return wx.getStorageSync(key)
    } catch (e) {
      console.error('Storage get error:', e)
      return null
    }
  },

  set: (key, value) => {
    try {
      wx.setStorageSync(key, value)
      return true
    } catch (e) {
      console.error('Storage set error:', e)
      return false
    }
  },

  remove: (key) => {
    try {
      wx.removeStorageSync(key)
      return true
    } catch (e) {
      console.error('Storage remove error:', e)
      return false
    }
  },

  clear: () => {
    try {
      wx.clearStorageSync()
      return true
    } catch (e) {
      console.error('Storage clear error:', e)
      return false
    }
  }
}

// Token相关（JWT 双 Token）
const tokenStorage = {
  getAccessToken: () => storage.get('accessToken'),
  setAccessToken: (token) => storage.set('accessToken', token),
  getRefreshToken: () => storage.get('refreshToken'),
  setRefreshToken: (token) => storage.set('refreshToken', token),
  // 兼容旧代码
  get: () => storage.get('accessToken'),
  set: (token) => storage.set('accessToken', token),
  remove: () => {
    storage.remove('accessToken')
    storage.remove('refreshToken')
  }
}

// 用户信息相关
const userStorage = {
  get: () => storage.get('userInfo'),
  set: (userInfo) => storage.set('userInfo', userInfo),
  remove: () => storage.remove('userInfo')
}

// 搜索历史
const searchStorage = {
  get: () => storage.get('searchHistory') || [],
  set: (history) => storage.set('searchHistory', history),
  add: (keyword) => {
    const history = searchStorage.get()
    const index = history.indexOf(keyword)
    if (index > -1) {
      history.splice(index, 1)
    }
    history.unshift(keyword)
    if (history.length > 10) {
      history.pop()
    }
    searchStorage.set(history)
  },
  remove: (keyword) => {
    const history = searchStorage.get()
    const index = history.indexOf(keyword)
    if (index > -1) {
      history.splice(index, 1)
    }
    searchStorage.set(history)
  },
  clear: () => storage.set('searchHistory', [])
}

// 当前选中的水站持久化（仅 UI 偏好，不关联客户归属）
const stationStorage = {
  get: () => storage.get('selectedStation'),
  set: (station) => storage.set('selectedStation', station),
  getId: () => {
    const s = storage.get('selectedStation')
    return s ? s.id : null
  },
  // 水站切换提示"不再提示"状态
  getSwitchNoticeDisabled: () => storage.get('stationSwitchNoticeDisabled') === true,
  setSwitchNoticeDisabled: (disabled) => storage.set('stationSwitchNoticeDisabled', !!disabled),
  remove: () => storage.remove('selectedStation')
}

// 上次用过的支付方式（2026-09-19 产品口径：「默认订单里会沿用上次支付方式，前端记一下就好」）
//
// ⚠️ **只存本地、不进后端**。理由（产品明确说了"不用再写进后端"）：它是**交互偏好**，
// 不是业务事实 —— 存到后端就要跟着客户档案走，还要考虑多端同步与脏数据；
// 而它唯一的作用是"下次打开下单页时预选上一次那一项"。
// 真正的可用性判据仍在后端 `PayMethod.availableMethods()`：记住的方式若在本站不可用
// （比如上次用货到付款、换个站没被开通），下单页会按后端下发的 enabled 回退到默认项。
const payMethodStorage = {
  get: () => {
    const v = parseInt(storage.get('lastPayMethod'), 10)
    return isNaN(v) ? null : v
  },
  set: (method) => {
    const v = parseInt(method, 10)
    if (!isNaN(v)) storage.set('lastPayMethod', v)
  },
  remove: () => storage.remove('lastPayMethod')
}

// 2026-11-04：原 selectedAddress 裸对象在异常退出后会被下一账号消费。
// 归属随选择一起保存；不推断旧条目的主人，读取时按当前账号核对并一次性消费。
// 2026-11-09: Only keep the address ID for this login epoch; finding stations re-reads
// the owned address list rather than reusing old coordinates or another account's address.
let activeDeliveryAddress = null
const deliveryAddressStorage = {
  remember(address, session = captureSession()) {
    if (!session.loggedIn || !session.customerId || !isCurrentSession(session)) return false
    activeDeliveryAddress = address && address.id ? { session, id: address.id } : null
    return true
  },
  getId(session = captureSession()) {
    return session.loggedIn && isCurrentSession(session) && activeDeliveryAddress
      && isCurrentSession(activeDeliveryAddress.session) ? activeDeliveryAddress.id : null
  }
}

const selectedAddressStorage = {
  set: (address, session = captureSession()) => {
    if (!session.loggedIn || !session.customerId || !isCurrentSession(session)
      || !address || !address.id) return false
    const saved = storage.set('selectedAddress', { customerId: String(session.customerId), address })
    if (saved) deliveryAddressStorage.remember(address, session)
    return saved
  },
  take: (session = captureSession()) => {
    const selection = storage.get('selectedAddress')
    storage.remove('selectedAddress')
    if (!session.loggedIn || !session.customerId || !isCurrentSession(session)
      || !selection || selection.customerId !== String(session.customerId)
      || !selection.address || !selection.address.id) return null
    deliveryAddressStorage.remember(selection.address, session)
    return selection.address
  }
}

module.exports = {
  storage,
  tokenStorage,
  userStorage,
  searchStorage,
  stationStorage,
  payMethodStorage,
  selectedAddressStorage,
  deliveryAddressStorage
}
