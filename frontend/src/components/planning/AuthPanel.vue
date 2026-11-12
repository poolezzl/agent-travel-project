<script setup lang="ts">
import { computed, ref } from 'vue'
import { storeToRefs } from 'pinia'

import type { ErrorInfo } from '@/api/http'
import { useSessionStore } from '@/stores/session'

type Mode = 'login' | 'register'

const session = useSessionStore()
const { user, loading, error } = storeToRefs(session)

const mode = ref<Mode>('login')
const loginName = ref('')
const password = ref('')
const localError = ref<string | null>(null)

const submitLabel = computed(() => {
  if (loading.value) return mode.value === 'login' ? '正在登录…' : '正在注册…'
  return mode.value === 'login' ? '登录' : '注册并登录'
})

function friendlyMessage(err: ErrorInfo | null): string {
  if (!err) return ''
  if (err.status === 423) return '账号已被锁定，请稍后再试或联系管理员。'
  if (err.status === 429) return '请求过于频繁，请稍后重试。'
  if (err.status === 401) return '登录名或密码错误。'
  if (err.status === 409) return '该登录名已存在，请直接登录或更换登录名。'
  if (err.code === 'UNAUTHENTICATED') return '登录状态已失效，请重新登录。'
  return err.message
}

function switchMode(next: Mode): void {
  mode.value = next
  localError.value = null
  session.clearSession()
}

async function handleSubmit(): Promise<void> {
  localError.value = null
  const name = loginName.value.trim()
  if (name.length === 0) {
    localError.value = '请输入登录名。'
    return
  }
  if (mode.value === 'register' && password.value.length < 12) {
    localError.value = '注册密码至少 12 位。'
    return
  }
  if (password.value.length === 0) {
    localError.value = '请输入密码。'
    return
  }
  try {
    if (mode.value === 'login') await session.login(name, password.value)
    else await session.register(name, password.value)
    password.value = ''
  } catch {
    // 错误已写入 store.error，UI 展示 friendlyMessage。
  }
}

async function handleLogout(): Promise<void> {
  await session.logout()
}
</script>

<template>
  <section class="auth-panel" data-testid="auth-panel" aria-labelledby="auth-heading">
    <h2 id="auth-heading">登录 / 注册</h2>

    <div v-if="!user" class="auth-panel__form">
      <div class="auth-panel__tabs" role="tablist" aria-label="认证方式">
        <button
          type="button"
          role="tab"
          :aria-selected="mode === 'login'"
          :class="{ 'is-active': mode === 'login' }"
          @click="switchMode('login')"
        >
          登录
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="mode === 'register'"
          :class="{ 'is-active': mode === 'register' }"
          @click="switchMode('register')"
        >
          注册
        </button>
      </div>

      <form class="auth-panel__fields" novalidate @submit.prevent="handleSubmit">
        <label class="field">
          <span class="field__label">登录名</span>
          <input
            v-model="loginName"
            class="field__control"
            type="text"
            autocomplete="username"
            :disabled="loading"
          />
        </label>

        <label class="field">
          <span class="field__label">密码{{ mode === 'register' ? '（至少 12 位）' : '' }}</span>
          <input
            v-model="password"
            class="field__control"
            type="password"
            :autocomplete="mode === 'register' ? 'new-password' : 'current-password'"
            :disabled="loading"
          />
        </label>

        <p v-if="localError" class="auth-panel__error" role="alert" data-testid="auth-local-error">
          {{ localError }}
        </p>
        <p v-else-if="error" class="auth-panel__error" role="alert" data-testid="auth-server-error">
          {{ friendlyMessage(error) }}
        </p>

        <div class="auth-panel__actions">
          <button
            class="button button--primary"
            type="submit"
            data-testid="auth-submit"
            :disabled="loading"
          >
            {{ submitLabel }}
          </button>
        </div>
      </form>
    </div>

    <div v-else class="auth-panel__session">
      <p>
        已登录：
        <strong>{{ user.login }}</strong>
        （{{ user.role }}）
      </p>
      <p class="auth-panel__note">
        访问/刷新令牌只驻留内存，不写入本地存储；刷新页面后需重新登录。
      </p>
      <button class="button" type="button" data-testid="logout-button" @click="handleLogout">
        退出登录
      </button>
    </div>
  </section>
</template>

<style scoped>
.auth-panel {
  display: grid;
  min-width: 0;
  gap: 14px;
  padding: clamp(16px, 3vw, 24px);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
}

.auth-panel h2 {
  margin: 0;
  color: var(--ink);
  font-size: 20px;
}

.auth-panel__form {
  display: grid;
  gap: 14px;
}

.auth-panel__tabs {
  display: flex;
  gap: 8px;
}

.auth-panel__tabs button {
  min-height: 40px;
  padding: 8px 18px;
  border: 1px solid var(--line-strong);
  border-radius: 999px;
  color: var(--ink);
  background: var(--surface-soft);
  cursor: pointer;
}

.auth-panel__tabs button.is-active {
  border-color: var(--accent);
  color: #ffffff;
  background: var(--accent);
}

.auth-panel__fields {
  display: grid;
  gap: 12px;
}

.field {
  display: grid;
  gap: 6px;
}

.field__label {
  color: var(--ink-secondary);
  font-size: 13px;
  font-weight: 650;
}

.field__control {
  width: 100%;
  min-height: 42px;
  padding: 9px 11px;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-md);
  color: var(--ink);
  background: var(--surface-soft);
}

.field__control:focus-visible {
  border-color: var(--accent);
  outline: 3px solid var(--focus);
  outline-offset: 1px;
}

.auth-panel__error {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid #c2413b;
  border-radius: var(--radius-md);
  color: #7f1d1d;
  background: #fdeceb;
  font-size: 14px;
  line-height: 1.6;
}

.auth-panel__actions {
  display: flex;
}

.auth-panel__session {
  display: grid;
  gap: 10px;
}

.auth-panel__session p {
  margin: 0;
  color: var(--ink-secondary);
  line-height: 1.6;
}

.auth-panel__note {
  font-size: 13px;
  color: var(--ink-muted);
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
  justify-self: start;
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
