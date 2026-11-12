// 积分接口封装。来源：contracts/openapi/s08-public.yaml。
// 前端不计算积分价格或余额：报价/预扣/余额一律以服务端返回为准。

import { request } from './http'
import type {
  BalanceResponse,
  GrantRequest,
  PointsResponse,
  QuoteRequest,
  QuoteResponse,
  ReserveRequest,
  SettlementRequest,
} from './types'

export function quote(
  payload: QuoteRequest,
  idempotencyKey: string,
): Promise<QuoteResponse | undefined> {
  return request<QuoteResponse>('/api/v1/points/quotes', {
    method: 'POST',
    body: payload,
    idempotencyKey,
  })
}

export function balance(): Promise<BalanceResponse | undefined> {
  return request<BalanceResponse>('/api/v1/points/balance')
}

export function reserve(
  payload: ReserveRequest,
  idempotencyKey: string,
): Promise<PointsResponse | undefined> {
  return request<PointsResponse>('/api/v1/points/reserve', {
    method: 'POST',
    body: payload,
    idempotencyKey,
  })
}

export function confirm(
  payload: SettlementRequest,
  idempotencyKey: string,
): Promise<PointsResponse | undefined> {
  return request<PointsResponse>('/api/v1/points/confirm', {
    method: 'POST',
    body: payload,
    idempotencyKey,
  })
}

export function refund(
  payload: SettlementRequest,
  idempotencyKey: string,
): Promise<PointsResponse | undefined> {
  return request<PointsResponse>('/api/v1/points/refund', {
    method: 'POST',
    body: payload,
    idempotencyKey,
  })
}

export function grant(
  payload: GrantRequest,
  idempotencyKey: string,
): Promise<PointsResponse | undefined> {
  return request<PointsResponse>('/api/v1/points/grant', {
    method: 'POST',
    body: payload,
    idempotencyKey,
  })
}
