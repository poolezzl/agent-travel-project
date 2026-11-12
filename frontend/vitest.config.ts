import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    // Node 24.14 + vite 8.3 + vitest 5.0.1 下默认进程池（forks/threads）在收集阶段
    // 抛 "Cannot read properties of undefined (reading 'config')"，改用 VM 线程池规避。
    pool: 'vmThreads',
    include: ['src/**/*.spec.ts'],
    restoreMocks: true,
    clearMocks: true,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      reportsDirectory: './coverage',
    },
  },
})
