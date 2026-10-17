<script setup lang="ts">
withDefaults(
  defineProps<{
    label?: string
    blocks?: number
  }>(),
  { label: '正在加载演示内容（本地模拟）', blocks: 3 },
)
</script>

<template>
  <section class="loading-skeleton" data-testid="loading-skeleton" role="status" aria-live="polite">
    <p class="loading-skeleton__label">{{ label }}</p>
    <p class="loading-skeleton__hint">
      骨架屏只表示当前页面在等待本地模拟结果，不请求任何真实接口。
    </p>
    <div class="loading-skeleton__blocks" aria-hidden="true">
      <span v-for="index in blocks" :key="index" class="loading-skeleton__block" />
    </div>
  </section>
</template>

<style scoped>
.loading-skeleton {
  display: grid;
  min-width: 0;
  gap: 10px;
  padding: clamp(16px, 3vw, 24px);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
}

.loading-skeleton__label {
  margin: 0;
  color: var(--ink);
  font-weight: 700;
}

.loading-skeleton__hint {
  margin: 0;
  color: var(--ink-muted);
  font-size: 13px;
  line-height: 1.55;
}

.loading-skeleton__blocks {
  display: grid;
  gap: 10px;
}

.loading-skeleton__block {
  height: 18px;
  border-radius: 999px;
  background: linear-gradient(90deg, #e8eef6, #f5f8fc, #e8eef6);
  background-size: 200% 100%;
  animation: loading-skeleton-pulse 1.6s ease-in-out infinite;
}

.loading-skeleton__block:nth-child(2n) {
  width: 82%;
}

.loading-skeleton__block:nth-child(3n) {
  width: 64%;
}

@keyframes loading-skeleton-pulse {
  from {
    background-position: 200% 0;
  }

  to {
    background-position: -200% 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .loading-skeleton__block {
    animation: none;
  }
}
</style>
