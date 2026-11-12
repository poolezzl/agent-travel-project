import { describe, expect, it, vi } from 'vitest'

describe('router 的 mock profile 门禁', () => {
  it('非 mock 构建根路由为 S14 规划页，且不注册演示路由', async () => {
    vi.resetModules()
    vi.stubGlobal('__APP_PROFILE__', 'local')
    vi.stubEnv('VITE_ENABLE_MOCK', 'false')

    const { default: router } = await import('./index')

    expect(router.resolve('/').name).toBe('planning')
    expect(router.resolve('/scaffold').name).toBe('scaffold')
  })

  it('mock 构建根路由为 S07 演示页，并保留骨架回归页', async () => {
    vi.resetModules()
    vi.stubGlobal('__APP_PROFILE__', 'mock')
    vi.stubEnv('VITE_ENABLE_MOCK', 'true')

    const { default: router } = await import('./index')

    expect(router.resolve('/').name).toBe('s07-demo')
    expect(router.resolve('/scaffold').name).toBe('scaffold')
  })
})
