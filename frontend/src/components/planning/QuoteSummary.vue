<script setup lang="ts">
import { computed } from 'vue'

import type { QuoteResponse } from '@/api/types'

const props = defineProps<{
  quote: QuoteResponse
  creating?: boolean
}>()

const emit = defineEmits<{
  create: []
}>()

function formatEpoch(epochSecond: number): string {
  const date = new Date(epochSecond * 1000)
  if (Number.isNaN(date.getTime())) return '未知'
  return date.toLocaleString()
}

const expiresLabel = computed(() => formatEpoch(props.quote.expiresAtEpochSecond))
</script>

<template>
  <section class="quote-summary" data-testid="quote-summary" aria-labelledby="quote-heading">
    <h2 id="quote-heading">服务端报价</h2>
    <p>以下价格为服务端按方案数量计算，前端不自行计价。</p>

    <dl class="quote-summary__grid">
      <div>
        <dt>方案数量</dt>
        <dd>{{ quote.planCount }} 套</dd>
      </div>
      <div>
        <dt>所需积分</dt>
        <dd data-testid="quote-points">{{ quote.points }} 积分</dd>
      </div>
      <div>
        <dt>报价有效期至</dt>
        <dd>{{ expiresLabel }}</dd>
      </div>
      <div>
        <dt>报价引用</dt>
        <dd>{{ quote.quoteReference }}</dd>
      </div>
    </dl>

    <div class="quote-summary__actions">
      <button
        class="button button--primary"
        type="button"
        data-testid="create-task-button"
        :disabled="creating"
        @click="emit('create')"
      >
        {{ creating ? '正在创建任务并预扣…' : '创建任务并预扣积分' }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.quote-summary {
  display: grid;
  min-width: 0;
  gap: 14px;
  padding: clamp(16px, 3vw, 24px);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
}

.quote-summary h2 {
  margin: 0;
  color: var(--ink);
  font-size: 20px;
}

.quote-summary > p {
  margin: 0;
  color: var(--ink-secondary);
  font-size: 14px;
  line-height: 1.6;
}

.quote-summary__grid {
  display: grid;
  gap: 10px;
  margin: 0;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
}

.quote-summary__grid div {
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--surface-soft);
}

.quote-summary__grid dt {
  color: var(--ink-muted);
  font-size: 12px;
}

.quote-summary__grid dd {
  margin: 4px 0 0;
  color: var(--ink);
  font-weight: 650;
  overflow-wrap: anywhere;
}

.quote-summary__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.button {
  min-height: 42px;
  padding: 9px 16px;
  border: 1px solid var(--line-strong);
  border-radius: 999px;
  color: var(--ink);
  background: var(--surface-soft);
  font-weight: 650;
  cursor: pointer;
}

.button--primary {
  border-color: var(--accent);
  color: #ffffff;
  background: var(--accent);
}

.button:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.button:focus-visible {
  outline: 3px solid var(--focus);
  outline-offset: 2px;
}
</style>
