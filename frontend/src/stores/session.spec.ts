import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, clearAuthTokens, getAccessToken } from '@/api/http'
import type { CurrentUser, TokenResponse } from '@/api/types'
import { useSessionStore } from './session'

vi.mock('@/api/auth', () => ({
  register: vi.fn(),
  login: vi.fn(),
  refresh: vi.fn(),
  logout: vi.fn(),
  me: vi.fn(),
}))

import * as authApi from '@/api/auth'

const tokenResponse: TokenResponse = {
  accessToken: 'access-a',
  refreshToken: 'refresh-r',
  expiresInSeconds: 900,
  userId: 'u1',
  role: 'USER',
}

const currentUser: CurrentUser = {
  userId: 'u1',
  login: 'alice',
  role: 'USER',
  status: 'ACTIVE',
  createdAt: '2026-11-11T00:00:00Z',
}

beforeEach(() => {
  setActivePinia(createPinia())
  clearAuthTokens()
  vi.clearAllMocks()
})

describe('useSessionStore', () => {
  it('登录成功后令牌只驻留内存，并拉取当前用户', async () => {
    vi.mocked(authApi.login).mockResolvedValue(tokenResponse)
    vi.mocked(authApi.me).mockResolvedValue(currentUser)

    const store = useSessionStore()
    await store.login('alice', 'password123')

    expect(store.isAuthenticated).toBe(true)
    expect(store.accessToken).toBe('access-a')
    expect(store.user?.login).toBe('alice')
    expect(getAccessToken()).toBe('access-a')
    // 不写入本地存储
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })

  it('退出登录清空会话与 http 令牌', async () => {
    vi.mocked(authApi.login).mockResolvedValue(tokenResponse)
    vi.mocked(authApi.me).mockResolvedValue(currentUser)
    vi.mocked(authApi.logout).mockResolvedValue(undefined)

    const store = useSessionStore()
    await store.login('alice', 'password123')
    await store.logout()

    expect(store.isAuthenticated).toBe(false)
    expect(store.accessToken).toBeNull()
    expect(getAccessToken()).toBeNull()
  })

  it('登录失败把错误写入 store.error 并抛出', async () => {
    vi.mocked(authApi.login).mockRejectedValue(
      new ApiError({ code: 'INVALID_CREDENTIALS', message: '密码错误', status: 401 }),
    )

    const store = useSessionStore()
    await expect(store.login('alice', 'wrong')).rejects.toBeTruthy()
    expect(store.isAuthenticated).toBe(false)
    expect(store.error?.code).toBe('INVALID_CREDENTIALS')
  })

  it('fetchMe 遇到网络/5xx 错误保留登录态', async () => {
    vi.mocked(authApi.me).mockRejectedValue(
      new ApiError({ code: 'INTERNAL', message: '服务器错误', status: 500 }),
    )

    const store = useSessionStore()
    store.applySession(tokenResponse)

    await expect(store.fetchMe()).rejects.toBeTruthy()
    expect(store.isAuthenticated).toBe(true)
  })

  it('fetchMe 遇到 401 清空会话', async () => {
    vi.mocked(authApi.me).mockRejectedValue(
      new ApiError({ code: 'UNAUTHENTICATED', message: '登录失效', status: 401 }),
    )

    const store = useSessionStore()
    store.applySession(tokenResponse)

    await expect(store.fetchMe()).rejects.toBeTruthy()
    expect(store.isAuthenticated).toBe(false)
    expect(getAccessToken()).toBeNull()
  })
})
