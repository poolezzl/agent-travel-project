<script setup lang="ts">
import type { RevisionMessage } from '@/stores/revision'

defineProps<{
  messages: RevisionMessage[]
}>()
</script>

<template>
  <div class="conversation">
    <div
      v-for="(message, index) in messages"
      :key="message.id"
      class="message"
      :class="`message--${message.role}`"
      :data-testid="`revision-message-${index}`"
    >
      <div class="message__meta">
        <span class="message__role">{{ message.role === 'user' ? '我' : '助手' }}</span>
        <span class="message__time">{{ message.createdAtLocal }}</span>
      </div>
      <div class="message__body">
        <template v-if="message.kind === 'query'">
          <p class="message__text">{{ message.text }}</p>
          <span class="message__badge message__badge--query" data-testid="revision-query-badge">
            仅问答 · 未创建新版本 · 未扣费
          </span>
        </template>

        <template v-else-if="message.kind === 'clarify'">
          <p class="message__text">{{ message.text }}</p>
          <span class="message__badge message__badge--clarify" data-testid="revision-clarify">
            需要澄清 · 尚未修改
          </span>
          <p class="message__hint">补充说明后重新发送即可继续。</p>
        </template>

        <template v-else-if="message.kind === 'change'">
          <p class="message__text">{{ message.text }}</p>
          <p class="message__hint">请在确认弹窗中核对影响范围后再决定是否修改。</p>
        </template>

        <template v-else-if="message.kind === 'error'">
          <p class="message__text message__text--error">{{ message.text }}</p>
        </template>

        <template v-else>
          <p class="message__text">{{ message.text }}</p>
          <p v-if="message.summary" class="message__summary">{{ message.summary }}</p>
        </template>
      </div>
    </div>

    <p v-if="messages.length === 0" class="conversation__empty" data-testid="revision-empty">
      输入问题或修改要求，服务端会判定问答 / 修改 / 澄清。
    </p>
  </div>
</template>

<style scoped>
.conversation {
  display: grid;
  gap: 12px;
  min-width: 0;
}

.conversation__empty {
  margin: 0;
  padding: 12px 14px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius-md);
  color: var(--ink-muted);
  font-size: 13px;
}

.message {
  display: grid;
  gap: 6px;
}

.message--user .message__body {
  background: #e6f2f1;
  border-color: #bfe0dd;
}

.message--assistant .message__body {
  background: var(--surface-soft);
}

.message__meta {
  display: flex;
  gap: 8px;
  align-items: baseline;
  color: var(--ink-muted);
  font-size: 12px;
}

.message__role {
  font-weight: 650;
}

.message__body {
  display: grid;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  min-width: 0;
}

.message__text {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.6;
}

.message__text--error {
  color: #7f1d1d;
}

.message__summary {
  margin: 0;
  color: var(--ink-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.message__hint {
  margin: 0;
  color: var(--ink-muted);
  font-size: 12px;
  line-height: 1.5;
}

.message__badge {
  justify-self: start;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 650;
}

.message__badge--query {
  color: #14532d;
  background: #dcfce7;
}

.message__badge--clarify {
  color: #713b00;
  background: #fff4cf;
}
</style>
