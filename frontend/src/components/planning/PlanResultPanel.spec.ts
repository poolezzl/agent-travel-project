import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { PlanView } from '@/api/types'
import PlanResultPanel from './PlanResultPanel.vue'

const planView: PlanView = {
  task_id: 'task-1',
  status: 'PARTIAL',
  trip_id: 'trip-1',
  version_id: 'version-1',
  version_no: 2,
  etag: 'abcdef1234567890',
  schema_version: 's19-plan-v1',
  generated_at: '2026-11-16T06:00:00Z',
  source_metadata: [
    {
      source_type: 'knowledge_base',
      source_record_id: 'SRC-1',
      retrieved_at: '2026-11-10T00:00:00Z',
      valid_until: '2027-01-01T00:00:00Z',
      freshness_status: 'fresh',
      is_estimated: false,
    },
  ],
  degraded_fields: ['days[0].weather'],
  plan: {
    plan_version: 's19-plan-v1',
    generated_at: '2026-11-16T06:00:00Z',
    sample_only: true,
    notice: '示例数据，不代表真实事实。',
    trip: {
      city_id: 'beijing',
      city_name: '北京',
      start_date: '2026-11-20',
      end_date: '2026-11-21',
      day_count: 2,
      party_size: 2,
      transport_preference: '混合',
    },
    summary: {
      point_count: 1,
      total_distance_m: 1200,
      total_transport_min: 18,
      estimated_route_legs: 1,
      estimated_cost: { currency: 'CNY', amount_minor: 6000, basis: 'known_ticket_rules' },
      source_completeness: 1,
      degraded: true,
      stale_fields: [],
      unknown_fields: ['days[0].weather'],
    },
    days: [
      {
        date: '2026-11-20',
        weekday: '周五',
        city_id: 'beijing',
        weather: { status: 'unknown', date: '2026-11-20', message: '天气服务暂不可用' },
        segments: [
          {
            sequence_no: 1,
            point_id: 'POI-BJ-001',
            name: '故宫博物院',
            district: '东城区',
            category: 'attraction',
            start_time: '09:00',
            end_time: '11:30',
            duration_min: 150,
            duration_source: 'knowledge',
            is_must_visit: true,
            is_estimated: false,
            facts: [
              {
                fact_type: 'highlights',
                value: '中轴线宫殿群',
                chunk_id: 'CHUNK-1',
                source_record_id: 'SRC-1',
              },
            ],
            transport_to_next: null,
          },
        ],
        meals: [],
        lodging: { date: '2026-11-20', candidates: [] },
        reservations: [],
        warnings: [],
      },
    ],
    quality: { status: 'warn', checks: [] },
    conflicts: [],
    confirmation: { required: false },
    sources: [],
    degraded_fields: ['days[0].weather'],
  },
}

describe('PlanResultPanel', () => {
  it('renders the published plan with explicit sample and degrade labels', () => {
    const wrapper = mount(PlanResultPanel, {
      props: { plan: planView, planError: null, degradedFields: [] },
    })

    expect(wrapper.get('[data-testid="plan-ready"]').text()).toContain('故宫博物院')
    expect(wrapper.get('[data-testid="plan-ready"]').text()).toContain('必去')
    expect(wrapper.get('[data-testid="plan-sample"]').text()).toContain('示例数据')
    expect(wrapper.get('[data-testid="result-degraded"]').text()).toContain('days[0].weather')
    expect(wrapper.text()).toContain('v2')
  })

  it('shows the backend error message instead of any fixture when no version is readable', () => {
    const wrapper = mount(PlanResultPanel, {
      props: {
        plan: null,
        planError: {
          code: 'CONFIRMATION_REQUIRED',
          message: '任务存在待确认冲突，确认前不创建版本',
          status: 409,
        },
        degradedFields: [],
      },
    })

    expect(wrapper.get('[data-testid="result-empty"]').text()).toContain('待确认冲突')
    expect(wrapper.text()).not.toContain('故宫博物院')
  })
})
