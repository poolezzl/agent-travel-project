import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { TimelineEntry } from '@/stores/revision'
import VersionTimeline from './VersionTimeline.vue'

const entry: TimelineEntry = {
  versionId: 'aaaaaaaa-0000-0000-0000-000000000000',
  parentVersionId: 'bbbbbbbb-0000-0000-0000-000000000000',
  kind: 'change',
  summary: '替换第 1 天景点为天坛',
  affectedDays: [1],
  changeCount: 1,
  etag: 'etag-1',
  contentHash: 'hash-1',
  atLocal: '2026/11/20 10:00:00',
  reused: false,
}

describe('VersionTimeline', () => {
  it('空态文案标注 PENDING-S21-01', () => {
    const wrapper = mount(VersionTimeline, {
      props: { timeline: [], currentVersionId: null, rollingBack: false },
    })

    expect(wrapper.get('[data-testid="revision-timeline"]').text()).toContain(
      '历史版本列表接口未提供（PENDING-S21-01）',
    )
  })

  it('当前版本标「当前」，非当前版本可二次确认回滚', async () => {
    const wrapper = mount(VersionTimeline, {
      props: {
        timeline: [entry],
        currentVersionId: 'bbbbbbbb-0000-0000-0000-000000000000',
        rollingBack: false,
      },
    })

    // 当前版本是 parent，因此 entry 是非当前版本，提供回滚按钮。
    await wrapper.get('[data-testid="rollback-button"]').trigger('click')
    await wrapper.get('[data-testid="rollback-confirm"]').trigger('click')
    expect(wrapper.emitted('rollback')).toEqual([['aaaaaaaa-0000-0000-0000-000000000000']])
  })

  it('当前版本不提供回滚按钮', () => {
    const wrapper = mount(VersionTimeline, {
      props: {
        timeline: [entry],
        currentVersionId: entry.versionId,
        rollingBack: false,
      },
    })

    expect(wrapper.find('[data-testid="rollback-button"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('当前')
  })
})
