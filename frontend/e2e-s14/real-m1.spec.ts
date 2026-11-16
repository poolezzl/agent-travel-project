import { expect, test } from '@playwright/test'

// 真实后端 E2E：需要 8080 backend + 5174 Vite + MySQL/Redis/真实供应商。
// 默认跳过，显式 S14_REAL=1 时执行，避免无后端环境误报。
test.describe('S14 真实后端端到端', () => {
  test('登录→报价→建任务→SSE→最终 Plan', async ({ page, request }) => {
    test.skip(process.env.S14_REAL !== '1', 'set S14_REAL=1 with the real stack running')
    const login = `real-${Date.now()}`
    const password = 'E2ePass123!xyz'
    const registered = await request.post('/api/v1/auth/register', {
      data: { login, password },
    })
    expect(registered.ok()).toBeTruthy()

    await page.goto('/')
    await page.getByLabel('登录名').fill(login)
    await page.locator('input[type="password"]').fill(password)
    await page.getByTestId('auth-submit').click()
    await expect(page.getByTestId('planning-form')).toBeVisible()

    const day = (offset: number): string =>
      new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(
        new Date(Date.now() + 8 * 3600_000 + offset * 86_400_000),
      )
    await page.getByLabel('出发日期').fill(day(1))
    await page.getByLabel('返回日期（三日行程）').fill(day(3))

    await page.getByRole('button', { name: '获取服务端报价' }).click()
    await expect(page.getByTestId('quote-summary')).toBeVisible()
    await expect(page.getByTestId('quote-points')).toContainText('10')

    await page.getByTestId('create-task-button').click()
    await expect(page.getByTestId('task-status-banner')).toBeVisible()
    await expect(page.getByTestId('plan-result')).toBeVisible({ timeout: 180_000 })
    await expect(page.getByTestId('plan-result')).toContainText('故宫博物院')
    await expect(page.getByTestId('task-status-banner')).toContainText('成功')
  })
})
