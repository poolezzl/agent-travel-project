export type MockStageStatus = 'pending' | 'running' | 'done' | 'failed' | 'skipped'

export interface MockStage {
  id: string
  label: string
  detail: string
  status: MockStageStatus
}

export type MockOutcome = 'running' | 'success' | 'partial' | 'error'

export const mockStagePlan: ReadonlyArray<Pick<MockStage, 'id' | 'label' | 'detail'>> = [
  { id: 'queued', label: '排队', detail: '演示任务在本地队列等待，不调用真实调度。' },
  { id: 'parsing', label: '解析需求', detail: '解析城市、日期、必去点与偏好（本地规则）。' },
  { id: 'sources', label: '采集来源', detail: '合成来源与时效标识，均为 Mock / 待核验。' },
  { id: 'route', label: '生成路线', detail: '按合成点位拼装三日顺序与交通段（估算）。' },
  { id: 'integrate', label: '整合方案', detail: '组合节奏、覆盖与预算区间，数值为估算。' },
  { id: 'finalize', label: '完成 / 部分 / 失败', detail: '输出演示结果或明确的降级说明。' },
]

export function buildMockStages(progressIndex: number, outcome: MockOutcome): MockStage[] {
  return mockStagePlan.map((stage, index) => {
    const status = resolveStageStatus(index, progressIndex, outcome)
    return { ...stage, status }
  })
}

function resolveStageStatus(
  index: number,
  progressIndex: number,
  outcome: MockOutcome,
): MockStageStatus {
  if (outcome === 'error') {
    if (index < progressIndex) return 'done'
    if (index === progressIndex) return 'failed'
    return 'skipped'
  }
  if (outcome === 'running' && index >= progressIndex) {
    return index === progressIndex ? 'running' : 'pending'
  }
  return 'done'
}
