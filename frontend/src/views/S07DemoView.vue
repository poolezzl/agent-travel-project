<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { storeToRefs } from 'pinia'

import type { DemoStatus, PlanningInput } from '@/fixtures/s07'
import { createDemoViewModel, defaultPlanningInput, mockStatusOptions } from '@/fixtures/s07'
import CommunitySamples from '@/components/s07/CommunitySamples.vue'
import DemoNotice from '@/components/s07/DemoNotice.vue'
import LoadingSkeleton from '@/components/s07/LoadingSkeleton.vue'
import MapPlaceholder from '@/components/s07/MapPlaceholder.vue'
import MockProgress from '@/components/s07/MockProgress.vue'
import MockStatusSelector from '@/components/s07/MockStatusSelector.vue'
import PlanComparison from '@/components/s07/PlanComparison.vue'
import PlanningForm from '@/components/s07/PlanningForm.vue'
import ResultState from '@/components/s07/ResultState.vue'
import ScheduleTimeline from '@/components/s07/ScheduleTimeline.vue'
import type { MockOutcome } from '@/components/s07/mockProgress'
import { buildMockStages, mockStagePlan } from '@/components/s07/mockProgress'
import { useAppStore } from '@/stores/app'

const STAGE_INTERVAL_MS = 600
const LOADING_PREVIEW_INDEX = 2
const FAILED_PREVIEW_INDEX = 3
const LAST_STAGE_INDEX = Math.max(mockStagePlan.length - 1, 0)

const appStore = useAppStore()
const { isMockProfile, profile } = storeToRefs(appStore)

const status = ref<DemoStatus>('success')
const outcome = ref<MockOutcome>('success')
const progressIndex = ref(LAST_STAGE_INDEX)
const planningInput = ref<PlanningInput>({
  ...defaultPlanningInput,
  mustVisit: [...defaultPlanningInput.mustVisit],
})

let stageTimer: ReturnType<typeof setTimeout> | null = null

const viewModel = computed(() => createDemoViewModel(status.value))
const stages = computed(() => buildMockStages(progressIndex.value, outcome.value))
const isSimulating = computed(() => status.value === 'loading')
const hasResultData = computed(() => status.value === 'success' || status.value === 'partial')
const envelope = computed(() => viewModel.value.s02Envelope)

const selectedStatus = computed<DemoStatus>({
  get: () => status.value,
  set: (value) => applyStatus(value),
})

function clearStageTimer(): void {
  if (stageTimer !== null) {
    clearTimeout(stageTimer)
    stageTimer = null
  }
}

function applyStatus(value: DemoStatus): void {
  clearStageTimer()
  status.value = value
  if (value === 'loading') {
    progressIndex.value = LOADING_PREVIEW_INDEX
    outcome.value = 'running'
    return
  }
  if (value === 'error') {
    progressIndex.value = FAILED_PREVIEW_INDEX
    outcome.value = 'error'
    return
  }
  progressIndex.value = LAST_STAGE_INDEX
  outcome.value = value === 'partial' ? 'partial' : 'success'
}

function runSimulation(): void {
  if (isSimulating.value) return
  applyStatus('loading')
  progressIndex.value = 0

  const advance = (): void => {
    if (progressIndex.value >= LAST_STAGE_INDEX) {
      stageTimer = null
      outcome.value = 'success'
      status.value = 'success'
      return
    }
    progressIndex.value += 1
    stageTimer = setTimeout(advance, STAGE_INTERVAL_MS)
  }

  stageTimer = setTimeout(advance, STAGE_INTERVAL_MS)
}

function handleSubmit(input: PlanningInput): void {
  planningInput.value = { ...input, mustVisit: [...input.mustVisit] }
  runSimulation()
}

onBeforeUnmount(clearStageTimer)
</script>

