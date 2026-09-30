export type S02AgentStatus = 'ok' | 'partial' | 'degraded' | 'failed'

export type S02SourceType =
  'map' | 'weather' | 'knowledge_base' | 'reservation_rule' | 'poi' | 'user_input' | 'system'

export interface S02SourceMetadata {
  source_type: S02SourceType
  source_record_id: string
  retrieved_at: string
  valid_until?: string | null
  freshness_status?: 'fresh' | 'stale' | 'unknown'
  is_estimated?: boolean
}

export interface S02EnvelopeBase {
  schema_version: 'agent-contract-v1'
  task_id: string
  trace_id: string
  request_id: string
  generated_at: string
  status: S02AgentStatus
  source_metadata: S02SourceMetadata[]
  message?: string
  degraded_fields?: string[]
  error_code?: string
}

export interface S02OrchestratorEnvelope extends S02EnvelopeBase {
  plan?: { days: unknown[] }
}

export interface S02RoutePayload {
  ordered_points: string[]
  segments: unknown[]
  is_estimated: boolean
}

export interface S02RouteEnvelope extends S02EnvelopeBase {
  route?: S02RoutePayload
}

export interface S02WeatherDailyPayload {
  date: string
  temp_min: number
  temp_max: number
  precip_prob: number | null
  condition: string
  data_type: 'forecast' | 'climate_statistics'
  source_record_id: string
  retrieved_at: string
  valid_until: string | null
}

export interface S02WeatherEnvelope extends S02EnvelopeBase {
  daily?: S02WeatherDailyPayload[]
  travel_advice?: Record<string, unknown>
}

export interface S02ReservationPayload {
  point_id: string
  policy_id: string
  ticket_type: string
  currency?: string
  amount_minor?: number
  effective_from: string
  effective_until: string | null
  timezone: string
  last_verified: string
  stale: boolean
}

export interface S02ReservationEnvelope extends S02EnvelopeBase {
  reservations?: S02ReservationPayload[]
  timeline?: unknown[]
}

const generatedAt = '2026-09-30T08:00:00Z'

function baseEnvelope(status: S02AgentStatus, requestId: string): S02EnvelopeBase {
  return {
    schema_version: 'agent-contract-v1',
    task_id: 'task-s07-synthetic-demo',
    trace_id: 'trace-s07-synthetic-demo',
    request_id: requestId,
    generated_at: generatedAt,
    status,
    source_metadata: [],
  }
}

export const s02Fixtures = {
  orchestrator: {
    ok: {
      ...baseEnvelope('ok', 'req-s07-orchestrator-ok'),
      plan: { days: [] },
    },
    partial: {
      ...baseEnvelope('partial', 'req-s07-orchestrator-partial'),
      plan: { days: [] },
      message: 'Synthetic provider fields are unavailable.',
      degraded_fields: ['provider_fields'],
    },
    degraded: {
      ...baseEnvelope('degraded', 'req-s07-orchestrator-degraded'),
      plan: { days: [] },
      message: 'Synthetic provider fields are unavailable.',
      degraded_fields: ['provider_fields'],
    },
    failed: {
      ...baseEnvelope('failed', 'req-s07-orchestrator-failed'),
      error_code: 'AGENT_UNAVAILABLE',
      message: 'Synthetic agent service is unavailable.',
    },
  } satisfies Record<S02AgentStatus, S02OrchestratorEnvelope>,
  route: {
    ok: {
      ...baseEnvelope('ok', 'req-s07-route-ok'),
      route: {
        ordered_points: ['synthetic-bj-001'],
        segments: [],
        is_estimated: false,
      },
    },
  } satisfies { ok: S02RouteEnvelope },
  weather: {
    ok: {
      ...baseEnvelope('ok', 'req-s07-weather-ok'),
      daily: [],
      travel_advice: {},
    },
  } satisfies { ok: S02WeatherEnvelope },
  reservations: {
    ok: {
      ...baseEnvelope('ok', 'req-s07-reservations-ok'),
      reservations: [],
      timeline: [],
    },
  } satisfies { ok: S02ReservationEnvelope },
} as const
