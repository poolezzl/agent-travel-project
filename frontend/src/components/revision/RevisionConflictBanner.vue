<script setup lang="ts">
import type { ConflictInfo } from '@/stores/revision'

defineProps<{
  conflict: ConflictInfo
  refreshing: boolean
}>()

defineEmits<{
  (event: 're-preview'): void
}>()
</script>

<template>
  <div class="conflict-banner" role="alert" data-testid="conflict-banner">
    <p class="conflict-banner__title">行程版本已变化</p>
    <p class="conflict-banner__detail">
      <template v-if="conflict.serverVersionNo !== undefined">
        当前服务端为 v{{ conflict.serverVersionNo }}
      </template>
      <template v-else>已重新加载最新版本</template>
      <template v-if="refreshing"> · 正在刷新…</template>
    </p>
    <p class="conflict-banner__meta">
      <template v-if="conflict.changedDays.length > 0">
        相比你基于的版本，第 {{ conflict.changedDays.join('、') }} 天内容已变化
      </template>
      <template v-else>版本号已更新，内容未变化</template>
    </p>
    <p v-if="conflict.serverAt" class="conflict-banner__meta">
      服务端版本时间：{{ conflict.serverAt }}
    </p>
    <p v-if="conflict.traceId" class="conflict-banner__meta">traceId：{{ conflict.traceId }}</p>
    <p class="conflict-banner__note">
      基准版本已变化，请重新预览后再确认；若是回滚操作，请从版本时间线重新发起回滚。
    </p>
    <div v-if="conflict.kind !== 'rollback'" class="conflict-banner__actions">
      <button
        class="button button--primary"
        type="button"
        data-testid="conflict-reload"
        @click="$emit('re-preview')"
      >
        重新预览
      </button>
    </div>
  </div>
</template>

<style scoped>
.conflict-banner {
  display: grid;
  gap: 8px;
  padding: 12px 14px;
  border: 1px solid #dc2626;
  border-radius: var(--radius-md);
  color: #7f1d1d;
  background: #fef2f2;
}

.conflict-banner__title {
  margin: 0;
  font-weight: 700;
}

.conflict-banner__detail,
.conflict-banner__note,
.conflict-banner__meta {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
}

.conflict-banner__meta {
  color: #9f1239;
  overflow-wrap: anywhere;
}

.conflict-banner__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 2px;
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
</style>
