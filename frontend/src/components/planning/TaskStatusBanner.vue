<script setup lang="ts">
import { computed } from 'vue'

import { canCancelStatus, isTerminalStatus, taskStatusLabel } from '@/api/taskStatus'

const props = defineProps<{
  status: string | null
  reservedPoints: number | null
  cancelling?: boolean
  creating?: boolean
}>()

const emit = defineEmits<{
  cancel: []
  retry: []
}>()

const label = computed(() => (props.status ? taskStatusLabel(props.status) : '未知状态'))
const terminal = computed(() => props.status !== null && isTerminalStatus(props.status))
const cancellable = computed(() => props.status !== null && canCancelStatus(props.status))
const failed = computed(() => props.status === 'FAILED')
const awaiting = computed(() => props.status === 'AWAITING_CONFIRMATION')
</script>

<template>
  <section
    class="task-status-banner"
    :data-status="status ?? 'UNKNOWN'"
    data-testid="task-status-banner"
    aria-labelledby="status-heading"
  >
    <h2 id="status-heading">任务状态</h2>
    <div class="task-status-banner__row">
      <span class="task-status-banner__pill" :class="{ 'is-terminal': terminal }">
        {{ label }}
        <code>{{ status ?? 'UNKNOWN' }}</code>
      </span>
      <span
        v-if="reservedPoints !== null && status !== 'REFUNDED'"
        class="task-status-banner__points"
      >
        已预扣积分：<strong>{{ reservedPoints }}</strong>
      </span>
    </div>

    <p v-if="awaiting" class="task-status-banner__note">
      任务等待确认；确认/拒绝操作属于后续步骤（S21），本步骤仅展示该状态。
    </p>
    <p v-else-if="status === 'REFUNDED'" class="task-status-banner__note">
      本次预扣积分已退回。<RouterLink to="/wallet">查看钱包流水</RouterLink>
    </p>
    <p v-else-if="terminal" class="task-status-banner__note">
      任务已进入终态，SSE 流已关闭，不再自动重连。
    </p>

    <div v-if="cancellable || failed" class="task-status-banner__actions">
      <button
        v-if="cancellable"
        class="button"
        type="button"
        data-testid="cancel-task-button"
        :disabled="cancelling"
        @click="emit('cancel')"
      >
        {{ cancelling ? '正在取消…' : '取消任务' }}
      </button>
      <button
        v-if="failed"
        class="button button--primary"
        type="button"
        data-testid="retry-task-button"
        :disabled="creating"
        @click="emit('retry')"
      >
        {{ creating ? '正在重新创建…' : '重试' }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.task-status-banner {
  display: grid;
  min-width: 0;
  gap: 12px;
  padding: clamp(16px, 3vw, 24px);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
}

.task-status-banner h2 {
  margin: 0;
  color: var(--ink);
  font-size: 20px;
}

.task-status-banner__row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.task-status-banner__pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-radius: 999px;
  color: #713b00;
  background: #fff4cf;
  font-weight: 650;
}

.task-status-banner__pill.is-terminal {
  color: #0f5132;
  background: #d6f3e6;
}

.task-status-banner__pill code {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 12px;
  font-weight: 400;
}

.task-status-banner__points {
  color: var(--ink-secondary);
  font-size: 14px;
}

.task-status-banner__note {
  margin: 0;
  color: var(--ink-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.task-status-banner__actions {
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
