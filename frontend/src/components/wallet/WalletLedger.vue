<script setup lang="ts">
import type { LedgerPage } from '@/api/wallet'

defineProps<{ page: LedgerPage; loading: boolean; showOwner?: boolean }>()
const emit = defineEmits<{ next: [offset: number] }>()

const labels: Record<string, string> = {
  GRANT: '赠送',
  RESERVE: '任务预扣',
  CONFIRM: '确认消费',
  REFUND: '已退款',
  TOP_UP: '模拟充值',
  ADMIN_ADJUST: '管理员调整',
}
const date = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium' })
</script>

<template>
  <p v-if="page.entries.length === 0">暂无积分流水。</p>
  <ul v-else class="ledger">
    <li v-for="entry in page.entries" :key="entry.id">
      <div>
        <strong>{{ labels[entry.entryType] ?? '其他流水' }}</strong>
        <time :datetime="entry.createdAt">{{ date.format(new Date(entry.createdAt)) }}</time>
        <small v-if="showOwner">账户 {{ entry.userId }}</small>
        <small v-if="entry.taskId">任务 {{ entry.taskId }}</small>
      </div>
      <div>
        <strong>{{ entry.pointsDelta > 0 ? '+' : '' }}{{ entry.pointsDelta }} 积分</strong>
        <small>入账后余额 {{ entry.balanceAfter }}</small>
      </div>
    </li>
  </ul>
  <button
    v-if="page.nextOffset !== null"
    type="button"
    :disabled="loading"
    @click="emit('next', page.nextOffset)"
  >
    下一页
  </button>
</template>

<style scoped>
.ledger {
  margin: 8px 0;
  padding: 0;
  list-style: none;
}

.ledger li {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px 16px;
  padding: 10px 0;
  border-bottom: 1px solid var(--line);
}

.ledger li > div {
  display: grid;
  gap: 2px;
  min-width: 0;
  overflow-wrap: anywhere;
}

.ledger time,
.ledger small {
  color: var(--ink-secondary);
}
</style>
