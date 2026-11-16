<script setup lang="ts">
import type { SourceMetadata } from '@/api/types'

defineProps<{
  sources: SourceMetadata[]
}>()

function freshnessLabel(status: string): string {
  if (status === 'fresh') return '新鲜'
  if (status === 'stale') return '过期'
  return '未知'
}

function validUntilLabel(value: string | null): string {
  if (!value) return '有效期未声明'
  return `有效期至 ${value}`
}

function timeLabel(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString()
}
</script>

<template>
  <section class="source-badges" data-testid="source-badges" aria-labelledby="source-heading">
    <h2 id="source-heading">事实来源与时效</h2>
    <p>
      事实数据应展示「来源 + 更新时间 + 预报/统计/估算/未知」；来源随后端已发布行程版本一并返回。
    </p>

    <p v-if="sources.length === 0" class="source-badges__empty" data-testid="source-empty">
      当前行程版本没有可展示的来源记录；未收录的事实不会用默认值补齐。
    </p>
    <ul v-else class="source-badges__list">
      <li v-for="source in sources" :key="source.source_record_id">
        <span class="source-badges__type">{{ source.source_type }}</span>
        <span class="source-badges__id">{{ source.source_record_id }}</span>
        <span class="source-badges__badge">更新时间 {{ timeLabel(source.retrieved_at) }}</span>
        <span class="source-badges__badge">{{ validUntilLabel(source.valid_until) }}</span>
        <span class="source-badges__badge"
          >时效：{{ freshnessLabel(source.freshness_status) }}</span
        >
        <span class="source-badges__badge" :class="{ 'is-estimated': source.is_estimated }">
          {{ source.is_estimated ? '估算' : '非估算' }}
        </span>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.source-badges {
  display: grid;
  min-width: 0;
  gap: 12px;
  padding: clamp(16px, 3vw, 24px);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
}

.source-badges h2 {
  margin: 0;
  color: var(--ink);
  font-size: 20px;
}

.source-badges > p {
  margin: 0;
  color: var(--ink-secondary);
  font-size: 14px;
  line-height: 1.6;
}

.source-badges__empty {
  padding: 10px 12px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius-md);
  color: var(--ink-muted);
  font-size: 13px;
}

.source-badges__list {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.source-badges__list li {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--surface-soft);
  font-size: 13px;
}

.source-badges__type {
  color: var(--accent);
  font-weight: 700;
}

.source-badges__id {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  color: var(--ink-secondary);
}

.source-badges__badge {
  padding: 2px 8px;
  border-radius: 999px;
  color: var(--ink-secondary);
  background: var(--surface);
}

.source-badges__badge.is-estimated {
  color: #713b00;
  background: #fff4cf;
}
</style>
