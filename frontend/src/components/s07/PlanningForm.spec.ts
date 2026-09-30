import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import type { PlanningInput } from '@/fixtures/s07'
import { defaultPlanningInput } from '@/fixtures/s07'
import PlanningForm from './PlanningForm.vue'

function mountForm(overrides: Partial<{ submitting: boolean; disabled: boolean }> = {}) {
  return mount(PlanningForm, {
    props: {
      modelValue: { ...defaultPlanningInput, mustVisit: [...defaultPlanningInput.mustVisit] },
      ...overrides,
    },
  })
}

describe('PlanningForm', () => {
  it('提交合法输入时携带解析后的必去点', async () => {
    const wrapper = mountForm()

    await wrapper.findAll('textarea')[0]?.setValue('合成·A\n合成·B，合成·C')
    await wrapper.get('form').trigger('submit')

    const emitted = wrapper.emitted('submit')
    expect(emitted).toHaveLength(1)
    const payload = emitted?.[0]?.[0] as PlanningInput
    expect(payload.mustVisit).toEqual(['合成·A', '合成·B', '合成·C'])
    expect(payload.city).toBe('北京')
  })

  it('城市为空时阻止提交并给出可读错误', async () => {
    const wrapper = mountForm()

    await wrapper.get('select').setValue('')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('submit')).toBeUndefined()
    expect(wrapper.get('[role="alert"]').text()).toContain('请选择城市')
  })

  it('返回日期早于出发日期时阻止提交', async () => {
    const wrapper = mountForm()
    const dateInputs = wrapper.findAll('input[type="date"]')

    await dateInputs[0]?.setValue('2026-10-12')
    await dateInputs[1]?.setValue('2026-10-10')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('submit')).toBeUndefined()
    expect(wrapper.get('[role="alert"]').text()).toContain('返回日期不能早于出发日期')
  })

  it('必去点超过 5 个时阻止提交', async () => {
    const wrapper = mountForm()

    await wrapper.findAll('textarea')[0]?.setValue('A\nB\nC\nD\nE\nF')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('submit')).toBeUndefined()
    expect(wrapper.get('[role="alert"]').text()).toContain('必去点最多 5 个')
  })

  it('模拟进行中禁用提交按钮且不重复触发', async () => {
    const wrapper = mountForm({ submitting: true })

    const button = wrapper.get('button[type="submit"]')
    expect(button.attributes('disabled')).toBeDefined()
    expect(button.text()).toContain('正在模拟编排')

    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  it('所有控件都是带标签的原生表单元素，键盘可达', () => {
    const wrapper = mountForm()

    expect(wrapper.findAll('label').length).toBeGreaterThanOrEqual(6)
    expect(wrapper.findAll('fieldset legend')).toHaveLength(1)
    expect(wrapper.get('button[type="submit"]').attributes('type')).toBe('submit')

    for (const control of wrapper.findAll('input, select, textarea, button')) {
      expect(control.attributes('tabindex')).toBeUndefined()
    }
  })
})
