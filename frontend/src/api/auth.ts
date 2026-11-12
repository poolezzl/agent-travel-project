// 认证接口封装。来源：contracts/openapi/s08-public.yaml。

import { request } from './http'
import type {
  CurrentUser,
  LoginRequest,
  RefreshRequest,
  RegisterRequest,
  TokenResponse,
} from './types'

export function register(payload: RegisterRequest): Promise<TokenResponse | undefined> {
  return request<TokenResponse>('/api/v1/auth/register', {
    method: 'POST',
    body: payload,
    auth: false,
    retryOnUnauthorized: false,
  })
}

export function login(payload: LoginRequest): Promise<TokenResponse | undefined> {
  return request<TokenResponse>('/api/v1/auth/login', {
    method: 'POST',
    body: payload,
    auth: false,
    retryOnUnauthorized: false,
  })
}

export function refresh(payload: RefreshRequest): Promise<TokenResponse | undefined> {
  return request<TokenResponse>('/api/v1/auth/refresh', {
    method: 'POST',
    body: payload,
    auth: false,
    retryOnUnauthorized: false,
  })
}

export function logout(payload: RefreshRequest): Promise<void> {
  return request<void>('/api/v1/auth/logout', {
    method: 'POST',
    body: payload,
    retryOnUnauthorized: false,
  }).then(() => undefined)
}

export function me(): Promise<CurrentUser | undefined> {
  return request<CurrentUser>('/api/v1/auth/me')
}
