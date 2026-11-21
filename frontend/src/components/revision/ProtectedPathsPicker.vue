<script setup lang="ts">
import { computed } from 'vue'

import type { PlanView } from '@/api/types'

const props = defineProps<{
  plan: PlanView
  modelValue: string[]
  disabled?: boolean
}>()

const emit = defineEmits<{
  (event: 'update:modelValue', value: string[]): void
}>()

interface LockOption {
  path: string
  label: string
}

const options = computed<LockOption[]>(() => {
  const result: LockOption[] = []
  props.plan.plan.days.forEach((day, dayIndex) => {
    day.segments.forEach((segment, segmentIndex) => {
      if (!segment.is_must_visit) return
      result.push({
        path: `/days/${dayIndex + 1}/segments/${segmentIndex}`,
        label: `第 ${dayIndex + 1} 天 第 ${segmentIndex + 1} 段 · ${segment.name}`,
      })
    })
  })
  return result
})

function toggle(path: string): void {
  if (props.disabled) return
  const next = props.modelValue.includes(path)
    ? props.modelValue.filter((item) => item !== path)
    : [...props.modelValue, path]
  emit('update:modelValue', next)
}
</script>

<template>
  <div class="protected">
    <p class="protected__title">保护项（锁定必去点，修改时保持不变）</p>
    <p v-if="options.length === 0" class="protected__empty">无必去点，未设置保护项。</p>
    <label v-for="option in options" :key="option.path" class="protected__item">
      <input
        type="checkbox"
        data-testid="protected-path"
        :checked="modelValue.includes(option.path)"
        :disabled="disabled"
        @change="toggle(option.path)"
      />
      <span>{{ option.label }}</span>
    </label>
  </div>
</template>

<style scoped>
.protected {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.protected__title {
  margin: 0;
  color: var(--ink-secondary);
  font-size: 13px;
  font-weight: 650;
}

.protected__empty {
  margin: 0;
  color: var(--ink-muted);
  font-size: 13px;
}

.protected__item {
  display: flex;
  gap: 8px;
  align-items: baseline;
  color: var(--ink);
  font-size: 13px;
  line-height: 1.5;
  overflow-wrap: anywhere;
  cursor: pointer;
}
</style>
