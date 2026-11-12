// fetch 封装：同源代理 baseURL、自动附加 Authorization、解析 ProblemDetail、401 一次刷新重放、超时。
// 访问/刷新令牌只驻留内存（见 setAuthTokens/getAccessToken），不落 localStorage/sessionStorage。

import type { ProblemDetail } from './types'

const BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '')

export interface ApiErrorInfo {
  code: string
  message: string
  status: number
  detail?: string
  traceId?: string
  type?: string
  title?: string
  timestamp?: string
}

/** 统一错误对象：{ code, message, traceId }（另保留 status/detail/type/title/timestamp 便于展示）。 */
export class ApiError extends Error {
  readonly code: string
  readonly status: number
  readonly detail?: string
  readonly traceId?: string
  readonly type?: string
  readonly title?: string
  readonly timestamp?: string

  constructor(info: ApiErrorInfo) {
    super(info.message)
    this.name = 'ApiError'
    this.code = info.code
    this.status = info.status
    this.detail = info.detail
    this.traceId = info.traceId
    this.type = info.type
    this.title = info.title
    this.timestamp = info.timestamp
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  /** 写请求幂等键；省略则不携带该头。 */
  idempotencyKey?: string
  /** 默认 30s；到期 AbortController 中断。 */
  timeoutMs?: number
  signal?: AbortSignal
  /** 是否附加 Authorization 头（默认 true）。 */
  auth?: boolean
  /** 401 时是否触发一次刷新重放（默认 true）。 */
  retryOnUnauthorized?: boolean
}

type RefreshHandler = () => Promise<void>

let accessToken: string | null = null
let refreshToken: string | null = null
let refreshHandler: RefreshHandler | null = null
let refreshInflight: Promise<void> | null = null

export function setAuthTokens(access: string, refresh: string): void {
  accessToken = access
  refreshToken = refresh
}

export function clearAuthTokens(): void {
  accessToken = null
  refreshToken = null
}

export function getAccessToken(): string | null {
  return accessToken
}

export function getRefreshToken(): string | null {
  return refreshToken
}

/** session store 注册 401 刷新处理器（刷新失败时由处理器负责清空会话）。 */
export function setUnauthorizedRefreshHandler(handler: RefreshHandler | null): void {
  refreshHandler = handler
}

/** 构造完整请求地址（baseURL 为空时走同源代理 /api）。 */
export function apiUrl(path: string): string {
  return `${BASE_URL}${path}`
}

function authHeader(): Record<string, string> {
  return accessToken ? { Authorization: `Bearer ${accessToken}` } : {}
}

function isProblemContentType(contentType: string | null): boolean {
  return contentType !== null && contentType.includes('application/problem+json')
}

async function toApiError(response: Response): Promise<ApiError> {
  let problem: ProblemDetail | null = null
  if (isProblemContentType(response.headers.get('content-type'))) {
    try {
      problem = (await response.json()) as ProblemDetail
    } catch {
      problem = null
    }
  }
  if (problem) {
    return new ApiError({
      code: problem.code,
      message: problem.detail || problem.title || '请求失败',
      status: problem.status || response.status,
      detail: problem.detail,
      traceId: problem.traceId,
      type: problem.type,
      title: problem.title,
      timestamp: problem.timestamp,
    })
  }
  return new ApiError({
    code: 'HTTP_ERROR',
    message: response.statusText || `请求失败（HTTP ${response.status}）`,
    status: response.status,
  })
}

/** 供 SSE 等非 JSON 请求复用的错误解析入口。 */
export function readApiError(response: Response): Promise<ApiError> {
  return toApiError(response)
}

export interface ErrorInfo {
  code: string
  message: string
  status?: number
  traceId?: string
}

/** 把任意抛错归一为 { code, message, traceId? }，便于 UI 与 store 统一展示。 */
export function toErrorInfo(error: unknown): ErrorInfo {
  if (error instanceof ApiError) {
    return {
      code: error.code,
      message: error.message,
      status: error.status,
      traceId: error.traceId,
    }
  }
  if (error instanceof Error) {
    return { code: 'CLIENT_ERROR', message: error.message }
  }
  return { code: 'CLIENT_ERROR', message: '未知错误' }
}

async function parseSuccess<T>(response: Response): Promise<T | undefined> {
  if (response.status === 204) return undefined
  const text = await response.text()
  if (text.length === 0) return undefined
  return JSON.parse(text) as T
}

async function doFetch(path: string, options: RequestOptions): Promise<Response> {
  const method = options.method ?? 'GET'
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (options.auth !== false) Object.assign(headers, authHeader())
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey

  const controller = new AbortController()
  const timeoutMs = options.timeoutMs ?? 30_000
  const timer = setTimeout(
    () => controller.abort(new DOMException('timeout', 'TimeoutError')),
    timeoutMs,
  )
  const onExternalAbort = (): void => controller.abort(options.signal?.reason)
  options.signal?.addEventListener('abort', onExternalAbort, { once: true })

  try {
    return await fetch(apiUrl(path), {
      method,
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      signal: controller.signal,
    })
  } finally {
    clearTimeout(timer)
    options.signal?.removeEventListener('abort', onExternalAbort)
  }
}

/** 同一时刻只发起一次刷新（令牌轮换）：并发 401 复用同一个 inflight promise，避免互相踢下线。 */
async function refreshOnce(): Promise<void> {
  if (refreshInflight) return refreshInflight
  const handler = refreshHandler
  if (!handler) {
    throw new ApiError({ code: 'UNAUTHENTICATED', message: '未注册刷新处理器', status: 401 })
  }
  refreshInflight = (async () => {
    try {
      await handler()
    } catch (error) {
      clearAuthTokens()
      throw error
    } finally {
      refreshInflight = null
    }
  })()
  return refreshInflight
}

/**
 * 发起请求并解析响应。401 且带 refresh token 时触发一次刷新重放；刷新失败抛出刷新错误并清空令牌。
 */
export async function request<T>(
  path: string,
  options: RequestOptions = {},
): Promise<T | undefined> {
  const response = await doFetch(path, options)

  const canRetry =
    options.retryOnUnauthorized !== false &&
    options.auth !== false &&
    response.status === 401 &&
    accessToken !== null &&
    refreshToken !== null &&
    refreshHandler !== null

  if (!canRetry) {
    if (response.ok) return parseSuccess<T>(response)
    throw await toApiError(response)
  }

  // 刷新失败：refreshOnce 已清空令牌并抛出刷新错误（session store 已在处理器中清空用户态）。
  await refreshOnce()

  const retried = await doFetch(path, options)
  if (retried.ok) return parseSuccess<T>(retried)
  throw await toApiError(retried)
}

/** SSE 等流式请求复用此头（fetch + ReadableStream 手动解析，见 useTaskStream）。 */
export function authorizationHeader(): Record<string, string> {
  return authHeader()
}
