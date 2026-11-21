import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import RevisionComposer from './RevisionComposer.vue'

describe('RevisionComposer', () => {
  it('空文本禁用发送，有文本可发送', async () => {
    const wrapper = mount(RevisionComposer, { props: { text: '', loading: false } })

    expect(wrapper.get('[data-testid="revision-send"]').attributes('disabled')).toBeDefined()

    await wrapper.setProps({ text: '换成天坛' })
    expect(wrapper.get('[data-testid="revision-send"]').attributes('disabled')).toBeUndefined()
  })

  it('输入框限制 2000 字', () => {
    const wrapper = mount(RevisionComposer, { props: { text: '', loading: false } })

    expect(wrapper.get('[data-testid="revision-input"]').attributes('maxlength')).toBe('2000')
  })

  it('Enter 发送，Shift+Enter 不发送', async () => {
    const wrapper = mount(RevisionComposer, { props: { text: '换成天坛', loading: false } })
    const input = wrapper.get('[data-testid="revision-input"]')

    await input.trigger('keydown', { key: 'Enter', shiftKey: true })
    expect(wrapper.emitted('send')).toBeFalsy()

    await input.trigger('keydown', { key: 'Enter', shiftKey: false })
    expect(wrapper.emitted('send')).toHaveLength(1)
  })
})
