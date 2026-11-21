<script setup lang="ts">
import { computed } from 'vue'

import type { AtomicChange, PlanDay, PlanView } from '@/api/types'
import {
  formatLocation,
  formatValue,
  groupChangesByDay,
  oldValueAt,
  oldValueAtFromDays,
  operationClass,
  operationLabel,
} from '@/composables/revisionDiff'

const props = defineProps<{
  changes: AtomicChange[]
  plan?: PlanView | null
  /** 确认前的 days 快照：replace/remove 的「原值」从这里读取（缺失时回退到当前 plan）。 */
  beforeDays?: PlanDay[]
  unaffectedDays?: number[]
  unverifiedDays?: number[]
}>()

const groups = computed(() => groupChangesByDay(props.changes))

function resolved(change: AtomicChange): boolean {
  return formatLocation(change.path, props.plan) !== change.path
}

function oldValueFor(change: AtomicChange): unknown {
  if (props.beforeDays !== undefined) {
    return oldValueAtFromDays(change.path, props.beforeDays)
  }
  return oldValueAt(change.path, props.plan)
}

function changeText(change: AtomicChange): { old?: string; next?: string } {
  if (change.operation === 'replace') {
    const oldValue = oldValueFor(change)
    return {
      old: oldValue === undefined ? '原值不可用' : formatValue(oldValue),
      next: formatValue(change.value),
    }
  }
  if (change.operation === 'remove') {
    const oldValue = oldValueFor(change)
    return { old: oldValue === undefined ? formatValue(change.value) : formatValue(oldValue) }
  }
  return { next: formatValue(change.value) }
}
</script>

<template>
  <div class="revision-diff" data-testid="revision-diff">
    <section
      v-for="group in groups"
      :key="group.day ?? 'unknown'"
      class="revision-diff__day"
      :data-testid="`revision-diff-day-${group.day ?? 'unknown'}`"
    >
      <h4 class="revision-diff__day-title">
        <template v-if="group.day !== null">第 {{ group.day }} 天</template>
        <template v-else>无法定位的变更</template>
      </h4>
      <ul class="revision-diff__list">
        <li
          v-for="(change, index) in group.changes"
          :key="`${change.operation}-${change.path}-${index}`"
          class="revision-diff__item"
        >
          <span class="revision-diff__op" :class="operationClass(change.operation)">
            {{ operationLabel(change.operation) }}
          </span>
          <div class="revision-diff__body">
            <p class="revision-diff__location">
              位置：<span v-if="resolved(change)">{{ formatLocation(change.path, plan) }}</span>
              <template v-else>
                {{ change.path }}
                <span class="revision-diff__raw">原样展示</span>
              </template>
            </p>
            <p v-if="changeText(change).old !== undefined" class="revision-diff__value">
              <span class="revision-diff__old">原值：{{ changeText(change).old }}</span>
            </p>
            <p v-if="changeText(change).next !== undefined" class="revision-diff__value">
              <span class="revision-diff__new">新值：{{ changeText(change).next }}</span>
            </p>
            <p v-if="!resolved(change)" class="revision-diff__raw-value">
              原始值：{{ formatValue(change.value) }}
            </p>
          </div>
        </li>
      </ul>
    </section>

    <div
      v-if="unaffectedDays?.length"
      class="revision-diff__unchanged"
      data-testid="revision-diff-unchanged"
    >
      未受影响的日期：第 {{ unaffectedDays.join('、') }} 天（已验证未变更）
    </div>
    <div
      v-if="unverifiedDays?.length"
      class="revision-diff__unverified"
      data-testid="revision-diff-unverified"
    >
      未核对日期：第 {{ unverifiedDays.join('、') }} 天
    </div>
  </div>
</template>

<style scoped>
.revision-diff {
  display: grid;
  gap: 12px;
  min-width: 0;
}

.revision-diff__day {
  display: grid;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
}

.revision-diff__day-title {
  margin: 0;
  color: var(--ink);
  font-size: 14px;
}

.revision-diff__list {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.revision-diff__item {
  display: grid;
  gap: 8px;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: start;
}

.revision-diff__op {
  min-width: 44px;
  padding: 2px 8px;
  border-radius: 999px;
  text-align: center;
  font-size: 12px;
  font-weight: 650;
}

.revision-diff__op--add {
  color: #14532d;
  background: #dcfce7;
}

.revision-diff__op--remove {
  color: #7f1d1d;
  background: #fee2e2;
}

.revision-diff__op--replace {
  color: #1e3a8a;
  background: #dbeafe;
}

.revision-diff__op--reorder {
  color: #374151;
  background: #e5e7eb;
}

.revision-diff__body {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.revision-diff__location,
.revision-diff__value,
.revision-diff__raw-value {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.revision-diff__raw {
  margin-left: 6px;
  padding: 1px 7px;
  border-radius: 999px;
  color: #713b00;
  background: #fff4cf;
  font-size: 11px;
}

.revision-diff__old {
  color: #7f1d1d;
}

.revision-diff__new {
  color: #14532d;
}

.revision-diff__unchanged,
.revision-diff__unverified {
  margin: 0;
  padding: 8px 10px;
  border-radius: var(--radius-md);
  font-size: 13px;
  line-height: 1.6;
}

.revision-diff__unchanged {
  color: #14532d;
  background: #f0fdf4;
}

.revision-diff__unverified {
  color: #713b00;
  background: #fff4cf;
}
</style>
