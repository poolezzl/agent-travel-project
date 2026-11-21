import { expect, test, type Page, type Route } from '@playwright/test'

// S21 e2e：问答/澄清/修改确认/拒绝/重复确认/409/回滚/弱网重试。
// 全部后端交互用 page.route(/\/api\/v1\//) mock，不依赖 MySQL/Redis/8080/Python。

const LOGIN = 'alice'
const PASSWORD = 'password123'

interface State {
  versionNo: number
  versionId: string
  etag: string
  dayName: string
}

function defaultState(): State {
  return { versionNo: 1, versionId: 'version-1', etag: 'base-etag', dayName: '故宫博物院' }
}

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

function balanceResponse(): Record<string, unknown> {
  return { balancePoints: 100 }
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

function frame(seq: number, status: string): string {
  const data = {
    sequence_no: seq,
    event_type: 'TASK_STATUS_CHANGED',
    status,
    created_at: '2026-11-11T00:00:00Z',
  }
  return `id: ${seq}\nevent: task-progress\ndata: ${JSON.stringify(data)}\n\n`
}

const SUCCESS_EVENTS = frame(1, 'QUEUED') + frame(2, 'ASSEMBLING') + frame(3, 'SUCCEEDED')

function planView(state: State): Record<string, unknown> {
  return {
    task_id: 'task-1',
    status: 'PARTIAL',
    trip_id: 'trip-1',
    version_id: state.versionId,
    version_no: state.versionNo,
    etag: state.etag,
    schema_version: 's19-plan-v1',
    generated_at: '2026-11-11T00:00:00Z',
    source_metadata: [],
    degraded_fields: ['days[0].weather'],
    plan: {
      plan_version: 's19-plan-v1',
      generated_at: '2026-11-11T00:00:00Z',
      sample_only: true,
      notice: '示例数据，不代表真实事实。',
      trip: {
        city_id: 'beijing',
        city_name: '北京',
        start_date: '2026-12-01',
        end_date: '2026-12-03',
        day_count: 3,
        party_size: 2,
        transport_preference: '混合',
      },
      summary: {
        point_count: 1,
        total_distance_m: 0,
        total_transport_min: 0,
        estimated_route_legs: 1,
        estimated_cost: { currency: 'CNY', amount_minor: 6000, basis: 'known_ticket_rules' },
        source_completeness: 1,
        degraded: true,
        stale_fields: [],
        unknown_fields: ['days[0].weather'],
      },
      days: [
        {
          date: '2026-12-01',
          weekday: '周二',
          city_id: 'beijing',
          weather: { status: 'unknown', date: '2026-12-01' },
          segments: [
            {
              sequence_no: 1,
              point_id: 'POI-BJ-001',
              name: state.dayName,
              district: '东城区',
              category: 'attraction',
              start_time: '09:00',
              end_time: '11:30',
              duration_min: 150,
              duration_source: 'knowledge',
              is_must_visit: true,
              is_estimated: false,
              facts: [],
              transport_to_next: null,
            },
          ],
          meals: [],
          lodging: { date: '2026-12-01', candidates: [] },
          reservations: [],
          warnings: [],
        },
        {
          date: '2026-12-02',
          weekday: '周三',
          city_id: 'beijing',
          weather: { status: 'unknown', date: '2026-12-02' },
          segments: [],
          meals: [],
          lodging: { date: '2026-12-02', candidates: [] },
          reservations: [],
          warnings: [],
        },
        {
          date: '2026-12-03',
          weekday: '周四',
          city_id: 'beijing',
          weather: { status: 'unknown', date: '2026-12-03' },
          segments: [],
          meals: [],
          lodging: { date: '2026-12-03', candidates: [] },
          reservations: [],
          warnings: [],
        },
      ],
      quality: { status: 'warn', checks: [] },
      conflicts: [],
      confirmation: { required: false },
      sources: [],
      degraded_fields: ['days[0].weather'],
    },
  }
}

function previewQuery(): Record<string, unknown> {
  return {
    intent: 'query',
    base_version_id: 'version-1',
    version_id: null,
    parent_version_id: null,
    etag: 'base-etag',
    status: 'PRECHECKED',
    needs_confirm: false,
    affected_days: [],
    affected_agents: [],
    changes: [],
    answer: '这样安排交通时间足够。',
    clarification_question: null,
    summary: '',
    createdAt: '2026-11-11T00:00:00Z',
    reused: false,
    change_set: null,
    source_snapshot_ids: null,
    schema_version: null,
    model_version: null,
    prompt_version: null,
    content_hash: null,
  }
}

function previewAmbiguous(): Record<string, unknown> {
  return {
    ...previewQuery(),
    intent: 'ambiguous',
    answer: null,
    clarification_question: '您想替换为哪个景点？',
  }
}

function previewChange(): Record<string, unknown> {
  return {
    ...previewQuery(),
    intent: 'change',
    status: 'AWAITING_CONFIRMATION',
    needs_confirm: true,
    affected_days: [1],
    affected_agents: ['route-planner'],
    changes: [{ operation: 'replace', path: '/days/1/segments/0', value: { name: '天坛' } }],
    answer: null,
    clarification_question: null,
    summary: '替换第 1 天景点为天坛',
  }
}

function createdResult(state: State): Record<string, unknown> {
  return {
    ...previewChange(),
    status: 'CREATED',
    needs_confirm: false,
    version_id: state.versionId,
    parent_version_id: 'version-1',
    etag: state.etag,
    content_hash: `hash-${state.versionNo}`,
    change_set: [{ operation: 'replace', path: '/days/1/segments/0', value: { name: '天坛' } }],
    summary: '已创建新版本',
  }
}

function problem(code: string, detail: string, traceId = 'trace-9'): Record<string, unknown> {
  return {
    type: `https://agent-travel.local/problems/${code}`,
    title: 'Error',
    status: 409,
    detail,
    code,
    traceId,
    timestamp: '2026-11-11T00:00:00Z',
  }
}

interface BackendOptions {
  events?: string
  onPreview?: (
    body: Record<string, unknown>,
  ) =>
    | { status: number; body: Record<string, unknown> }
    | Promise<{ status: number; body: Record<string, unknown> }>
  onConfirm?: (route: Route, body: Record<string, unknown>, state: State) => Promise<void>
  onRollback?: (route: Route, state: State) => Promise<void>
}

async function installBackend(page: Page, options: BackendOptions = {}): Promise<State> {
  const state = defaultState()

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
    if (method === 'GET' && path === '/api/v1/points/balance') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(balanceResponse()),
      })
    }
    if (method === 'POST' && path === '/api/v1/points/quotes') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(quoteResponse()),
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
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: options.events ?? SUCCESS_EVENTS,
      })
    }
    if (method === 'GET' && /\/api\/v1\/tasks\/[^/]+\/plan$/.test(path)) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(planView(state)),
      })
    }
    if (method === 'GET' && /\/api\/v1\/tasks\/[^/]+$/.test(path)) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(taskView('SUCCEEDED', 3)),
      })
    }

    if (method === 'POST' && /\/api\/v1\/trips\/[^/]+\/revisions\/preview$/.test(path)) {
      const body = (request.postDataJSON() ?? {}) as Record<string, unknown>
      const response = options.onPreview
        ? await options.onPreview(body)
        : { status: 200, body: previewChange() }
      return route.fulfill({
        status: response.status,
        contentType: 'application/json',
        body: JSON.stringify(response.body),
      })
    }

    if (method === 'POST' && /\/api\/v1\/trips\/[^/]+\/revisions\/confirm$/.test(path)) {
      const body = (request.postDataJSON() ?? {}) as Record<string, unknown>
      if (options.onConfirm) {
        return options.onConfirm(route, body, state)
      }
      state.versionNo += 1
      state.versionId = `version-${state.versionNo}`
      state.etag = `etag-${state.versionNo}`
      state.dayName = '天坛'
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(createdResult(state)),
      })
    }

    if (method === 'POST' && /\/api\/v1\/trips\/[^/]+\/revisions\/rollback\/[^/]+$/.test(path)) {
      if (options.onRollback) {
        return options.onRollback(route, state)
      }
      state.versionNo += 1
      state.versionId = `version-${state.versionNo}`
      state.etag = `etag-${state.versionNo}`
      state.dayName = '故宫博物院'
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          ...createdResult(state),
          summary: '已创建回滚版本',
          content_hash: 'hash-1',
        }),
      })
    }

    return route.fulfill({
      status: 404,
      contentType: 'application/problem+json',
      body: JSON.stringify(problem('NOT_FOUND', 'not found', 'trace-x')),
    })
  })

  return state
}

