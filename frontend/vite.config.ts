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
