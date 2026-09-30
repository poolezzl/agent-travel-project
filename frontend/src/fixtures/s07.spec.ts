import { describe, expect, it } from 'vitest'

import {
  createDemoViewModel,
  defaultPlanningInput,
  mockStatusOptions,
  toS02Status,
  validityLabel,
  validityStatus,
} from './s07'

describe('S07 演示 fixture 契约', () => {
  it('保留 S02 公共信封字段形状', () => {
    const envelope = createDemoViewModel('success').s02Envelope

    expect(envelope.schema_version).toBe('agent-contract-v1')
    expect(typeof envelope.task_id).toBe('string')
    expect(typeof envelope.trace_id).toBe('string')
    expect(typeof envelope.request_id).toBe('string')
    expect(typeof envelope.generated_at).toBe('string')
    expect(envelope.status).toBe('ok')
    expect(Array.isArray(envelope.source_metadata)).toBe(true)
  })

  it('把演示状态映射为 S02 状态并不隐藏降级字段', () => {
    expect(toS02Status('success')).toBe('ok')
    expect(toS02Status('partial')).toBe('partial')
    expect(toS02Status('error')).toBe('failed')

    const partial = createDemoViewModel('partial').s02Envelope
    expect(partial.degraded_fields).toContain('provider_fields')

    const failed = createDemoViewModel('error').s02Envelope
    expect(failed.error_code).toBe('AGENT_UNAVAILABLE')
  })

  it('成功态提供三日时间轴、方案对比与 3-5 条社区样张', () => {
    const model = createDemoViewModel('success')

    expect(model.days).toHaveLength(3)
    expect(model.days.map((day) => day.items.length).every((count) => count > 0)).toBe(true)
    expect(model.variants.length).toBeGreaterThanOrEqual(1)
    expect(model.variants.length).toBeLessThanOrEqual(3)
    expect(model.community.length).toBeGreaterThanOrEqual(3)
    expect(model.community.length).toBeLessThanOrEqual(5)
  })

  it('所有事实性数值都带 Mock / 估算 / 待核验口径，不使用空值冒充事实', () => {
    const model = createDemoViewModel('success')
    const marking = /Mock|示例|估算|待核验|unknown|不可用|静态|合成/

    for (const day of model.days) {
      expect(day.weather).toMatch(marking)
      expect(day.weatherEvidence.label).toMatch(marking)
      for (const item of day.items) {
        expect(item.poiId.startsWith('synthetic-')).toBe(true)
        expect(item.duration).toMatch(marking)
        expect(item.price).toMatch(marking)
        expect(item.reservation).toMatch(marking)
        expect(item.evidence.label).toMatch(marking)
      }
    }

    for (const variant of model.variants) {
      expect(variant.budget).toMatch(marking)
      expect(variant.commute).toMatch(marking)
    }

    for (const sample of model.community) {
      expect(sample.likes).toMatch(marking)
      expect(sample.note).toMatch(marking)
    }
  })

  it('空态与加载态不返回任何行程数据', () => {
    expect(createDemoViewModel('empty').days).toHaveLength(0)
    expect(createDemoViewModel('empty').variants).toHaveLength(0)
    expect(createDemoViewModel('empty').mapPoints).toHaveLength(0)
    expect(createDemoViewModel('loading').days).toHaveLength(0)
    expect(createDemoViewModel('error').days).toHaveLength(0)
  })

  it('默认输入与状态选项覆盖 S07 演示所需字段', () => {
    expect(defaultPlanningInput.city).toBe('北京')
    expect(defaultPlanningInput.planCount).toBeGreaterThanOrEqual(1)
    expect(mockStatusOptions.map((option) => option.value)).toEqual([
      'success',
      'loading',
      'empty',
      'partial',
      'error',
    ])
  })
})

describe('来源时效（valid_until）', () => {
  // fixture 固定有效期 2026-10-01T08:00:00Z：取其之前/之后的时刻分别验证两种判定
  const before = new Date('2026-09-30T07:00:00Z')
  const after = new Date('2026-10-01T09:00:00Z')

  it('按有效期派生 有效/过期/未声明 三态', () => {
    expect(validityStatus('2026-10-01T08:00:00Z', before)).toBe('active')
    expect(validityStatus('2026-10-01T08:00:00Z', after)).toBe('expired')
    expect(validityStatus(null, after)).toBe('unspecified')
    expect(validityStatus('not-a-date', after)).toBe('unspecified')
  })

  it('时效标签始终携带 Mock 口径并区分过期态', () => {
    expect(validityLabel('2026-10-01T08:00:00Z', before)).toBe(
      '有效期至：2026-10-01T08:00:00Z · 有效（Mock）',
    )
    expect(validityLabel('2026-10-01T08:00:00Z', after)).toContain('已过期')
    expect(validityLabel('2026-10-01T08:00:00Z', after)).toMatch(/Mock/)
    expect(validityLabel(null, after)).toContain('未声明')
  })

  it('逐事实 Evidence 与信封 source_metadata 都携带有效期', () => {
    const model = createDemoViewModel('success')

    expect(model.s02Envelope.source_metadata[0]?.valid_until).toBe('2026-10-01T08:00:00Z')
    for (const day of model.days) {
      expect(day.weatherEvidence.validUntil).toBe('2026-10-01T08:00:00Z')
      for (const entry of day.items) {
        expect(entry.evidence.validUntil).toBe('2026-10-01T08:00:00Z')
      }
    }
  })
})
