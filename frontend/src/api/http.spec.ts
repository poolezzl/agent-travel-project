import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  ApiError,
  clearAuthTokens,
  getAccessToken,
  request,
  setAuthTokens,
  setUnauthorizedRefreshHandler,
  toErrorInfo,
} from './http'

interface FakeResponse {
  ok: boolean
  status: number
  statusText: string
  headers: { get: (name: string) => string | null }
  json: () => Promise<unknown>
  text: () => Promise<string>
}

function jsonResponse(
  status: number,
  body: unknown,
  contentType = 'application/json',
): FakeResponse {
  const text = JSON.stringify(body)
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: status >= 200 && status < 300 ? 'OK' : 'Error',
    headers: {
      get: (name: string) => (name.toLowerCase() === 'content-type' ? contentType : null),
    },
    json: async () => body,
    text: async () => text,
  }
}

function problemResponse(status: number, problem: Record<string, unknown>): FakeResponse {
  return jsonResponse(status, problem, 'application/problem+json')
}

const problem = {
  type: 'https://agent-travel.local/problems/INVALID_ARGUMENT',
  title: 'Bad Request',
  status: 400,
  detail: '参数错误',
  code: 'INVALID_ARGUMENT',
  traceId: 'trace-abc',
  timestamp: '2026-11-11T00:00:00Z',
}

describe('http request', () => {
  beforeEach(() => {
    clearAuthTokens()
    setUnauthorizedRefreshHandler(null)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('解析 ProblemDetail 为 ApiError（含 code/traceId）', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => problemResponse(400, problem)),
    )

    await expect(request('/api/v1/tasks')).rejects.toMatchObject({
      name: 'ApiError',
      code: 'INVALID_ARGUMENT',
      status: 400,
      traceId: 'trace-abc',
      message: '参数错误',
    })
  })

  it('401 触发一次刷新并重放原请求', async () => {
    setAuthTokens('old-access', 'old-refresh')
    let refreshed = false
    setUnauthorizedRefreshHandler(async () => {
      refreshed = true
      setAuthTokens('new-access', 'new-refresh')
    })

    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValueOnce(jsonResponse(200, { ok: true }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await request('/api/v1/auth/me')

    expect(result).toEqual({ ok: true })
    expect(refreshed).toBe(true)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    // 重放请求使用新令牌
    const retryInit = fetchMock.mock.calls[1]?.[1]
    expect(retryInit?.headers).toMatchObject({ Authorization: 'Bearer new-access' })
  })

  it('刷新失败清空令牌并抛出刷新错误', async () => {
    setAuthTokens('old-access', 'old-refresh')
    setUnauthorizedRefreshHandler(async () => {
      throw new ApiError({ code: 'UNAUTHENTICATED', message: '刷新令牌无效', status: 401 })
    })

    vi.stubGlobal(
      'fetch',
      vi.fn(async () => problemResponse(401, { ...problem, code: 'UNAUTHENTICATED' })),
    )

    await expect(request('/api/v1/auth/me')).rejects.toMatchObject({ code: 'UNAUTHENTICATED' })
    expect(getAccessToken()).toBeNull()
  })

  it('并发 401 只触发一次刷新（inflight 去重）', async () => {
    setAuthTokens('old-access', 'old-refresh')
    let refreshCount = 0
    setUnauthorizedRefreshHandler(async () => {
      refreshCount += 1
      setAuthTokens('new-access', 'new-refresh')
    })

    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValueOnce(jsonResponse(401, {}))
      .mockResolvedValue(jsonResponse(200, { ok: true }))
    vi.stubGlobal('fetch', fetchMock)

    const [a, b] = await Promise.all([request('/api/v1/a'), request('/api/v1/b')])

    expect(a).toEqual({ ok: true })
    expect(b).toEqual({ ok: true })
    expect(refreshCount).toBe(1)
    expect(fetchMock).toHaveBeenCalledTimes(4)
  })

  it('写请求携带 Idempotency-Key 头', async () => {
    const fetchMock = vi.fn(async () => jsonResponse(201, { task_id: 't1' }))
    vi.stubGlobal('fetch', fetchMock)

    await request('/api/v1/tasks', { method: 'POST', body: {}, idempotencyKey: 'key-1' })

    const init = fetchMock.mock.calls[0]?.[1]
    expect(init?.headers).toMatchObject({ 'Idempotency-Key': 'key-1' })
  })
})

describe('toErrorInfo', () => {
  it('ApiError 携带 code/message/traceId', () => {
    expect(
      toErrorInfo(new ApiError({ code: 'X', message: 'm', status: 500, traceId: 't' })),
    ).toEqual({ code: 'X', message: 'm', status: 500, traceId: 't' })
  })

  it('普通 Error 归一为 CLIENT_ERROR', () => {
    expect(toErrorInfo(new Error('网络错误'))).toEqual({
      code: 'CLIENT_ERROR',
      message: '网络错误',
    })
  })
})
