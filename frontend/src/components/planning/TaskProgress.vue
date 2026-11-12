<script setup lang="ts">
import { computed } from 'vue'

import { taskStatusLabel } from '@/api/taskStatus'
import type { TaskEvent } from '@/api/types'

const props = defineProps<{
  events: TaskEvent[]
  activeStatus: string | null
  connected: boolean
}>()

interface Step {
  key: string
  label: string
}

const STEPS: Step[] = [
  { key: 'PARSING', label: '解析需求' },
  { key: 'COLLECTING', label: '采集来源' },
  { key: 'SCHEDULING', label: '日程编排' },
  { key: 'ROUTING', label: '路线规划' },
  { key: 'ASSEMBLING', label: '整合方案' },
]

function stepIndex(status: string): number {
  const index = STEPS.findIndex((step) => step.key === status)
  return index
}

const activeIndex = computed<number>(() => {
  if (!props.activeStatus) return -1
  return stepIndex(props.activeStatus)
})

function localTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleTimeString()
}
</script>

<template>
  <section class="task-progress" data-testid="task-progress" aria-labelledby="progress-heading">
    <h2 id="progress-heading">进度</h2>

    <div class="task-progress__stream-state" data-testid="stream-state">
      <span class="task-progress__dot" :class="{ 'is-live': connected }" aria-hidden="true" />
      {{ connected ? 'SSE 订阅中' : 'SSE 已断开' }}
    </div>

    <ol class="task-progress__steps">
      <li
        v-for="(step, index) in STEPS"
        :key="step.key"
        :class="{
          'is-active': index === activeIndex,
          'is-done': activeIndex >= 0 && index < activeIndex,
        }"
      >
        {{ step.label }}
      </li>
    </ol>

    <div v-if="events.length === 0" class="task-progress__empty">
      尚未收到进度事件；连接建立后按 sequence_no 顺序追加。
    </div>
    <ol v-else class="task-progress__timeline">
      <li v-for="event in [...events].reverse()" :key="event.sequence_no">
        <span class="task-progress__seq">#{{ event.sequence_no }}</span>
        <span class="task-progress__time">{{ localTime(event.created_at) }}</span>
        <span class="task-progress__status">{{ taskStatusLabel(event.status) }}</span>
        <span v-if="event.step_name" class="task-progress__step">{{ event.step_name }}</span>
        <span
          v-if="event.attempt_no !== null && event.attempt_no !== undefined"
          class="task-progress__attempt"
        >
          第 {{ event.attempt_no }} 次
        </span>
        <span v-if="event.message" class="task-progress__message">{{ event.message }}</span>
        <span
          v-if="event.degraded_fields && event.degraded_fields.length > 0"
          class="task-progress__degraded"
        >
          降级：{{ event.degraded_fields.join('、') }}
        </span>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.task-progress {
  display: grid;
  min-width: 0;
  gap: 12px;
  padding: clamp(16px, 3vw, 24px);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
}

.task-progress h2 {
  margin: 0;
  color: var(--ink);
  font-size: 20px;
}

.task-progress__stream-state {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--ink-secondary);
  font-size: 13px;
}

.task-progress__dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--ink-muted);
}

.task-progress__dot.is-live {
  background: #16a34a;
  box-shadow: 0 0 0 4px rgb(22 163 74 / 15%);
}

.task-progress__steps {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.task-progress__steps li {
  padding: 8px 14px;
  border: 1px solid var(--line-strong);
  border-radius: 999px;
  color: var(--ink-muted);
  background: var(--surface-soft);
  font-size: 13px;
}

.task-progress__steps li.is-active {
  border-color: var(--accent);
  color: #ffffff;
  background: var(--accent);
}

.task-progress__steps li.is-done {
  color: #0f5132;
  background: #d6f3e6;
}

.task-progress__empty {
  padding: 10px 12px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius-md);
  color: var(--ink-muted);
  font-size: 13px;
}

.task-progress__timeline {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.task-progress__timeline li {
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

.task-progress__seq {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  color: var(--accent);
  font-weight: 700;
}

.task-progress__time {
  color: var(--ink-muted);
}

.task-progress__status {
  color: var(--ink);
  font-weight: 650;
}

.task-progress__step,
.task-progress__attempt {
  color: var(--ink-secondary);
}

.task-progress__message {
  width: 100%;
  color: var(--ink-secondary);
  overflow-wrap: anywhere;
}

.task-progress__degraded {
  width: 100%;
  color: #713b00;
  font-weight: 650;
}
</style>
