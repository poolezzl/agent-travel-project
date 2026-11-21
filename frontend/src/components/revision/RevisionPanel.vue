<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, watch } from 'vue'

import ConfirmRevisionDialog from '@/components/revision/ConfirmRevisionDialog.vue'
import ProtectedPathsPicker from '@/components/revision/ProtectedPathsPicker.vue'
import RevisionComposer from '@/components/revision/RevisionComposer.vue'
import RevisionConflictBanner from '@/components/revision/RevisionConflictBanner.vue'
import RevisionConversation from '@/components/revision/RevisionConversation.vue'
import RevisionDiffView from '@/components/revision/RevisionDiffView.vue'
import VersionTimeline from '@/components/revision/VersionTimeline.vue'
import { usePlanningStore } from '@/stores/planning'
import { useRevisionStore } from '@/stores/revision'

const planning = usePlanningStore()
const revision = useRevisionStore()

const { plan } = storeToRefs(planning)
const {
  inputText,
  lockedPaths,
  messages,
  draft,
  timeline,
  conflict,
  lastApplied,
  error,
  previewLoading,
  confirmLoading,
  rollbackLoading,
  refreshingPlan,
  phase,
  pending,
  baseVersion,
  currentVersionId,
} = storeToRefs(revision)

const composerDisabled = computed(
  () => previewLoading.value || confirmLoading.value || rollbackLoading.value,
)

// 切到不同 trip 时重置会话内状态（本面板只服务当前 trip），并把必去点预填为默认保护项。
watch(
  () => plan.value?.trip_id ?? null,
  () => {
    revision.reset()
    revision.applyDefaultLockedPaths(plan.value)
  },
  { immediate: true },
)
</script>

<template>
  <section class="revision-panel" data-testid="revision-panel" aria-labelledby="revision-heading">
    <header class="revision-panel__header">
      <h2 id="revision-heading" class="revision-panel__title">行程问答与修改</h2>
      <p class="revision-panel__base">基准版本 {{ baseVersion }}</p>
    </header>

    <div
      v-if="plan && plan.degraded_fields.length"
      class="revision-panel__degraded"
      data-testid="revision-degraded"
    >
      降级字段（修订不影响既有降级提示）：{{ plan.degraded_fields.join('、') }}
    </div>

    <RevisionConflictBanner
      v-if="conflict"
      :conflict="conflict"
      :refreshing="refreshingPlan"
      @re-preview="revision.rePreview"
    />

    <RevisionConversation :messages="messages" />

    <RevisionDiffView
      v-if="lastApplied"
      :changes="lastApplied.changes"
      :plan="plan"
      :before-days="lastApplied.beforeDays"
      :unaffected-days="lastApplied.verifiedUnchanged"
      :unverified-days="lastApplied.unverified"
    />

    <ProtectedPathsPicker
      v-if="plan"
      :plan="plan"
      :model-value="lockedPaths"
      :disabled="composerDisabled"
      @update:model-value="revision.setLockedPaths"
    />

    <RevisionComposer
      :text="inputText"
      :loading="composerDisabled"
      @update:text="revision.setInput"
      @send="revision.submit"
    />

    <div
      v-if="error && phase === 'failed'"
      class="revision-panel__error"
      data-testid="revision-error"
    >
      <p class="revision-panel__error-text">{{ error.message }}</p>
      <p v-if="error.traceId" class="revision-panel__error-trace">traceId：{{ error.traceId }}</p>
      <button class="button" type="button" data-testid="revision-retry" @click="revision.retry">
        重试
      </button>
    </div>

    <VersionTimeline
      :timeline="timeline"
      :current-version-id="currentVersionId"
      :rolling-back="rollbackLoading"
      @rollback="revision.rollback"
    />

    <ConfirmRevisionDialog
      v-if="pending && plan"
      :result="pending"
      :plan="plan"
      :locked-paths="draft?.lockedPaths ?? lockedPaths"
      :confirming="confirmLoading"
      @confirm="revision.confirmChange"
      @decline="revision.declineConfirm"
    />
  </section>
</template>

<style scoped>
.revision-panel {
  display: grid;
  gap: 14px;
  min-width: 0;
  padding: clamp(16px, 3vw, 24px);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
}

.revision-panel__header {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  align-items: baseline;
  justify-content: space-between;
}

.revision-panel__title {
  margin: 0;
  color: var(--ink);
  font-size: 20px;
}

.revision-panel__base {
  margin: 0;
  color: var(--ink-muted);
  font-size: 13px;
  overflow-wrap: anywhere;
}

.revision-panel__degraded {
  padding: 10px 12px;
  border: 1px solid #d97706;
  border-radius: var(--radius-md);
  color: #713b00;
  background: #fff4cf;
  font-size: 13px;
  line-height: 1.6;
}

.revision-panel__error {
  display: grid;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid #dc2626;
  border-radius: var(--radius-md);
  color: #7f1d1d;
  background: #fef2f2;
}

.revision-panel__error-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
}

.revision-panel__error-trace {
  margin: 0;
  color: #9f1239;
  font-size: 12px;
  overflow-wrap: anywhere;
}

.button {
  min-height: 38px;
  padding: 8px 16px;
  border: 1px solid var(--line-strong);
  border-radius: 999px;
  color: var(--ink);
  background: var(--surface-soft);
  font-weight: 650;
  cursor: pointer;
  justify-self: start;
}

.button:focus-visible {
  outline: 3px solid var(--focus);
  outline-offset: 2px;
}
</style>
