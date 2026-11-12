<script setup lang="ts">
defineProps<{
  degradedFields: string[]
}>()
</script>

<template>
  <section class="plan-result" data-testid="plan-result" aria-labelledby="result-heading">
    <h2 id="result-heading">行程结果</h2>

    <div class="plan-result__empty" role="status" data-testid="result-empty">
      <strong>当前后端未提供行程结果接口（PENDING-S14-01）</strong>
      <p>
        后端仅提供 GET /v1/tasks/{task_id} 返回 TaskView（状态、state_version、reserved_points
        等），没有 plan / trip_version / agent_result 的读取端点，因此本页不渲染任何行程内容，
        也不以 S07 fixture 冒充真实结果。
      </p>
    </div>

    <div
      v-if="degradedFields.length > 0"
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
  gap: 12px;
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

.plan-result__degraded ul {
  margin: 0;
  padding-left: 20px;
}
</style>
