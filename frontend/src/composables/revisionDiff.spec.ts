import { describe, expect, it } from 'vitest'

import type { AtomicChange, PlanDay, PlanView } from '@/api/types'
import {
  agentLabel,
  dayStatus,
  deepEqual,
  effectiveChanges,
  formatLocation,
  groupChangesByDay,
  lockedPathLabel,
  oldValueAt,
  oldValueAtFromDays,
  operationLabel,
  parseChangePath,
} from './revisionDiff'

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

const plan: PlanView = {
  task_id: 'task-1',
  status: 'PARTIAL',
  trip_id: 'trip-1',
  version_id: 'version-1',
  version_no: 1,
  etag: 'etag',
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

describe('parseChangePath', () => {
  it('解析 /days/2/segments/1', () => {
    expect(parseChangePath('/days/2/segments/1')).toEqual({
      dayIndex: 2,
      collection: 'segments',
      index: 1,
    })
  })

  it('非法结构返回 null', () => {
    expect(parseChangePath('days/2/segments/1')).toBeNull()
    expect(parseChangePath('/x/2/segments/1')).toBeNull()
    expect(parseChangePath('/days/0/segments/1')).toBeNull()
  })

  it('未知集合降级为 unknown', () => {
    expect(parseChangePath('/days/2/foo/1')).toEqual({
      dayIndex: 2,
      collection: 'unknown',
      index: -1,
    })
  })
})

describe('formatLocation / lockedPathLabel', () => {
  it('解析出人类可读位置', () => {
    expect(formatLocation('/days/2/segments/0', plan)).toBe('第 2 天 · 第 1 段 · 颐和园')
  })

  it('越界索引回退原样路径', () => {
    expect(formatLocation('/days/99/segments/0', plan)).toBe('/days/99/segments/0')
    expect(formatLocation('/days/1/segments/8', plan)).toBe('/days/1/segments/8')
  })

  it('未知路径原样返回', () => {
    expect(formatLocation('/days/2/foo/1', plan)).toBe('/days/2/foo/1')
  })

  it('锁定路径文案', () => {
    expect(lockedPathLabel('/days/1/segments/0', plan)).toBe('第 1 天 第 1 段 · 故宫博物院')
  })
})

describe('groupChangesByDay', () => {
  it('按天分组且未知路径归入降级分组', () => {
    const changes: AtomicChange[] = [
      { operation: 'replace', path: '/days/2/segments/0', value: 'a' },
      { operation: 'replace', path: '/days/1/segments/0', value: 'b' },
      { operation: 'add', path: '/unknown/x', value: 'c' },
    ]
    const groups = groupChangesByDay(changes)
    expect(groups).toHaveLength(3)
    expect(groups[0]?.day).toBe(1)
    expect(groups[1]?.day).toBe(2)
    expect(groups[2]?.day).toBeNull()
    expect(groups[2]?.changes).toHaveLength(1)
  })
})

describe('dayStatus', () => {
  it('区分受影响、已验证未变更与未核对', () => {
    const before = [makeDay('2026-12-01', '故宫博物院'), makeDay('2026-12-02', '颐和园')]
    const after = [makeDay('2026-12-01', '天坛'), makeDay('2026-12-02', '颐和园')]
    const status = dayStatus(before, after, [1])

    expect(status.affected).toEqual([1])
    expect(status.verifiedUnchanged).toEqual([2])
    expect(status.unverified).toEqual([])
  })

  it('天数不匹配时归入未核对', () => {
    const before = [makeDay('2026-12-01', '故宫博物院')]
    const after = [makeDay('2026-12-01', '故宫博物院'), makeDay('2026-12-02', '颐和园')]
    const status = dayStatus(before, after, [])

    expect(status.verifiedUnchanged).toEqual([1])
    expect(status.unverified).toEqual([2])
  })

  it('跨日 affected=[1,2] 时不标已验证未变更', () => {
    const before = [
      makeDay('2026-12-01', '故宫博物院'),
      makeDay('2026-12-02', '颐和园'),
      makeDay('2026-12-03', '长城'),
    ]
    const after = [
      makeDay('2026-12-01', '天坛'),
      makeDay('2026-12-02', '圆明园'),
      makeDay('2026-12-03', '长城'),
    ]
    const status = dayStatus(before, after, [1, 2])

    expect(status.verifiedUnchanged).not.toContain(1)
    expect(status.verifiedUnchanged).not.toContain(2)
    expect(status.verifiedUnchanged).toContain(3)
  })
})

describe('agentLabel / operationLabel / oldValueAt / deepEqual / effectiveChanges', () => {
  it('agentLabel 中文映射，未知原样', () => {
    expect(agentLabel('route-planner')).toBe('路线规划')
    expect(agentLabel('some-unknown')).toBe('some-unknown')
  })

  it('operationLabel 中文映射', () => {
    expect(operationLabel('add')).toBe('新增')
    expect(operationLabel('reorder')).toBe('重排')
  })

  it('oldValueAt 解析旧值', () => {
    expect(oldValueAt('/days/1/segments/0', plan)).toMatchObject({ name: '故宫博物院' })
    expect(oldValueAt('/days/99/segments/0', plan)).toBeUndefined()
  })

  it('oldValueAtFromDays 从快照读取原值（不受刷新后 plan 影响）', () => {
    const beforeDays = [makeDay('2026-12-01', '故宫博物院')]
    const afterDays = [makeDay('2026-12-01', '天坛')]
    expect(oldValueAtFromDays('/days/1/segments/0', beforeDays)).toMatchObject({
      name: '故宫博物院',
    })
    expect(oldValueAtFromDays('/days/1/segments/0', afterDays)).toMatchObject({ name: '天坛' })
    expect(oldValueAtFromDays('/days/1/segments/0', undefined)).toBeUndefined()
  })

  it('deepEqual 比较对象', () => {
    expect(deepEqual({ a: 1, b: [1, 2] }, { a: 1, b: [1, 2] })).toBe(true)
    expect(deepEqual({ a: 1 }, { a: 2 })).toBe(false)
  })

  it('effectiveChanges 优先 change_set', () => {
    const changes: AtomicChange[] = [{ operation: 'add', path: '/days/1/segments/0' }]
    const changeSet: AtomicChange[] = [{ operation: 'replace', path: '/days/1/segments/1' }]
    expect(effectiveChanges({ changes, change_set: changeSet } as never)).toEqual(changeSet)
    expect(effectiveChanges({ changes, change_set: null } as never)).toEqual(changes)
  })
})
