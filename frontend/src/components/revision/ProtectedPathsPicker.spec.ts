import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { PlanDay, PlanView } from '@/api/types'
import ProtectedPathsPicker from './ProtectedPathsPicker.vue'

function makePlan(): PlanView {
  const day: PlanDay = {
    date: '2026-12-01',
    weekday: '周一',
    city_id: 'beijing',
    weather: { status: 'unknown', date: '2026-12-01' },
    segments: [
      {
        sequence_no: 1,
        point_id: 'poi-1',
        name: '故宫博物院',
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
    lodging: { date: '2026-12-01', candidates: [] },
    reservations: [],
    warnings: [],
  }

  return {
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
      days: [day],
      quality: { status: 'pass', checks: [] },
      conflicts: [],
      confirmation: { required: false },
      sources: [],
      degraded_fields: [],
    },
  }
}

const plan = makePlan()

describe('ProtectedPathsPicker', () => {
  it('disabled 时不 emit update:modelValue', async () => {
    const wrapper = mount(ProtectedPathsPicker, {
      props: { plan, modelValue: ['/days/1/segments/0'], disabled: true },
    })

    await wrapper.get('[data-testid="protected-path"]').trigger('change')

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('非 disabled 时切换保护项', async () => {
    const wrapper = mount(ProtectedPathsPicker, {
      props: { plan, modelValue: [], disabled: false },
    })

    await wrapper.get('[data-testid="protected-path"]').trigger('change')

    expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toEqual(['/days/1/segments/0'])
  })
})
