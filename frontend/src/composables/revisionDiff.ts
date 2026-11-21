// 修订 diff 纯函数：路径解析、按天分组、新旧值对比、保护项/Agent 展示映射、未受影响天数计算。
// 全部为无副作用纯函数，便于单测。展示不依赖 v-html，值统一走 JSON 摘要 + 截断。

import type {
  AtomicChange,
  PlanDay,
  PlanView,
  RevisionOperation,
  RevisionResult,
} from '@/api/types'

export interface ParsedChangePath {
  /** 1 起算的天序号。 */
  dayIndex: number
  collection: 'segments' | 'meals' | 'lodging' | 'reservations' | 'unknown'
  /** 0 起算的数组索引；lodging 固定 0；未知集合时为 -1。 */
  index: number
}

const COLLECTION_LABEL: Record<'segments' | 'meals' | 'lodging' | 'reservations', string> = {
  segments: '段',
  meals: '餐',
  lodging: '住宿',
  reservations: '项预约',
}

const COLLECTIONS = new Set(['segments', 'meals', 'lodging', 'reservations'])

/**
 * 解析 /days/{1 起算天}/{segments|meals|lodging|reservations}/{0 起算索引}。
 * 结构不满足约定时返回 null；索引非负整数或集合未知时降级返回（collection='unknown' / index=-1）。
 */
export function parseChangePath(path: string): ParsedChangePath | null {
  if (!path.startsWith('/days/')) return null
  const parts = path.split('/').filter((part) => part.length > 0)
  if (parts.length < 3 || parts[0] !== 'days') return null
  const dayIndex = Number(parts[1])
  if (!Number.isInteger(dayIndex) || dayIndex < 1) return null
  const collection = parts[2]
  if (!COLLECTIONS.has(collection)) {
    return { dayIndex, collection: 'unknown', index: -1 }
  }
  const typed = collection as ParsedChangePath['collection']
  const indexRaw = parts[3]
  const index = indexRaw === undefined ? 0 : Number(indexRaw)
  if (indexRaw !== undefined && (!Number.isInteger(index) || index < 0)) {
    return { dayIndex, collection: typed, index: -1 }
  }
  return { dayIndex, collection: typed, index }
}

function isInRange(
  day: PlanDay,
  collection: ParsedChangePath['collection'],
  index: number,
): boolean {
  switch (collection) {
    case 'segments':
      return index >= 0 && index < day.segments.length
    case 'meals':
      return index >= 0 && index < day.meals.length
    case 'lodging':
      return index === 0
    case 'reservations':
      return index >= 0 && index < day.reservations.length
    default:
      return false
  }
}

function resolveItemName(
  day: PlanDay | undefined,
  collection: string,
  index: number,
): string | null {
  if (!day) return null
  switch (collection) {
    case 'segments':
      return day.segments[index]?.name ?? null
    case 'meals': {
      const meal = day.meals[index]
      if (!meal) return null
      const label = meal.meal === 'lunch' ? '午餐' : '晚餐'
      const name = meal.candidates[0]?.name
      return name ? `${label} · ${name}` : label
    }
    case 'lodging': {
      if (index !== 0) return null
      return day.lodging?.candidates[0]?.name ?? day.lodging?.area ?? null
    }
    case 'reservations': {
      const reservation = day.reservations[index]
      if (!reservation) return null
      return reservation.ticket_type ?? reservation.point_id ?? null
    }
    default:
      return null
  }
}

/** 把变更路径格式化为人类可读位置；无法解析/越界时原样返回 path。 */
export function formatLocation(path: string, plan?: PlanView | null): string {
  const parsed = parseChangePath(path)
  if (!parsed || parsed.collection === 'unknown' || parsed.index < 0 || !plan) return path
  const day = plan.plan.days[parsed.dayIndex - 1]
  if (!day || !isInRange(day, parsed.collection, parsed.index)) return path
  const name = resolveItemName(day, parsed.collection, parsed.index)
  const label = COLLECTION_LABEL[parsed.collection]
  const ordinal = parsed.collection === 'lodging' ? label : `第 ${parsed.index + 1} ${label}`
  return name
    ? `第 ${parsed.dayIndex} 天 · ${ordinal} · ${name}`
    : `第 ${parsed.dayIndex} 天 · ${ordinal}`
}

/** 保护项（锁定路径）的人类可读文案；解析失败原样显示路径。 */
export function lockedPathLabel(path: string, plan?: PlanView | null): string {
  const parsed = parseChangePath(path)
  if (!parsed || parsed.collection === 'unknown' || parsed.index < 0 || !plan) return path
  const day = plan.plan.days[parsed.dayIndex - 1]
  if (!day || !isInRange(day, parsed.collection, parsed.index)) return path
  const name = resolveItemName(day, parsed.collection, parsed.index)
  const label = COLLECTION_LABEL[parsed.collection]
  const ordinal = parsed.collection === 'lodging' ? label : `第 ${parsed.index + 1} ${label}`
  return name
    ? `第 ${parsed.dayIndex} 天 ${ordinal} · ${name}`
    : `第 ${parsed.dayIndex} 天 ${ordinal}`
}

