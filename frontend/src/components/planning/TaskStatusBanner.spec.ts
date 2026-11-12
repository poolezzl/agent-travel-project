import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import TaskStatusBanner from './TaskStatusBanner.vue'

describe('TaskStatusBanner', () => {
  it('重试按钮：creating 为 true 时禁用并显示重建文案', () => {
    const wrapper = mount(TaskStatusBanner, {
      props: { status: 'FAILED', reservedPoints: 10, creating: true },
    })

    const button = wrapper.get('[data-testid="retry-task-button"]')
    expect(button.element.hasAttribute('disabled')).toBe(true)
    expect(button.text()).toContain('正在重新创建')
  })

  it('重试按钮：creating 为 false 时可用', () => {
    const wrapper = mount(TaskStatusBanner, {
      props: { status: 'FAILED', reservedPoints: 10, creating: false },
    })

    const button = wrapper.get('[data-testid="retry-task-button"]')
    expect(button.element.hasAttribute('disabled')).toBe(false)
    expect(button.text()).toContain('重试')
  })

  it('取消按钮：cancelling 为 true 时禁用', () => {
    const wrapper = mount(TaskStatusBanner, {
      props: { status: 'QUEUED', reservedPoints: 10, cancelling: true },
    })

    const button = wrapper.get('[data-testid="cancel-task-button"]')
    expect(button.element.hasAttribute('disabled')).toBe(true)
  })

  it('取消按钮：cancelling 为 false 时可用', () => {
    const wrapper = mount(TaskStatusBanner, {
      props: { status: 'QUEUED', reservedPoints: 10, cancelling: false },
    })

    const button = wrapper.get('[data-testid="cancel-task-button"]')
    expect(button.element.hasAttribute('disabled')).toBe(false)
  })
})
