<script setup lang="ts">
import type { MockStage, MockStageStatus } from './mockProgress'

const props = withDefaults(
  defineProps<{
    stages: MockStage[]
    active?: boolean
    summary?: string
  }>(),
  { active: false, summary: '' },
)

const statusLabel: Record<MockStageStatus, string> = {
  pending: '待执行',
  running: '模拟中',
  done: '已完成',
  failed: '失败',
  skipped: '未执行',
}

const doneCount = (): number => props.stages.filter((stage) => stage.status === 'done').length
</script>

<template>
  <section
    class="mock-progress"
    data-testid="mock-progress"
    :aria-busy="props.active"
    aria-live="polite"
  >
    <header class="mock-progress__head">
      <h2>模拟进度（本地，无真实接口）</h2>
      <p>
        进度只由页面本地状态驱动，用于演示排队/解析/采集/路线/整合/完成的呈现方式，不代表真实任务状态。
      </p>
    </header>

    <p class="mock-progress__summary">
      {{ props.summary || `已完成 ${doneCount()} / ${props.stages.length} 个模拟阶段` }}
    </p>

    <ol class="mock-progress__list">
      <li
        v-for="stage in props.stages"
        :key="stage.id"
        class="mock-progress__item"
        :data-status="stage.status"
      >
        <span class="mock-progress__marker" aria-hidden="true">
          {{ stage.status === 'done' ? '✓' : stage.status === 'failed' ? '×' : '·' }}
        </span>
        <div>
          <p class="mock-progress__label">
            {{ stage.label }}
            <span class="mock-progress__status">{{ statusLabel[stage.status] }}</span>
          </p>
          <p class="mock-progress__detail">{{ stage.detail }}</p>
        </div>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.mock-progress {
  min-width: 0;
  padding: clamp(16px, 3vw, 24px);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
}

.mock-progress__head h2 {
  margin: 0;
  color: var(--ink);
  font-size: 20px;
}

.mock-progress__head p {
  margin: 6px 0 0;
  color: var(--ink-secondary);
  font-size: 14px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.mock-progress__summary {
  margin: 12px 0;
  color: var(--ink-muted);
  font-size: 13px;
}

.mock-progress__list {
  display: grid;
  margin: 0;
  padding: 0;
  gap: 10px;
  list-style: none;
}

.mock-progress__item {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 10px;
  align-items: start;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-left: 4px solid var(--line-strong);
  border-radius: var(--radius-md);
  background: var(--surface-soft);
}

.mock-progress__item[data-status='running'] {
  border-left-color: #2563eb;
  background: #eef4ff;
}

.mock-progress__item[data-status='done'] {
  border-left-color: #0f766e;
}

.mock-progress__item[data-status='failed'] {
  border-left-color: #c2413b;
  background: #fdeceb;
}

.mock-progress__item[data-status='skipped'] {
  border-left-style: dashed;
  color: var(--ink-muted);
}

.mock-progress__marker {
  display: grid;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  color: #ffffff;
  background: var(--line-strong);
  font-weight: 800;
  place-items: center;
}

.mock-progress__item[data-status='done'] .mock-progress__marker {
  background: #0f766e;
}

.mock-progress__item[data-status='running'] .mock-progress__marker {
  background: #2563eb;
}

.mock-progress__item[data-status='failed'] .mock-progress__marker {
  background: #c2413b;
}

.mock-progress__label {
  display: flex;
  flex-wrap: wrap;
  margin: 0;
  gap: 8px;
  align-items: baseline;
  color: var(--ink);
  font-weight: 650;
  overflow-wrap: anywhere;
}

.mock-progress__status {
  padding: 2px 8px;
  border: 1px solid var(--line-strong);
  border-radius: 999px;
  color: var(--ink-secondary);
  font-size: 12px;
  font-weight: 600;
}

.mock-progress__detail {
  margin: 4px 0 0;
  color: var(--ink-secondary);
  font-size: 13px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}
</style>
