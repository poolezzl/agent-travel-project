import { request } from './http'
import type { PointsResponse } from './types'

// 字段以 contracts/openapi/s08-public.yaml 为准。
export interface TopUpPackage {
  code: string
  points: number
}

export interface WalletQuote {
  quoteId: string
  userId: string
  pointsDelta: number
  balanceAfter: number
  expiresAt: string
}

export interface LedgerEntry {
  id: string
  userId: string
  taskId: string | null
  entryType: string
  pointsDelta: number
  balanceAfter: number
  createdAt: string
}

export interface LedgerPage {
  entries: LedgerEntry[]
  nextOffset: number | null
}

const prefix = '/api/v1/points'

export function packages() {
  return request<TopUpPackage[]>(prefix + '/packages')
}

export function ledger(offset = 0) {
  return request<LedgerPage>(prefix + '/ledger?' + new URLSearchParams({ offset: String(offset) }))
}

export function adminLedger(offset = 0, userId = '') {
  const query = new URLSearchParams({ offset: String(offset) })
  if (userId) query.set('userId', userId)
  return request<LedgerPage>(prefix + '/admin/ledger?' + query)
}

export function quoteTopUp(packageCode: string, idempotencyKey: string) {
  return request<WalletQuote>(prefix + '/top-up/quotes', {
    method: 'POST',
    body: { packageCode },
    idempotencyKey,
  })
}

export function quoteAdjustment(userId: string, pointsDelta: number, idempotencyKey: string) {
  return request<WalletQuote>(prefix + '/admin/adjustments/quotes', {
    method: 'POST',
    body: { userId, pointsDelta },
    idempotencyKey,
  })
}

export function confirm(quoteId: string, idempotencyKey: string) {
  return request<PointsResponse>(prefix + '/wallet/confirm', {
    method: 'POST',
    body: { quoteId },
    idempotencyKey,
  })
}
