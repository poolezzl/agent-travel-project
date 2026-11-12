import { describe, expect, it, vi } from 'vitest'

import type { TaskEvent } from '@/api/types'
import { useTaskStream } from './useTaskStream'

function makeEvent(sequenceNo: number, status: string): TaskEvent {
  return {
    sequence_no: sequenceNo,
    event_type: 'TASK_STATUS_CHANGED',
    status,
    created_at: '2026-11-11T00:00:00Z',
  }
}

function frame(event: TaskEvent): string {
  return `id: ${event.sequence_no}\nevent: task-progress\ndata: ${JSON.stringify(event)}\n\n`
}

interface FakeReader {
  read: () => Promise<{ done: boolean; value: Uint8Array | undefined }>
  cancel: () => Promise<void>
}

function streamFetch(chunks: string[]): typeof fetch {
  const encoder = new TextEncoder()
  const values = chunks.map((chunk) => encoder.encode(chunk))
  let index = 0
  const reader: FakeReader = {
    read: async () =>
      index < values.length
        ? { done: false, value: values[index++] }
        : { done: true, value: undefined },
    cancel: async () => undefined,
  }
  const fn = vi.fn(async () => ({ ok: true, status: 200, body: { getReader: () => reader } }))
  return fn as unknown as typeof fetch
}

function byteStreamFetch(chunks: Uint8Array[]): typeof fetch {
  let index = 0
  const reader: FakeReader = {
    read: async () =>
      index < chunks.length
        ? { done: false, value: chunks[index++] }
        : { done: true, value: undefined },
    cancel: async () => undefined,
  }
  const fn = vi.fn(async () => ({ ok: true, status: 200, body: { getReader: () => reader } }))
  return fn as unknown as typeof fetch
}

function errorResponse(status: number): typeof fetch {
  const problem = {
    type: 'https://agent-travel.local/problems/X',
    title: 'Error',
    status,
    detail: 'err',
    code: status === 401 ? 'UNAUTHENTICATED' : status === 403 ? 'FORBIDDEN' : 'NOT_FOUND',
    traceId: 'trace-x',
    timestamp: '2026-11-11T00:00:00Z',
  }
  const fn = vi.fn(async () => ({
    ok: false,
    status,
    headers: {
      get: (name: string) =>
        name.toLowerCase() === 'content-type' ? 'application/problem+json' : null,
    },
    json: async () => problem,
    text: async () => JSON.stringify(problem),
  }))
  return fn as unknown as typeof fetch
}

describe('useTaskStream', () => {
  it('接收事件并去重，终态触发 onDone 且不再重连', async () => {
    vi.useFakeTimers()
    try {
      const delivered: TaskEvent[] = []
      const fetchImpl = streamFetch([
        frame(makeEvent(1, 'QUEUED')),
        frame(makeEvent(2, 'SUCCEEDED')),
      ])

      const stream = useTaskStream({
        getTaskId: () => 'task-1',
        getLastSequenceNo: () => 0,
        onEvent: (event) => delivered.push(event),
        onDone: vi.fn(),
        onError: vi.fn(),
        reconnectDelayMs: 2000,
        fetchImpl,
      })

      await stream.connect()

      expect(delivered.map((e) => e.sequence_no)).toEqual([1, 2])
      expect(fetchImpl).toHaveBeenCalledTimes(1)

      // 终态后推进超过重连延迟，也不应再次连接
      await vi.advanceTimersByTimeAsync(5000)
      expect(fetchImpl).toHaveBeenCalledTimes(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('从 lastSequenceNo 续传（after_sequence_no 查询参数）', async () => {
    const delivered: TaskEvent[] = []
    const fetchImpl = streamFetch([frame(makeEvent(5, 'SUCCEEDED'))])

    const stream = useTaskStream({
      getTaskId: () => 'task-1',
      getLastSequenceNo: () => 4,
      onEvent: (event) => delivered.push(event),
      onDone: vi.fn(),
      onError: vi.fn(),
      fetchImpl,
    })

    await stream.connect()

    const url = fetchImpl.mock.calls[0]?.[0] as string
    expect(url).toContain('after_sequence_no=4')
    expect(delivered.map((e) => e.sequence_no)).toEqual([5])
  })

  it('流结束 flush 解码器，中文字符跨 chunk 拆分不丢字、无乱码', async () => {
    const message = '排队中'
    const event: TaskEvent = { ...makeEvent(1, 'QUEUED'), message }
    const prefix = 'id: 1\nevent: task-progress\ndata: '
    const suffix = '\n\n'
    const full = `${prefix}${JSON.stringify(event)}${suffix}`
    const bytes = new TextEncoder().encode(full)
    // 在 message 首字节后切开，使 UTF-8 多字节序列跨 chunk 边界。
    const messageStart =
      new TextEncoder().encode(prefix).length + JSON.stringify(event).indexOf(message)
    const split = messageStart + 1
    const chunks = [bytes.slice(0, split), bytes.slice(split)]

    const delivered: TaskEvent[] = []
    const fetchImpl = byteStreamFetch(chunks)

    const stream = useTaskStream({
      getTaskId: () => 'task-1',
      getLastSequenceNo: () => 0,
      onEvent: (e) => delivered.push(e),
      onDone: vi.fn(),
      onError: vi.fn(),
      fetchImpl,
    })

    await stream.connect()

    expect(delivered).toHaveLength(1)
    expect(delivered[0]?.message).toBe(message)
  })

  it('401 停止重连（不重试）', async () => {
    vi.useFakeTimers()
    try {
      const onError = vi.fn()
      const fetchImpl = errorResponse(401)

      const stream = useTaskStream({
        getTaskId: () => 'task-1',
        getLastSequenceNo: () => 0,
        onEvent: vi.fn(),
        onDone: vi.fn(),
        onError,
        fetchImpl,
      })

      await stream.connect()

      expect(onError).toHaveBeenCalledTimes(1)
      await vi.advanceTimersByTimeAsync(60_000)
      expect(fetchImpl).toHaveBeenCalledTimes(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('网络错误按指数退避重连并受最大次数限制', async () => {
    vi.useFakeTimers()
    try {
      const onError = vi.fn()
      const fetchImpl = vi.fn(async () => {
        throw new Error('network')
      })

      const stream = useTaskStream({
        getTaskId: () => 'task-1',
        getLastSequenceNo: () => 0,
        onEvent: vi.fn(),
        onDone: vi.fn(),
        onError,
        reconnectDelayMs: 1000,
        maxReconnectAttempts: 3,
        fetchImpl: fetchImpl as unknown as typeof fetch,
      })

      await stream.connect()
      expect(fetchImpl).toHaveBeenCalledTimes(1)

      await vi.advanceTimersByTimeAsync(1000)
      expect(fetchImpl).toHaveBeenCalledTimes(2)

      await vi.advanceTimersByTimeAsync(2000)
      expect(fetchImpl).toHaveBeenCalledTimes(3)

      await vi.advanceTimersByTimeAsync(4000)
      expect(fetchImpl).toHaveBeenCalledTimes(4)

      await vi.advanceTimersByTimeAsync(60_000)
      expect(fetchImpl).toHaveBeenCalledTimes(4)
    } finally {
      vi.useRealTimers()
    }
  })
})
