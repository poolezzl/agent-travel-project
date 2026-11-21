// 行程修订接口封装。来源：contracts/openapi/s20-revisions.yaml s20-v1。
// 幂等键（confirm）与 If-Match（rollback）均走 request 的既有 header 能力，不改 http.ts 语义。

import { request } from './http'
import type { RevisionCommand, RevisionResult } from './types'

/** 修改/问答预检：query/ambiguous 不校验 etag，change 必须携带基准版本 etag。 */
export function preview(
  tripId: string,
  payload: RevisionCommand,
): Promise<RevisionResult | undefined> {
  return request<RevisionResult>(`/api/v1/trips/${tripId}/revisions/preview`, {
    method: 'POST',
    body: payload,
  })
}

/** 确认执行修改；Idempotency-Key 必须与 payload.confirmation_id 完全一致。 */
export function confirm(
  tripId: string,
  payload: RevisionCommand,
  idempotencyKey: string,
): Promise<RevisionResult | undefined> {
  return request<RevisionResult>(`/api/v1/trips/${tripId}/revisions/confirm`, {
    method: 'POST',
    body: payload,
    idempotencyKey,
  })
}

/** 回滚：以当前最新版本 etag 作为 If-Match，成功后创建新版本（不删除历史）。 */
export function rollback(
  tripId: string,
  versionId: string,
  ifMatch: string,
): Promise<RevisionResult | undefined> {
  return request<RevisionResult>(`/api/v1/trips/${tripId}/revisions/rollback/${versionId}`, {
    method: 'POST',
    headers: { 'If-Match': ifMatch },
  })
}
