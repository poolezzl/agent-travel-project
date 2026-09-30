<script setup lang="ts">
import type { DayPlan } from '@/fixtures/s07'

import EvidenceBadges from './EvidenceBadges.vue'

const props = defineProps<{
  days: DayPlan[]
}>()
</script>

<template>
  <section class="timeline" data-testid="schedule-timeline" aria-labelledby="timeline-heading">
    <header class="timeline__head">
      <h2 id="timeline-heading">演示时间轴（合成点位）</h2>
      <p>
        每个节点的时长、天气、预约与费用均为
        <strong>Mock / 示例 / 估算 / 待核验</strong>，不使用空值补齐，也不构成
        真实开放时间、票价或政策。
      </p>
    </header>

    <p v-if="props.days.length === 0" class="timeline__empty">
      当前没有可展示的时间轴节点：这是空态，不用占位数字填充。
    </p>

    <article v-for="day in props.days" :key="day.date" class="timeline__day">
      <header class="timeline__day-head">
        <h3>{{ day.dayLabel }}</h3>
        <p class="timeline__weather">
          {{ day.weather }}
        </p>
      </header>
      <EvidenceBadges :evidence="day.weatherEvidence" />

      <ol class="timeline__items">
        <li
          v-for="item in day.items"
          :key="item.id"
          class="timeline__item"
          data-testid="timeline-item"
        >
          <p class="timeline__time">
            <span class="timeline__clock">{{ item.time }}</span>
            <span class="timeline__poi">{{ item.poiName }}</span>
            <span class="timeline__poi-id">POI ID：{{ item.poiId }}</span>
          </p>
          <p class="timeline__activity">{{ item.activity }}</p>
          <dl class="timeline__facts">
            <div>
              <dt>交通</dt>
              <dd>{{ item.transport }}</dd>
            </div>
            <div>
              <dt>时长</dt>
              <dd>{{ item.duration }}</dd>
            </div>
            <div>
              <dt>天气</dt>
              <dd>{{ item.weather }}</dd>
            </div>
            <div>
              <dt>预约</dt>
              <dd>{{ item.reservation }}</dd>
            </div>
            <div>
              <dt>费用</dt>
              <dd>{{ item.price }}</dd>
            </div>
          </dl>
          <EvidenceBadges :evidence="item.evidence" />
        </li>
      </ol>
    </article>
  </section>
</template>

<style scoped>
.timeline {
  display: grid;
  min-width: 0;
  gap: 16px;
  padding: clamp(16px, 3vw, 24px);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
}

.timeline__head h2 {
  margin: 0;
  color: var(--ink);
  font-size: 20px;
}

.timeline__head p {
  margin: 6px 0 0;
  color: var(--ink-secondary);
  font-size: 14px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.timeline__empty {
  margin: 0;
  padding: 14px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius-md);
  color: var(--ink-secondary);
  background: var(--surface-soft);
  line-height: 1.6;
}

.timeline__day {
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--surface-soft);
}

.timeline__day-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  align-items: baseline;
  justify-content: space-between;
}

.timeline__day-head h3 {
  margin: 0;
  color: var(--ink);
  font-size: 17px;
}

.timeline__weather {
  margin: 0;
  color: var(--ink-secondary);
  font-size: 13px;
  overflow-wrap: anywhere;
}

.timeline__items {
  display: grid;
  margin: 12px 0 0;
  padding: 0;
  gap: 12px;
  list-style: none;
}

.timeline__item {
  min-width: 0;
  padding: 12px;
  border-left: 4px solid var(--accent);
  border-radius: var(--radius-md);
  background: var(--surface);
}

.timeline__time {
  display: flex;
  flex-wrap: wrap;
  margin: 0;
  gap: 8px 12px;
  align-items: baseline;
}

.timeline__clock {
  font-variant-numeric: tabular-nums;
  color: #0f766e;
  font-weight: 800;
}

.timeline__poi {
  color: var(--ink);
  font-weight: 700;
  overflow-wrap: anywhere;
}

.timeline__poi-id {
  color: var(--ink-muted);
  font-size: 12px;
  overflow-wrap: anywhere;
}

.timeline__activity {
  margin: 6px 0 0;
  color: var(--ink-secondary);
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.timeline__facts {
  display: grid;
  margin: 10px 0 0;
  gap: 6px;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
}

.timeline__facts div {
  min-width: 0;
}

.timeline__facts dt {
  color: var(--ink-muted);
  font-size: 12px;
}

.timeline__facts dd {
  margin: 2px 0 0;
  color: var(--ink-secondary);
  font-size: 13px;
  overflow-wrap: anywhere;
}
</style>
