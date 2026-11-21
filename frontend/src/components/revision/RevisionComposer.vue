<script setup lang="ts">
import { computed, ref } from 'vue'

const props = defineProps<{
  text: string
  loading: boolean
}>()

const emit = defineEmits<{
  (event: 'update:text', value: string): void
  (event: 'send'): void
}>()

const textarea = ref<HTMLTextAreaElement | null>(null)

const maxLength = 2000
const count = computed(() => props.text.length)
const canSend = computed(() => props.text.trim().length > 0 && !props.loading)

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    if (canSend.value) emit('send')
  }
}
</script>

<template>
  <div class="composer">
    <textarea
      ref="textarea"
      class="composer__input"
      :value="text"
      rows="3"
      :maxlength="maxLength"
      data-testid="revision-input"
      aria-label="行程问答或修改"
      placeholder="可问可改：例如「第二天换成天坛」「这样安排会不会太赶」；Shift+Enter 换行，Enter 发送"
      :disabled="loading"
      @input="emit('update:text', ($event.target as HTMLTextAreaElement).value)"
      @keydown="onKeydown"
    />
    <div class="composer__footer">
      <span class="composer__count" :class="{ 'composer__count--over': count >= maxLength }">
        {{ count }}/{{ maxLength }}
      </span>
      <button
        class="button button--primary composer__send"
        type="button"
        data-testid="revision-send"
        :disabled="!canSend"
        @click="emit('send')"
      >
        {{ loading ? '处理中…' : '发送' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.composer {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.composer__input {
  width: 100%;
  min-height: 84px;
  padding: 10px 12px;
  resize: vertical;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-md);
  color: var(--ink);
  background: var(--surface);
  line-height: 1.6;
}

.composer__input:focus-visible {
  outline: 3px solid var(--focus);
  outline-offset: 2px;
}

.composer__input:disabled {
  color: var(--ink-muted);
  background: var(--surface-soft);
}

.composer__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.composer__count {
  color: var(--ink-muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.composer__count--over {
  color: #b91c1c;
}

.button {
  min-height: 40px;
  padding: 8px 18px;
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
