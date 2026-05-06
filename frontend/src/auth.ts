import { reactive } from 'vue'
import axios from 'axios'
import { API_BASE_URL } from './config'
import type { User } from './types'

const TOKEN_KEY = 'he_manager_token'

export const authState = reactive({
  ready: false,
  token: localStorage.getItem(TOKEN_KEY) || '',
  user: null as User | null,
  hasUsers: true,
  error: '',
})

const applyToken = (token: string) => {
  authState.token = token
  if (token) {
    localStorage.setItem(TOKEN_KEY, token)
    axios.defaults.headers.common.Authorization = `Bearer ${token}`
  } else {
    localStorage.removeItem(TOKEN_KEY)
    delete axios.defaults.headers.common.Authorization
  }
}

export const initAuth = async () => {
  authState.ready = false
  authState.error = ''
  applyToken(authState.token)
  try {
    const status = await axios.get(`${API_BASE_URL}/auth/status`, { timeout: 5000 })
    authState.hasUsers = Boolean(status.data.has_users)

    if (authState.token) {
      const me = await axios.get(`${API_BASE_URL}/auth/me`, { timeout: 5000 })
      authState.user = me.data
    }
  } catch (err: any) {
    applyToken('')
    authState.user = null
    authState.error = err.code === 'ECONNABORTED'
      ? '连接后端超时，请确认 HE Manager 服务正在运行。'
      : '无法连接后端，请确认 HE Manager 服务正在运行。'
  } finally {
    authState.ready = true
  }
}

export const login = async (username: string, password: string) => {
  const res = await axios.post(`${API_BASE_URL}/auth/login`, { username, password })
  applyToken(res.data.access_token)
  authState.user = res.data.user
  authState.hasUsers = true
}

export const bootstrapAdmin = async (username: string, password: string) => {
  const res = await axios.post(`${API_BASE_URL}/auth/bootstrap`, { username, password, is_admin: true })
  applyToken(res.data.access_token)
  authState.user = res.data.user
  authState.hasUsers = true
}

export const logout = () => {
  applyToken('')
  authState.user = null
}
