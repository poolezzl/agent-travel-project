<script setup lang="ts">
import { reactive, ref, watch } from 'vue'

import type { PlanningInput } from '@/fixtures/s07'

const props = withDefaults(
  defineProps<{
    modelValue: PlanningInput
    submitting?: boolean
    disabled?: boolean
  }>(),
  { submitting: false, disabled: false },
)

const emit = defineEmits<{
  'update:modelValue': [value: PlanningInput]
  submit: [value: PlanningInput]
}>()

const cityOptions: Array<{ value: string; label: string; available: boolean }> = [
  { value: '北京', label: '北京 · sample_ready（合成样例）', available: true },
  { value: '上海', label: '上海 · planned（演示不可选）', available: false },
  { value: '杭州', label: '杭州 · planned（演示不可选）', available: false },
  { value: '成都', label: '成都 · planned（演示不可选）', available: false },
]

const transportOptions: PlanningInput['transport'][] = ['混合', '步行', '公交', '地铁', '打车']
const planCountOptions: PlanningInput['planCount'][] = [1, 2, 3]

function cloneInput(value: PlanningInput): PlanningInput {
  return { ...value, mustVisit: [...value.mustVisit] }
}

const draft = reactive<PlanningInput>(cloneInput(props.modelValue))
const mustVisitText = ref(props.modelValue.mustVisit.join('\n'))
const errors = ref<string[]>([])

watch(
  () => props.modelValue,
  (value) => {
    Object.assign(draft, cloneInput(value))
    mustVisitText.value = value.mustVisit.join('\n')
  },
  { deep: true },
)

function parseMustVisit(text: string): string[] {
  return text
    .split(/[\n,，、]+/)
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0)
}

function daysBetween(startDate: string, endDate: string): number {
  const start = Date.parse(`${startDate}T00:00:00Z`)
  const end = Date.parse(`${endDate}T00:00:00Z`)
  if (Number.isNaN(start) || Number.isNaN(end)) return 0
  return Math.round((end - start) / 86_400_000) + 1
}

function validate(input: PlanningInput): string[] {
  const messages: string[] = []
  const city = typeof input.city === 'string' ? input.city.trim() : ''
  if (city.length === 0) messages.push('请选择城市（当前仅北京为合成演示可用）。')
  if (!input.startDate || !input.endDate) {
    messages.push('请填写出发与返回日期。')
  } else {
    const days = daysBetween(input.startDate, input.endDate)
    if (days <= 0) messages.push('返回日期不能早于出发日期。')
    else if (days > 7) messages.push('演示范围最多 7 天，当前超出。')
  }
  if (input.mustVisit.length > 5) messages.push('必去点最多 5 个。')
  if (!planCountOptions.includes(input.planCount)) messages.push('方案数量只能是 1~3。')
  return messages
}

function syncInput(): void {
  const next: PlanningInput = { ...draft, mustVisit: parseMustVisit(mustVisitText.value) }
  emit('update:modelValue', next)
}

function handleSubmit(): void {
  if (props.submitting || props.disabled) return
  syncInput()
  const next: PlanningInput = { ...draft, mustVisit: parseMustVisit(mustVisitText.value) }
  errors.value = validate(next)
  if (errors.value.length > 0) return
  emit('submit', next)
}

function resetForm(): void {
  Object.assign(draft, cloneInput(props.modelValue))
  mustVisitText.value = props.modelValue.mustVisit.join('\n')
  errors.value = []
}
</script>

