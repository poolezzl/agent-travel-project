<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'

import { toErrorInfo } from '@/api/http'
import type { ErrorInfo } from '@/api/http'
import { isTerminalStatus } from '@/api/taskStatus'
import AuthPanel from '@/components/planning/AuthPanel.vue'
import ErrorPanel from '@/components/planning/ErrorPanel.vue'
import PlanResultPanel from '@/components/planning/PlanResultPanel.vue'
import PlanningForm from '@/components/planning/PlanningForm.vue'
import QuoteSummary from '@/components/planning/QuoteSummary.vue'
import RevisionPanel from '@/components/revision/RevisionPanel.vue'
import SourceBadges from '@/components/planning/SourceBadges.vue'
import TaskProgress from '@/components/planning/TaskProgress.vue'
import TaskStatusBanner from '@/components/planning/TaskStatusBanner.vue'
import { useTaskStream } from '@/composables/useTaskStream'
import { usePlanningStore } from '@/stores/planning'
import type { PlanningFormState } from '@/stores/planning'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
const planning = usePlanningStore()

const { isAuthenticated, user } = storeToRefs(session)
const {
  form,
  quote,
  quoteError,
  quoteLoading,
  task,
  taskError,
  taskLoading,
  events,
  activeStatus,
  degradedFields,
  plan,
  planError,
  planLoading,
  balancePoints,
  balanceError,
  cancelling,
  cancelError,
} = storeToRefs(planning)

const streamError = ref<ErrorInfo | null>(null)
const showCancelConfirm = ref(false)
let doneRefreshed = false

const stream = useTaskStream({
  getTaskId: () => planning.task?.task_id ?? '',
  getLastSequenceNo: () => planning.lastSequenceNo,
  onEvent: (event) => planning.applyEvent(event),
  onDone: () => onStreamDone(),
  onError: (error) => {
    streamError.value = toErrorInfo(error)
  },
  reconnectDelayMs: 2000,
})

watch(stream.connected, (value) => {
  planning.streamConnected = value
})

// 终态后读取后端已发布版本；409（未就绪/待确认）由结果面板显式展示。
watch(
  activeStatus,
  (value) => {
    if (value && isTerminalStatus(value)) {
      void planning.fetchPlan()
    }
  },
  { immediate: true },
)

function onStreamDone(): void {
  if (doneRefreshed) return
  doneRefreshed = true
  void planning.refreshTask()
  void planning.fetchBalance()
  void planning.fetchPlan()
}

async function handleSubmit(input: PlanningFormState): Promise<void> {
  planning.setForm(input)
  streamError.value = null
  try {
    await planning.requestQuote()
  } catch {
    // 错误已写入 quoteError。
  }
}

async function handleCreateTask(): Promise<void> {
  streamError.value = null
  showCancelConfirm.value = false
  doneRefreshed = false
  try {
    await planning.createTask()
  } catch {
    return
  }
  if (planning.task && !isTerminalStatus(planning.task.status)) {
    stream.connect()
  } else {
    onStreamDone()
  }
}

async function handleRetry(): Promise<void> {
  if (taskLoading.value) return
  streamError.value = null
  showCancelConfirm.value = false
  doneRefreshed = false
  try {
    await planning.createTask(true)
  } catch {
    return
  }
  if (planning.task && !isTerminalStatus(planning.task.status)) {
    stream.connect()
  } else {
    onStreamDone()
  }
}

function requestCancel(): void {
  showCancelConfirm.value = true
}

async function confirmCancel(): Promise<void> {
  showCancelConfirm.value = false
  try {
    await planning.cancelTask()
  } catch {
    return
  }
  stream.disconnect()
  await planning.fetchBalance()
}

function abortCancel(): void {
  showCancelConfirm.value = false
}

onMounted(() => {
  session.ensureRefreshHandler()
  if (session.isAuthenticated) {
    void session.fetchMe()
    void planning.fetchBalance()
  }
})

onBeforeUnmount(() => {
  stream.close()
})
</script>