async function loginAndPlan(page: Page): Promise<void> {
  await page.goto('/')
  await page.getByLabel('登录名').fill(LOGIN)
  await page.locator('input[type="password"]').fill(PASSWORD)
  await page.getByTestId('auth-submit').click()
  await expect(page.getByTestId('planning-form')).toBeVisible()

  await page.getByLabel('出发日期').fill('2026-12-01')
  await page.getByLabel('返回日期（三日行程）').fill('2026-12-03')
  await page.getByRole('button', { name: '获取服务端报价' }).click()
  await expect(page.getByTestId('quote-summary')).toBeVisible()
  await page.getByTestId('create-task-button').click()
  await expect(page.getByTestId('plan-result')).toBeVisible({ timeout: 10_000 })
  await expect(page.getByTestId('revision-panel')).toBeVisible()
}

test.describe('S21 问答/修改/确认/回滚（mock 后端）', () => {
  test('问答：仅显示答案，不建版本', async ({ page }) => {
    await installBackend(page, { onPreview: () => ({ status: 200, body: previewQuery() }) })
    await loginAndPlan(page)

    await page.getByTestId('revision-input').fill('这样安排合适吗')
    await page.getByTestId('revision-send').click()

    await expect(page.getByTestId('revision-message-1')).toBeVisible()
    await expect(page.getByTestId('revision-query-badge')).toContainText(
      '仅问答 · 未创建新版本 · 未扣费',
    )
    await expect(page.getByTestId('revision-timeline')).toContainText('历史版本列表接口未提供')
  })

  test('澄清：只有输入可用，无确认按钮', async ({ page }) => {
    await installBackend(page, { onPreview: () => ({ status: 200, body: previewAmbiguous() }) })
    await loginAndPlan(page)

    await page.getByTestId('revision-input').fill('换一个地方')
    await page.getByTestId('revision-send').click()

    await expect(page.getByTestId('revision-clarify')).toContainText('需要澄清 · 尚未修改')
    await expect(page.getByTestId('confirm-dialog')).toHaveCount(0)
  })

  test('修改预览→确认→刷新：计划更新并追加时间线', async ({ page }) => {
    await installBackend(page)
    await loginAndPlan(page)

    await page.getByTestId('revision-input').fill('换成天坛')
    await page.getByTestId('revision-send').click()

    await expect(page.getByTestId('confirm-dialog')).toBeVisible()
    await expect(page.getByTestId('confirm-dialog')).toContainText('替换第 1 天景点为天坛')
    await page.getByTestId('confirm-accept').click()

    await expect(page.getByTestId('revision-timeline')).toContainText('修改')
    await expect(page.getByTestId('plan-result')).toContainText('天坛')
    await expect(page.getByTestId('revision-panel')).toContainText('v2')
    // 确认成功后降级提示仍在，不因修改成功而隐藏。
    await expect(page.getByTestId('revision-degraded')).toBeVisible()
    // 结构化 diff 的 replace 原值应来自修改前快照（故宫博物院），新值是天坛，不得两边都是天坛。
    await expect(page.getByTestId('revision-diff')).toContainText('故宫博物院')
    await expect(page.getByTestId('revision-diff')).toContainText('天坛')
    // 窄屏/桌面均无横向溢出。
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1,
      ),
    ).toBe(true)
  })

  test('拒绝确认：不产生版本，会话留取消记录', async ({ page }) => {
    await installBackend(page)
    await loginAndPlan(page)

    await page.getByTestId('revision-input').fill('换成天坛')
    await page.getByTestId('revision-send').click()
    await expect(page.getByTestId('confirm-dialog')).toBeVisible()

    await page.getByTestId('confirm-decline').click()
    await expect(page.getByTestId('confirm-dialog')).toHaveCount(0)
    await expect(page.getByTestId('revision-panel')).toContainText('已取消本次修改')
    await expect(page.getByTestId('revision-timeline')).toContainText('历史版本列表接口未提供')
    await expect(page.getByTestId('plan-result')).toContainText('故宫博物院')
  })

  test('重复确认：服务端复用原版本', async ({ page }) => {
    await installBackend(page, {
      onConfirm: async (route) => {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            ...createdResult(defaultState()),
            reused: true,
            summary: '重复确认已复用原版本',
          }),
        })
      },
    })
    await loginAndPlan(page)

    await page.getByTestId('revision-input').fill('换成天坛')
    await page.getByTestId('revision-send').click()
    await page.getByTestId('confirm-accept').click()

    await expect(page.getByTestId('revision-panel')).toContainText('已复用原版本，未重复扣费')
    await expect(page.getByTestId('revision-timeline')).toContainText('历史版本列表接口未提供')
  })

  test('并发 409：冲突横幅并刷新到新版本', async ({ page }) => {
    await installBackend(page, {
      onConfirm: async (route) => {
        return route.fulfill({
          status: 409,
          contentType: 'application/problem+json',
          body: JSON.stringify(problem('REVISION_BASE_MISMATCH', '行程版本已变化，请刷新后重试')),
        })
      },
    })
    await loginAndPlan(page)

    await page.getByTestId('revision-input').fill('换成天坛')
    await page.getByTestId('revision-send').click()
    await page.getByTestId('confirm-accept').click()

    await expect(page.getByTestId('conflict-banner')).toBeVisible()
    await expect(page.getByTestId('conflict-banner')).toContainText('行程版本已变化')
    await expect(page.getByTestId('confirm-dialog')).toHaveCount(0)
  })

  test('回滚：单次修改后即可回滚', async ({ page }) => {
    await installBackend(page)
    await loginAndPlan(page)

    await page.getByTestId('revision-input').fill('换成天坛')
    await page.getByTestId('revision-send').click()
    await page.getByTestId('confirm-accept').click()
    await expect(page.getByTestId('revision-timeline')).toContainText('修改前版本')

    await page.getByTestId('rollback-button').first().click()
    await page.getByTestId('rollback-confirm').click()

    await expect(page.getByTestId('revision-timeline')).toContainText('回滚')
    await expect(page.getByTestId('plan-result')).toContainText('故宫博物院')
  })

  test('回滚 409：横幅无重新预览按钮，时间线仍可回滚', async ({ page }) => {
    await installBackend(page, {
      onRollback: async (route) => {
        await route.fulfill({
          status: 409,
          contentType: 'application/problem+json',
          body: JSON.stringify(problem('REVISION_BASE_MISMATCH', '行程版本已变化，请刷新后重试')),
        })
      },
    })
    await loginAndPlan(page)

    await page.getByTestId('revision-input').fill('换成天坛')
    await page.getByTestId('revision-send').click()
    await page.getByTestId('confirm-accept').click()
    await expect(page.getByTestId('revision-timeline')).toContainText('修改前版本')

    await page.getByTestId('rollback-button').first().click()
    await page.getByTestId('rollback-confirm').click()

    await expect(page.getByTestId('conflict-banner')).toBeVisible()
    await expect(page.getByTestId('conflict-reload')).toHaveCount(0)
    await expect(page.getByTestId('rollback-button').first()).toBeVisible()
  })

  test('丢响应重试：reused 后 plan 与时间线同步', async ({ page }) => {
    let confirmCount = 0
    await installBackend(page, {
      onConfirm: async (route, body, state) => {
        confirmCount += 1
        if (confirmCount === 1) {
          // 服务端已创建新版本，但响应在返回途中丢失。
          state.versionNo += 1
          state.versionId = `version-${state.versionNo}`
          state.etag = `etag-${state.versionNo}`
          state.dayName = '天坛'
          await route.abort()
          return
        }
        // 重试：服务端复用原版本（reused），version_id 与前端当前不同。
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ ...createdResult(state), reused: true }),
        })
      },
    })
    await loginAndPlan(page)

    await page.getByTestId('revision-input').fill('换成天坛')
    await page.getByTestId('revision-send').click()
    await page.getByTestId('confirm-accept').click()

    await expect(page.getByTestId('revision-retry')).toBeVisible()
    await page.getByTestId('revision-retry').click()

    await expect(page.getByTestId('revision-timeline')).toContainText('修改前版本')
    await expect(page.getByTestId('revision-timeline')).toContainText(
      '已同步原确认结果（未重复扣费）',
    )
    await expect(page.getByTestId('plan-result')).toContainText('天坛')
  })

  test('默认保护项已勾选', async ({ page }) => {
    await installBackend(page)
    await loginAndPlan(page)

    await expect(page.getByTestId('protected-path').first()).toBeChecked()
  })

  test('preview 在途保护项冻结且确认载荷与弹窗一致', async ({ page }) => {
    let confirmBody: Record<string, unknown> | null = null
    await installBackend(page, {
      onPreview: async () => {
        await new Promise((resolve) => setTimeout(resolve, 700))
        return { status: 200, body: previewChange() }
      },
      onConfirm: async (route, body, state) => {
        confirmBody = body
        state.versionNo += 1
        state.versionId = `version-${state.versionNo}`
        state.etag = `etag-${state.versionNo}`
        state.dayName = '天坛'
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createdResult(state)),
        })
      },
    })
    await loginAndPlan(page)

    await page.getByTestId('revision-input').fill('换成天坛')
    await page.getByTestId('revision-send').click()

    // preview 在途：保护项冻结，不可改动。
    await expect(page.getByTestId('protected-path').first()).toBeDisabled()

    await expect(page.getByTestId('confirm-dialog')).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('confirm-dialog')).toContainText('第 1 天 第 1 段 · 故宫博物院')

    await page.getByTestId('confirm-accept').click()
    await expect(page.getByTestId('revision-timeline')).toContainText('修改前版本')

    expect(confirmBody?.locked_paths).toEqual(['/days/1/segments/0'])
  })

  test('弱网重试：复用同一 confirmation_id', async ({ page }) => {
    const confirmationIds: string[] = []
    await installBackend(page, {
      onConfirm: async (route, body, state) => {
        const cid = String(body.confirmation_id ?? '')
        confirmationIds.push(cid)
        if (confirmationIds.length === 1) {
          await route.abort()
          return
        }
        state.versionNo += 1
        state.versionId = `version-${state.versionNo}`
        state.etag = `etag-${state.versionNo}`
        state.dayName = '天坛'
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createdResult(state)),
        })
      },
    })
    await loginAndPlan(page)

    await page.getByTestId('revision-input').fill('换成天坛')
    await page.getByTestId('revision-send').click()
    await page.getByTestId('confirm-accept').click()

    // 第一次确认网络中断 → 出现错误重试入口。
    await expect(page.getByTestId('revision-retry')).toBeVisible()
    await page.getByTestId('revision-retry').click()

    await expect(page.getByTestId('revision-timeline')).toContainText('修改')
    expect(confirmationIds).toHaveLength(2)
    expect(confirmationIds[0]).toBe(confirmationIds[1])
  })
})

