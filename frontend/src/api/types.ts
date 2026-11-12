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
