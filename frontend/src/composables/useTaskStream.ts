// 任务 SSE 订阅：fetch + ReadableStream 手动解析 text/event-stream。
// 后端 SSE 只接受 Authorization: Bearer 头（不支持 query token），故不能用 EventSource。
// 支持断线带 after_sequence_no 续传；终态关闭连接并停止重连；组件卸载时 close() 触发 abort。
// 401/403/404 视为不可重试，停止重连；其余网络错误按指数退避重连并受最大次数限制。

import { ref } from 'vue'
import type { Ref } from 'vue'

import { authorizationHeader, readApiError } from '@/api/http'
import { isTerminalStatus } from '@/api/taskStatus'
import { eventsUrl } from '@/api/tasks'
import type { TaskEvent } from '@/api/types'
import { acceptEvents, createSseParser, parseTaskEvent } from './sseParser'
import type { SseFrame } from './sseParser'

const MAX_RECONNECT_DELAY_MS = 30_000

export interface UseTaskStreamOptions {
  /** 连接建立时读取当前任务 id（同一实例可跨任务复用）。 */
  getTaskId: () => string
  /** 连接建立时读取续传起点序号。 */
  getLastSequenceNo: () => number
  onEvent: (event: TaskEvent) => void
  onDone: () => void
  onError: (error: unknown) => void
  /** 重连基础延迟（默认 2000ms），按 2^n 指数退避，上限 30s。 */
  reconnectDelayMs?: number
  /** 最大重连次数（默认 5）。 */
  maxReconnectAttempts?: number
  /** 测试注入：默认取全局 fetch。 */
  fetchImpl?: typeof fetch
}

export interface TaskStreamController {
  connect: () => Promise<void>
  /** 停止当前连接并清除重连定时器，但保留后续 connect() 能力（用于取消/切换任务）。 */
  disconnect: () => void
  /** 永久关闭（组件卸载）。 */
  close: () => void
  connected: Ref<boolean>
}

function isNoRetryStatus(status: number | undefined): boolean {
  return status === 401 || status === 403 || status === 404
}

export function useTaskStream(options: UseTaskStreamOptions): TaskStreamController {
  const connected = ref(false)
  const fetchImpl = options.fetchImpl ?? fetch
  const baseDelay = options.reconnectDelayMs ?? 2000
  const maxAttempts = options.maxReconnectAttempts ?? 5

  let controller: AbortController | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let closedByUser = false
  let generation = 0
  let lastAcceptedSeq = 0
  let reconnectAttempts = 0

  function clearReconnectTimer(): void {
    if (reconnectTimer !== null) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
  }

  function scheduleReconnect(): void {
    if (closedByUser) return
    if (reconnectAttempts >= maxAttempts) {
      options.onError(new Error('重连次数已达上限，已停止自动重连'))
      return
    }
    clearReconnectTimer()
    reconnectAttempts += 1
    const delay = Math.min(baseDelay * 2 ** (reconnectAttempts - 1), MAX_RECONNECT_DELAY_MS)
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      void connectInternal()
    }, delay)
  }

  async function connect(): Promise<void> {
    reconnectAttempts = 0
    await connectInternal()
  }

  async function connectInternal(): Promise<void> {
    if (closedByUser) return
    controller?.abort()
    controller = new AbortController()
    const myGeneration = ++generation
    connected.value = true
    clearReconnectTimer()
    lastAcceptedSeq = options.getLastSequenceNo()

    let terminalSeen = false

    const handleFrame = (frame: SseFrame): void => {
      const parsed = parseTaskEvent(frame.data)
      if (parsed.done) {
        terminalSeen = true
        options.onDone()
        return
      }
      if (!parsed.event) return
      const accepted = acceptEvents([parsed.event], lastAcceptedSeq)
      for (const event of accepted) {
        lastAcceptedSeq = event.sequence_no
        options.onEvent(event)
        if (isTerminalStatus(event.status)) {
          terminalSeen = true
          options.onDone()
          return
        }
      }
    }

    let reader: {
      read: () => Promise<ReadableStreamReadResult<Uint8Array>>
      cancel: () => Promise<void>
    } | null = null

    try {
      const response = await fetchImpl(eventsUrl(options.getTaskId(), lastAcceptedSeq), {
        headers: { Accept: 'text/event-stream', ...authorizationHeader() },
        signal: controller.signal,
      })

      if (!response.ok) {
        if (myGeneration === generation && !closedByUser) {
          connected.value = false
          const error = await readApiError(response)
          options.onError(error)
          if (!isNoRetryStatus(error.status)) {
            scheduleReconnect()
          }
        }
        return
      }

      // 成功建立连接，重置退避计数。
      reconnectAttempts = 0

      if (!response.body) {
        if (myGeneration === generation && !closedByUser) {
          connected.value = false
          scheduleReconnect()
        }
        return
      }

      reader = response.body.getReader()
      const decoder = new TextDecoder()
      const parser = createSseParser()

      while (true) {
        const result = await reader.read()
        if (result.done) break
        const text = decoder.decode(result.value, { stream: true })
        for (const frame of parser.push(text)) {
          handleFrame(frame)
          if (terminalSeen) break
        }
        if (terminalSeen) break
      }

      if (!terminalSeen) {
        // 流结束：收尾解码器，冲刷跨 chunk 边界残留的字节（避免多字节字符被截断丢失）。
        const tailText = decoder.decode()
        for (const frame of parser.push(tailText)) {
          handleFrame(frame)
          if (terminalSeen) break
        }
      }

      if (!terminalSeen) {
        for (const frame of parser.flush()) {
          handleFrame(frame)
          if (terminalSeen) break
        }
      }
    } catch (error) {
      if (myGeneration === generation && !closedByUser && !terminalSeen) {
        connected.value = false
        options.onError(error)
        scheduleReconnect()
        return
      }
    } finally {
      if (myGeneration === generation) connected.value = false
      if (reader) void reader.cancel().catch(() => undefined)
    }

    if (myGeneration === generation && !closedByUser && !terminalSeen) {
      scheduleReconnect()
    }
  }

  function disconnect(): void {
    generation += 1
    clearReconnectTimer()
    controller?.abort()
    connected.value = false
  }

  function close(): void {
    closedByUser = true
    generation += 1
    clearReconnectTimer()
    controller?.abort()
    connected.value = false
  }

  return { connect, disconnect, close, connected }
}
