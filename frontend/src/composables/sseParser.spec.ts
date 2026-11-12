import { describe, expect, it } from 'vitest'

import type { TaskEvent } from '@/api/types'
import { acceptEvents, createSseParser, parseTaskEvent } from './sseParser'

function event(sequenceNo: number, status = 'QUEUED'): TaskEvent {
  return {
    sequence_no: sequenceNo,
    event_type: 'TASK_STATUS_CHANGED',
    status,
    created_at: '2026-11-11T00:00:00Z',
  }
}

describe('createSseParser 分帧', () => {
  it('解析 id:/event:/data: 三种行与空行分隔', () => {
    const parser = createSseParser()
    const frames = parser.push('id: 3\nevent: task-progress\ndata: {"sequence_no":3}\n\n')
    expect(frames).toEqual([{ id: '3', event: 'task-progress', data: '{"sequence_no":3}' }])
  })

  it('跨 chunk 边界正确拼接被截断的行', () => {
    const parser = createSseParser()
    expect(parser.push('id: 1\ndata: {"sequence')).toEqual([])
    expect(parser.push('_no":1,"sta')).toEqual([])
    expect(parser.push('tus":"QUEUED"}\n\n')).toEqual([
      { id: '1', event: null, data: '{"sequence_no":1,"status":"QUEUED"}' },
    ])
  })

  it('多行 data 以换行拼接', () => {
    const parser = createSseParser()
    expect(parser.push('data: a\ndata: b\n\n')).toEqual([{ id: null, event: null, data: 'a\nb' }])
  })

  it('忽略注释行与未知字段', () => {
    const parser = createSseParser()
    expect(parser.push(': keepalive\ndata: x\nunknown-field: y\n\n')).toEqual([
      { id: null, event: null, data: 'x' },
    ])
  })

  it('flush 冲刷无尾随换行的残留事件', () => {
    const parser = createSseParser()
    expect(parser.push('data: {"sequence_no":9}')).toEqual([])
    expect(parser.flush()).toEqual([{ id: null, event: null, data: '{"sequence_no":9}' }])
  })
})

describe('parseTaskEvent', () => {
  it('识别 [DONE]', () => {
    expect(parseTaskEvent('[DONE]')).toEqual({ done: true, event: null })
  })

  it('解析合法 TaskEvent', () => {
    const result = parseTaskEvent('{"sequence_no":2,"status":"QUEUED"}')
    expect(result.done).toBe(false)
    expect(result.event?.sequence_no).toBe(2)
  })

  it('无效 JSON / 缺 sequence_no 返回 null', () => {
    expect(parseTaskEvent('not-json')).toEqual({ done: false, event: null })
    expect(parseTaskEvent('{"foo":1}')).toEqual({ done: false, event: null })
    expect(parseTaskEvent('')).toEqual({ done: false, event: null })
  })
})

describe('acceptEvents 去重与乱序', () => {
  it('只接受严格大于 lastSequenceNo 的事件', () => {
    expect(acceptEvents([event(1), event(2), event(3)], 1)).toEqual([event(2), event(3)])
  })

  it('丢弃重复序号', () => {
    expect(acceptEvents([event(2), event(2), event(3)], 1)).toEqual([event(2), event(3)])
  })

  it('乱序输入按序号升序输出', () => {
    expect(acceptEvents([event(5), event(3), event(4)], 2)).toEqual([event(3), event(4), event(5)])
  })
})
