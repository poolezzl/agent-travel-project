import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import { ApiError } from '@/api/http'
import type { AtomicChange, PlanDay, PlanView, RevisionResult, TaskView } from '@/api/types'
import { groupChangesByDay } from '@/composables/revisionDiff'
import { usePlanningStore } from '@/stores/planning'
import { useRevisionStore } from '@/stores/revision'

vi.mock('@/api/revisions', () => ({
  preview: vi.fn(),
  confirm: vi.fn(),
  rollback: vi.fn(),
}))

vi.mock('@/api/tasks', () => ({
  create: vi.fn(),
  get: vi.fn(),
  cancel: vi.fn(),
  eventsUrl: vi.fn(),
  getPlan: vi.fn(),
}))

vi.mock('@/api/points', () => ({
  quote: vi.fn(),
  balance: vi.fn(),
  reserve: vi.fn(),
  confirm: vi.fn(),
  refund: vi.fn(),
  grant: vi.fn(),
}))

import * as revisionsApi from '@/api/revisions'
import * as tasksApi from '@/api/tasks'
import * as pointsApi from '@/api/points'

function makeDay(date: string, name: string): PlanDay {
  return {
    date,
    weekday: '周一',
    city_id: 'beijing',
    weather: { status: 'unknown', date },
    segments: [
      {
        sequence_no: 1,
        point_id: `poi-${date}`,
        name,
        district: '东城区',
        category: 'attraction',
        start_time: '09:00',
        end_time: '11:00',
        duration_min: 120,
        duration_source: 'knowledge',
        is_must_visit: true,
        is_estimated: false,
        facts: [],
        transport_to_next: null,
      },
    ],
    meals: [],
    lodging: { date, candidates: [] },
    reservations: [],
    warnings: [],
  }
}

const planView: PlanView = {
  task_id: 'task-1',
  status: 'PARTIAL',
  trip_id: 'trip-1',
  version_id: 'version-1',
  version_no: 1,
  etag: 'base-etag',
  schema_version: 's19-plan-v1',
  generated_at: '2026-11-20T00:00:00Z',
  source_metadata: [],
  degraded_fields: [],
  plan: {
    plan_version: 's19-plan-v1',
    generated_at: '2026-11-20T00:00:00Z',
    sample_only: false,
    trip: {
      city_id: 'beijing',
      city_name: '北京',
      start_date: '2026-12-01',
      end_date: '2026-12-02',
      day_count: 2,
      party_size: 2,
      transport_preference: '混合',
    },
    summary: {
      point_count: 2,
      total_distance_m: 0,
      total_transport_min: 0,
      estimated_route_legs: 1,
      source_completeness: 1,
      degraded: false,
      stale_fields: [],
      unknown_fields: [],
    },
    days: [makeDay('2026-12-01', '故宫博物院'), makeDay('2026-12-02', '颐和园')],
    quality: { status: 'pass', checks: [] },
    conflicts: [],
    confirmation: { required: false },
    sources: [],
    degraded_fields: [],
  },
}

const changeResult: RevisionResult = {
  intent: 'change',
  base_version_id: 'version-1',
  version_id: null,
  parent_version_id: null,
  etag: 'base-etag',
  status: 'AWAITING_CONFIRMATION',
  needs_confirm: true,
  affected_days: [1],
  affected_agents: ['route-planner'],
  changes: [{ operation: 'replace', path: '/days/1/segments/0', value: { name: '天坛' } }],
  answer: null,
  clarification_question: null,
  summary: '替换第 1 天景点为天坛',
  createdAt: '2026-11-20T00:00:00Z',
  reused: false,
  change_set: null,
  source_snapshot_ids: null,
  schema_version: null,
  model_version: null,
  prompt_version: null,
  content_hash: null,
}

const queryResult: RevisionResult = {
  ...changeResult,
  intent: 'query',
  status: 'PRECHECKED',
  needs_confirm: false,
  affected_days: [],
  affected_agents: [],
  changes: [],
  answer: '这样安排交通时间足够。',
  summary: '',
}

const ambiguousResult: RevisionResult = {
  ...changeResult,
  intent: 'ambiguous',
  status: 'PRECHECKED',
  needs_confirm: false,
  affected_days: [],
  affected_agents: [],
  changes: [],
  clarification_question: '您想替换为哪个景点？',
  summary: '',
}

const createdResult: RevisionResult = {
  ...changeResult,
  status: 'CREATED',
  needs_confirm: false,
  version_id: 'version-2',
  parent_version_id: 'version-1',
  etag: 'new-etag',
  content_hash: 'hash-2',
  change_set: changeResult.changes,
  summary: '已创建新版本',
}

