<script setup lang="ts">
import type { MapPoint } from '@/fixtures/s07'

const props = defineProps<{
  points: MapPoint[]
}>()
</script>

<template>
  <section class="map-placeholder" data-testid="map-placeholder" aria-labelledby="map-heading">
    <header class="map-placeholder__head">
      <h2 id="map-heading">地图占位（未接入地图服务）</h2>
      <p>
        本区块不使用地图 SDK、不加载外部瓦片或图片，也不展示任何真实坐标。点位仅以合成 POI ID
        和示意位置列出，避免伪造真实地图能力。
      </p>
    </header>

    <div class="map-placeholder__canvas" role="img" aria-label="静态地图占位示意图，无真实坐标">
      <span
        v-for="point in props.points"
        :key="point.id"
        class="map-placeholder__dot"
        aria-hidden="true"
      >
        {{ point.id.slice(-3) }}
      </span>
    </div>

    <p v-if="props.points.length === 0" class="map-placeholder__empty">
      暂无可列出的示意点位：这是空态，不用假坐标填充。
    </p>

    <ul v-else class="map-placeholder__list">
      <li v-for="point in props.points" :key="point.id">
        <span class="map-placeholder__name">{{ point.name }}</span>
        <span class="map-placeholder__meta"
          >{{ point.id }} · {{ point.day }} · {{ point.position }}</span
        >
      </li>
    </ul>
  </section>
</template>

<style scoped>
.map-placeholder {
  display: grid;
  min-width: 0;
  gap: 14px;
  padding: clamp(16px, 3vw, 24px);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
}

.map-placeholder__head h2 {
  margin: 0;
  color: var(--ink);
  font-size: 20px;
}

.map-placeholder__head p {
  margin: 6px 0 0;
  color: var(--ink-secondary);
  font-size: 14px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.map-placeholder__canvas {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-content: flex-start;
  min-height: 140px;
  padding: 14px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius-md);
  background: repeating-linear-gradient(45deg, #f1f5f9, #f1f5f9 12px, #e8eef6 12px, #e8eef6 24px);
}

.map-placeholder__dot {
  display: grid;
  width: 44px;
  height: 44px;
  border: 2px solid #0f766e;
  border-radius: 50%;
  color: #0f766e;
  background: #ffffff;
  font-size: 12px;
  font-weight: 700;
  place-items: center;
}

.map-placeholder__empty {
  margin: 0;
  padding: 12px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius-md);
  color: var(--ink-secondary);
  background: var(--surface-soft);
}

.map-placeholder__list {
  display: grid;
  margin: 0;
  padding: 0;
  gap: 8px;
  list-style: none;
}

.map-placeholder__list li {
  display: grid;
  min-width: 0;
  gap: 2px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--surface-soft);
}

.map-placeholder__name {
  color: var(--ink);
  font-weight: 650;
  overflow-wrap: anywhere;
}

.map-placeholder__meta {
  color: var(--ink-muted);
  font-size: 12px;
  overflow-wrap: anywhere;
}
</style>