<template>
  <DemoNotice />

  <div class="s07-shell">
    <header class="s07-header">
      <div class="s07-header__text">
        <p class="s07-header__eyebrow">S07 · 开题演示页（Mock）</p>
        <h1>多智能体旅行规划 · 演示界面</h1>
        <p>
          这是开题阶段的演示页面：输入表单、模拟进度、时间轴、地图占位、方案对比与社区静态样张均为合成内容，
          未接入后端 API、SSE、登录、地图服务、供应商调用或持久化。
        </p>
      </div>
      <dl class="s07-header__meta">
        <div>
          <dt>运行配置</dt>
          <dd>{{ profile }}</dd>
        </div>
        <div>
          <dt>Mock 防线</dt>
          <dd>{{ isMockProfile ? '已显式启用' : '未启用' }}</dd>
        </div>
        <div>
          <dt>当前演示状态</dt>
          <dd data-testid="current-status">{{ status.toUpperCase() }} · Mock</dd>
        </div>
      </dl>
    </header>

    <div class="s07-layout">
      <div class="s07-column">
        <section v-if="isMockProfile" class="s07-panel" data-testid="mock-status-panel">
          <MockStatusSelector v-model="selectedStatus" :options="mockStatusOptions" />
        </section>

        <PlanningForm v-model="planningInput" :submitting="isSimulating" @submit="handleSubmit" />

        <MockProgress :stages="stages" :active="isSimulating" />

        <section class="s07-panel" data-testid="envelope-panel" aria-labelledby="envelope-heading">
          <h2 id="envelope-heading">契约信封（S02 形状）</h2>
          <p>
            下方字段沿用 S02 Agent 契约的公共信封形状，用于说明后续 S14
            需要替换的数据边界；本页不发送也不接收真实响应。
          </p>
          <dl class="s07-envelope">
            <div>
              <dt>schema_version</dt>
              <dd>{{ envelope.schema_version }}</dd>
            </div>
            <div>
              <dt>task_id</dt>
              <dd>{{ envelope.task_id }}</dd>
            </div>
            <div>
              <dt>trace_id</dt>
              <dd>{{ envelope.trace_id }}</dd>
            </div>
            <div>
              <dt>request_id</dt>
              <dd>{{ envelope.request_id }}</dd>
            </div>
            <div>
              <dt>generated_at</dt>
              <dd>{{ envelope.generated_at }}</dd>
            </div>
            <div>
              <dt>status</dt>
              <dd>{{ envelope.status }}</dd>
            </div>
          </dl>

          <div class="s07-envelope__extra" data-testid="envelope-degraded">
            <p v-if="envelope.message" class="s07-envelope__message">
              message：{{ envelope.message }}
            </p>
            <p v-if="envelope.error_code" class="s07-envelope__message">
              error_code：{{ envelope.error_code }}
            </p>
            <p v-if="envelope.degraded_fields?.length" class="s07-envelope__message">
              降级字段（不隐藏）：{{ envelope.degraded_fields.join('、') }}
            </p>
            <p v-else class="s07-envelope__message">降级字段：无</p>
          </div>

          <div class="s07-envelope__sources">
            <h3>来源与时效（source_metadata）</h3>
            <p v-if="envelope.source_metadata.length === 0" class="s07-envelope__message">
              本状态下没有 source_metadata 条目：空值保持为空，不用占位事实补齐。
            </p>
            <ul v-else>
              <li v-for="source in envelope.source_metadata" :key="source.source_record_id">
                {{ source.source_type }} / {{ source.source_record_id }} · 取得时间
                {{ source.retrieved_at }} · 时效 {{ source.freshness_status ?? 'unknown' }} ·
                {{ source.is_estimated ? '估算值' : '非估算' }}
              </li>
            </ul>
          </div>
        </section>
      </div>

      <div class="s07-column">
        <section class="s07-panel" data-testid="input-summary" aria-labelledby="summary-heading">
          <h2 id="summary-heading">当前输入摘要（本地）</h2>
          <dl class="s07-summary">
            <div>
              <dt>城市</dt>
              <dd>{{ planningInput.city }}</dd>
            </div>
            <div>
              <dt>日期</dt>
              <dd>{{ planningInput.startDate }} → {{ planningInput.endDate }}</dd>
            </div>
            <div>
              <dt>必去点</dt>
              <dd>
                {{
                  planningInput.mustVisit.length > 0 ? planningInput.mustVisit.join('、') : '未填写'
                }}
              </dd>
            </div>
            <div>
              <dt>交通偏好</dt>
              <dd>{{ planningInput.transport }}</dd>
            </div>
            <div>
              <dt>方案数量</dt>
              <dd>{{ planningInput.planCount }} 套</dd>
            </div>
            <div>
              <dt>偏好说明</dt>
              <dd>{{ planningInput.preference || '未填写' }}</dd>
            </div>
          </dl>
        </section>

        <LoadingSkeleton v-if="status === 'loading'" />

        <ResultState
          v-else-if="!hasResultData"
          :status="status"
          :title="viewModel.title"
          :description="viewModel.description"
          :code="envelope.error_code"
        />

        <template v-else>
          <ResultState
            :status="status"
            :title="viewModel.title"
            :description="viewModel.description"
          />

          <ScheduleTimeline :days="viewModel.days" />
          <MapPlaceholder :points="viewModel.mapPoints" />
          <PlanComparison :variants="viewModel.variants" />
        </template>

        <CommunitySamples :samples="viewModel.community" />
      </div>
    </div>

    <footer class="s07-footer">
      <p>
        演示边界：真实 REST/SSE、任务持久化、登录与积分、地图 SDK、供应商调用与端到端链路属于后续
        S08-S14 范围；本页所有数值与政策均标注 Mock / 示例 / 估算 / 待核验，不得作为真实信息使用。
      </p>
    </footer>
  </div>
