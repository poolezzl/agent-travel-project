import type { S02AgentStatus, S02OrchestratorEnvelope, S02SourceMetadata } from './s02'
import { s02Fixtures } from './s02'

export type DemoStatus = 'success' | 'loading' | 'empty' | 'partial' | 'error'
export type Freshness = 'fresh' | 'stale' | 'unknown'

export interface PlanningInput {
  city: string
  startDate: string
  endDate: string
  mustVisit: string[]
  preference: string
  transport: '混合' | '步行' | '公交' | '地铁' | '打车'
  planCount: 1 | 2 | 3
}

export interface Evidence {
  sourceType: string
  sourceId: string
  retrievedAt: string
  validUntil: string | null
  freshness: Freshness
  isEstimated: boolean
  label: string
}

export type ValidityStatus = 'active' | 'expired' | 'unspecified'

export function validityStatus(validUntil: string | null, now: Date = new Date()): ValidityStatus {
  if (!validUntil) return 'unspecified'
  const until = Date.parse(validUntil)
  if (Number.isNaN(until)) return 'unspecified'
  return now.getTime() >= until ? 'expired' : 'active'
}

export function validityLabel(validUntil: string | null, now: Date = new Date()): string {
  const status = validityStatus(validUntil, now)
  if (status === 'unspecified') return '有效期：未声明 · Mock'
  const suffix = status === 'expired' ? ' · 已过期（Mock 时效，仅演示）' : ' · 有效（Mock）'
  return `有效期至：${validUntil}${suffix}`
}

export interface TimelineItem {
  id: string
  time: string
  poiId: string
  poiName: string
  activity: string
  transport: string
  duration: string
  evidence: Evidence
  weather: string
  reservation: string
  price: string
}

export interface DayPlan {
  date: string
  dayLabel: string
  weather: string
  weatherEvidence: Evidence
  items: TimelineItem[]
}

export interface PlanVariant {
  id: string
  name: string
  pace: string
  coverage: string
  commute: string
  budget: string
  note: string
}

export interface MapPoint {
  id: string
  name: string
  day: string
  position: string
}

export interface CommunitySample {
  id: string
  title: string
  author: string
  tags: string[]
  likes: string
  comments: string
  note: string
}

export interface DemoViewModel {
  status: DemoStatus
  title: string
  description: string
  days: DayPlan[]
  variants: PlanVariant[]
  mapPoints: MapPoint[]
  community: CommunitySample[]
  s02Envelope: S02OrchestratorEnvelope
}

const mockRetrievedAt = '2026-09-30T08:00:00Z'
const mockValidUntil = '2026-10-01T08:00:00Z'

function evidence(
  sourceType: string,
  sourceId: string,
  options: Partial<Pick<Evidence, 'freshness' | 'isEstimated' | 'label' | 'validUntil'>> = {},
): Evidence {
  const isEstimated = options.isEstimated ?? true
  return {
    sourceType,
    sourceId,
    retrievedAt: mockRetrievedAt,
    validUntil: options.validUntil !== undefined ? options.validUntil : mockValidUntil,
    freshness: options.freshness ?? 'unknown',
    isEstimated,
    label: options.label ?? 'Mock / 示例 / 待核验',
  }
}

function item(
  id: string,
  time: string,
  poiId: string,
  poiName: string,
  activity: string,
  transport: string,
  duration: string,
  sourceType: string,
  sourceId: string,
  options: Partial<Pick<Evidence, 'freshness' | 'isEstimated' | 'label'>> = {},
): TimelineItem {
  return {
    id,
    time,
    poiId,
    poiName,
    activity,
    transport,
    duration,
    evidence: evidence(sourceType, sourceId, options),
    weather: '晴转多云 · 18–26°C · Mock / 示例',
    reservation: '预约：待核验 · Mock / 示例',
    price: '费用：未知 · 待核验',
  }
}

