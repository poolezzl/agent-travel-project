<script setup lang="ts">
import { ref } from 'vue'

import type { TimelineEntry } from '@/stores/revision'

const props = defineProps<{
  timeline: TimelineEntry[]
  currentVersionId: string | null
  rollingBack: boolean
}>()

const emit = defineEmits<{
  (event: 'rollback', versionId: string): void
}>()

const confirmingId = ref<string | null>(null)

function short(versionId: string): string {
  return versionId.slice(0, 8)
}

function isCurrent(entry: TimelineEntry): boolean {
  return entry.versionId === props.currentVersionId
}

function kindLabel(entry: TimelineEntry): string {
  if (entry.kind === 'change') return '修改'
  if (entry.kind === 'rollback') return '回滚'
  return '修改前版本'
}

function requestRollback(entry: TimelineEntry): void {
  confirmingId.value = entry.versionId
}

function confirmRollback(entry: TimelineEntry): void {
  confirmingId.value = null
  emit('rollback', entry.versionId)
}

function cancelRollback(): void {
  confirmingId.value = null
}
</script>

<template>
  <div class="timeline" data-testid="revision-timeline">
    <h3 class="timeline__title">版本时间线（本会话）</h3>
    <p v-if="timeline.length === 0" class="timeline__empty">
      历史版本列表接口未提供（PENDING-S21-01），当前仅记录本次会话内的变更。
    </p>

    <ol v-else class="timeline__list">
      <li
        v-for="(entry, index) in timeline"
        :key="entry.versionId"
        class="timeline__item"
        :data-testid="`timeline-item-${index}`"
      >
        <div class="timeline__head">
          <span class="timeline__kind" :class="`timeline__kind--${entry.kind}`">
            {{ kindLabel(entry) }}
          </span>
          <span v-if="isCurrent(entry)" class="timeline__current">当前</span>
          <span class="timeline__version">v{{ short(entry.versionId) }}</span>
          <span class="timeline__time">{{ entry.atLocal }}</span>
        </div>
        <p class="timeline__summary">{{ entry.summary }}</p>
        <p v-if="entry.kind === 'change' && entry.affectedDays.length" class="timeline__meta">
          影响日期：第 {{ entry.affectedDays.join('、') }} 天 · 变更 {{ entry.changeCount }} 条
        </p>

        <div v-if="!isCurrent(entry) && confirmingId !== entry.versionId" class="timeline__actions">
          <button
            class="button"
            type="button"
            data-testid="rollback-button"
            @click="requestRollback(entry)"
          >
            回滚到此版本
          </button>
        </div>

        <div v-if="!isCurrent(entry) && confirmingId === entry.versionId" class="timeline__confirm">
          <p>将以该历史版本内容创建一个新版本（历史不会被删除）。</p>
          <div class="timeline__confirm-actions">
            <button
              class="button button--primary"
              type="button"
              data-testid="rollback-confirm"
              :disabled="rollingBack"
              @click="confirmRollback(entry)"
            >
              {{ rollingBack ? '回滚中…' : '确认回滚' }}
            </button>
            <button class="button" type="button" :disabled="rollingBack" @click="cancelRollback">
              取消
            </button>
          </div>
        </div>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.timeline {
  display: grid;
  gap: 10px;
  min-width: 0;
}

.timeline__title {
  margin: 0;
  color: var(--ink);
  font-size: 16px;
}

.timeline__empty {
  margin: 0;
  padding: 12px 14px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius-md);
  color: var(--ink-muted);
  font-size: 13px;
  line-height: 1.6;
}

.timeline__list {
  display: grid;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.timeline__item {
  display: grid;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
}

.timeline__head {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  align-items: center;
}

.timeline__kind {
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 650;
}

.timeline__kind--change {
  color: #1e3a8a;
  background: #dbeafe;
}

.timeline__kind--rollback {
  color: #374151;
  background: #e5e7eb;
}

.timeline__kind--base {
  color: #6b7280;
  background: #f3f4f6;
}

.timeline__current {
  padding: 2px 8px;
  border-radius: 999px;
  color: #14532d;
  background: #dcfce7;
  font-size: 12px;
  font-weight: 650;
}

.timeline__version {
  color: var(--ink);
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}

.timeline__time {
  margin-left: auto;
  color: var(--ink-muted);
  font-size: 12px;
}

.timeline__summary,
.timeline__meta,
.timeline__confirm p {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.timeline__meta {
  color: var(--ink-secondary);
}

.timeline__actions,
.timeline__confirm {
  display: grid;
  gap: 8px;
  margin-top: 2px;
}

.timeline__confirm {
  padding: 8px 10px;
  border-radius: var(--radius-md);
  color: #713b00;
  background: #fff4cf;
}

.timeline__confirm-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.button {
  min-height: 36px;
  padding: 7px 14px;
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
  opacity: 0.55;
}

.button:focus-visible {
  outline: 3px solid var(--focus);
  outline-offset: 2px;
}
</style>
