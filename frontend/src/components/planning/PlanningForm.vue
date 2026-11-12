<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'

import type { PlanningFormState } from '@/stores/planning'

const props = withDefaults(
  defineProps<{
    modelValue: PlanningFormState
    submitting?: boolean
    disabled?: boolean
  }>(),
  { submitting: false, disabled: false },
)

const emit = defineEmits<{
  'update:modelValue': [value: PlanningFormState]
  submit: [value: PlanningFormState]
}>()

const transportOptions: PlanningFormState['transport'][] = ['混合', '步行', '公交', '地铁', '打车']
const planCountOptions = [1, 2, 3]

function cloneInput(value: PlanningFormState): PlanningFormState {
  return { ...value, mustVisit: [...value.mustVisit] }
}

const draft = reactive<PlanningFormState>(cloneInput(props.modelValue))
const mustVisitText = ref(props.modelValue.mustVisit.join('\n'))
const errors = ref<string[]>([])

const submitDisabled = computed(() => props.submitting || props.disabled)

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

function validate(input: PlanningFormState): string[] {
  const messages: string[] = []
  if (input.city !== '北京') messages.push('M1 仅支持北京单城。')
  if (!input.startDate || !input.endDate) {
    messages.push('请填写出发与返回日期。')
  } else {
    const days = daysBetween(input.startDate, input.endDate)
    if (days !== 3) messages.push(`M1 固定三日行程，当前为 ${days} 天。`)
  }
  if (!Number.isInteger(input.partySize) || input.partySize < 1) {
    messages.push('人数至少为 1。')
  }
  if (!Number.isInteger(input.budgetYuan) || input.budgetYuan < 1) {
    messages.push('预算需为不小于 1 的整数（元）。')
  }
  if (input.mustVisit.length > 5) messages.push('必去点最多 5 个。')
  if (!planCountOptions.includes(input.planCount)) messages.push('方案数量只能是 1~3。')
  return messages
}

function syncInput(): void {
  const next: PlanningFormState = { ...draft, mustVisit: parseMustVisit(mustVisitText.value) }
  emit('update:modelValue', next)
}

function handleSubmit(): void {
  if (submitDisabled.value) return
  syncInput()
  const next: PlanningFormState = { ...draft, mustVisit: parseMustVisit(mustVisitText.value) }
  errors.value = validate(next)
  if (errors.value.length > 0) return
  emit('submit', next)
}
</script>

<template>
  <form class="planning-form" data-testid="planning-form" novalidate @submit.prevent="handleSubmit">
    <header class="planning-form__head">
      <h2 id="planning-form-title">行程输入</h2>
      <p>表单校验为提交前预检，最终以服务端为准；报价与建任务均调用真实接口。</p>
    </header>

    <p v-if="errors.length > 0" class="planning-form__errors" role="alert">
      <strong>请先修正以下 {{ errors.length }} 项：</strong>
      <span v-for="message in errors" :key="message">{{ message }}</span>
    </p>

    <div class="planning-form__grid">
      <label class="field">
        <span class="field__label">城市（M1 固定）</span>
        <input class="field__control" type="text" :value="draft.city" disabled />
      </label>

      <label class="field">
        <span class="field__label">出发日期</span>
        <input
          v-model="draft.startDate"
          class="field__control"
          type="date"
          :disabled="submitDisabled"
          @change="syncInput"
        />
      </label>

      <label class="field">
        <span class="field__label">返回日期（三日行程）</span>
        <input
          v-model="draft.endDate"
          class="field__control"
          type="date"
          :disabled="submitDisabled"
          @change="syncInput"
        />
      </label>

      <label class="field">
        <span class="field__label">人数</span>
        <input
          v-model.number="draft.partySize"
          class="field__control"
          type="number"
          min="1"
          step="1"
          :disabled="submitDisabled"
          @change="syncInput"
        />
      </label>

      <label class="field">
        <span class="field__label">预算总额（元，整数）</span>
        <input
          v-model.number="draft.budgetYuan"
          class="field__control"
          type="number"
          min="1"
          step="1"
          :disabled="submitDisabled"
          @change="syncInput"
        />
      </label>

      <label class="field">
        <span class="field__label">交通偏好</span>
        <select
          v-model="draft.transport"
          class="field__control"
          :disabled="submitDisabled"
          @change="syncInput"
        >
          <option v-for="option in transportOptions" :key="option" :value="option">
            {{ option }}
          </option>
        </select>
      </label>

      <label class="field field--wide">
        <span class="field__label">必去点（每行一个，最多 5 个）</span>
        <textarea
          v-model="mustVisitText"
          class="field__control"
          rows="3"
          :disabled="submitDisabled"
          placeholder="例如：故宫博物院"
          @change="syncInput"
        />
      </label>

      <label class="field field--wide">
        <span class="field__label">偏好说明</span>
        <textarea
          v-model="draft.preference"
          class="field__control"
          rows="3"
          :disabled="submitDisabled"
          @change="syncInput"
        />
      </label>
    </div>

    <fieldset class="planning-form__counts" :disabled="submitDisabled">
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
      <button class="button button--primary" type="submit" :disabled="submitDisabled">
        {{ submitting ? '正在报价…' : '获取服务端报价' }}
      </button>
      <span class="planning-form__hint" aria-live="polite">
        {{ submitting ? '正在请求报价，请稍候。' : '报价仅展示服务端积分，不预扣。' }}
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

.field__control:disabled {
  color: var(--ink-muted);
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
