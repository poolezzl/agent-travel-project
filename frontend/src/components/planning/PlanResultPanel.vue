<script setup lang="ts">
import type { ErrorInfo } from '@/api/http'
import type { PlanDay, PlanMeal, PlanSegment, PlanView } from '@/api/types'

const props = defineProps<{
  plan: PlanView | null
  planError: ErrorInfo | null
  planLoading?: boolean
  degradedFields: string[]
}>()

function emptyTitle(): string {
  if (props.planLoading) return '正在读取已发布行程版本'
  if (!props.planError) return '行程结果等待生成'
  if (props.planError.code === 'CONFIRMATION_REQUIRED') return '方案存在待确认冲突'
  if (props.planError.code === 'PLAN_NOT_READY') return '行程版本尚未生成'
  return '行程结果读取失败'
}

function emptyMessage(): string {
  if (!props.planError) {
    return '任务仍在执行或尚未返回终态；结果只读取后端已发布版本，不使用前端 fixture。'
  }
  return props.planError.message
}

function money(value?: { currency?: string | null; amount_minor?: number | null } | null): string {
  if (
    !value ||
    !value.currency ||
    value.amount_minor === null ||
    value.amount_minor === undefined
  ) {
    return '未知'
  }
  return `${value.currency} ${(value.amount_minor / 100).toFixed(2)}`
}

function segmentBadges(segment: PlanSegment): string[] {
  const badges: string[] = []
  if (segment.is_must_visit) badges.push('必去')
  if (segment.duration_source === 'assumed') badges.push('时长未知（按默认值安排）')
  if (segment.is_estimated) badges.push('估算')
  return badges
}

function transportText(segment: PlanSegment): string {
  const leg = segment.transport_to_next
  if (!leg) return ''
  const estimate = leg.is_estimated ? '（直线估算，仅展示）' : ''
  return `↕ ${leg.mode} ${Math.round(leg.distance_m)} 米 / ${Math.round(leg.duration_min)} 分钟${estimate}`
}

function mealText(meal: PlanMeal): string {
  const label = meal.meal === 'lunch' ? '午餐' : '晚餐'
  if (!meal.candidates.length) return `${label}：暂无合法候选`
  return `${label}：${meal.candidates
    .slice(0, 2)
    .map((item) => item.name)
    .join(' / ')}`
}

function dayWeather(day: PlanDay): string {
  const weather = day.weather
  if (!weather || weather.status === 'unknown') return '天气暂不可用'
  const range =
    weather.temp_min === null || weather.temp_min === undefined
      ? ''
      : `${Math.round(weather.temp_min)}~${Math.round(weather.temp_max ?? weather.temp_min)}℃`
  const stale = weather.stale ? '（已过期）' : ''
  return `${weather.condition ?? ''} ${range}${stale}`
}
</script>

<template>
  <section class="plan-result" data-testid="plan-result" aria-labelledby="result-heading">
    <h2 id="result-heading">行程结果</h2>

    <div v-if="plan" class="plan-result__body" data-testid="plan-ready">
      <p v-if="plan.plan.notice" class="plan-result__notice" data-testid="plan-notice">
        {{ plan.plan.notice }}
      </p>

      <dl class="plan-result__summary">
        <div>
          <dt>城市 / 日期</dt>
          <dd>
            {{ plan.plan.trip.city_name }} · {{ plan.plan.trip.start_date }} ~
            {{ plan.plan.trip.end_date }}
          </dd>
        </div>
        <div>
          <dt>版本</dt>
          <dd>v{{ plan.version_no }} · {{ plan.etag.slice(0, 12) }}</dd>
        </div>
        <div>
          <dt>点位 / 交通</dt>
          <dd>
            {{ plan.plan.summary.point_count }} 个 ·
            {{ Math.round(plan.plan.summary.total_transport_min) }} 分钟
          </dd>
        </div>
        <div>
          <dt>已知门票</dt>
          <dd>{{ money(plan.plan.summary.estimated_cost) }}</dd>
        </div>
        <div>
          <dt>来源完整率</dt>
          <dd>{{ Math.round((plan.plan.summary.source_completeness ?? 0) * 100) }}%</dd>
        </div>
      </dl>

      <div v-if="plan.plan.sample_only" class="plan-result__sample" data-testid="plan-sample">
        示例数据：本方案基于合成样例，不代表真实票价、开放时间或位置。
      </div>

      <article v-for="day in plan.plan.days" :key="day.date" class="plan-result__day">
        <header>
          <h3>{{ day.date }} · {{ day.weekday }}</h3>
          <span>{{ dayWeather(day) }}</span>
        </header>

        <ol v-if="day.segments.length" class="plan-result__segments">
          <li v-for="segment in day.segments" :key="segment.point_id">
            <div class="plan-result__time">{{ segment.start_time }}–{{ segment.end_time }}</div>
            <div class="plan-result__point">
              <strong>{{ segment.name }}</strong>
              <span v-for="badge in segmentBadges(segment)" :key="badge" class="plan-result__badge">
                {{ badge }}
              </span>
              <p v-if="segment.facts.length" class="plan-result__fact">
                {{ segment.facts[0]?.value }}
              </p>
              <p v-if="segment.transport_to_next" class="plan-result__transport">
                {{ transportText(segment) }}
              </p>
            </div>
          </li>
        </ol>
        <p v-else class="plan-result__empty-day">当日没有已发布景点可安排。</p>

        <p v-for="meal in day.meals" :key="meal.meal" class="plan-result__meal">
          {{ mealText(meal) }}
        </p>
        <p v-if="day.reservations.length" class="plan-result__reservation">
          预约：
          <template v-for="item in day.reservations" :key="item.policy_id">
            {{ item.ticket_type ?? item.policy_id }}（{{ money(item)
            }}<template v-if="item.stale">，规则已过期</template>）·
          </template>
        </p>
      </article>

      <div
        v-if="plan.plan.conflicts.length"
        class="plan-result__conflicts"
        data-testid="plan-conflicts"
      >
        <strong>待处理冲突（确认前不会创建版本）：</strong>
        <ul>
          <li v-for="conflict in plan.plan.conflicts" :key="conflict.code + conflict.message">
            {{ conflict.message }}
          </li>
        </ul>
      </div>

      <div
        v-if="plan.degraded_fields.length"
        class="plan-result__degraded"
        data-testid="result-degraded"
      >
        <strong>降级字段（显式展示，不隐藏）：</strong>
        <ul>
          <li v-for="field in plan.degraded_fields" :key="field">{{ field }}</li>
        </ul>
      </div>

      <p class="plan-result__quality">
        质量检查：{{ plan.plan.quality.status }} · {{ plan.plan.quality.checks.length }} 项
      </p>
    </div>

    <div v-else class="plan-result__empty" role="status" data-testid="result-empty">
      <strong>{{ emptyTitle() }}</strong>
      <p>{{ emptyMessage() }}</p>
      <p v-if="planError?.traceId" class="plan-result__trace">traceId：{{ planError.traceId }}</p>
      <p class="plan-result__hint">
        结果来自后端已发布版本（GET /api/v1/tasks/{task_id}/plan），不渲染 S07 fixture。
      </p>
    </div>

    <div
      v-if="!plan && degradedFields.length > 0"
      class="plan-result__degraded"
      data-testid="result-degraded"
    >
      <strong>降级字段（显式展示，不隐藏）：</strong>
      <ul>
        <li v-for="field in degradedFields" :key="field">{{ field }}</li>
      </ul>
    </div>
  </section>
