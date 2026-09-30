import { createPinia } from 'pinia'
import { mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import S07DemoView from './S07DemoView.vue'

function mountView(): VueWrapper {
  return mount(S07DemoView, { global: { plugins: [createPinia()] } })
}

async function selectStatus(wrapper: VueWrapper, value: string): Promise<void> {
  await wrapper.get(`input[name="demo-status"][value="${value}"]`).setValue()
  await nextTick()
}

beforeEach(() => {
  vi.stubGlobal('__APP_PROFILE__', 'mock')
  vi.stubEnv('VITE_ENABLE_MOCK', 'true')
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
  vi.useRealTimers()
})

describe('S07DemoView', () => {
  it('默认成功态渲染免责声明与全部演示区块', () => {
    const wrapper = mountView()

    expect(wrapper.get('[data-testid="demo-notice"]').text()).toContain('演示数据，未接入真实接口')

    for (const testId of [
      'planning-form',
      'mock-progress',
      'envelope-panel',
      'input-summary',
      'schedule-timeline',
      'map-placeholder',
      'plan-comparison',
      'community-samples',
    ]) {
      expect(wrapper.find(`[data-testid="${testId}"]`).exists()).toBe(true)
    }

    expect(wrapper.get('[data-testid="current-status"]').text()).toContain('SUCCESS')
  })

  it('状态选择器只在显式 mock profile 下渲染', () => {
    const mockWrapper = mountView()
    expect(mockWrapper.find('[data-testid="mock-status-selector"]').exists()).toBe(true)

    vi.stubGlobal('__APP_PROFILE__', 'local')
    vi.stubEnv('VITE_ENABLE_MOCK', 'false')
    const localWrapper = mountView()

    expect(localWrapper.find('[data-testid="mock-status-selector"]').exists()).toBe(false)
    expect(localWrapper.get('[data-testid="demo-notice"]').exists()).toBe(true)
  })

  it('加载态显示骨架屏与进行中的模拟阶段', async () => {
    const wrapper = mountView()

    await selectStatus(wrapper, 'loading')

    expect(wrapper.find('[data-testid="loading-skeleton"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="mock-progress"]').text()).toContain('模拟中')
    expect(wrapper.get('[data-testid="current-status"]').text()).toContain('LOADING')
  })

  it('空态不渲染时间轴并且不使用占位数字', async () => {
    const wrapper = mountView()

    await selectStatus(wrapper, 'empty')

    expect(wrapper.find('[data-testid="schedule-timeline"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="result-state"]').text()).toContain('还没有可展示的演示行程')
    expect(wrapper.get('[data-testid="result-state"]').text()).not.toMatch(/\d{2,} 分钟/)
  })

  it('错误态展示演示错误码与来源空态说明', async () => {
    const wrapper = mountView()

    await selectStatus(wrapper, 'error')

    expect(wrapper.get('[data-testid="result-state"]').text()).toContain('AGENT_UNAVAILABLE')
    expect(wrapper.get('[data-testid="envelope-degraded"]').text()).toContain('error_code')
  })

  it('部分完成态保留可见的降级字段，不静默吞掉', async () => {
    const wrapper = mountView()

    await selectStatus(wrapper, 'partial')

    const envelopeText = wrapper.get('[data-testid="envelope-degraded"]').text()
    expect(envelopeText).toContain('降级字段')
    expect(envelopeText).toContain('provider_fields')
    expect(wrapper.get('[data-testid="schedule-timeline"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="result-state"]').text()).toContain('部分完成')
  })

  it('提交表单驱动本地模拟进度并在结束后回到成功态', async () => {
    vi.useFakeTimers()
    const wrapper = mountView()

    await wrapper.get('[data-testid="planning-form"]').trigger('submit')
    await nextTick()

    expect(wrapper.get('[data-testid="current-status"]').text()).toContain('LOADING')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()

    await vi.advanceTimersByTimeAsync(600 * 8)
    await nextTick()

    expect(wrapper.get('[data-testid="current-status"]').text()).toContain('SUCCESS')
    expect(wrapper.find('[data-testid="schedule-timeline"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('提交后输入摘要反映最新合成输入', async () => {
    vi.useFakeTimers()
    const wrapper = mountView()

    await wrapper.findAll('textarea')[0]?.setValue('合成·新点位')
    await wrapper.get('[data-testid="planning-form"]').trigger('submit')
    await nextTick()

    expect(wrapper.get('[data-testid="input-summary"]').text()).toContain('合成·新点位')

    wrapper.unmount()
  })
})
