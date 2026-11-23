<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'

import { toErrorInfo, type ErrorInfo } from '@/api/http'
import { balance } from '@/api/points'
import * as wallet from '@/api/wallet'
import type { LedgerPage, TopUpPackage, WalletQuote } from '@/api/wallet'
import AuthPanel from '@/components/planning/AuthPanel.vue'
import ErrorPanel from '@/components/planning/ErrorPanel.vue'
import WalletLedger from '@/components/wallet/WalletLedger.vue'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
const { user } = storeToRefs(session)
const admin = computed(() => user.value?.role === 'ADMIN')
const packages = ref<TopUpPackage[]>([])
const balancePoints = ref<number | null>(null)
const ownLedger = ref<LedgerPage>()
const platformLedger = ref<LedgerPage>()
const accountId = ref('')
const filter = ref('')
const activeFilter = ref('')
const adjustment = ref<number>()
const pending = ref<{ quote: WalletQuote; key: string; admin: boolean }>()
const busy = ref(false)
const reading = ref(false)
const readingAdmin = ref(false)
const readError = ref<ErrorInfo | null>(null)
const adminError = ref<ErrorInfo | null>(null)
const actionError = ref<ErrorInfo | null>(null)
const success = ref('')
let ownerGeneration = 0
let quoteAttempt: { input: string; key: string } | undefined

async function reload(offset = 0): Promise<void> {
  if (reading.value || !user.value) return
  const generation = ownerGeneration
  reading.value = true
  readError.value = null
  try {
    const [total, options, page] = await Promise.all([
      balance(),
      wallet.packages(),
      wallet.ledger(offset),
    ])
    if (generation !== ownerGeneration) return
    balancePoints.value = total?.balancePoints ?? null
    packages.value = options ?? []
    ownLedger.value = page
  } catch (error) {
    if (generation === ownerGeneration) readError.value = toErrorInfo(error)
  } finally {
    if (generation === ownerGeneration) reading.value = false
  }
}

async function readPlatform(offset: number, userId: string): Promise<void> {
  if (readingAdmin.value || !admin.value) return
  const generation = ownerGeneration
  readingAdmin.value = true
  adminError.value = null
  try {
    const page = await wallet.adminLedger(offset, userId)
    if (generation !== ownerGeneration) return
    platformLedger.value = page
    activeFilter.value = userId
  } catch (error) {
    if (generation === ownerGeneration) adminError.value = toErrorInfo(error)
  } finally {
    if (generation === ownerGeneration) readingAdmin.value = false
  }
}

async function issue(
  input: string,
  request: (key: string) => Promise<WalletQuote | undefined>,
  adminAdjustment = false,
): Promise<void> {
  if (busy.value || pending.value || !user.value) return
  const generation = ownerGeneration
  busy.value = true
  actionError.value = null
  success.value = ''
  try {
    if (quoteAttempt?.input !== input) quoteAttempt = { input, key: crypto.randomUUID() }
    const quote = await request(quoteAttempt.key)
    if (generation !== ownerGeneration) return
    if (!quote) throw new Error('服务未返回积分报价')
    pending.value = { quote, key: crypto.randomUUID(), admin: adminAdjustment }
    quoteAttempt = undefined
  } catch (error) {
    if (generation === ownerGeneration) actionError.value = toErrorInfo(error)
  } finally {
    if (generation === ownerGeneration) busy.value = false
  }
}

function quoteTopUp(code: string): void {
  void issue(code, (key) => wallet.quoteTopUp(code, key))
}

function quoteAdjustment(): void {
  const target = accountId.value.trim()
  const delta = adjustment.value
  if (typeof delta !== 'number' || !Number.isInteger(delta) || delta === 0) {
    actionError.value = { code: 'INVALID_INPUT', message: '请输入非零整数' }
    return
  }
  void issue(
    JSON.stringify([target, delta]),
    (key) => wallet.quoteAdjustment(target, delta, key),
    true,
  )
}

async function confirm(): Promise<void> {
  const entry = pending.value
  if (!entry || busy.value || reading.value || readingAdmin.value || !user.value) return
  const generation = ownerGeneration
  busy.value = true
  actionError.value = null
  try {
    const result = await wallet.confirm(entry.quote.quoteId, entry.key)
    if (generation !== ownerGeneration) return
    if (!result) throw new Error('服务未返回入账结果，可重试确认')
    pending.value = undefined
    success.value = entry.admin ? '积分调整已入账' : '模拟充值已到账'
    if (entry.quote.userId === user.value?.userId) balancePoints.value = result.balancePoints
    await reload()
    if (entry.admin && platformLedger.value) await readPlatform(0, activeFilter.value)
  } catch (error) {
    if (generation === ownerGeneration) actionError.value = toErrorInfo(error)
  } finally {
    if (generation === ownerGeneration) busy.value = false
  }
}

function dismissQuote(): void {
  pending.value = undefined
  actionError.value = null
}

watch(
  () => user.value && `${user.value.userId}:${user.value.role}`,
  () => {
    ownerGeneration++
    balancePoints.value = null
    packages.value = []
    ownLedger.value = undefined
    platformLedger.value = undefined
    accountId.value = ''
    filter.value = ''
    activeFilter.value = ''
    adjustment.value = undefined
    pending.value = undefined
    quoteAttempt = undefined
    busy.value = reading.value = readingAdmin.value = false
    readError.value = adminError.value = actionError.value = null
    success.value = ''
    void reload()
  },
  { immediate: true, flush: 'sync' },
)

