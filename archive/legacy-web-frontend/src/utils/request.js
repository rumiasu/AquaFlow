import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

// ========== 请求拦截：自动附加 access_token ==========
request.interceptors.request.use(config => {
  const token = localStorage.getItem('accessToken')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }

  // 注意：禁止在请求里自动注入 stationId。
  // 水站归属必须以后端登录态（AuthContext）为准，前端传入的 stationId 一律视为不可信，
  // 否则改一行 localStorage 即可越权遍历其它水站数据（见安全测评 A5）。
  return config
})

// ========== Token 自动续期 ==========
let isRefreshing = false
let failedQueue = []

const processQueue = (error, token = null) => {
  failedQueue.forEach(prom => {
    if (error) {
      prom.reject(error)
    } else {
      prom.resolve(token)
    }
  })
  failedQueue = []
}

const refreshAccessToken = async () => {
  const refreshToken = localStorage.getItem('refreshToken')
  if (!refreshToken) {
    throw new Error('无refreshToken')
  }

  // 直接用 axios 发请求，避免被拦截器循环
  const res = await axios.post('/api/auth/refresh', { refreshToken })
  if (res.data && res.data.code === 0) {
    const { accessToken, refreshToken: newRefreshToken } = res.data.data
    localStorage.setItem('accessToken', accessToken)
    // refresh_token 不变，但也更新一下（防止后端轮换）
    if (newRefreshToken) {
      localStorage.setItem('refreshToken', newRefreshToken)
    }
    return accessToken
  } else {
    throw new Error(res.data?.message || '刷新令牌失败')
  }
}

// ========== 响应拦截：401 自动续期 ==========
request.interceptors.response.use(
  response => {
    const res = response.data
    if (res.code !== 0) {
      ElMessage.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message))
    }
    return res.data
  },
  async error => {
    const originalRequest = error.config

    // 401 且不是刷新/登录请求 → 尝试自动续期
    if (error.response && error.response.status === 401 &&
        !originalRequest._retry &&
        !originalRequest.url.includes('/auth/refresh') &&
        !originalRequest.url.includes('/auth/login')) {

      if (isRefreshing) {
        // 正在刷新中，排队等待
        return new Promise((resolve, reject) => {
          failedQueue.push({ resolve, reject })
        }).then(token => {
          originalRequest.headers.Authorization = `Bearer ${token}`
          return request(originalRequest)
        })
      }

      originalRequest._retry = true
      isRefreshing = true

      try {
        const newToken = await refreshAccessToken()
        processQueue(null, newToken)
        originalRequest.headers.Authorization = `Bearer ${newToken}`
        return request(originalRequest)
      } catch (refreshError) {
        processQueue(refreshError, null)
        // 续期失败，清除登录状态
        localStorage.removeItem('accessToken')
        localStorage.removeItem('refreshToken')
        localStorage.removeItem('userInfo')
        localStorage.removeItem('userRole')
        localStorage.removeItem('stationId')
        localStorage.removeItem('staffId')
        router.push('/login')
        ElMessage.error('登录已过期，请重新登录')
        return Promise.reject(refreshError)
      } finally {
        isRefreshing = false
      }
    }

    // 非 401 错误
    if (!error.response || error.response.status !== 401) {
      ElMessage.error(error.message || '网络错误')
    }
    return Promise.reject(error)
  }
)

export default request
