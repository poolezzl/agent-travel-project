<script setup lang="ts">
import type { PlanVariant } from '@/fixtures/s07'

const props = defineProps<{
  variants: PlanVariant[]
}>()
</script>

<template>
  <section class="plan-compare" data-testid="plan-comparison" aria-labelledby="compare-heading">
    <header class="plan-compare__head">
      <h2 id="compare-heading">方案对比（演示）</h2>
      <p>节奏、覆盖点数、通勤时长与预算区间均为合成估算，用于演示对比方式，不代表真实报价。</p>
    </header>

    <p v-if="props.variants.length === 0" class="plan-compare__empty">
      暂无方案可比：这是空态，不用假数字填充。
    </p>

    <div v-else class="plan-compare__scroll">
      <table>
        <caption>
          合成方案对比表（Mock / 估算）
        </caption>
        <thead>
          <tr>
            <th scope="col">方案</th>
            <th scope="col">节奏</th>
            <th scope="col">覆盖</th>
            <th scope="col">通勤</th>
            <th scope="col">预算</th>
            <th scope="col">说明</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="variant in props.variants" :key="variant.id">
            <th scope="row">{{ variant.name }}</th>
            <td>{{ variant.pace }}</td>
            <td>{{ variant.coverage }}</td>
            <td>{{ variant.commute }}</td>
            <td>{{ variant.budget }}</td>
            <td>{{ variant.note }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.plan-compare {
  display: grid;
  min-width: 0;
  gap: 14px;
  padding: clamp(16px, 3vw, 24px);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
}

.plan-compare__head h2 {
  margin: 0;
  color: var(--ink);
  font-size: 20px;
}

.plan-compare__head p {
  margin: 6px 0 0;
  color: var(--ink-secondary);
  font-size: 14px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.plan-compare__empty {
  margin: 0;
  padding: 12px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius-md);
  color: var(--ink-secondary);
  background: var(--surface-soft);
}

.plan-compare__scroll {
  min-width: 0;
  overflow-x: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

caption {
  padding-bottom: 8px;
  color: var(--ink-muted);
  font-size: 13px;
  text-align: left;
}

th,
td {
  padding: 10px 12px;
  border: 1px solid var(--line);
  text-align: left;
  vertical-align: top;
  overflow-wrap: anywhere;
}

thead th {
  color: var(--ink);
  background: var(--surface-soft);
}

tbody th {
  color: var(--ink);
  background: var(--surface-soft);
}
</style>