<template>
  <div class="s14-shell">
    <header class="s14-header">
      <div class="s14-header__text">
        <p class="s14-header__eyebrow">S14 · M1 真实端到端前端</p>
        <h1>北京单城三日 · 规划与任务</h1>
        <p>
          表单、报价、建任务、预扣与 SSE 进度均调用真实接口（本地 Mock 供应商端到端）；
          终态后读取后端已发布行程版本，示例数据与降级字段显式标注。
        </p>
      </div>
      <dl class="s14-header__meta">
        <div>
          <dt>认证状态</dt>
          <dd>{{ isAuthenticated ? `已登录（${user?.login ?? ''}）` : '未登录' }}</dd>
        </div>
        <div>
          <dt><RouterLink to="/wallet">积分钱包</RouterLink></dt>
          <dd data-testid="balance-points">{{ balancePoints === null ? '—' : balancePoints }}</dd>
        </div>
      </dl>
    </header>

    <AuthPanel v-if="!isAuthenticated" />

    <div v-else class="s14-layout">
      <div class="s14-column">
        <PlanningForm
          v-model="form"
          :submitting="quoteLoading"
          data-testid="planning-form"
          @submit="handleSubmit"
        />

        <ErrorPanel v-if="quoteError" title="报价失败" :error="quoteError" />

        <QuoteSummary
          v-if="quote && !task"
          :quote="quote"
          :creating="taskLoading"
          @create="handleCreateTask"
        />
      </div>

      <div class="s14-column">
        <ErrorPanel v-if="taskError" title="创建任务失败" :error="taskError" />
        <ErrorPanel v-if="cancelError" title="取消失败" :error="cancelError" />
        <ErrorPanel v-if="streamError" title="进度订阅中断" :error="streamError" />
        <ErrorPanel v-if="balanceError" title="余额获取失败" :error="balanceError" />

        <template v-if="task">
          <TaskStatusBanner
            :status="activeStatus"
            :reserved-points="task.reserved_points"
            :cancelling="cancelling"
            :creating="taskLoading"
            @cancel="requestCancel"
            @retry="handleRetry"
          />

          <div
            v-if="showCancelConfirm"
            class="s14-confirm"
            data-testid="cancel-confirm"
            role="alert"
          >
            <p>确认取消任务？取消后服务端将停止后续步骤并按需退款。</p>
            <div class="s14-confirm__actions">
              <button class="button button--primary" type="button" @click="confirmCancel">
                确认取消
              </button>
              <button class="button" type="button" @click="abortCancel">再想想</button>
            </div>
          </div>

          <TaskProgress
            :events="events"
            :active-status="activeStatus"
            :connected="planning.streamConnected"
          />

          <PlanResultPanel
            v-if="activeStatus && isTerminalStatus(activeStatus)"
            :plan="plan"
            :plan-error="planError"
            :plan-loading="planLoading"
            :degraded-fields="degradedFields"
          />
          <RevisionPanel v-if="activeStatus && isTerminalStatus(activeStatus) && plan" />
          <SourceBadges :sources="plan?.source_metadata ?? []" />
        </template>
      </div>
    </div>

    <footer class="s14-footer">
      <p>
        结果来自后端已发布版本（GET /api/v1/tasks/{task_id}/plan，S19 关闭 PENDING-S14-01）； 未配置
        Python 编排服务时本地后端使用 MockAgentExecutor，因此只能称为「本地 Mock
        供应商端到端」，未配置真实供应商时不宣称真实端到端通过。
      </p>
    </footer>
  </div>
</template>

<style scoped>
.s14-shell {
  display: grid;
  min-width: 0;
  gap: 20px;
  padding: 20px clamp(16px, 4vw, 32px) 40px;
}

.s14-header {
  display: grid;
  gap: 16px;
  padding: clamp(16px, 4vw, 32px);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
  grid-template-columns: minmax(0, 1fr);
}

.s14-header__eyebrow {
  margin: 0;
  color: var(--accent);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.1em;
}

.s14-header__text h1 {
  margin: 8px 0 10px;
  color: var(--ink);
  font-size: clamp(24px, 4vw, 36px);
  line-height: 1.2;
  overflow-wrap: anywhere;
}

.s14-header__text p {
  margin: 0;
  color: var(--ink-secondary);
  line-height: 1.7;
  overflow-wrap: anywhere;
}

.s14-header__meta {
  display: grid;
  gap: 10px;
  margin: 0;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
}

.s14-header__meta div {
  min-width: 0;
  padding: 12px;
  border-radius: var(--radius-md);
  background: var(--surface-soft);
}

.s14-header__meta dt {
  color: var(--ink-muted);
  font-size: 12px;
}

.s14-header__meta dd {
  margin: 4px 0 0;
  color: var(--ink);
  font-weight: 650;
  overflow-wrap: anywhere;
}

.s14-layout {
  display: grid;
  min-width: 0;
  gap: 16px;
  align-items: start;
  grid-template-columns: minmax(0, 1fr);
}

.s14-column {
  display: grid;
  min-width: 0;
  gap: 16px;
}

.s14-confirm {
  display: grid;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid #d97706;
  border-radius: var(--radius-md);
  color: #713b00;
  background: #fff4cf;
}

.s14-confirm p {
  margin: 0;
  font-size: 14px;
  line-height: 1.6;
}

.s14-confirm__actions {
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

.button:focus-visible {
  outline: 3px solid var(--focus);
  outline-offset: 2px;
}

.s14-footer {
  margin: 0 clamp(16px, 4vw, 32px);
  padding: 14px 16px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius-lg);
  color: var(--ink-secondary);
  background: var(--surface-soft);
}

.s14-footer p {
  margin: 0;
  font-size: 13px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

@media (min-width: 1080px) {
  .s14-layout {
    grid-template-columns: minmax(0, 420px) minmax(0, 1fr);
  }

  .s14-header {
    grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
    align-items: start;
  }
}
</style>
