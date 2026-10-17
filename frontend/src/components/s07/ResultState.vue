<script setup lang="ts">
import type { DemoStatus } from '@/fixtures/s07'

const props = withDefaults(
  defineProps<{
    status: DemoStatus
    title: string
    description: string
    code?: string
  }>(),
  { code: '' },
)

const iconByStatus: Record<DemoStatus, string> = {
  success: '✓',
  loading: '…',
  empty: '○',
  partial: '!',
  error: '×',
}
</script>

<template>
  <section
    class="result-state"
    :class="`result-state--${props.status}`"
    :aria-live="props.status === 'loading' ? 'polite' : 'assertive'"
    :aria-busy="props.status === 'loading'"
    data-testid="result-state"
  >
    <span class="result-state__icon" aria-hidden="true">{{ iconByStatus[props.status] }}</span>
    <div>
      <p class="eyebrow">{{ props.status.toUpperCase() }} · Mock</p>
      <h2>{{ props.title }}</h2>
      <p>{{ props.description }}</p>
      <p v-if="props.code" class="result-state__code">演示错误码：{{ props.code }}</p>
    </div>
  </section>
</template>

<style scoped>
.result-state {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 16px;
  align-items: start;
  min-width: 0;
  padding: clamp(20px, 4vw, 32px);
  border: 1px solid var(--line);
  border-left: 5px solid var(--status-color, var(--accent));
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
}

.result-state--loading {
  --status-color: #2563eb;
}

.result-state--empty {
  --status-color: #64748b;
}

.result-state--partial {
  --status-color: #d97706;
}

.result-state--error {
  --status-color: #c2413b;
}

.result-state__icon {
  display: grid;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  color: #ffffff;
  background: var(--status-color, var(--accent));
  font-size: 22px;
  font-weight: 800;
  place-items: center;
}

.eyebrow {
  margin: 0 0 6px;
  color: var(--status-color, var(--accent));
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.1em;
}

h2 {
  margin: 0;
  color: var(--ink);
  font-size: clamp(22px, 4vw, 30px);
  overflow-wrap: anywhere;
}

p {
  margin: 8px 0 0;
  color: var(--ink-secondary);
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.result-state__code {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 13px;
}
</style>
