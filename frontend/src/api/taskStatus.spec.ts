import { describe, expect, it } from 'vitest'

import { canCancelStatus, isTerminalStatus, taskStatusLabel, TERMINAL_STATUSES } from './taskStatus'

describe('taskStatus 映射', () => {
  it('覆盖全部 14 态并映射中文', () => {
    const labels: Record<string, string> = {
      CREATED: '已创建',
      BALANCE_RESERVED: '积分已预扣',
      QUEUED: '排队中',
      PARSING: '解析需求',
      COLLECTING: '采集来源',
      SCHEDULING: '日程编排',
      ROUTING: '路线规划',
      ASSEMBLING: '整合方案',
      AWAITING_CONFIRMATION: '待确认',
      SUCCEEDED: '成功',
      PARTIAL: '部分成功',
      FAILED: '失败',
      CANCELLED: '已取消',
      REFUNDED: '已退款',
    }
    for (const [status, label] of Object.entries(labels)) {
      expect(taskStatusLabel(status)).toBe(label)
    }
  })

  it('未知状态回退原始字符串', () => {
    expect(taskStatusLabel('UNKNOWN_XYZ')).toBe('UNKNOWN_XYZ')
  })

  it('终态判定覆盖成功/部分/失败/取消/退款', () => {
    expect(TERMINAL_STATUSES).toEqual(['SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED', 'REFUNDED'])
    expect(isTerminalStatus('SUCCEEDED')).toBe(true)
    expect(isTerminalStatus('QUEUED')).toBe(false)
    expect(isTerminalStatus('AWAITING_CONFIRMATION')).toBe(false)
  })

  it('可取消判定覆盖非终态，终态不可取消', () => {
    expect(canCancelStatus('QUEUED')).toBe(true)
    expect(canCancelStatus('AWAITING_CONFIRMATION')).toBe(true)
    expect(canCancelStatus('SUCCEEDED')).toBe(false)
    expect(canCancelStatus('CANCELLED')).toBe(false)
  })
})
