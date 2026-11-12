import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/api/http'
import type { QuoteResponse, TaskEvent, TaskView } from '@/api/types'
import {
  buildCreateTaskRequest,
  newIdempotencyKey,
  usePlanningStore,
  yuanToAmountMinor,
} from './planning'

vi.mock('@/api/points', () => ({
  quote: vi.fn(),
  balance: vi.fn(),
  reserve: vi.fn(),
  confirm: vi.fn(),
  refund: vi.fn(),
  grant: vi.fn(),
}))

vi.mock('@/api/tasks', () => ({
  create: vi.fn(),
  get: vi.fn(),
  cancel: vi.fn(),
  eventsUrl: vi.fn(),
}))

import * as pointsApi from '@/api/points'
import * as tasksApi from '@/api/tasks'

const quoteResponse: QuoteResponse = {
  quoteId: 'q1',
  quoteReference: 'qr-1',
  planCount: 1,
  points: 10,
  expiresAtEpochSecond: 1_800_000_000,
}

const taskView: TaskView = {
  task_id: 'task-1',
  owner_id: 'owner-1',
  task_type: 'GENERATION',
  status: 'QUEUED',
  state_version: 2,
  reserved_points: 10,
  operation_deadline_at: '2026-11-11T00:00:00Z',
  confirmation_deadline_at: null,
  created_at: '2026-11-11T00:00:00Z',
  updated_at: '2026-11-11T00:00:00Z',
  reused: false,
}

function makeEvent(sequenceNo: number, status: string, degraded: string[] = []): TaskEvent {
  return {
    sequence_no: sequenceNo,
    event_type: 'TASK_STATUS_CHANGED',
    status,
    degraded_fields: degraded,
    created_at: '2026-11-11T00:00:00Z',
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
})

describe('yuanToAmountMinor', () => {
  it('整元换算为分', () => {
    expect(yuanToAmountMinor(3000)).toBe(300_000)
    expect(yuanToAmountMinor(0)).toBe(0)
  })
})

describe('buildCreateTaskRequest', () => {
  it('生成 snake_case 契约载荷，预算走 currency/amount_minor', () => {
    const request = buildCreateTaskRequest({
      city: '北京',
      startDate: '2026-12-01',
      endDate: '2026-12-03',
      partySize: 2,
      budgetYuan: 3000,
      mustVisit: ['故宫'],
      preference: '少走路',
      transport: '混合',
      planCount: 2,
    })
    expect(request.task_type).toBe('GENERATION')
    expect(request.plan_count).toBe(2)
    expect(request.request).toMatchObject({
      city: '北京',
      days: 3,
      party_size: 2,
      budget: { currency: 'CNY', amount_minor: 300_000 },
      must_visit: ['故宫'],
      preferences: '少走路',
      plan_count: 2,
    })
  })
})

describe('usePlanningStore', () => {
  it('报价幂等键：同 planCount 复用，planCount 变化重新生成', async () => {
    vi.mocked(pointsApi.quote).mockResolvedValue(quoteResponse)
    const store = usePlanningStore()

    await store.requestQuote()
    await store.requestQuote()
    expect(pointsApi.quote).toHaveBeenCalledTimes(2)
    expect(pointsApi.quote.mock.calls[0]?.[1]).toBe(pointsApi.quote.mock.calls[1]?.[1])

    store.setForm({ planCount: 2 })
    await store.requestQuote()
    expect(pointsApi.quote.mock.calls[2]?.[1]).not.toBe(pointsApi.quote.mock.calls[1]?.[1])
  })

  it('建任务幂等键：同载荷复用，forceNewKey 强制新键', async () => {
    vi.mocked(tasksApi.create).mockResolvedValue(taskView)
    const store = usePlanningStore()

    await store.createTask()
    await store.createTask()
    expect(tasksApi.create.mock.calls[0]?.[1]).toBe(tasksApi.create.mock.calls[1]?.[1])

    await store.createTask(true)
    expect(tasksApi.create.mock.calls[2]?.[1]).not.toBe(tasksApi.create.mock.calls[1]?.[1])
  })

  it('createTask 在途时重复调用只创建一次任务（防连点）', async () => {
    vi.mocked(tasksApi.create).mockResolvedValue(taskView)
    const store = usePlanningStore()

    const first = store.createTask()
    const second = store.createTask()
    await Promise.all([first, second])

    expect(tasksApi.create).toHaveBeenCalledTimes(1)
  })

  it('applyEvent 按序号去重并聚合降级字段', () => {
    const store = usePlanningStore()
    store.applyEvent(makeEvent(1, 'QUEUED', ['weather']))
    store.applyEvent(makeEvent(1, 'QUEUED', ['weather']))
    store.applyEvent(makeEvent(2, 'SUCCEEDED', ['route']))

    expect(store.events.map((e) => e.sequence_no)).toEqual([1, 2])
    expect(store.lastSequenceNo).toBe(2)
    expect(store.degradedFields).toEqual(['weather', 'route'])
  })

  it('activeStatus：终态以 TaskView 为准，忽略陈旧事件', () => {
    const store = usePlanningStore()
    store.events = [makeEvent(1, 'QUEUED')]
    store.task = { ...taskView, status: 'REFUNDED' }

    expect(store.activeStatus).toBe('REFUNDED')
  })

  it('cancelTask 取消前刷新取最新版本，并用刷新后的版本', async () => {
    const refreshed = { ...taskView, state_version: 7 }
    vi.mocked(tasksApi.get).mockResolvedValue(refreshed)
    vi.mocked(tasksApi.cancel).mockResolvedValue({ ...refreshed, status: 'CANCELLED' })

    const store = usePlanningStore()
    store.task = taskView // state_version 2

    await store.cancelTask()

    expect(tasksApi.get).toHaveBeenCalledWith('task-1')
    expect(tasksApi.cancel).toHaveBeenCalledWith(
      'task-1',
      { expected_version: 7 },
      expect.any(String),
    )
  })

  it('cancelTask 409 且刷新成功时提示已同步', async () => {
    vi.mocked(tasksApi.get).mockResolvedValue({ ...taskView, state_version: 7 })
    vi.mocked(tasksApi.cancel).mockRejectedValue(
      new ApiError({ code: 'VERSION_CONFLICT', message: '版本冲突', status: 409 }),
    )

    const store = usePlanningStore()
    store.task = taskView

    await expect(store.cancelTask()).rejects.toBeTruthy()
    expect(store.cancelError?.message).toBe('状态已变化，已同步，请重试')
    // get 调用两次：取消前刷新一次，409 后再同步一次
    expect(tasksApi.get).toHaveBeenCalledTimes(2)
  })

  it('cancelTask 409 且刷新失败时提示同步失败', async () => {
    vi.mocked(tasksApi.get).mockRejectedValue(
      new ApiError({ code: 'NETWORK_ERROR', message: '网络错误', status: 500 }),
    )
    vi.mocked(tasksApi.cancel).mockRejectedValue(
      new ApiError({ code: 'VERSION_CONFLICT', message: '版本冲突', status: 409 }),
    )

    const store = usePlanningStore()
    store.task = taskView

    await expect(store.cancelTask()).rejects.toBeTruthy()
    expect(store.cancelError?.message).toBe('状态已变化，且同步失败，请检查网络后重试')
  })

  it('newIdempotencyKey 生成唯一 UUID', () => {
    expect(newIdempotencyKey()).not.toBe(newIdempotencyKey())
  })
})
