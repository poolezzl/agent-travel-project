<script setup lang="ts">
import type { DemoStatus } from '@/fixtures/s07'

const props = defineProps<{
  modelValue: DemoStatus
  options: Array<{ value: DemoStatus; label: string }>
}>()

const emit = defineEmits<{
  'update:modelValue': [value: DemoStatus]
}>()
</script>

<template>
  <fieldset class="status-switcher" data-testid="mock-status-selector">
    <legend>Mock 状态选择器</legend>
    <p>仅显式 mock mode + VITE_ENABLE_MOCK=true 时可见。</p>
    <div class="status-switcher__options">
      <label v-for="option in props.options" :key="option.value">
        <input
          type="radio"
          name="demo-status"
          :value="option.value"
          :checked="props.modelValue === option.value"
          @change="emit('update:modelValue', option.value)"
        />
        <span>{{ option.label }}</span>
      </label>
    </div>
  </fieldset>
</template>

<style scoped>
.status-switcher {
  min-width: 0;
  margin: 0;
  padding: 16px;
  border: 1px dashed var(--accent);
  border-radius: var(--radius-md);
  background: #eef8f6;
}

.status-switcher legend {
  padding: 0 6px;
  color: var(--ink);
  font-weight: 750;
}

.status-switcher p {
  margin: 0 0 12px;
  color: var(--ink-muted);
  font-size: 13px;
}

.status-switcher__options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.status-switcher label {
  cursor: pointer;
}

.status-switcher input {
  position: absolute;
  opacity: 0;
}

.status-switcher span {
  display: inline-flex;
  min-height: 40px;
  align-items: center;
  padding: 8px 12px;
  border: 1px solid var(--line-strong);
  border-radius: 999px;
  color: var(--ink-secondary);
  background: var(--surface);
  transition:
    border-color 160ms ease,
    background 160ms ease;
}

.status-switcher input:checked + span {
  border-color: var(--accent);
  color: #074c43;
  background: #d9f3ee;
  font-weight: 700;
}

.status-switcher input:focus-visible + span {
  outline: 3px solid var(--focus);
  outline-offset: 2px;
}
</style>
