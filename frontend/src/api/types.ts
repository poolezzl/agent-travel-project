// S14 手写契约类型。字段一律 snake_case，与 OpenAPI 一致，不做 camelCase 转换层。
// 每个类型标注来源文件与版本；未知/自由字段保留 unknown，不丢弃、不猜测。

// ===== 公共问题详情（s08-public.yaml / s11-tasks.yaml）=====

/**
 * 后端统一错误载荷。content-type 为 application/problem+json。
 * 来源：contracts/openapi/s08-public.yaml `ProblemDetail`、s11-tasks.yaml `Problem`。
 */
export interface ProblemDetail {
  type: string
  title: string
  status: number
  detail: string
  code: string
  traceId: string
  timestamp: string
}

// ===== 认证（contracts/openapi/s08-public.yaml v1）=====

export type Role = 'USER' | 'ADMIN'

export interface RegisterRequest {
  login: string
  password: string
  role?: 'USER'
}

export interface LoginRequest {
  login: string
  password: string
}

export interface RefreshRequest {
  refreshToken: string
}

export interface TokenResponse {
  accessToken: string
  refreshToken: string
  expiresInSeconds: number
  userId: string
  role: Role
}

export interface CurrentUser {
  userId: string
  login: string
  role: Role
  status: string
  createdAt: string
}

// ===== 积分（contracts/openapi/s08-public.yaml v1）=====

export interface QuoteRequest {
  planCount: number
  taskId?: string | null
}

export interface QuoteResponse {
  quoteId: string
  quoteReference: string
  planCount: number
  points: number
  expiresAtEpochSecond: number
}

export interface ReserveRequest {
  quoteReference: string
  taskId?: string | null
}

export interface SettlementRequest {
  reservationReference: string
}

export interface GrantRequest {
  userId: string
  points: number
}

export interface PointsResponse {
  businessReference: string
  pointsDelta: number
  balancePoints: number
  reused: boolean
}

export interface BalanceResponse {
  balancePoints: number
}

// ===== 任务（contracts/openapi/s11-tasks.yaml）=====

export type TaskType = 'GENERATION' | 'REVISION'

/** 14 个任务状态，来源 s11-tasks.yaml `TaskView.status`。 */
export type TaskStatus =
  | 'CREATED'
  | 'BALANCE_RESERVED'
  | 'QUEUED'
  | 'PARSING'
  | 'COLLECTING'
  | 'SCHEDULING'
  | 'ROUTING'
  | 'ASSEMBLING'
  | 'AWAITING_CONFIRMATION'
  | 'SUCCEEDED'
  | 'PARTIAL'
  | 'FAILED'
  | 'CANCELLED'
  | 'REFUNDED'

/**
 * 规划输入。s11-tasks.yaml 中 `CreateTaskRequest.request` 为
 * `additionalProperties: true` 的不可变自由对象；本类型是 S14 M1 前端提交的形状，
 * 后端（MockAgentExecutor）当前不深解析，字段仅用于持久化与后续 S19 解析 Agent。
 * 金额统一 currency/amount_minor 口径；预算为总额。
 */
export interface Money {
  /** ISO 4217 货币代码，M1 固定 CNY。 */
  currency: string
  /** 整数最小单位金额（CNY 为「分」）。 */
  amount_minor: number
}

export interface PlanningRequestPayload {
  city: string
  start_date: string
  end_date: string
  days: number
  party_size: number
  budget: Money
  must_visit: string[]
  preferences: string
  transport: string
  plan_count: number
}

export interface CreateTaskRequest {
  task_type: TaskType
  trip_id?: string
  base_version_id?: string | null
  plan_count: number
  request: PlanningRequestPayload
}

export interface CancelTaskRequest {
  expected_version: number
}

export interface TaskView {
  task_id: string
  owner_id: string
  quote_id?: string | null
  task_type: TaskType
  status: TaskStatus
  state_version: number
  reserved_points: number
  operation_deadline_at: string
  confirmation_deadline_at?: string | null
  created_at: string
  updated_at: string
  reused: boolean
}

export interface TaskEvent {
  sequence_no: number
  event_type: string
  status: string
  step_name?: string | null
  attempt_no?: number | null
  message?: string | null
  degraded_fields?: string[]
  created_at: string
}

// ===== 事实来源元数据（展示口径）=====

/**
 * 事实来源元数据，形状对齐 agents/S02 契约 `source_metadata`。
 * S14 后端未提供行程结果查询接口，此类型当前无从填充，仅用于来源/时效/估算展示口径。
 */
export interface SourceMetadata {
  source_type: string
  source_record_id: string
  retrieved_at: string
  valid_until: string | null
  freshness_status: string
  is_estimated: boolean
}

// ===== 最终行程（contracts/openapi/s11-tasks.yaml `PlanView` + contracts/s19/plan.schema.json）=====

export interface PlanTransport {
  mode: string
  distance_m: number
  duration_min: number
  is_estimated: boolean
  source_record_id: string
  retrieved_at: string
}

export interface PlanFact {
  fact_type: string
  value: string
  chunk_id?: string | null
  source_record_id: string
}

export interface PlanSegment {
  sequence_no: number
  point_id: string
  name: string
  district?: string | null
  category?: string
  start_time: string
  end_time: string
  duration_min: number
  duration_source: 'knowledge' | 'assumed'
  is_must_visit: boolean
  is_estimated: boolean
  facts: PlanFact[]
  transport_to_next?: PlanTransport | null
}

