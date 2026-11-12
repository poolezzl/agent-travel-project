import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { PlanningFormState } from '@/stores/planning'
import PlanningForm from './PlanningForm.vue'

function baseForm(): PlanningFormState {
  return {
    city: '北京',
    startDate: '2026-12-01',
    endDate: '2026-12-03',
    partySize: 2,
    budgetYuan: 3000,
    mustVisit: [],
    preference: '',
    transport: '混合',
    planCount: 1,
  }
}

describe('PlanningForm 校验（预检）', () => {
  it('日期非三日时阻止提交', async () => {
    const wrapper = mount(PlanningForm, {
      props: { modelValue: { ...baseForm(), endDate: '2026-12-02' } },
    })

    await wrapper.find('form').trigger('submit')

    expect(wrapper.text()).toContain('固定三日')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('预算为 0 时阻止提交', async () => {
    const wrapper = mount(PlanningForm, {
      props: { modelValue: { ...baseForm(), budgetYuan: 0 } },
    })

    await wrapper.find('form').trigger('submit')

    expect(wrapper.text()).toContain('预算')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('合法输入触发 submit 且载荷为三日北京', async () => {
    const wrapper = mount(PlanningForm, { props: { modelValue: baseForm() } })

    await wrapper.find('form').trigger('submit')

    const emitted = wrapper.emitted('submit')
    expect(emitted).toHaveLength(1)
    expect(emitted?.[0]?.[0]).toMatchObject({ city: '北京', planCount: 1 })
  })

  it('报价中 submitting 为 true 时提交按钮禁用', () => {
    const wrapper = mount(PlanningForm, {
      props: { modelValue: baseForm(), submitting: true },
    })

    expect(wrapper.get('button[type="submit"]').element.hasAttribute('disabled')).toBe(true)
  })
})
