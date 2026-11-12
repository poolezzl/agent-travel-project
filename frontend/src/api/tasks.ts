// 任务接口封装。来源：contracts/openapi/s11-tasks.yaml。
// 注意：CreateTaskRequest 没有 quote_id 字段；报价仅用于展示服务端价格，建任务时服务端自行计价预扣。

import { apiUrl, request } from './http'
import type { CancelTaskRequest, CreateTaskRequest, TaskView } from './types'

export function create(
  payload: CreateTaskRequest,
  idempotencyKey: string,
): Promise<TaskView | undefined> {
  return request<TaskView>('/api/v1/tasks', {
    method: 'POST',
    body: payload,
    idempotencyKey,
  })
}

export function get(taskId: string): Promise<TaskView | undefined> {
  return request<TaskView>(`/api/v1/tasks/${taskId}`)
}

export function cancel(
  taskId: string,
  payload: CancelTaskRequest,
  idempotencyKey: string,
): Promise<TaskView | undefined> {
  return request<TaskView>(`/api/v1/tasks/${taskId}/cancel`, {
    method: 'POST',
    body: payload,
    idempotencyKey,
  })
}

/** SSE 事件流地址。after_sequence_no 用于断线续传（后端也支持 Last-Event-ID 头，二选一）。 */
export function eventsUrl(taskId: string, afterSequenceNo: number): string {
  const query = afterSequenceNo > 0 ? `?after_sequence_no=${afterSequenceNo}` : ''
  return apiUrl(`/api/v1/tasks/${taskId}/events${query}`)
}
