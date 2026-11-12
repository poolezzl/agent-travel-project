// SSE 手动解析器（纯函数，可单测）。
// 后端 SSE 只接受 Authorization: Bearer 头，禁止使用 EventSource，故用 fetch + ReadableStream 手动解析
// text/event-stream。支持 id:/event:/data: 三种行、空行分隔事件、[DONE] 与流结束。

import type { TaskEvent } from '@/api/types'

export interface SseFrame {
  id: string | null
  event: string | null
  data: string
}

export interface SseParser {
  push(chunk: string): SseFrame[]
  /** 流结束时冲刷残留缓冲行与未派发事件。 */
  flush(): SseFrame[]
}

/** 返回一个跨 chunk 边界的行缓冲解析器。 */
export function createSseParser(): SseParser {
  let buffer = ''
  let id: string | null = null
  let event: string | null = null
  const dataLines: string[] = []

  function dispatch(): SseFrame | null {
    if (id === null && event === null && dataLines.length === 0) return null
    const frame: SseFrame = { id, event, data: dataLines.join('\n') }
    id = null
    event = null
    dataLines.length = 0
    return frame
  }

  function processLine(rawLine: string): SseFrame | null {
    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
    if (line === '') return dispatch()
    if (line.startsWith(':')) return null
    const colon = line.indexOf(':')
    if (colon === -1) return null
    const field = line.slice(0, colon)
    let value = line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'id') id = value
    else if (field === 'event') event = value
    else if (field === 'data') dataLines.push(value)
    return null
  }

  return {
    push(chunk: string): SseFrame[] {
      buffer += chunk
      const frames: SseFrame[] = []
      const lines = buffer.split('\n')
      buffer = lines.pop() ?? ''
      for (const line of lines) {
        const frame = processLine(line)
        if (frame) frames.push(frame)
      }
      return frames
    },
    flush(): SseFrame[] {
      const frames: SseFrame[] = []
      if (buffer.length > 0) {
        const frame = processLine(buffer)
        if (frame) frames.push(frame)
        buffer = ''
      }
      const tail = dispatch()
      if (tail) frames.push(tail)
      return frames
    },
  }
}

export interface ParsedTaskEvent {
  done: boolean
  event: TaskEvent | null
}

/** 解析单条 data 载荷：`[DONE]` 标记流结束；无效 JSON 或缺少 sequence_no 时返回 null（跳过）。 */
export function parseTaskEvent(data: string): ParsedTaskEvent {
  const trimmed = data.trim()
  if (trimmed === '[DONE]') return { done: true, event: null }
  if (trimmed.length === 0) return { done: false, event: null }
  try {
    const parsed: unknown = JSON.parse(trimmed)
    if (typeof parsed !== 'object' || parsed === null) return { done: false, event: null }
    const candidate = parsed as Partial<TaskEvent>
    if (typeof candidate.sequence_no !== 'number') return { done: false, event: null }
    return { done: false, event: parsed as TaskEvent }
  } catch {
    return { done: false, event: null }
  }
}

/**
 * 事件接收策略：只接受 sequence_no 严格大于 lastSequenceNo 的事件；按序号去重并升序排序。
 * 用于处理重复/乱序（只接受更大序号，丢弃重复与过期序号）。
 */
export function acceptEvents(incoming: TaskEvent[], lastSequenceNo: number): TaskEvent[] {
  const seen = new Set<number>()
  const accepted: TaskEvent[] = []
  for (const event of incoming) {
    const sequence = event.sequence_no
    if (typeof sequence !== 'number') continue
    if (sequence <= lastSequenceNo) continue
    if (seen.has(sequence)) continue
    seen.add(sequence)
    accepted.push(event)
  }
  accepted.sort((a, b) => a.sequence_no - b.sequence_no)
  return accepted
}
