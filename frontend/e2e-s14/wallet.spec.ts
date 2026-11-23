import { expect, test, type Page } from '@playwright/test'

const userId = '51785fa1-ba3b-4000-8000-000000000002'
const quoteId = '51785fa1-ba3b-4000-8000-000000000001'
const targetId = '51785fa1-ba3b-4000-8000-000000000003'

async function signIn(page: Page, role: 'USER' | 'ADMIN') {
  await page.route('**/api/v1/auth/login', (route) =>
    route.fulfill({
      json: { accessToken: 'test-access', refreshToken: 'test-refresh', userId, role },
    }),
  )
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({ json: { userId, role, login: '测试账号' } }),
  )
  await page.goto('/wallet')
  await page.getByLabel('登录名').fill('测试账号')
  await page.getByLabel('密码').fill('test-session-only')
  await page.getByRole('button', { name: '登录', exact: true }).click()
}

async function mockWallet(page: Page) {
  await page.route('**/api/v1/points/packages', (route) =>
    route.fulfill({ json: [{ code: 'standard', points: 100 }] }),
  )
  await page.route('**/api/v1/points/ledger?*', (route) =>
    route.fulfill({
      json: {
        entries: [
          {
            id: 'refund-1',
            userId,
            taskId: targetId,
            entryType: 'REFUND',
            pointsDelta: 10,
            balanceAfter: 120,
            createdAt: '2026-11-23T10:00:00Z',
          },
        ],
        nextOffset: null,
      },
    }),
  )
}

test('充值确认防连点，网络失败后沿用去重键，显示退款流水', async ({ page }) => {
  let balance = 120
  const confirmKeys: string[] = []
  await mockWallet(page)
  await page.route('**/api/v1/points/balance', (route) =>
    route.fulfill({ json: { balancePoints: balance } }),
  )
  await page.route('**/api/v1/points/top-up/quotes', (route) =>
    route.fulfill({
      json: {
        quoteId,
        userId,
        pointsDelta: 100,
        balanceAfter: 220,
        expiresAt: '2099-01-01T00:00:00Z',
      },
    }),
  )
  await page.route('**/api/v1/points/wallet/confirm', async (route) => {
    confirmKeys.push(route.request().headers()['idempotency-key'])
    expect(route.request().postDataJSON()).toEqual({ quoteId })
    await new Promise((resolve) => setTimeout(resolve, 250))
    if (confirmKeys.length === 1) {
      await route.abort('failed')
    } else {
      balance = 220
      await route.fulfill({ json: { balancePoints: balance } })
    }
  })

  await signIn(page, 'USER')
  await expect(page.getByTestId('wallet-balance')).toHaveText('120')
  await expect(page.getByText('已退款')).toBeVisible()
  await expect(page.getByText('+10 积分')).toBeVisible()
  await expect(page.getByRole('heading', { name: '管理员调整' })).toHaveCount(0)
  await page.getByRole('button', { name: '100 积分' }).press('Enter')
  await expect(page.getByRole('heading', { name: '确认模拟充值' })).toBeVisible()
  await page.getByRole('button', { name: '确认入账' }).evaluate((button: HTMLButtonElement) => {
    button.click()
    button.click()
  })
  await expect(page.getByText('积分操作失败')).toBeVisible()
  expect(confirmKeys).toHaveLength(1)
  await page.getByRole('button', { name: '确认入账' }).click()
  await expect(page.getByText('模拟充值已到账')).toBeVisible()
  await expect(page.getByTestId('wallet-balance')).toHaveText('220')
  expect(confirmKeys).toHaveLength(2)
  expect(confirmKeys[0]).toBeTruthy()
  expect(confirmKeys[1]).toBe(confirmKeys[0])
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
    await page.evaluate(() => window.innerWidth),
  )
})

test('管理员先查看报价和目标，再确认扣减', async ({ page }) => {
  await mockWallet(page)
  await page.route('**/api/v1/points/balance', (route) =>
    route.fulfill({ json: { balancePoints: 100 } }),
  )
  await page.route('**/api/v1/points/admin/adjustments/quotes', (route) => {
    expect(route.request().postDataJSON()).toEqual({ userId: targetId, pointsDelta: -25 })
    return route.fulfill({
      json: {
        quoteId,
        userId: targetId,
        pointsDelta: -25,
        balanceAfter: 75,
        expiresAt: '2099-01-01T00:00:00Z',
      },
    })
  })
  await page.route('**/api/v1/points/wallet/confirm', (route) => {
    expect(route.request().postDataJSON()).toEqual({ quoteId })
    return route.fulfill({ json: { balancePoints: 75 } })
  })
  await signIn(page, 'ADMIN')
  await page.getByLabel('目标账户编号').fill(targetId)
  await page.getByLabel('调整积分').fill('-25')
  await page.getByRole('button', { name: '获取调整报价' }).click()
  await expect(page.getByRole('heading', { name: '确认积分调整' })).toBeVisible()
  await expect(page.getByText('预计余额 75 积分', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: '确认入账' }).click()
  await expect(page.getByText('积分调整已入账')).toBeVisible()
  await expect(page.getByTestId('wallet-balance')).toHaveText('100')
})

test('钱包刷新与确认互斥，避免旧余额覆盖新入账', async ({ page }) => {
  let releaseConfirmation: () => void = () => {}
  const confirmationPending = new Promise<void>((resolve) => {
    releaseConfirmation = resolve
  })
  let balance = 120
  let reads = 0
  await mockWallet(page)
  await page.route('**/api/v1/points/balance', async (route) => {
    reads += 1
    if (reads === 2) await new Promise((resolve) => setTimeout(resolve, 300))
    await route.fulfill({ json: { balancePoints: balance } })
  })
  await page.route('**/api/v1/points/top-up/quotes', (route) =>
    route.fulfill({
      json: {
        quoteId,
        userId,
        pointsDelta: 100,
        balanceAfter: 220,
        expiresAt: '2099-01-01T00:00:00Z',
      },
    }),
  )
  await page.route('**/api/v1/points/wallet/confirm', async (route) => {
    await confirmationPending
    balance = 220
    return route.fulfill({ json: { balancePoints: balance } })
  })

  await signIn(page, 'USER')
  await expect(page.getByTestId('wallet-balance')).toHaveText('120')
  await page.getByRole('button', { name: '100 积分' }).click()
  const refresh = page.getByRole('button', { name: '刷新钱包' })
  await refresh.click()
  const confirm = page.getByRole('button', { name: '确认入账' })
  await expect(confirm).toBeDisabled()
  await expect(confirm).toBeEnabled()
  await confirm.click()
  try {
    await expect(refresh).toBeDisabled()
  } finally {
    releaseConfirmation()
  }
  await expect(page.getByTestId('wallet-balance')).toHaveText('220')
  await expect(refresh).toBeEnabled()
})
