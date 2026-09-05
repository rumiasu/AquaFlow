// 本地存储工具

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

module.exports = {
  storage,
  tokenStorage,
  userStorage,
  searchStorage,
  stationStorage
}