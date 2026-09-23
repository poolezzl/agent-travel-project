<script setup lang="ts">
import { onMounted } from 'vue'
import { storeToRefs } from 'pinia'

import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
const { isMockProfile, isReady, profile } = storeToRefs(appStore)

onMounted(() => {
  appStore.markReady()
})
</script>

<template>
  <main class="scaffold-shell">
    <section class="scaffold-card" aria-labelledby="scaffold-title">
      <el-tag type="info" effect="plain">S03 · Frontend Scaffold</el-tag>
      <h1 id="scaffold-title">前端工程骨架已就绪</h1>
      <p>Vue 3、Vite、TypeScript、Pinia、Vue Router、Element Plus 与 Vitest 已完成基础接入。</p>
      <dl class="scaffold-status" aria-label="工程状态">
        <div>
          <dt>运行配置</dt>
          <dd>{{ profile }}</dd>
        </div>
        <div>
          <dt>Mock 标识</dt>
          <dd>{{ isMockProfile ? '已显式启用' : '未启用' }}</dd>
        </div>
        <div>
          <dt>应用状态</dt>
          <dd>{{ isReady ? 'ready' : 'initializing' }}</dd>
        </div>
      </dl>
      <el-alert
        title="本页面只验证工程骨架，不包含业务数据或真实接口。"
        type="info"
        :closable="false"
        show-icon
      />
    </section>
  </main>
</template>
