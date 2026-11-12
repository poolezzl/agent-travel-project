import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

export default defineConfig(({ mode }) => ({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    // 后端无 CORS 配置，前端不得直连 http://127.0.0.1:8080；开发态经同源 /api 反代。
    // 生产构建使用相对 /api，由部署层反代（VITE_API_BASE_URL 为空时前端请求同源 /api）。
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  preview: {
    host: '127.0.0.1',
    port: 4173,
    strictPort: true,
  },
  define: {
    __APP_PROFILE__: JSON.stringify(mode === 'mock' ? 'mock' : 'local'),
  },
  build: {
    sourcemap: mode !== 'production',
    target: 'es2022',
    outDir: 'dist',
  },
}))
