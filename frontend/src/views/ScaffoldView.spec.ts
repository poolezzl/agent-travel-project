import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import ScaffoldView from './ScaffoldView.vue'

vi.stubGlobal('__APP_PROFILE__', 'mock')

describe('ScaffoldView', () => {
  it('mounts with Pinia and Element Plus', async () => {
    const wrapper = mount(ScaffoldView, {
      global: {
        plugins: [createPinia(), ElementPlus],
      },
    })

    await wrapper.vm.$nextTick()

    expect(wrapper.get('h1').text()).toBe('前端工程骨架已就绪')
    expect(wrapper.text()).toContain('不包含业务数据或真实接口')
    expect(wrapper.find('.el-alert').exists()).toBe(true)
  })
})
