import { describe, expect, it } from 'vitest'

import { buildMockStages, mockStagePlan } from './mockProgress'

describe('mock progress stages', () => {
  it('按进度索引标记已完成、进行中与待执行', () => {
    const stages = buildMockStages(2, 'running')

    expect(stages.map((stage) => stage.status)).toEqual([
      'done',
      'done',
      'running',
      'pending',
      'pending',
      'pending',
    ])
  })

  it('失败态标记失败阶段并把后续阶段标记为未执行', () => {
    const stages = buildMockStages(3, 'error')

    expect(stages[3]?.status).toBe('failed')
    expect(stages[4]?.status).toBe('skipped')
    expect(stages[5]?.status).toBe('skipped')
    expect(stages.filter((stage) => stage.status === 'failed')).toHaveLength(1)
  })

  it('完成态把全部阶段标记为已完成', () => {
    const stages = buildMockStages(mockStagePlan.length, 'success')

    expect(stages.every((stage) => stage.status === 'done')).toBe(true)
  })

  it('阶段文案明确为本地模拟，不声称真实能力', () => {
    const stages = buildMockStages(0, 'running')

    expect(stages.map((stage) => stage.label)).toEqual([
      '排队',
      '解析需求',
      '采集来源',
      '生成路线',
      '整合方案',
      '完成 / 部分 / 失败',
    ])
    expect(stages[0]?.detail).toContain('不调用真实调度')
  })
})