const defaultDays: DayPlan[] = [
  {
    date: '2026-10-10',
    dayLabel: 'Day 1 · 城市缓入',
    weather: '晴转多云 · 18–26°C · Mock / 示例',
    weatherEvidence: evidence('weather', 'synthetic-weather-bj-d1', {
      freshness: 'fresh',
      label: 'Mock / 示例 / 预报占位',
    }),
    items: [
      item(
        'd1-1',
        '09:30',
        'synthetic-bj-001',
        '合成·北城晨光公园',
        '轻量漫步与摄影练习',
        '抵达 / 步行',
        '约 90 分钟 · 估算',
        'poi',
        'synthetic-poi-001',
        { freshness: 'unknown' },
      ),
      item(
        'd1-2',
        '12:00',
        'synthetic-bj-002',
        '合成·中轴故事馆',
        '室内展陈与午间休息',
        '步行约 18 分钟',
        '约 120 分钟 · 估算',
        'knowledge_base',
        'synthetic-kb-002',
        { freshness: 'stale', label: 'Mock / 示例 / stale / 待核验' },
      ),
      item(
        'd1-3',
        '16:00',
        'synthetic-bj-003',
        '合成·暮色观景台',
        '日落时段构图练习',
        '混合交通约 28 分钟',
        '约 80 分钟 · 估算',
        'map',
        'synthetic-map-003',
        { freshness: 'unknown' },
      ),
    ],
  },
  {
    date: '2026-10-11',
    dayLabel: 'Day 2 · 深度探索',
    weather: '多云 · 16–24°C · Mock / 示例',
    weatherEvidence: evidence('weather', 'synthetic-weather-bj-d2', {
      freshness: 'stale',
      label: 'Mock / 示例 / stale / 待核验',
    }),
    items: [
      item(
        'd2-1',
        '08:30',
        'synthetic-bj-004',
        '合成·东环创意街区',
        '街区观察与早餐',
        '地铁约 22 分钟',
        '约 75 分钟 · 估算',
        'map',
        'synthetic-map-004',
        { freshness: 'fresh' },
      ),
      item(
        'd2-2',
        '11:00',
        'synthetic-bj-005',
        '合成·石景实验园',
        '公共艺术路线',
        '步行约 14 分钟',
        '约 150 分钟 · 估算',
        'poi',
        'synthetic-poi-005',
        { freshness: 'unknown' },
      ),
      item(
        'd2-3',
        '15:30',
        'synthetic-bj-006',
        '合成·西郊静谧书屋',
        '自由阅读与备选休息',
        '打车约 24 分钟',
        '约 100 分钟 · 估算',
        'reservation_rule',
        'synthetic-rule-006',
        { freshness: 'stale', label: 'Mock / 示例 / stale / 待核验' },
      ),
    ],
  },
  {
    date: '2026-10-12',
    dayLabel: 'Day 3 · 松弛收束',
    weather: '天气数据不可用 · Mock / unknown',
    weatherEvidence: evidence('weather', 'synthetic-weather-bj-d3', {
      freshness: 'unknown',
      label: 'Mock / unknown / 待核验',
    }),
    items: [
      item(
        'd3-1',
        '10:00',
        'synthetic-bj-007',
        '合成·南城慢行廊道',
        '低强度散步与返程整理',
        '公交约 20 分钟',
        '约 70 分钟 · 估算',
        'map',
        'synthetic-map-007',
        { freshness: 'unknown' },
      ),
      item(
        'd3-2',
        '13:00',
        'synthetic-bj-008',
        '合成·回程前共享空间',
        '自由安排 / 备用室内点',
        '待核验',
        '约 90 分钟 · 估算',
        'knowledge_base',
        'synthetic-kb-008',
        { freshness: 'unknown' },
      ),
    ],
  },
]

const defaultVariants: PlanVariant[] = [
  {
    id: 'pace',
    name: '方案 A · 适中节奏',
    pace: '适中 · Mock',
    coverage: '8 个合成点位 · Mock',
    commute: '约 128 分钟 · 估算',
    budget: '¥ 680–980 · 估算 / Mock',
    note: '在覆盖与休息之间取平衡，所有金额和时长待核验。',
  },
  {
    id: 'slow',
    name: '方案 B · 松弛体验',
    pace: '宽松 · Mock',
    coverage: '6 个合成点位 · Mock',
    commute: '约 92 分钟 · 估算',
    budget: '¥ 560–820 · 估算 / Mock',
    note: '减少切换，预留天气或体力变化的调整空间。',
  },
  {
    id: 'dense',
    name: '方案 C · 紧凑探索',
    pace: '紧凑 · Mock',
    coverage: '10 个合成点位 · Mock',
    commute: '约 176 分钟 · 估算',
    budget: '¥ 760–1,120 · 估算 / Mock',
    note: '覆盖更高但转场更多，仅用于演示方案差异。',
  },
]

