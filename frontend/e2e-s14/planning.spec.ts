import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

const LOGIN = 'alice'
const PASSWORD = 'password123'

function tokenResponse(): Record<string, unknown> {
  return { accessToken: 'a', refreshToken: 'r', expiresInSeconds: 900, userId: 'u1', role: 'USER' }
}

function currentUser(): Record<string, unknown> {
  return {
    userId: 'u1',
    login: LOGIN,
    role: 'USER',
    status: 'ACTIVE',
    createdAt: '2026-11-11T00:00:00Z',
  }
}

function quoteResponse(): Record<string, unknown> {
  return {
    quoteId: 'q1',
    quoteReference: 'qr-1',
    planCount: 1,
    points: 10,
    expiresAtEpochSecond: 2_000_000_000,
  }
}

function balanceResponse(): Record<string, unknown> {
  return { balancePoints: 100 }
}

function taskView(status: string, stateVersion: number): Record<string, unknown> {
  return {
    task_id: 'task-1',
    owner_id: 'u1',
    task_type: 'GENERATION',
    status,
    state_version: stateVersion,
    reserved_points: 10,
    operation_deadline_at: '2026-11-11T00:00:00Z',
    confirmation_deadline_at: null,
    created_at: '2026-11-11T00:00:00Z',
    updated_at: '2026-11-11T00:00:00Z',
    reused: false,
  }
}

function frame(seq: number, status: string, extra: Record<string, unknown> = {}): string {
  const data = {
    sequence_no: seq,
    event_type: 'TASK_STATUS_CHANGED',
    status,
    created_at: '2026-11-11T00:00:00Z',
    ...extra,
  }
  return `id: ${seq}\nevent: task-progress\ndata: ${JSON.stringify(data)}\n\n`
}

const SUCCESS_EVENTS = frame(1, 'QUEUED') + frame(2, 'ASSEMBLING') + frame(3, 'SUCCEEDED')
const QUEUED_EVENTS = frame(1, 'QUEUED')

interface BackendOptions {
  events: string
  cancelStatus?: string
}

async function installBackend(page: Page, options: BackendOptions): Promise<void> {
  // 仅拦截后端 API 前缀 /api/v1/；不能用 '**/api/**'，否则会命中 Vite 开发服务器下的 /src/api/* 模块请求。
  await page.route(/\/api\/v1\//, async (route) => {
    const request = route.request()
    const method = request.method()
    const path = new URL(request.url()).pathname

    if (method === 'POST' && path === '/api/v1/auth/login') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(tokenResponse()),
      })
    }
    if (method === 'GET' && path === '/api/v1/auth/me') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(currentUser()),
      })
    }
    if (method === 'POST' && path === '/api/v1/points/quotes') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(quoteResponse()),
      })
    }
    if (method === 'GET' && path === '/api/v1/points/balance') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(balanceResponse()),
      })
    }
    if (method === 'POST' && path === '/api/v1/tasks') {
      return route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify(taskView('QUEUED', 2)),
      })
    }
    if (method === 'GET' && /\/api\/v1\/tasks\/[^/]+\/events$/.test(path)) {
      return route.fulfill({ status: 200, contentType: 'text/event-stream', body: options.events })
    }
    if (method === 'GET' && /\/api\/v1\/tasks\/[^/]+$/.test(path)) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(taskView('SUCCEEDED', 3)),
      })
    }
    if (method === 'POST' && /\/api\/v1\/tasks\/[^/]+\/cancel$/.test(path)) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(taskView(options.cancelStatus ?? 'REFUNDED', 4)),
      })
    }
    return route.fulfill({
      status: 404,
      contentType: 'application/problem+json',
      body: JSON.stringify({
        type: 'https://agent-travel.local/problems/NOT_FOUND',
        title: 'Not Found',
        status: 404,
        detail: 'not found',
        code: 'NOT_FOUND',
        traceId: 'trace-x',
        timestamp: '2026-11-11T00:00:00Z',
      }),
    })
  })
}

async function loginAndPlan(page: Page): Promise<void> {
  await page.goto('/')
  await page.getByLabel('登录名').fill(LOGIN)
  await page.locator('input[type="password"]').fill(PASSWORD)
  await page.getByTestId('auth-submit').click()
  await expect(page.getByTestId('planning-form')).toBeVisible()

  await page.getByLabel('出发日期').fill('2026-12-01')
  await page.getByLabel('返回日期（三日行程）').fill('2026-12-03')
}

test.describe('S14 M1 真实端到端（mock 后端）', () => {
  test('未登录→登录→填表→报价→建任务→SSE 进度→终态', async ({ page }) => {
    await installBackend(page, { events: SUCCESS_EVENTS })
    await loginAndPlan(page)

    await page.getByRole('button', { name: '获取服务端报价' }).click()
    await expect(page.getByTestId('quote-summary')).toBeVisible()
    await expect(page.getByTestId('quote-points')).toContainText('10')

    await page.getByTestId('create-task-button').click()
    await expect(page.getByTestId('task-status-banner')).toBeVisible()
    await expect(page.getByTestId('task-progress')).toBeVisible()

    await expect(page.getByTestId('plan-result')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('plan-result')).toContainText('PENDING-S14-01')
    await expect(page.getByTestId('task-status-banner')).toContainText('成功')
    await expect(page.getByTestId('plan-result')).not.toContainText('合成·北城晨光公园')
  })

  test('排队中可二次确认取消并显示退款', async ({ page }) => {
    await installBackend(page, { events: QUEUED_EVENTS, cancelStatus: 'REFUNDED' })
    await loginAndPlan(page)

    await page.getByRole('button', { name: '获取服务端报价' }).click()
    await expect(page.getByTestId('quote-summary')).toBeVisible()
    await page.getByTestId('create-task-button').click()

    await expect(page.getByTestId('task-status-banner')).toContainText('排队中')
    await page.getByTestId('cancel-task-button').click()
    await expect(page.getByTestId('cancel-confirm')).toBeVisible()
    await page.getByRole('button', { name: '确认取消' }).click()

    await expect(page.getByTestId('task-status-banner')).toContainText('已退款')
  })
})