const terminalTask: TaskView = {
  task_id: 'task-1',
  owner_id: 'owner-1',
  task_type: 'GENERATION',
  status: 'SUCCEEDED',
  state_version: 3,
  reserved_points: 10,
  operation_deadline_at: '2026-11-20T00:00:00Z',
  confirmation_deadline_at: null,
  created_at: '2026-11-20T00:00:00Z',
  updated_at: '2026-11-20T00:00:00Z',
  reused: false,
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  const planning = usePlanningStore()
  planning.task = terminalTask
  planning.plan = planView
})

function revision(): ReturnType<typeof useRevisionStore> {
  return useRevisionStore()
}

describe('useRevisionStore 状态机', () => {
  it('query 分支：不建版本、不写时间线、不刷新余额、不改 plan', async () => {
    vi.mocked(revisionsApi.preview).mockResolvedValue(queryResult)
    const planning = usePlanningStore()
    const store = revision()
    store.setInput('这样安排合适吗')
    await store.submit()

    expect(store.phase).toBe('answered')
    expect(store.draft?.confirmationId ?? null).toBeNull()
    expect(store.timeline).toHaveLength(0)
    expect(pointsApi.balance).not.toHaveBeenCalled()
    expect(tasksApi.getPlan).not.toHaveBeenCalled()
    expect(planning.plan?.version_id).toBe('version-1')
  })

  it('ambiguous 分支：进入澄清态，无确认弹窗', async () => {
    vi.mocked(revisionsApi.preview).mockResolvedValue(ambiguousResult)
    const store = revision()
    store.setInput('换一个地方')
    await store.submit()

    expect(store.phase).toBe('needsClarification')
    expect(store.pending).toBeNull()
  })

  it('change 分支：生成 confirmationId 并进入 awaitingConfirm', async () => {
    vi.mocked(revisionsApi.preview).mockResolvedValue(changeResult)
    const store = revision()
    store.setInput('换景点')
    await store.submit()

    expect(store.phase).toBe('awaitingConfirm')
    expect(store.pending).toEqual(changeResult)
    expect(store.draft?.confirmationId).toBeTruthy()
  })

  it('契约异常：changes 或 affected_days 为空不弹确认', async () => {
    vi.mocked(revisionsApi.preview).mockResolvedValue({
      ...changeResult,
      changes: [],
      affected_days: [],
    })
    const store = revision()
    store.setInput('换景点')
    await store.submit()

    expect(store.phase).toBe('failed')
    expect(store.pending).toBeNull()
    expect(store.error?.code).toBe('INVALID_RESPONSE')
  })
})

describe('confirmation_id 生命周期', () => {
  it('弱网 confirm 失败后重试复用同一 confirmation_id', async () => {
    vi.mocked(revisionsApi.preview).mockResolvedValue(changeResult)
    vi.mocked(revisionsApi.confirm).mockRejectedValueOnce(new Error('network down'))
    const store = revision()
    store.setInput('换景点')
    await store.submit()
    const id1 = store.draft?.confirmationId
    expect(id1).toBeTruthy()

    await store.confirmChange()
    expect(revisionsApi.confirm).toHaveBeenCalledTimes(1)
    expect(vi.mocked(revisionsApi.confirm).mock.calls[0]?.[2]).toBe(id1)

    vi.mocked(revisionsApi.confirm).mockResolvedValue(createdResult)
    await store.retry()
    expect(revisionsApi.confirm).toHaveBeenCalledTimes(2)
    expect(vi.mocked(revisionsApi.confirm).mock.calls[1]?.[2]).toBe(id1)
  })

  it('文本变更后重新预览重建 confirmation_id', async () => {
    vi.mocked(revisionsApi.preview).mockResolvedValue(changeResult)
    const store = revision()
    store.setInput('换景点')
    await store.submit()
    const id1 = store.draft?.confirmationId

    store.setInput('换成天坛')
    await store.submit()
    expect(store.draft?.confirmationId).not.toBe(id1)
  })
})

