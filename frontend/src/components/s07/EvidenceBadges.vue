<script setup lang="ts">
import type { Evidence } from '@/fixtures/s07'

const props = defineProps<{
  evidence: Evidence
}>()

const freshnessLabel: Record<Evidence['freshness'], string> = {
  fresh: 'fresh · Mock',
  stale: 'stale · 待核验',
  unknown: 'unknown · 待核验',
}
</script>

<template>
  <div class="evidence" :data-freshness="props.evidence.freshness">
    <span class="evidence__flag">{{ props.evidence.label }}</span>
    <span>来源：{{ props.evidence.sourceType }} / {{ props.evidence.sourceId }}</span>
    <span>取得时间：{{ props.evidence.retrievedAt }}</span>
    <span>{{ freshnessLabel[props.evidence.freshness] }}</span>
    <span v-if="props.evidence.isEstimated">估算值</span>
  </div>
</template>

<style scoped>
.evidence {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
  color: var(--ink-muted);
  font-size: 12px;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.evidence span {
  padding: 3px 7px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: var(--surface-soft);
}

.evidence .evidence__flag {
  border-color: #d97706;
  color: #713b00;
  background: #fff4cf;
  font-weight: 700;
}

.evidence[data-freshness='stale'] span:nth-last-child(2),
.evidence[data-freshness='unknown'] span:nth-last-child(2) {
  border-style: dashed;
}
</style>
