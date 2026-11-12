import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import type { CurrentUser, TaskView } from '@/api/types'
import { usePlanningStore } from '@/stores/planning'
import { useSessionStore } from '@/stores/session'
import S14PlanningView from './S14PlanningView.vue'

vi.mock('@/api/auth', () => ({
  register: vi.fn(),
  login: vi.fn(),
  refresh: vi.fn(),
  logout: vi.fn(),
  me: vi.fn(),
}))

vi.mock('@/api/points', () => ({
  quote: vi.fn(),
  balance: vi.fn(),
  reserve: vi.fn(),
  confirm: vi.fn(),
  refund: vi.fn(),
  grant: vi.fn(),
}))

vi.mock('@/api/tasks', () => ({
  create: vi.fn(),
  get: vi.fn(),
  cancel: vi.fn(),
  eventsUrl: vi.fn(),
}))

import * as authApi from '@/api/auth'
import * as pointsApi from '@/api/points'

const currentUser: CurrentUser = {
  userId: 'u1',
  login: 'alice',
  role: 'USER',
  status: 'ACTIVE',
  createdAt: '2026-11-11T00:00:00Z',
}

const taskView: TaskView = {
  task_id: 'task-1',
  owner_id: 'owner-1',
  task_type: 'GENERATION',
  status: 'SUCCEEDED',
  state_version: 3,
  reserved_points: 10,
  operation_deadline_at: '2026-11-11T00:00:00Z',
  confirmation_deadline_at: null,
  created_at: '2026-11-11T00:00:00Z',
  updated_at: '2026-11-11T00:00:00Z',
  reused: false,
}

function mountView(): ReturnType<typeof mount> {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(S14PlanningView, { global: { plugins: [pinia] } })
}

describe('S14PlanningView', () => {
  it('未登录时显示认证面板，不显示规划表单', () => {
    const wrapper = mountView()

    expect(wrapper.get('[data-testid="auth-panel"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="planning-form"]').exists()).toBe(false)
  })

  it('终态任务的结果区展示接口缺失空态而非 fixture', async () => {
    vi.mocked(authApi.me).mockResolvedValue(currentUser)
    vi.mocked(pointsApi.balance).mockResolvedValue({ balancePoints: 100 })

    const pinia = createPinia()
    setActivePinia(pinia)
    const session = useSessionStore()
    session.applySession({
      accessToken: 'a',
      refreshToken: 'r',
      expiresInSeconds: 900,
      userId: 'u1',
      role: 'USER',
    })
    session.user = currentUser
    const planning = usePlanningStore()
    planning.task = taskView

    const wrapper = mount(S14PlanningView, { global: { plugins: [pinia] } })

    expect(wrapper.get('[data-testid="plan-result"]').text()).toContain('PENDING-S14-01')
    expect(wrapper.text()).not.toContain('合成·北城晨光公园')
    expect(wrapper.text()).not.toContain('synthetic-bj-001')
  })
})