describe('确认与并发', () => {
  it('连点去重：只发出 1 次 confirm 请求', async () => {
    vi.mocked(revisionsApi.preview).mockResolvedValue(changeResult)
    vi.mocked(revisionsApi.confirm).mockResolvedValue(createdResult)
    vi.mocked(pointsApi.balance).mockResolvedValue({ balancePoints: 98 })
    const store = revision()
    store.setInput('换景点')
    await store.submit()

    await Promise.all([store.confirmChange(), store.confirmChange()])
    expect(revisionsApi.confirm).toHaveBeenCalledTimes(1)
  })

  it('reused 且 version_id 与当前一致：不追加时间线，余额只刷新一次', async () => {
    vi.mocked(revisionsApi.preview).mockResolvedValue(changeResult)
    vi.mocked(revisionsApi.confirm).mockResolvedValue({
      ...createdResult,
      reused: true,
      version_id: 'version-2',
    })
    vi.mocked(pointsApi.balance).mockResolvedValue({ balancePoints: 98 })
    // 当前已是新版本（真正的双击重复确认），version_id 与当前一致。
    usePlanningStore().plan = {
      ...planView,
      version_id: 'version-2',
      version_no: 2,
      etag: 'new-etag',
    }
    const store = revision()
    store.setInput('换景点')
    await store.submit()
    await store.confirmChange()

    expect(store.timeline).toHaveLength(0)
    expect(pointsApi.balance).toHaveBeenCalledTimes(1)
  })

  it('reused 且 version_id 与当前不一致：刷新 plan 并同步时间线', async () => {
    vi.mocked(revisionsApi.preview).mockResolvedValue(changeResult)
    vi.mocked(revisionsApi.confirm).mockResolvedValue({ ...createdResult, reused: true })
    vi.mocked(pointsApi.balance).mockResolvedValue({ balancePoints: 98 })
    vi.mocked(tasksApi.getPlan).mockResolvedValue({
      ...planView,
      version_id: 'version-2',
      version_no: 2,
      etag: 'new-etag',
    })
    const store = revision()
    store.setInput('换景点')
    await store.submit()
    await store.confirmChange()

    const changeEntry = store.timeline.find((entry) => entry.kind === 'change')
    expect(store.timeline.map((entry) => entry.kind)).toEqual(['base', 'change'])
    expect(changeEntry?.versionId).toBe('version-2')
    expect(changeEntry?.reused).toBe(true)
    expect(tasksApi.getPlan).toHaveBeenCalled()
    expect(pointsApi.balance).toHaveBeenCalledTimes(1)
  })

  it('reused 且 version_id 为空：刷新 plan 与余额，不追加时间线', async () => {
    vi.mocked(revisionsApi.preview).mockResolvedValue(changeResult)
    vi.mocked(revisionsApi.confirm).mockResolvedValue({
      ...createdResult,
      reused: true,
      version_id: null,
    })
    vi.mocked(pointsApi.balance).mockResolvedValue({ balancePoints: 98 })
    vi.mocked(tasksApi.getPlan).mockResolvedValue(planView)
    const store = revision()
    store.setInput('换景点')
    await store.submit()
    await store.confirmChange()

    expect(store.timeline).toHaveLength(0)
    expect(tasksApi.getPlan).toHaveBeenCalled()
    expect(pointsApi.balance).toHaveBeenCalledTimes(1)
  })

  it('decline：不发 confirm、不改 plan、时间线为空', async () => {
    vi.mocked(revisionsApi.preview).mockResolvedValue(changeResult)
    const store = revision()
    store.setInput('换景点')
    await store.submit()
    store.declineConfirm()

    expect(revisionsApi.confirm).not.toHaveBeenCalled()
    expect(store.pending).toBeNull()
    expect(store.timeline).toHaveLength(0)
  })
})