</template>

<style scoped>
.s07-shell {
  display: grid;
  min-width: 0;
  gap: 20px;
  padding: 20px clamp(16px, 4vw, 32px) 40px;
}

.s07-header {
  display: grid;
  gap: 16px;
  padding: clamp(16px, 4vw, 32px);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
  grid-template-columns: minmax(0, 1fr);
}

.s07-header__eyebrow {
  margin: 0;
  color: var(--accent);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.1em;
}

.s07-header__text h1 {
  margin: 8px 0 10px;
  color: var(--ink);
  font-size: clamp(24px, 4vw, 36px);
  line-height: 1.2;
  overflow-wrap: anywhere;
}

.s07-header__text p {
  margin: 0;
  color: var(--ink-secondary);
  line-height: 1.7;
  overflow-wrap: anywhere;
}

.s07-header__meta {
  display: grid;
  gap: 10px;
  margin: 0;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
}

.s07-header__meta div {
  min-width: 0;
  padding: 12px;
  border-radius: var(--radius-md);
  background: var(--surface-soft);
}

.s07-header__meta dt {
  color: var(--ink-muted);
  font-size: 12px;
}

.s07-header__meta dd {
  margin: 4px 0 0;
  color: var(--ink);
  font-weight: 650;
  overflow-wrap: anywhere;
}

.s07-layout {
  display: grid;
  min-width: 0;
  gap: 16px;
  align-items: start;
  grid-template-columns: minmax(0, 1fr);
}

.s07-column {
  display: grid;
  min-width: 0;
  gap: 16px;
}

.s07-panel {
  min-width: 0;
  padding: clamp(16px, 3vw, 24px);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
}

.s07-panel h2 {
  margin: 0;
  color: var(--ink);
  font-size: 20px;
}

.s07-panel > p {
  margin: 6px 0 0;
  color: var(--ink-secondary);
  font-size: 14px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.s07-envelope,
.s07-summary {
  display: grid;
  gap: 10px;
  margin: 14px 0 0;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
}

.s07-envelope div,
.s07-summary div {
  min-width: 0;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--surface-soft);
}

.s07-envelope dt,
.s07-summary dt {
  color: var(--ink-muted);
  font-size: 12px;
}

.s07-envelope dd,
.s07-summary dd {
  margin: 4px 0 0;
  color: var(--ink);
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 13px;
  overflow-wrap: anywhere;
}

.s07-summary dd {
  font-family: inherit;
}

.s07-envelope__extra,
.s07-envelope__sources {
  margin-top: 14px;
}

.s07-envelope__sources h3 {
  margin: 0 0 6px;
  color: var(--ink);
  font-size: 15px;
}

.s07-envelope__message {
  margin: 6px 0 0;
  color: var(--ink-secondary);
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.s07-envelope__sources ul {
  display: grid;
  margin: 6px 0 0;
  padding: 0;
  gap: 6px;
  list-style: none;
}

.s07-envelope__sources li {
  padding: 8px 10px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius-md);
  color: var(--ink-secondary);
  font-size: 13px;
  overflow-wrap: anywhere;
}

.s07-footer {
  margin: 0 clamp(16px, 4vw, 32px);
  padding: 14px 16px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius-lg);
  color: var(--ink-secondary);
  background: var(--surface-soft);
}

.s07-footer p {
  margin: 0;
  font-size: 13px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

@media (min-width: 1080px) {
  .s07-layout {
    grid-template-columns: minmax(0, 380px) minmax(0, 1fr);
  }

  .s07-header {
    grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
    align-items: start;
  }
}
</style>
