import { defineConfig, devices } from '@playwright/test'

// S14 e2e 独立配置：端口 5174，webServer 使用 development 模式（profile=local，/ → S14PlanningView）。
// 测试通过 page.route('**/api/**') 拦截并 mock 后端响应，不依赖真实 MySQL/Redis/8080 后端。
const BASE_URL = 'http://127.0.0.1:5174'

export default defineConfig({
  testDir: './e2e-s14',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: BASE_URL,
    screenshot: 'off',
    trace: 'off',
  },
  webServer: {
    command: 'npm run dev -- --port 5174',
    url: BASE_URL,
    reuseExistingServer: true,
    timeout: 120_000,
  },
  projects: [
    {
      name: 'desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
    },
    {
      name: 'mobile',
      use: { ...devices['Pixel 5'] },
    },
  ],
})