</template>

<style scoped>
.plan-result {
  display: grid;
  min-width: 0;
  gap: 14px;
  padding: clamp(16px, 3vw, 24px);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
}

.plan-result h2 {
  margin: 0;
  color: var(--ink);
  font-size: 20px;
}

.plan-result__body {
  display: grid;
  gap: 14px;
}

.plan-result__notice,
.plan-result__sample {
  margin: 0;
  padding: 10px 12px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius-md);
  color: var(--ink-secondary);
  background: var(--surface-soft);
  font-size: 13px;
  line-height: 1.6;
}

.plan-result__summary {
  display: grid;
  gap: 10px;
  margin: 0;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
}

.plan-result__summary div {
  min-width: 0;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  background: var(--surface-soft);
}

.plan-result__summary dt {
  color: var(--ink-muted);
  font-size: 12px;
}

.plan-result__summary dd {
  margin: 4px 0 0;
  color: var(--ink);
  font-weight: 650;
  overflow-wrap: anywhere;
}

.plan-result__day {
  display: grid;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
}

.plan-result__day header {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  align-items: baseline;
  justify-content: space-between;
}

.plan-result__day h3 {
  margin: 0;
  color: var(--ink);
  font-size: 16px;
}

.plan-result__day header span {
  color: var(--ink-secondary);
  font-size: 13px;
}

.plan-result__segments {
  display: grid;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.plan-result__segments li {
  display: grid;
  gap: 10px;
  grid-template-columns: 96px minmax(0, 1fr);
}

.plan-result__time {
  color: var(--ink-secondary);
  font-variant-numeric: tabular-nums;
  font-size: 13px;
}

.plan-result__point strong {
  color: var(--ink);
  overflow-wrap: anywhere;
}

.plan-result__badge {
  margin-left: 6px;
  padding: 1px 7px;
  border-radius: 999px;
  color: #713b00;
  background: #fff4cf;
  font-size: 11px;
}

.plan-result__fact,
.plan-result__transport,
.plan-result__meal,
.plan-result__reservation,
.plan-result__empty-day {
  margin: 4px 0 0;
  color: var(--ink-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.plan-result__conflicts {
  display: grid;
  gap: 8px;
  padding: 12px 14px;
  border: 1px solid #d97706;
  border-radius: var(--radius-md);
  color: #713b00;
  background: #fff4cf;
  font-size: 14px;
}

.plan-result__conflicts ul,
.plan-result__degraded ul {
  margin: 0;
  padding-left: 20px;
}

.plan-result__degraded {
  display: grid;
  gap: 8px;
  padding: 12px 14px;
  border: 1px solid #d97706;
  border-radius: var(--radius-md);
  color: #713b00;
  background: #fff4cf;
  font-size: 14px;
}

.plan-result__quality {
  margin: 0;
  color: var(--ink-secondary);
  font-size: 13px;
}

.plan-result__empty {
  display: grid;
  gap: 8px;
  padding: 14px 16px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius-md);
  color: var(--ink-secondary);
}

.plan-result__empty strong {
  color: var(--ink);
}

.plan-result__empty p {
  margin: 0;
  font-size: 14px;
  line-height: 1.65;
}

.plan-result__hint,
.plan-result__trace {
  color: var(--ink-muted);
  font-size: 12px;
}
</style>
