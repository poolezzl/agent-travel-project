<script setup lang="ts">
import type { CommunitySample } from '@/fixtures/s07'

const props = defineProps<{
  samples: CommunitySample[]
}>()
</script>

<template>
  <section
    class="community-samples"
    data-testid="community-samples"
    aria-labelledby="community-heading"
  >
    <header class="community-samples__head">
      <h2 id="community-heading">社区静态样张（只读）</h2>
      <p>
        以下为静态样张，作者、标题、标签和互动数字均为合成内容，不实现发布、点赞、评论或复用等写操作。
      </p>
    </header>

    <p v-if="props.samples.length === 0" class="community-samples__empty">
      暂无静态样张：这是空态，不用假内容填充。
    </p>

    <ul v-else class="community-samples__list">
      <li v-for="sample in props.samples" :key="sample.id" class="community-samples__item">
        <p class="community-samples__title">{{ sample.title }}</p>
        <p class="community-samples__meta">{{ sample.author }} · 静态样张 / Mock</p>
        <ul class="community-samples__tags">
          <li v-for="tag in sample.tags" :key="tag">{{ tag }}</li>
        </ul>
        <p class="community-samples__numbers">{{ sample.likes }} · {{ sample.comments }}</p>
        <p class="community-samples__note">{{ sample.note }}</p>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.community-samples {
  display: grid;
  min-width: 0;
  gap: 14px;
  padding: clamp(16px, 3vw, 24px);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  background: var(--surface);
  box-shadow: var(--shadow-soft);
}

.community-samples__head h2 {
  margin: 0;
  color: var(--ink);
  font-size: 20px;
}

.community-samples__head p {
  margin: 6px 0 0;
  color: var(--ink-secondary);
  font-size: 14px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.community-samples__empty {
  margin: 0;
  padding: 12px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius-md);
  color: var(--ink-secondary);
  background: var(--surface-soft);
}

.community-samples__list {
  display: grid;
  margin: 0;
  padding: 0;
  gap: 12px;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  list-style: none;
}

.community-samples__item {
  display: grid;
  min-width: 0;
  gap: 6px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--surface-soft);
}

.community-samples__title {
  margin: 0;
  color: var(--ink);
  font-weight: 700;
  overflow-wrap: anywhere;
}

.community-samples__meta,
.community-samples__numbers,
.community-samples__note {
  margin: 0;
  color: var(--ink-muted);
  font-size: 12px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.community-samples__tags {
  display: flex;
  flex-wrap: wrap;
  margin: 0;
  padding: 0;
  gap: 6px;
  list-style: none;
}

.community-samples__tags li {
  padding: 2px 8px;
  border: 1px solid var(--line-strong);
  border-radius: 999px;
  color: var(--ink-secondary);
  font-size: 12px;
}
</style>
