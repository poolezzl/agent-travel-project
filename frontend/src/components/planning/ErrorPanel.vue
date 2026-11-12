<script setup lang="ts">
import type { ErrorInfo } from '@/api/http'

defineProps<{
  title: string
  error: ErrorInfo | null
}>()
</script>

<template>
  <section
    v-if="error"
    class="error-panel"
    data-testid="error-panel"
    role="alert"
    aria-label="错误详情"
  >
    <h2>{{ title }}</h2>
    <dl class="error-panel__grid">
      <div>
        <dt>错误码</dt>
        <dd>{{ error.code }}</dd>
      </div>
      <div v-if="error.status !== undefined">
        <dt>HTTP 状态</dt>
        <dd>{{ error.status }}</dd>
      </div>
      <div v-if="error.traceId">
        <dt>traceId</dt>
        <dd>{{ error.traceId }}</dd>
      </div>
    </dl>
    <p class="error-panel__message">{{ error.message }}</p>
  </section>
</template>

<style scoped>
.error-panel {
  display: grid;
  min-width: 0;
  gap: 12px;
  padding: clamp(16px, 3vw, 24px);
  border: 1px solid #c2413b;
  border-radius: var(--radius-lg);
  background: #fdeceb;
}

.error-panel h2 {
  margin: 0;
  color: #7f1d1d;
  font-size: 18px;
}

.error-panel__grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0;
}

.error-panel__grid div {
  padding: 8px 12px;
  border: 1px solid #e2b7b3;
  border-radius: var(--radius-md);
  background: #ffffff;
}

.error-panel__grid dt {
  color: var(--ink-muted);
  font-size: 12px;
}

.error-panel__grid dd {
  margin: 4px 0 0;
  color: var(--ink);
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 13px;
  overflow-wrap: anywhere;
}

.error-panel__message {
  margin: 0;
  color: #7f1d1d;
  font-size: 14px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}
</style>