describe('409 与回滚', () => {
  it('confirm 409：进入冲突、刷新 plan、保留文本', async () => {
    vi.mocked(revisionsApi.preview).mockResolvedValue(changeResult)
    vi.mocked(revisionsApi.confirm).mockRejectedValue(
      new ApiError({
        code: 'REVISION_BASE_MISMATCH',
        message: '行程版本已变化',
        status: 409,
        traceId: 'trace-9',
      }),
    )
    vi.mocked(tasksApi.getPlan).mockResolvedValue({
      ...planView,
      version_id: 'version-2',
      version_no: 2,
      etag: 'new-etag',
    })
    const store = revision()
    store.setInput('换景点')
    await store.submit()
    await store.confirmChange()

    expect(store.conflict?.code).toBe('REVISION_BASE_MISMATCH')
    expect(store.conflict?.serverVersionNo).toBe(2)
    expect(store.inputText).toBe('换景点')
    expect(store.pending).toBeNull()
  })

  it('回滚前强制刷新取最新 etag 再发 If-Match', async () => {
    vi.mocked(tasksApi.getPlan).mockResolvedValue({
      ...planView,
      version_no: 3,
      etag: 'latest-etag',
    })
    const rollbackResult: RevisionResult = {
      ...createdResult,
      version_id: 'version-3',
      parent_version_id: 'version-1',
      etag: 'rollback-etag',
      content_hash: 'hash-0',
      summary: '已创建回滚版本',
    }
    vi.mocked(revisionsApi.rollback).mockResolvedValue(rollbackResult)
    const store = revision()
    store.timeline = [
      {
        versionId: 'version-0',
        parentVersionId: null,
        kind: 'change',
        summary: '初始',
        affectedDays: [],
        changeCount: 0,
        etag: 'x',
        contentHash: null,
        atLocal: 'x',
        reused: false,
      },
    ]

    await store.rollback('version-0')

    expect(tasksApi.getPlan).toHaveBeenCalled()
    expect(revisionsApi.rollback).toHaveBeenCalledWith('trip-1', 'version-0', 'latest-etag')
  })

  it('rollback 409：刷新后进入冲突', async () => {
    vi.mocked(tasksApi.getPlan).mockResolvedValue({
      ...planView,
      version_no: 2,
      etag: 'new-etag',
    })
    vi.mocked(revisionsApi.rollback).mockRejectedValue(
      new ApiError({ code: 'REVISION_BASE_MISMATCH', message: '行程版本已变化', status: 409 }),
    )
    const store = revision()
    store.timeline = [
      {
        versionId: 'version-0',
        parentVersionId: null,
        kind: 'change',
        summary: '初始',
        affectedDays: [],
        changeCount: 0,
        etag: 'x',
        contentHash: null,
        atLocal: 'x',
        reused: false,
      },
    ]

    await store.rollback('version-0')

    expect(store.conflict?.code).toBe('REVISION_BASE_MISMATCH')
    expect(store.conflict?.serverVersionNo).toBe(2)
  })
})

describe('错误归一化与空输入', () => {
  it('422/503：错误归一化、保留输入、重试复用同一草稿', async () => {
    vi.mocked(revisionsApi.preview).mockRejectedValue(
      new ApiError({
        code: 'REVISION_PLAN_UNAVAILABLE',
        message: '该修改不满足可行性，请调整要求',
        status: 422,
        traceId: 'trace-4',
      }),
    )
    const store = revision()
    store.setInput('换成天坛')
    await store.submit()

    expect(store.error?.kind).toBe('preview')
    expect(store.error?.code).toBe('REVISION_PLAN_UNAVAILABLE')
    expect(store.inputText).toBe('换成天坛')

    vi.mocked(revisionsApi.preview).mockResolvedValue(changeResult)
    await store.retry()
    expect(store.phase).toBe('awaitingConfirm')
  })

  it('空输入不发预览请求', async () => {
    const store = revision()
    store.setInput('   ')
    await store.submit()

    expect(revisionsApi.preview).not.toHaveBeenCalled()
  })
})

describe('修改前版本与默认保护项', () => {
  it('首次确认补 base 条目，单次修改后即可回滚', async () => {
    vi.mocked(revisionsApi.preview).mockResolvedValue(changeResult)
    vi.mocked(revisionsApi.confirm).mockResolvedValue(createdResult)
    vi.mocked(pointsApi.balance).mockResolvedValue({ balancePoints: 98 })
    vi.mocked(tasksApi.getPlan).mockResolvedValue({
      ...planView,
      version_id: 'version-2',
      version_no: 2,
      etag: 'new-etag',
    })
    const store = revision()
    store.setInput('换景点')
    await store.submit()
    await store.confirmChange()

    expect(store.timeline.map((entry) => entry.kind)).toEqual(['base', 'change'])
    expect(store.timeline[0]?.versionId).toBe('version-1')
    expect(store.timeline[1]?.versionId).toBe('version-2')
    expect(
      store.timeline.some((entry) => entry.kind === 'base' && entry.versionId === 'version-1'),
    ).toBe(true)
  })

  it('applyDefaultLockedPaths 预填必去点路径', () => {
    const store = revision()
    store.applyDefaultLockedPaths(planView)

    expect(store.lockedPaths).toEqual(['/days/1/segments/0', '/days/2/segments/0'])
  })
})