<template>
  <form class="planning-form" data-testid="planning-form" novalidate @submit.prevent="handleSubmit">
    <header class="planning-form__head">
      <h2 id="planning-form-title">行程输入（演示）</h2>
      <p>表单只驱动本地合成演示，不发起任何网络请求；提交不会创建真实任务或消耗积分。</p>
    </header>

    <p v-if="errors.length > 0" class="planning-form__errors" role="alert">
      <strong>请先修正以下 {{ errors.length }} 项：</strong>
      <span v-for="message in errors" :key="message">{{ message }}</span>
    </p>

    <div class="planning-form__grid">
      <label class="field">
        <span class="field__label">城市</span>
        <select
          v-model="draft.city"
          class="field__control"
          :disabled="props.disabled"
          @change="syncInput"
        >
          <option v-for="option in cityOptions" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
      </label>

      <label class="field">
        <span class="field__label">出发日期</span>
        <input
          v-model="draft.startDate"
          class="field__control"
          type="date"
          :disabled="props.disabled"
          @change="syncInput"
        />
      </label>

      <label class="field">
        <span class="field__label">返回日期</span>
        <input
          v-model="draft.endDate"
          class="field__control"
          type="date"
          :disabled="props.disabled"
          @change="syncInput"
        />
      </label>

      <label class="field">
        <span class="field__label">交通偏好</span>
        <select
          v-model="draft.transport"
          class="field__control"
          :disabled="props.disabled"
          @change="syncInput"
        >
          <option v-for="option in transportOptions" :key="option" :value="option">
            {{ option }}
          </option>
        </select>
      </label>

      <label class="field field--wide">
        <span class="field__label">必去点（每行一个，最多 5 个，合成点位）</span>
        <textarea
          v-model="mustVisitText"
          class="field__control"
          rows="3"
          :disabled="props.disabled"
          placeholder="合成·北城晨光公园"
          @change="syncInput"
        />
      </label>

      <label class="field field--wide">
        <span class="field__label">偏好说明</span>
        <textarea
          v-model="draft.preference"
          class="field__control"
          rows="3"
          :disabled="props.disabled"
          @change="syncInput"
        />
      </label>
    </div>

    <fieldset class="planning-form__counts" :disabled="props.disabled">
      <legend>方案数量</legend>
      <label v-for="count in planCountOptions" :key="count">
        <input
          v-model.number="draft.planCount"
          type="radio"
          name="plan-count"
          :value="count"
          @change="syncInput"
        />
        <span>{{ count }} 套</span>
      </label>
    </fieldset>

    <div class="planning-form__actions">
      <button
        class="button button--primary"
        type="submit"
        :disabled="props.submitting || props.disabled"
      >
        {{ props.submitting ? '正在模拟编排…' : '生成演示行程' }}
      </button>
      <button class="button" type="button" :disabled="props.disabled" @click="resetForm">
        重置为默认合成输入
      </button>
      <span class="planning-form__hint" aria-live="polite">
        {{
          props.submitting
            ? '本地模拟进度进行中，可继续浏览其他区块。'
            : '提交按钮在模拟期间禁用，避免重复触发。'
        }}
      </span>
    </div>
  </form>
</template>

<style scoped>
.planning-form {
  display: grid;
  min-width: 0;
  gap: 16px;
  padding: clamp(16px, 3vw, 24px);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
}

.planning-form__head h2 {
  margin: 0;
  color: var(--ink);
  font-size: 20px;
}

.planning-form__head p {
  margin: 6px 0 0;
  color: var(--ink-secondary);
  font-size: 14px;
  line-height: 1.6;
}

.planning-form__errors {
  display: grid;
  gap: 4px;
  margin: 0;
  padding: 12px 14px;
  border: 1px solid #c2413b;
  border-radius: var(--radius-md);
  color: #7f1d1d;
  background: #fdeceb;
  font-size: 14px;
  line-height: 1.6;
}

.planning-form__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 14px;
}

.field {
  display: grid;
  min-width: 0;
  gap: 6px;
}

.field--wide {
  grid-column: 1 / -1;
}

.field__label {
  color: var(--ink-secondary);
  font-size: 13px;
  font-weight: 650;
}

.field__control {
  width: 100%;
  min-width: 0;
  min-height: 42px;
  padding: 9px 11px;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-md);
  color: var(--ink);
  background: var(--surface-soft);
  overflow-wrap: anywhere;
}

.field__control:focus-visible {
  border-color: var(--accent);
  outline: 3px solid var(--focus);
  outline-offset: 1px;
}

.planning-form__counts {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 0;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
}

.planning-form__counts legend {
  padding: 0 6px;
  color: var(--ink-secondary);
  font-size: 13px;
  font-weight: 650;
}

.planning-form__counts label {
  display: inline-flex;
  align-items: center;
  min-height: 40px;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid var(--line-strong);
  border-radius: 999px;
  background: var(--surface-soft);
  cursor: pointer;
}

.planning-form__counts input:focus-visible {
  outline: 3px solid var(--focus);
  outline-offset: 2px;
}

.planning-form__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.planning-form__hint {
  color: var(--ink-muted);
  font-size: 13px;
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