// 真实后端链路，默认跳过：需要 8080 backend + 5175 Vite + MySQL/Redis/Python。
test.describe('S21 真实后端端到端', () => {
  test('登录→生成→修改→确认→回滚', async ({ page, request }) => {
    test.skip(process.env.S21_REAL !== '1', 'set S21_REAL=1 with the real stack running')
    const login = `real-${Date.now()}`
    const password = 'E2ePass123!xyz'
    const registered = await request.post('/api/v1/auth/register', { data: { login, password } })
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
    await page.getByTestId('create-task-button').click()
    await expect(page.getByTestId('plan-result')).toBeVisible({ timeout: 180_000 })
    await expect(page.getByTestId('revision-panel')).toBeVisible()

    await page.getByTestId('revision-input').fill('第二天换成天坛')
    await page.getByTestId('revision-send').click()
    await expect(page.getByTestId('confirm-dialog')).toBeVisible({ timeout: 60_000 })
    await page.getByTestId('confirm-accept').click()
    await expect(page.getByTestId('revision-timeline')).toContainText('修改', { timeout: 60_000 })
    await page.getByTestId('rollback-button').first().click()
    await page.getByTestId('rollback-confirm').click()
    await expect(page.getByTestId('revision-timeline')).toContainText('回滚', { timeout: 60_000 })
  })
})
