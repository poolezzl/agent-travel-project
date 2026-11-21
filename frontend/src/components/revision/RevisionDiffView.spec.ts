import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { AtomicChange } from '@/api/types'
import RevisionDiffView from './RevisionDiffView.vue'

describe('RevisionDiffView', () => {
  it('按天分组渲染，未知路径原样展示并标注', () => {
    const changes: AtomicChange[] = [
      { operation: 'add', path: '/days/1/segments/0', value: { name: '天坛' } },
      { operation: 'replace', path: '/unknown/x', value: '无法定位' },
    ]
    const wrapper = mount(RevisionDiffView, { props: { changes, plan: null } })

    expect(wrapper.get('[data-testid="revision-diff-day-1"]').text()).toContain('新增')
    expect(wrapper.get('[data-testid="revision-diff-day-unknown"]').text()).toContain('原样展示')
    expect(wrapper.get('[data-testid="revision-diff-day-unknown"]').text()).toContain('/unknown/x')
  })

  it('渲染未受影响与未核对日期标注', () => {
    const wrapper = mount(RevisionDiffView, {
      props: { changes: [], plan: null, unaffectedDays: [2, 3], unverifiedDays: [4] },
    })

    expect(wrapper.text()).toContain('未受影响的日期：第 2、3 天（已验证未变更）')
    expect(wrapper.text()).toContain('未核对日期：第 4 天')
  })
})