const defaultMapPoints: MapPoint[] = defaultDays.flatMap((day) =>
  day.items.map((timelineItem) => ({
    id: timelineItem.poiId,
    name: timelineItem.poiName,
    day: day.dayLabel,
    position: '示意点位 · 无坐标',
  })),
)

const defaultCommunity: CommunitySample[] = [
  {
    id: 'sample-01',
    title: '把三天拆成几个可调整的片段',
    author: '合成用户 A',
    tags: ['摄影', '慢节奏'],
    likes: '18 次点赞 · Mock / 静态',
    comments: '4 条评论 · Mock / 静态',
    note: '静态样张，不支持点赞、评论或复用。',
  },
  {
    id: 'sample-02',
    title: '下雨时保留一个室内备选',
    author: '合成用户 B',
    tags: ['备选', '室内'],
    likes: '12 次点赞 · Mock / 静态',
    comments: '2 条评论 · Mock / 静态',
    note: '静态样张，不代表真实社区内容。',
  },
  {
    id: 'sample-03',
    title: '用合成点位练习方案对比',
    author: '合成用户 C',
    tags: ['方案', '演示'],
    likes: '9 次点赞 · Mock / 静态',
    comments: '1 条评论 · Mock / 静态',
    note: '静态样张，不支持操作或数据回写。',
  },
]

const defaultSourceMetadata: S02SourceMetadata[] = [
  {
    source_type: 'system',
    source_record_id: 'synthetic-s07-fixture',
    retrieved_at: mockRetrievedAt,
    valid_until: mockValidUntil,
    freshness_status: 'unknown',
    is_estimated: true,
  },
]

function s02EnvelopeFor(status: DemoStatus): S02OrchestratorEnvelope {
  if (status === 'error') return s02Fixtures.orchestrator.failed
  if (status === 'partial') return s02Fixtures.orchestrator.partial
  if (status === 'empty') {
    return {
      ...s02Fixtures.orchestrator.ok,
      request_id: 'req-s07-orchestrator-empty',
      plan: { days: [] },
      source_metadata: defaultSourceMetadata,
    }
  }
  return {
    ...s02Fixtures.orchestrator.ok,
    source_metadata: defaultSourceMetadata,
  }
}

export function createDemoViewModel(status: DemoStatus = 'success'): DemoViewModel {
  const isDataState = status === 'success' || status === 'partial'
  const isPartial = status === 'partial'
  return {
    status,
    title:
      status === 'error'
        ? '演示任务暂时失败'
        : status === 'empty'
          ? '还没有可展示的演示行程'
          : status === 'loading'
            ? '正在生成演示行程'
            : isPartial
              ? '演示行程已部分完成'
              : '北京三日合成演示行程',
    description:
      status === 'error'
        ? '这是可恢复的本地演示错误，不代表真实接口状态。'
        : status === 'empty'
          ? '提交一组输入，或从 Mock 状态选择器切回成功态。'
          : status === 'loading'
            ? '本地模拟编排正在按阶段推进，不会发起网络请求。'
            : isPartial
              ? '部分区块可展示，其余字段保持缺失并明确标记。'
              : '所有景点、时长、天气、费用和政策均为合成示例。',
    days: isDataState ? defaultDays : [],
    variants: isDataState ? defaultVariants : [],
    mapPoints: isDataState ? defaultMapPoints : [],
    community: defaultCommunity,
    s02Envelope: s02EnvelopeFor(status),
  }
}

export const defaultPlanningInput: PlanningInput = {
  city: '北京',
  startDate: '2026-10-10',
  endDate: '2026-10-12',
  mustVisit: ['合成·北城晨光公园'],
  preference: '摄影优先，节奏适中，保留一个天气备选。',
  transport: '混合',
  planCount: 3,
}

export const mockStatusOptions: Array<{ value: DemoStatus; label: string }> = [
  { value: 'success', label: '成功 · Mock' },
  { value: 'loading', label: '加载中 · Mock' },
  { value: 'empty', label: '空状态 · Mock' },
  { value: 'partial', label: '部分完成 · Mock' },
  { value: 'error', label: '错误 · Mock' },
]

export function toS02Status(status: DemoStatus): S02AgentStatus {
  if (status === 'error') return 'failed'
  if (status === 'partial') return 'partial'
  return 'ok'
}
