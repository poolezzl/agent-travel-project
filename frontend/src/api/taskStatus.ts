// 任务状态映射与推导（纯函数，可单测）。14 态来源：contracts/openapi/s11-tasks.yaml。

import type { TaskStatus } from './types'

export const TERMINAL_STATUSES: readonly TaskStatus[] = [
  'SUCCEEDED',
  'PARTIAL',
  'FAILED',
  'CANCELLED',
  'REFUNDED',
] as const

export const CANCELLABLE_STATUSES: readonly TaskStatus[] = [
  'CREATED',
  'BALANCE_RESERVED',
  'QUEUED',
  'PARSING',
  'COLLECTING',
  'SCHEDULING',
  'ROUTING',
  'ASSEMBLING',
  'AWAITING_CONFIRMATION',
] as const

const STATUS_LABELS: Record<TaskStatus, string> = {
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

export function taskStatusLabel(status: string): string {
  return STATUS_LABELS[status as TaskStatus] ?? status
}

export function isTerminalStatus(status: string): boolean {
  return TERMINAL_STATUSES.includes(status as TaskStatus)
}

export function canCancelStatus(status: string): boolean {
  return CANCELLABLE_STATUSES.includes(status as TaskStatus)
}
