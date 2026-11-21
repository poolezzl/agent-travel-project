import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { PlanDay, PlanView, RevisionResult } from '@/api/types'
import ConfirmRevisionDialog from './ConfirmRevisionDialog.vue'

function makeDay(date: string, name: string): PlanDay {
  return {
    date,
    weekday: '周一',
    city_id: 'beijing',
    weather: { status: 'unknown', date },
    segments: [
      {
        sequence_no: 1,
        point_id: 'poi-1',
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
  version_id: '11111111-2222-3333-4444-555555555555',
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
      end_date: '2026-12-01',
      day_count: 1,
      party_size: 2,
      transport_preference: '混合',
    },
    summary: {
      point_count: 1,
      total_distance_m: 0,
      total_transport_min: 0,
      estimated_route_legs: 1,
      source_completeness: 1,
      degraded: false,
      stale_fields: [],
      unknown_fields: [],
    },
    days: [makeDay('2026-12-01', '故宫博物院')],
    quality: { status: 'pass', checks: [] },
    conflicts: [],
    confirmation: { required: false },
    sources: [],
    degraded_fields: [],
  },
}

const result: RevisionResult = {
  intent: 'change',
  base_version_id: '11111111-2222-3333-4444-555555555555',
  version_id: null,
  parent_version_id: null,
  etag: 'etag',
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

describe('ConfirmRevisionDialog', () => {
  it('包含必填信息：基准版本、summary、影响日期、Agent、保护项、费用说明', () => {
    const wrapper = mount(ConfirmRevisionDialog, {
      props: { result, plan, lockedPaths: ['/days/1/segments/0'], confirming: false },
    })

    const text = wrapper.get('[data-testid="confirm-dialog"]').text()
    expect(text).toContain('基准版本')
    expect(text).toContain('v1（11111111')
    expect(text).toContain('替换第 1 天景点为天坛')
    expect(text).toContain('第 1 天')
    expect(text).toContain('路线规划')
    expect(text).toContain('故宫博物院')
    expect(text).toContain('费用说明')
    expect(text).toContain('确认修改')
    expect(text).toContain('暂不修改')
  })

  it('点击暂不修改只发 decline，不发 confirm', async () => {
    const wrapper = mount(ConfirmRevisionDialog, {
      props: { result, plan, lockedPaths: [], confirming: false },
    })

    await wrapper.get('[data-testid="confirm-decline"]').trigger('click')
    expect(wrapper.emitted('decline')).toHaveLength(1)
    expect(wrapper.emitted('confirm')).toBeFalsy()
  })

  it('点击确认修改发 confirm', async () => {
    const wrapper = mount(ConfirmRevisionDialog, {
      props: { result, plan, lockedPaths: [], confirming: false },
    })

    await wrapper.get('[data-testid="confirm-accept"]').trigger('click')
    expect(wrapper.emitted('confirm')).toHaveLength(1)
  })
})