onMounted(() => session.ensureRefreshHandler())
</script>

<template>
  <main class="wallet">
    <header class="wallet-header">
      <div>
        <RouterLink to="/">返回行程规划</RouterLink>
        <h1>积分钱包</h1>
      </div>
      <p v-if="user" class="wallet-balance">
        可用积分 <strong data-testid="wallet-balance">{{ balancePoints ?? '—' }}</strong>
      </p>
    </header>

    <AuthPanel v-if="!user" />
    <template v-else>
      <p v-if="reading" role="status">正在读取钱包…</p>
      <ErrorPanel v-if="readError" title="钱包读取失败" :error="readError" />
      <ErrorPanel v-if="actionError" title="积分操作失败" :error="actionError" />
      <p v-if="success" role="status">{{ success }}</p>
      <section aria-labelledby="top-up-heading">
        <h2 id="top-up-heading">模拟充值</h2>
        <p>积分仅供站内使用，无需付款。</p>
        <div class="wallet-actions">
          <button
            v-for="item in packages"
            :key="item.code"
            class="wallet-button"
            type="button"
            :disabled="busy || !!pending"
            @click="quoteTopUp(item.code)"
          >
            {{ item.points }} 积分
          </button>
        </div>
      </section>

      <section v-if="admin" aria-labelledby="adjust-heading">
        <h2 id="adjust-heading">管理员调整</h2>
        <form class="wallet-actions" @submit.prevent="quoteAdjustment">
          <label
            >目标账户编号 <input v-model="accountId" required :disabled="busy || !!pending"
          /></label>
          <label>
            调整积分
            <input
              v-model.number="adjustment"
              type="number"
              step="1"
              required
              :disabled="busy || !!pending"
            />
          </label>
          <button class="wallet-button" :disabled="busy || !!pending">获取调整报价</button>
        </form>
      </section>

      <section v-if="pending" aria-labelledby="wallet-confirm-heading" class="wallet-confirm">
        <h2 id="wallet-confirm-heading">{{ pending.admin ? '确认积分调整' : '确认模拟充值' }}</h2>
        <p v-if="pending.admin">目标账户 {{ pending.quote.userId }}</p>
        <p>
          变动 {{ pending.quote.pointsDelta }} 积分，预计余额
          {{ pending.quote.balanceAfter }} 积分。
        </p>
        <p>报价有效至 {{ new Date(pending.quote.expiresAt).toLocaleTimeString('zh-CN') }}。</p>
        <div class="wallet-actions">
          <button
            class="wallet-button"
            type="button"
            :disabled="busy || reading || readingAdmin"
            @click="confirm"
          >
            {{ busy ? '正在确认…' : '确认入账' }}
          </button>
          <button class="wallet-button" type="button" :disabled="busy" @click="dismissQuote">
            取消报价
          </button>
        </div>
      </section>
      <p v-else-if="busy && !success" role="status">正在获取报价…</p>

      <section aria-labelledby="wallet-ledger-heading">
        <div class="wallet-actions">
          <h2 id="wallet-ledger-heading">我的积分流水</h2>
          <button
            class="wallet-button"
            type="button"
            :disabled="reading || busy"
            @click="!busy && reload()"
          >
            刷新钱包
          </button>
        </div>
        <WalletLedger
          v-if="ownLedger"
          :page="ownLedger"
          :loading="reading || busy"
          @next="!busy && reload($event)"
        />
      </section>

      <section v-if="admin" aria-labelledby="platform-ledger-heading">
        <h2 id="platform-ledger-heading">平台积分流水</h2>
        <form class="wallet-actions" @submit.prevent="!busy && readPlatform(0, filter.trim())">
          <label
            >账户编号（可留空） <input v-model="filter" :disabled="readingAdmin || busy"
          /></label>
          <button class="wallet-button" :disabled="readingAdmin || busy">查询流水</button>
        </form>
        <p v-if="readingAdmin" role="status">正在查询流水…</p>
        <ErrorPanel v-if="adminError" title="流水查询失败" :error="adminError" />
        <WalletLedger
          v-if="platformLedger"
          :page="platformLedger"
          :loading="readingAdmin || busy"
          show-owner
          @next="!busy && readPlatform($event, activeFilter)"
        />
      </section>
    </template>
  </main>
</template>

<style scoped>
.wallet {
  display: grid;
  gap: 16px;
  width: min(100% - 32px, 920px);
  margin: 24px auto;
}

.wallet-header,
.wallet > section {
  padding: clamp(16px, 3vw, 24px);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
}

.wallet-header,
.wallet-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 12px;
}

.wallet-header {
  justify-content: space-between;
}

.wallet h1,
.wallet h2,
.wallet p {
  margin: 0 0 8px;
  overflow-wrap: anywhere;
}

.wallet-balance strong {
  font-size: 1.5em;
}

.wallet-actions label {
  display: grid;
  min-width: 0;
  max-width: 100%;
  gap: 5px;
}

.wallet input,
.wallet-button {
  min-height: 42px;
  max-width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-md);
  color: var(--ink);
  background: var(--surface-soft);
}

.wallet-button {
  cursor: pointer;
}

.wallet-button:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.wallet .wallet-confirm {
  border-color: var(--accent);
}
</style>