export interface PlanWeather {
  status: 'ok' | 'degraded' | 'unknown'
  date: string
  condition?: string | null
  temp_min?: number | null
  temp_max?: number | null
  precip_prob?: number | null
  data_type?: string | null
  source_record_id?: string | null
  stale?: boolean
  message?: string
}

export interface PlanMealCandidate {
  poi_id: string
  name: string
  category?: string
  price?: { kind?: string; currency?: string | null; amount_minor?: number | null }
  detour_min?: number
  source_record_id?: string
}

export interface PlanMeal {
  date: string
  meal: 'lunch' | 'dinner'
  anchor_point_id: string
  start_time?: string | null
  candidates: PlanMealCandidate[]
}

export interface PlanLodging {
  date: string
  area?: string | null
  candidates: PlanMealCandidate[]
}

export interface PlanReservation {
  point_id: string
  policy_id: string
  ticket_type?: string
  reservation_required: boolean
  channel?: string | null
  currency?: string | null
  amount_minor?: number | null
  stale: boolean
  holiday_exception?: string | null
  timeline?: Array<{ release_at?: string; status?: string; [key: string]: unknown }>
}

export interface PlanDay {
  date: string
  weekday: string
  city_id: string
  weather: PlanWeather
  segments: PlanSegment[]
  meals: PlanMeal[]
  lodging: PlanLodging
  reservations: PlanReservation[]
  warnings: string[]
}

export interface PlanQualityCheck {
  code: string
  status: 'pass' | 'warn' | 'fail' | 'skipped'
  message: string
  fields?: string[]
}

export interface PlanConflict {
  code: string
  severity: 'blocking' | 'warning'
  message: string
  affected_days?: string[]
  affected_points?: string[]
  options?: Array<{ option_id: string; label: string; impact: string }>
}

export interface PlanDocument {
  plan_version: string
  generated_at: string
  sample_only: boolean
  notice?: string
  trip: {
    city_id: string
    city_name: string
    start_date: string
    end_date: string
    day_count: number
    party_size: number
    transport_preference: string
    budget?: Money
    preferences?: string[]
  }
  summary: {
    point_count: number
    total_distance_m: number
    total_transport_min: number
    estimated_route_legs: number
    estimated_cost?: Money & { basis?: string }
    source_completeness: number
    degraded: boolean
    stale_fields: string[]
    unknown_fields: string[]
  }
  days: PlanDay[]
  quality: { status: 'pass' | 'warn' | 'fail'; checks: PlanQualityCheck[] }
  conflicts: PlanConflict[]
  confirmation: { required: boolean; conflict_codes?: string[] }
  sources: SourceMetadata[]
  degraded_fields: string[]
}

export interface PlanView {
  task_id: string
  status: 'SUCCEEDED' | 'PARTIAL'
  trip_id: string
  version_id: string
  version_no: number
  etag: string
  content_hash?: string
  schema_version: string
  generated_at: string
  plan: PlanDocument
  summary?: Record<string, unknown> | null
  source_metadata: SourceMetadata[]
  degraded_fields: string[]
}

// ===== 行程修订（contracts/openapi/s20-revisions.yaml s20-v1）=====

export type RevisionIntent = 'query' | 'change' | 'ambiguous'
export type RevisionOperation = 'add' | 'remove' | 'replace' | 'reorder'
export type RevisionStatus = 'PRECHECKED' | 'AWAITING_CONFIRMATION' | 'CREATED'

/**
 * 单条原子变更。path 形如 /days/{1 起算天}/{segments|meals|lodging|reservations}/{0 起算索引}。
 * value 为服务端给出的自由 JSON（新增内容、替换后的新值等），前端只做摘要展示不猜测结构。
 */
export interface AtomicChange {
  operation: RevisionOperation
  path: string
  value?: unknown
}

/**
 * 修订命令载荷。preview 只需 base_version_id / etag / revision_request / locked_paths；
 * confirm 额外带 confirmed: true 与 confirmation_id，且请求头 Idempotency-Key 必须与其一致。
 */
export interface RevisionCommand {
  base_version_id: string
  etag?: string | null
  intent?: RevisionIntent | null
  locked_paths?: string[]
  affected_days?: number[]
  affected_agents?: string[]
  changes?: AtomicChange[]
  needs_confirm?: boolean
  confirmed?: boolean
  confirmation_id?: string | null
  revision_request: string
}

/**
 * 修订结果。注意：契约与后端 Java 模型中该字段为 `createdAt`（camelCase，例外），
 * 其余字段均为 snake_case。createdAt 表示基准版本的创建时间，不得当作「本次请求时间」展示。
 */
export interface RevisionResult {
  intent: RevisionIntent
  base_version_id: string
  version_id: string | null
  parent_version_id: string | null
  etag: string
  status: RevisionStatus
  needs_confirm: boolean
  affected_days: number[]
  affected_agents: string[]
  changes: AtomicChange[]
  answer: string | null
  clarification_question: string | null
  summary: string
  createdAt: string
  reused: boolean
  change_set: AtomicChange[] | null
  source_snapshot_ids: string[] | null
  schema_version: string | null
  model_version: string | null
  prompt_version: string | null
  content_hash: string | null
}
