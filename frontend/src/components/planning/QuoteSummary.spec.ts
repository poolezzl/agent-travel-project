import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { QuoteResponse } from '@/api/types'
import QuoteSummary from './QuoteSummary.vue'

const quote: QuoteResponse = {
  quoteId: 'q1',
  quoteReference: 'qr-1',
  planCount: 1,
  points: 10,
  expiresAtEpochSecond: 1_800_000_000,
}

describe('QuoteSummary', () => {
  it('创建任务按钮：creating 为 true 时禁用', () => {
    const wrapper = mount(QuoteSummary, { props: { quote, creating: true } })

    expect(wrapper.get('[data-testid="create-task-button"]').element.hasAttribute('disabled')).toBe(
      true,
    )
  })

  it('创建任务按钮：creating 为 false 时可用', () => {
    const wrapper = mount(QuoteSummary, { props: { quote, creating: false } })

    expect(wrapper.get('[data-testid="create-task-button"]').element.hasAttribute('disabled')).toBe(
      false,
    )
  })
})