describe('R6-03：单日多点 / 跨日 / confirm 400 / 冲突差异', () => {
  it('单日多点：changes 归 1 组 2 条，第 1 天不标已验证未变更', async () => {
    const changes: AtomicChange[] = [
      { operation: 'replace', path: '/days/1/segments/0', value: { name: '天坛' } },
      { operation: 'replace', path: '/days/1/segments/1', value: { name: '颐和园' } },
    ]
    const preview: RevisionResult = { ...changeResult, affected_days: [1], changes }
    const created: RevisionResult = {
      ...preview,
      status: 'CREATED',
      needs_confirm: false,
      version_id: 'version-2',
      parent_version_id: 'version-1',
      etag: 'new-etag',
      content_hash: 'hash-2',
      change_set: changes,
      summary: '已创建新版本',
    }
    vi.mocked(revisionsApi.preview).mockResolvedValue(preview)
    vi.mocked(revisionsApi.confirm).mockResolvedValue(created)
    vi.mocked(pointsApi.balance).mockResolvedValue({ balancePoints: 98 })
    vi.mocked(tasksApi.getPlan).mockResolvedValue({
      ...planView,
      version_id: 'version-2',
      version_no: 2,
      etag: 'new-etag',
      plan: {
        ...planView.plan,
        days: [makeDay('2026-12-01', '天坛'), makeDay('2026-12-02', '颐和园')],
      },
    })
    const store = revision()
    store.setInput('换景点')
    await store.submit()
    await store.confirmChange()

    const groups = groupChangesByDay(changes)
    expect(groups).toHaveLength(1)
    expect(groups[0]?.changes).toHaveLength(2)
    expect(store.lastApplied?.changes).toHaveLength(2)
    expect(store.lastApplied?.verifiedUnchanged).not.toContain(1)
    expect(store.lastApplied?.verifiedUnchanged).toContain(2)
  })

  it('跨日：changes 归 2 组，受影响天不标已验证未变更', async () => {
    const changes: AtomicChange[] = [
      { operation: 'replace', path: '/days/1/segments/0', value: { name: '天坛' } },
      { operation: 'replace', path: '/days/2/segments/0', value: { name: '圆明园' } },
    ]
    const preview: RevisionResult = { ...changeResult, affected_days: [1, 2], changes }
    const created: RevisionResult = {
      ...preview,
      status: 'CREATED',
      needs_confirm: false,
      version_id: 'version-2',
      parent_version_id: 'version-1',
      etag: 'new-etag',
      content_hash: 'hash-2',
      change_set: changes,
      summary: '已创建新版本',
    }
    vi.mocked(revisionsApi.preview).mockResolvedValue(preview)
    vi.mocked(revisionsApi.confirm).mockResolvedValue(created)
    vi.mocked(pointsApi.balance).mockResolvedValue({ balancePoints: 98 })
    vi.mocked(tasksApi.getPlan).mockResolvedValue({
      ...planView,
      version_id: 'version-2',
      version_no: 2,
      etag: 'new-etag',
      plan: {
        ...planView.plan,
        days: [makeDay('2026-12-01', '天坛'), makeDay('2026-12-02', '圆明园')],
      },
    })
    const store = revision()
    store.setInput('换景点')
    await store.submit()
    await store.confirmChange()

    expect(groupChangesByDay(changes)).toHaveLength(2)
    expect(store.lastApplied?.verifiedUnchanged).not.toContain(1)
    expect(store.lastApplied?.verifiedUnchanged).not.toContain(2)
  })

  it('confirm 400：失败态、不进冲突、保留草稿可重试', async () => {
    vi.mocked(revisionsApi.preview).mockResolvedValue(changeResult)
    vi.mocked(revisionsApi.confirm).mockRejectedValue(
      new ApiError({
        code: 'INVALID_INPUT',
        message: '幂等键必须与 confirmation_id 一致',
        status: 400,
      }),
    )
    const store = revision()
    store.setInput('换景点')
    await store.submit()
    await store.confirmChange()

    expect(store.phase).toBe('failed')
    expect(store.error?.kind).toBe('confirm')
    expect(store.error?.code).toBe('INVALID_INPUT')
    expect(store.conflict).toBeNull()
    expect(store.pending).toBeNull()
    expect(store.draft).not.toBeNull()
    expect(tasksApi.getPlan).not.toHaveBeenCalled()
  })

  it('409 冲突按天差异：仅第 2 天变化时 changedDays 为 [2]', async () => {
    vi.mocked(revisionsApi.preview).mockResolvedValue(changeResult)
    vi.mocked(revisionsApi.confirm).mockRejectedValue(
      new ApiError({ code: 'REVISION_BASE_MISMATCH', message: '行程版本已变化', status: 409 }),
    )
    vi.mocked(tasksApi.getPlan).mockResolvedValue({
      ...planView,
      version_id: 'version-2',
      version_no: 2,
      etag: 'new-etag',
      plan: {
        ...planView.plan,
        days: [planView.plan.days[0], makeDay('2026-12-02', '圆明园')],
      },
    })
    const store = revision()
    store.setInput('换景点')
    await store.submit()
    await store.confirmChange()

    expect(store.conflict?.changedDays).toEqual([2])
  })
})