export function operationLabel(operation: string): string {
  switch (operation) {
    case 'add':
      return '新增'
    case 'remove':
      return '删除'
    case 'replace':
      return '替换'
    case 'reorder':
      return '重排'
    default:
      return operation
  }
}

export function operationClass(operation: string): string {
  return `revision-diff__op--${operation}`
}

const AGENT_LABELS: Record<string, string> = {
  orchestrator: '编排',
  'route-planner': '路线规划',
  weather: '天气',
  attractions: '景点',
  'dining-lodging': '餐饮住宿',
  'reservation-rules': '预约规则',
  revision: '修正',
}

/** Agent key → 中文映射；未知 key 原样显示。 */
export function agentLabel(key: string): string {
  return AGENT_LABELS[key] ?? key
}

function truncate(text: string, maxLength: number): string {
  if (text.length <= maxLength) return text
  return `${text.slice(0, maxLength - 1)}…`
}

/** 值 → JSON 摘要（字符串直接返回，其余 JSON.stringify + 超长截断）。 */
export function formatValue(value: unknown, maxLength = 240): string {
  if (value === undefined || value === null) return '—'
  if (typeof value === 'string') return truncate(value, maxLength)
  let text: string
  try {
    text = JSON.stringify(value)
  } catch {
    text = String(value)
  }
  return truncate(text, maxLength)
}

/** 从指定的 days 快照中读取变更路径上的值（用于 replace/remove 的「原值」，不依赖当前 plan）。 */
export function oldValueAtFromDays(path: string, days: PlanDay[] | null | undefined): unknown {
  const parsed = parseChangePath(path)
  if (!parsed || parsed.collection === 'unknown' || parsed.index < 0 || !days) return undefined
  const day = days[parsed.dayIndex - 1]
  if (!day) return undefined
  switch (parsed.collection) {
    case 'segments':
      return day.segments[parsed.index]
    case 'meals':
      return day.meals[parsed.index]
    case 'lodging':
      return parsed.index === 0 ? day.lodging : undefined
    case 'reservations':
      return day.reservations[parsed.index]
    default:
      return undefined
  }
}

/** 读取变更路径在当前 plan 上的旧值（向后兼容：内部转调 oldValueAtFromDays）。 */
export function oldValueAt(path: string, plan?: PlanView | null): unknown {
  return oldValueAtFromDays(path, plan?.plan.days)
}

export interface DayGroup {
  /** 1 起算天序号；null 表示无法解析的天，统一归入降级分组。 */
  day: number | null
  changes: AtomicChange[]
}

/** 按天分组；无法解析路径的变更归入 day=null 分组，不丢弃。 */
export function groupChangesByDay(changes: AtomicChange[]): DayGroup[] {
  const byDay = new Map<number, AtomicChange[]>()
  const unparsed: AtomicChange[] = []
  for (const change of changes) {
    const parsed = parseChangePath(change.path)
    if (!parsed || parsed.collection === 'unknown' || parsed.index < 0) {
      unparsed.push(change)
      continue
    }
    const list = byDay.get(parsed.dayIndex) ?? []
    list.push(change)
    byDay.set(parsed.dayIndex, list)
  }
  const groups: DayGroup[] = [...byDay.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([day, list]) => ({ day, changes: list }))
  if (unparsed.length > 0) groups.push({ day: null, changes: unparsed })
  return groups
}

export function deepEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false
  if (Array.isArray(a) !== Array.isArray(b)) return false
  const aObj = a as Record<string, unknown>
  const bObj = b as Record<string, unknown>
  const aKeys = Object.keys(aObj)
  const bKeys = Object.keys(bObj)
  if (aKeys.length !== bKeys.length) return false
  for (const key of aKeys) {
    if (!Object.prototype.hasOwnProperty.call(bObj, key)) return false
    if (!deepEqual(aObj[key], bObj[key])) return false
  }
  return true
}

export interface DayStatus {
  affected: number[]
  verifiedUnchanged: number[]
  unverified: number[]
}

/** 对比刷新前后 days：受影响天、已验证未变更天、未核对天。只有真正比较过才归入 verifiedUnchanged。 */
export function dayStatus(before: PlanDay[], after: PlanDay[], affectedDays: number[]): DayStatus {
  const affected = new Set(affectedDays)
  const verifiedUnchanged: number[] = []
  const unverified: number[] = []
  const max = Math.max(before.length, after.length)
  for (let i = 0; i < max; i++) {
    const dayIndex = i + 1
    if (affected.has(dayIndex)) continue
    const b = before[i]
    const a = after[i]
    if (b !== undefined && a !== undefined && deepEqual(b, a)) {
      verifiedUnchanged.push(dayIndex)
    } else {
      unverified.push(dayIndex)
    }
  }
  return { affected: [...affectedDays], verifiedUnchanged, unverified }
}

/** 确认成功后的实际变更集：优先 change_set，回退 changes。 */
export function effectiveChanges(result: RevisionResult): AtomicChange[] {
  return result.change_set && result.change_set.length > 0 ? result.change_set : result.changes
}

export type { RevisionOperation }
