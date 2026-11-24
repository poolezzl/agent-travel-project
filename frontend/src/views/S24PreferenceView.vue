<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { toErrorInfo, type ErrorInfo } from '@/api/http'
import {
  clearPreferences,
  compareTripVersions,
  historyTrips,
  preferenceSummary,
  reuseTrip,
  type HistoricalTrip,
  type PreferenceSummary,
  type TripFilter,
  type VersionComparison,
} from '@/api/preference'
import AuthPanel from '@/components/planning/AuthPanel.vue'
import ErrorPanel from '@/components/planning/ErrorPanel.vue'
import { useSessionStore } from '@/stores/session'

const session = useSessionStore()
const trips = ref<HistoricalTrip[]>([])
const summary = ref<PreferenceSummary | null>(null)
const filter = ref<TripFilter>({ limit: 20, offset: 0 })
const searchText = ref('')
const statusFilter = ref('')
const busy = ref(false)
const error = ref<ErrorInfo | null>(null)
const message = ref('')
const comparison = ref<VersionComparison | null>(null)
const comparingTrip = ref<string>('')

async function load(): Promise<void> {
  if (!session.user) return
  error.value = null
  try {
    const activeFilter: TripFilter = { ...filter.value }
    if (searchText.value) activeFilter.q = searchText.value
    if (statusFilter.value) activeFilter.status = statusFilter.value
    const [tripList, preference] = await Promise.all([
      historyTrips(activeFilter),
      preferenceSummary(),
    ])
    trips.value = tripList
    summary.value = preference
  } catch (cause) {
    error.value = toErrorInfo(cause)
  }
}

async function reuse(trip: HistoricalTrip): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = null
  message.value = ''
  try {
    const key = 'reuse-' + trip.id + '-' + Date.now()
    const result = await reuseTrip(trip.id, key)
    message.value = '已生成新草稿，清理了 ' + result.removedFields.length + ' 类私密字段。'
    await load()
  } catch (cause) {
    error.value = toErrorInfo(cause)
  } finally {
    busy.value = false
  }
}

async function compare(trip: HistoricalTrip): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = null
  try {
    comparison.value = await compareTripVersions(trip.id)
    comparingTrip.value = trip.title
  } catch (cause) {
    error.value = toErrorInfo(cause)
  } finally {
    busy.value = false
  }
}

async function clear(): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = null
  message.value = ''
  try {
    const key = 'clear-' + Date.now()
    await clearPreferences(key)
    message.value = '偏好画像已清空，历史记录保留。'
    await load()
  } catch (cause) {
    error.value = toErrorInfo(cause)
  } finally {
    busy.value = false
  }
}

function formatDate(value: string): string {
  return value ? value.slice(0, 10) : ''
}

function formatValue(value: unknown): string {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

onMounted(load)
</script>

<template>
  <main class="s24-page">
    <h1>历史行程与偏好画像</h1>

    <AuthPanel v-if="!session.user" />
    <template v-else>
      <ErrorPanel v-if="error" :error="error" />
      <p v-if="message" class="notice">{{ message }}</p>

      <section class="panel">
        <h2>偏好画像</h2>
        <template v-if="summary && summary.eligible">
          <p class="narrative">{{ summary.narrative }}</p>
          <p class="meta">基于 {{ summary.sampleCount }} 条历史行程 · 版本 {{ summary.summaryVersion }}</p>
          <ul class="dimensions">
            <li v-for="item in summary.dimensions" :key="item.key">
              <strong>{{ item.key }}</strong>
              <span>{{ JSON.stringify(item.valueJson) }}</span>
              <span class="weight">权重 {{ item.weight }}</span>
            </li>
          </ul>
          <button type="button" :disabled="busy" @click="clear">清空偏好画像</button>
        </template>
        <p v-else class="empty">
          历史行程不足 3 条，暂不生成画像。当前 {{ summary?.sampleCount ?? 0 }} 条。
        </p>
      </section>

      <section class="panel">
        <h2>历史行程</h2>
        <div class="filters">
          <input v-model="searchText" type="search" placeholder="搜索标题" @keyup.enter="load" />
          <select v-model="statusFilter" @change="load">
            <option value="">全部状态</option>
            <option value="DRAFT">草稿</option>
            <option value="ACTIVE">已定稿</option>
            <option value="ARCHIVED">已归档</option>
          </select>
          <button type="button" @click="load">查询</button>
        </div>
        <ul class="trips">
          <li v-for="trip in trips" :key="trip.id">
            <div>
              <strong>{{ trip.title }}</strong>
              <span class="meta">{{ formatDate(trip.startDate) }} 至 {{ formatDate(trip.endDate) }}</span>
              <span class="meta">{{ trip.cities.join('、') || '未记录城市' }} · {{ trip.versionCount }} 个版本</span>
            </div>
            <span class="actions">
              <button type="button" :disabled="busy || trip.versionCount < 2" @click="compare(trip)">版本对比</button>
              <button type="button" :disabled="busy" @click="reuse(trip)">再来一单</button>
            </span>
          </li>
        </ul>
        <p v-if="trips.length === 0" class="empty">没有符合条件的历史行程。</p>
      </section>

      <section v-if="comparison" class="panel">
        <h2>版本演进对比 · {{ comparingTrip }}</h2>
        <p class="meta">
          版本 {{ comparison.from.versionNo }}（{{ comparison.from.versionStatus }}）
          → 版本 {{ comparison.to.versionNo }}（{{ comparison.to.versionStatus }}）
        </p>
        <p v-if="comparison.changes.length === 0" class="empty">两个版本内容一致。</p>
        <table v-else class="diff-table">
          <thead>
            <tr><th>字段</th><th>变化</th><th>旧值</th><th>新值</th></tr>
          </thead>
          <tbody>
            <tr v-for="change in comparison.changes" :key="change.path">
              <td><code>{{ change.path }}</code></td>
              <td>{{ change.changeType }}</td>
              <td>{{ formatValue(change.before) }}</td>
              <td>{{ formatValue(change.after) }}</td>
            </tr>
          </tbody>
        </table>
      </section>
    </template>
  </main>
</template>

<style scoped>
.s24-page {
  max-width: 860px;
  margin: 0 auto;
  padding: 24px;
}
.panel {
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: 8px;
  padding: 16px;
  margin: 16px 0;
}
.narrative {
  font-size: 15px;
  line-height: 1.7;
}
.meta {
  color: #909399;
  font-size: 12px;
  margin-right: 8px;
}
.dimensions,
.trips {
  list-style: none;
  padding: 0;
}
.dimensions li,
.trips li {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
}
.weight {
  color: #909399;
}
.actions {
  display: flex;
  gap: 8px;
}
.diff-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.diff-table th,
.diff-table td {
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
  padding: 6px 8px;
  text-align: left;
  vertical-align: top;
}
.diff-table code {
  color: #409eff;
}
.filters {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.filters input,
.filters select {
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 6px 8px;
}
button {
  border: 1px solid #409eff;
  color: #409eff;
  background: transparent;
  border-radius: 4px;
  padding: 6px 12px;
  cursor: pointer;
}
button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.notice {
  color: #67c23a;
}
.empty {
  color: #909399;
}
</style>
