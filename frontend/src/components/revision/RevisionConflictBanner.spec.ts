import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { ConflictInfo } from '@/stores/revision'
import RevisionConflictBanner from './RevisionConflictBanner.vue'

const conflict: ConflictInfo = {
  code: 'REVISION_BASE_MISMATCH',
  kind: 'preview',
  serverVersionNo: 3,
  serverAt: '2026-11-20T01:00:00Z',
  traceId: 'trace-9',
  changedDays: [],
}

describe('RevisionConflictBanner', () => {
  it('preview 冲突展示重新预览按钮并发出事件', async () => {
    const wrapper = mount(RevisionConflictBanner, {
      props: { conflict, refreshing: false },
    })

    const text = wrapper.get('[data-testid="conflict-banner"]').text()
    expect(text).toContain('行程版本已变化')
    expect(text).toContain('v3')
    expect(text).toContain('trace-9')

    await wrapper.get('[data-testid="conflict-reload"]').trigger('click')
    expect(wrapper.emitted('re-preview')).toHaveLength(1)
  })

  it('rollback 冲突不渲染重新预览按钮', () => {
    const wrapper = mount(RevisionConflictBanner, {
      props: { conflict: { ...conflict, kind: 'rollback' }, refreshing: false },
    })

    expect(wrapper.find('[data-testid="conflict-reload"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="conflict-banner"]').text()).toContain('行程版本已变化')
  })

  it('changedDays 非空时展示按天差异', () => {
    const wrapper = mount(RevisionConflictBanner, {
      props: { conflict: { ...conflict, changedDays: [1, 2] }, refreshing: false },
    })

    expect(wrapper.get('[data-testid="conflict-banner"]').text()).toContain(
      '相比你基于的版本，第 1、2 天内容已变化',
    )
  })

  it('changedDays 为空时展示内容未变化', () => {
    const wrapper = mount(RevisionConflictBanner, {
      props: { conflict: { ...conflict, changedDays: [] }, refreshing: false },
    })

    expect(wrapper.get('[data-testid="conflict-banner"]').text()).toContain(
      '版本号已更新，内容未变化',
    )
  })
})
