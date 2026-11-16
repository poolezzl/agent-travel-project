import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { SourceMetadata } from '@/api/types'
import SourceBadges from './SourceBadges.vue'

const sample: SourceMetadata = {
  source_type: 'poi',
  source_record_id: 'poi-1',
  retrieved_at: '2026-11-11T00:00:00Z',
  valid_until: null,
  freshness_status: 'unknown',
  is_estimated: true,
}

describe('SourceBadges', () => {
  it('无来源时展示显式空态，而非空壳或默认值', () => {
    const wrapper = mount(SourceBadges, { props: { sources: [] } })

    expect(wrapper.get('[data-testid="source-empty"]').text()).toContain('没有可展示的来源记录')
    expect(wrapper.text()).not.toContain('poi-1')
  })

  it('有来源时渲染来源/更新时间/时效/估算徽标', () => {
    const wrapper = mount(SourceBadges, { props: { sources: [sample] } })

    expect(wrapper.text()).toContain('poi')
    expect(wrapper.text()).toContain('poi-1')
    expect(wrapper.text()).toContain('估算')
    expect(wrapper.find('[data-testid="source-empty"]').exists()).toBe(false)
  })
})
