import { request } from './http'

// 字段以 contracts/openapi/s24-preference.yaml 为准。
export interface HistoricalTrip {
  id: string
  title: string
  startDate: string
  endDate: string
  status: string
  cities: string[]
  versionCount: number
  currentVersionId: string | null
}

export interface PreferenceItem {
  key: string
  valueJson: unknown
  sourceType: string
  confidence: number | null
  weight: number
  lastUsedAt: string | null
}

export interface PreferenceSummary {
  summaryVersion: number
  sampleCount: number
  eligible: boolean
  dimensions: PreferenceItem[]
  narrative: string
  generatedAt: string
}

export interface ReuseResult {
  tripId: string
  versionId: string
  removedFields: string[]
}

export interface VersionSummary {
  versionId: string
  versionNo: number
  versionStatus: string
  createdAt: string | null
  schemaVersion: string | null
}

export interface VersionChange {
  path: string
  changeType: 'ADDED' | 'REMOVED' | 'MODIFIED'
  before: unknown
  after: unknown
}

export interface VersionComparison {
  tripId: string
  from: VersionSummary
  to: VersionSummary
  changes: VersionChange[]
}

const prefix = '/api/v1'

export interface TripFilter {
  from?: string
  to?: string
  city?: string
  days?: number
  status?: string
  q?: string
  limit?: number
  offset?: number
}

export function historyTrips(filter: TripFilter = {}) {
  const query = new URLSearchParams()
  Object.entries(filter).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') query.set(key, String(value))
  })
  const suffix = query.toString() ? '?' + query.toString() : ''
  return request<HistoricalTrip[]>(prefix + '/history/trips' + suffix)
}

export function reuseTrip(tripId: string, idempotencyKey: string) {
  return request<ReuseResult>(prefix + '/trips/' + encodeURIComponent(tripId) + '/reuse', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function compareTripVersions(tripId: string, from?: string, to?: string) {
  const query = new URLSearchParams()
  if (from) query.set('from', from)
  if (to) query.set('to', to)
  const suffix = query.toString() ? '?' + query.toString() : ''
  return request<VersionComparison>(
    prefix + '/trips/' + encodeURIComponent(tripId) + '/versions/compare' + suffix,
  )
}

export function preferenceSummary() {
  return request<PreferenceSummary>(prefix + '/preferences/summary')
}

export function clearPreferences(idempotencyKey: string) {
  return request<void>(prefix + '/preferences/summary', {
    method: 'DELETE',
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}
