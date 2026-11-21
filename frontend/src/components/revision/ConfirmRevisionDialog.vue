<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

import type { PlanView, RevisionResult } from '@/api/types'
import RevisionDiffView from '@/components/revision/RevisionDiffView.vue'
import { agentLabel, lockedPathLabel } from '@/composables/revisionDiff'

const props = defineProps<{
  result: RevisionResult
  plan: PlanView
  lockedPaths: string[]
  confirming: boolean
}>()

const emit = defineEmits<{
  (event: 'confirm'): void
  (event: 'decline'): void
}>()

const dialog = ref<HTMLElement | null>(null)
const openedAt = ref(Date.now())
const elapsedSeconds = ref(0)

let previousFocus: HTMLElement | null = null
let timer: ReturnType<typeof setInterval> | null = null

const staleAfterSeconds = 60
const isStale = () => elapsedSeconds.value >= staleAfterSeconds

function versionLabel(): string {
  return `v${props.plan.version_no}（${props.plan.version_id.slice(0, 8)}）`
}

function agentText(agent: string): string {
  const label = agentLabel(agent)
  return label === agent ? label : `${label}（${agent}）`
}

onMounted(() => {
  previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  dialog.value?.focus()
  timer = setInterval(() => {
    elapsedSeconds.value = Math.floor((Date.now() - openedAt.value) / 1000)
  }, 1000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  previousFocus?.focus()
})

function decline(): void {
  if (props.confirming) return
  emit('decline')
}

function confirm(): void {
  if (props.confirming) return
  emit('confirm')
}
</script>

<template>
  <div class="dialog-overlay">
    <div
      ref="dialog"
      class="dialog"
      role="dialog"
      aria-modal="true"
      aria-labelledby="confirm-dialog-title"
      tabindex="-1"
      data-testid="confirm-dialog"
      @keydown.esc="decline"
    >
      <h3 id="confirm-dialog-title" class="dialog__title">确认修改</h3>

      <dl class="dialog__meta">
        <div>
          <dt>基准版本</dt>
          <dd>{{ versionLabel() }}</dd>
        </div>
        <div>
          <dt>变更条数</dt>
          <dd>{{ result.changes.length }}</dd>
        </div>
        <div>
          <dt>影响日期</dt>
          <dd>
            {{ result.affected_days.length ? `第 ${result.affected_days.join('、')} 天` : '—' }}
          </dd>
        </div>
      </dl>

      <p class="dialog__summary">{{ result.summary }}</p>

      <div class="dialog__section">
        <h4>受影响的 Agent</h4>
        <ul v-if="result.affected_agents.length" class="dialog__list">
          <li v-for="agent in result.affected_agents" :key="agent">{{ agentText(agent) }}</li>
        </ul>
        <p v-else class="dialog__empty">无</p>
      </div>

      <div class="dialog__section">
        <h4>保护项（本次保持不变）</h4>
        <ul v-if="lockedPaths.length" class="dialog__list">
          <li v-for="path in lockedPaths" :key="path">{{ lockedPathLabel(path, plan) }}</li>
        </ul>
        <p v-else class="dialog__empty">未设置保护项</p>
      </div>

      <div class="dialog__section">
        <h4>变更预览</h4>
        <RevisionDiffView :changes="result.changes" :plan="plan" />
      </div>

      <p class="dialog__fee">
        费用说明：确认成功后由服务端结算（S20 口径：每次成功确认 2
        积分），失败不扣费；确认前不修改已展示行程。
      </p>

      <p v-if="isStale()" class="dialog__stale" data-testid="confirm-decline-note">
        弹窗已打开超过 {{ staleAfterSeconds }} 秒，建议重新预览后再确认（不会自动提交）。
      </p>

      <div class="dialog__actions">
        <button
          class="button button--primary"
          type="button"
          data-testid="confirm-accept"
          :disabled="confirming"
          @click="confirm"
        >
          {{ confirming ? '确认中…' : '确认修改' }}
        </button>
        <button
          class="button"
          type="button"
          data-testid="confirm-decline"
          :disabled="confirming"
          @click="decline"
        >
          暂不修改
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dialog-overlay {
  position: fixed;
  inset: 0;
  z-index: 50;
  display: grid;
  padding: 16px;
  background: rgb(17 24 39 / 45%);
  place-items: center;
}

.dialog {
  display: grid;
  gap: 12px;
  width: min(100%, 640px);
  max-height: 90vh;
  padding: clamp(16px, 3vw, 24px);
  overflow-y: auto;
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
}

.dialog:focus {
  outline: none;
}

.dialog__title {
  margin: 0;
  color: var(--ink);
  font-size: 20px;
}

.dialog__meta {
  display: grid;
  gap: 8px;
  margin: 0;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
}

.dialog__meta div {
  min-width: 0;
  padding: 8px 10px;
  border-radius: var(--radius-md);
  background: var(--surface-soft);
}

.dialog__meta dt {
  color: var(--ink-muted);
  font-size: 12px;
}

.dialog__meta dd {
  margin: 4px 0 0;
  color: var(--ink);
  font-weight: 650;
  overflow-wrap: anywhere;
}

.dialog__summary {
  margin: 0;
  padding: 10px 12px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius-md);
  color: var(--ink-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.dialog__section {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.dialog__section h4 {
  margin: 0;
  color: var(--ink);
  font-size: 14px;
}

.dialog__list {
  display: grid;
  gap: 4px;
  margin: 0;
  padding-left: 20px;
  color: var(--ink-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.dialog__empty {
  margin: 0;
  color: var(--ink-muted);
  font-size: 13px;
}

.dialog__fee {
  margin: 0;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  color: #1e3a8a;
  background: #dbeafe;
  font-size: 13px;
  line-height: 1.6;
}

.dialog__stale {
  margin: 0;
  padding: 8px 10px;
  border-radius: var(--radius-md);
  color: #713b00;
  background: #fff4cf;
  font-size: 13px;
}

.dialog__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.button {
  min-height: 42px;
  padding: 9px 18px;
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
