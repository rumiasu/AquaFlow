// 本地存储工具

const storage = {
  // 获取
  get: (key) => {
    try {
      return wx.getStorageSync(key)
    } catch (e) {
      console.error('Storage get error:', e)
      return null
    }
  },

  // 设置
  set: (key, value) => {
    try {
      wx.setStorageSync(key, value)
      return true
    } catch (e) {
      console.error('Storage set error:', e)
      return false
    }
  },

  // 移除
  remove: (key) => {
    try {
      wx.removeStorageSync(key)
      return true
    } catch (e) {
      console.error('Storage remove error:', e)
      return false
    }
  },

  // 清空
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

// Token相关
const tokenStorage = {
  get: () => storage.get('token'),
  set: (token) => storage.set('token', token),
  remove: () => storage.remove('token')
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
      searchStorage.set(history)
    }
  },
  clear: () => storage.set('searchHistory', [])
}

module.exports = {
  storage,
  tokenStorage,
  userStorage,
  searchStorage
}
