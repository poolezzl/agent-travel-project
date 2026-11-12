// 会话 store：访问/刷新令牌只驻留内存（Pinia），不写 localStorage/sessionStorage；刷新页面即需重新登录。
// 401 时由 http 层触发一次刷新重放；刷新失败清空会话。

import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import * as authApi from '@/api/auth'
import {
  ApiError,
  clearAuthTokens,
  setAuthTokens,
  setUnauthorizedRefreshHandler,
  toErrorInfo,
} from '@/api/http'
import type { ErrorInfo } from '@/api/http'
import type { CurrentUser, TokenResponse } from '@/api/types'

export const useSessionStore = defineStore('session', () => {
  const accessToken = ref<string | null>(null)
  const refreshToken = ref<string | null>(null)
  const user = ref<CurrentUser | null>(null)
  const loading = ref(false)
  const error = ref<ErrorInfo | null>(null)

  const isAuthenticated = computed(() => accessToken.value !== null)

  function applySession(tokens: TokenResponse | undefined): void {
    if (!tokens) return
    accessToken.value = tokens.accessToken
    refreshToken.value = tokens.refreshToken
    setAuthTokens(tokens.accessToken, tokens.refreshToken)
  }

  function clearSession(): void {
    accessToken.value = null
    refreshToken.value = null
    user.value = null
    clearAuthTokens()
  }

  function ensureRefreshHandler(): void {
    setUnauthorizedRefreshHandler(async () => {
      const currentRefresh = refreshToken.value
      if (!currentRefresh) throw new Error('缺少刷新令牌')
      try {
        const tokens = await authApi.refresh({ refreshToken: currentRefresh })
        applySession(tokens)
      } catch (refreshError) {
        clearSession()
        throw refreshError
      }
    })
  }

  async function fetchMe(): Promise<void> {
    if (!isAuthenticated.value) return
    try {
      const me = await authApi.me()
      user.value = me ?? null
    } catch (fetchError) {
      // 仅 401（刷新重放后仍失败）才清会话；其他错误（网络/5xx）保留登录态并交由上层提示。
      if (fetchError instanceof ApiError && fetchError.status === 401) {
        clearSession()
      }
      throw fetchError
    }
  }

  async function register(login: string, password: string): Promise<void> {
    loading.value = true
    error.value = null
    try {
      applySession(await authApi.register({ login, password }))
      await fetchMe()
    } catch (registerError) {
      error.value = toErrorInfo(registerError)
      throw registerError
    } finally {
      loading.value = false
    }
  }

  async function login(login: string, password: string): Promise<void> {
    loading.value = true
    error.value = null
    try {
      applySession(await authApi.login({ login, password }))
      await fetchMe()
    } catch (loginError) {
      error.value = toErrorInfo(loginError)
      throw loginError
    } finally {
      loading.value = false
    }
  }

  async function logout(): Promise<void> {
    const currentRefresh = refreshToken.value
    try {
      if (currentRefresh) await authApi.logout({ refreshToken: currentRefresh })
    } finally {
      clearSession()
    }
  }

  return {
    accessToken,
    refreshToken,
    user,
    loading,
    error,
    isAuthenticated,
    applySession,
    clearSession,
    ensureRefreshHandler,
    fetchMe,
    register,
    login,
    logout,
  }
})
