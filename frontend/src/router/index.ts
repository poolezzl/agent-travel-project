import { createRouter, createWebHistory } from 'vue-router'

// Mock 防线（编译期）：演示页路由与全部 fixture 只允许进入 mock 构建的模块图。
// production/local 构建下 __APP_PROFILE__ 被静态替换为 'local'，本条件恒为 false，
// 演示页的动态导入会被整体消除，主包不包含任何演示数据或状态选择器入口。
// typeof 守卫仅为单元测试环境兜底（vitest 未注入该编译期常量时回退到 env 变量）。
const isMockProfile =
  typeof __APP_PROFILE__ === 'undefined'
    ? import.meta.env.VITE_APP_PROFILE === 'mock' && import.meta.env.VITE_ENABLE_MOCK === 'true'
    : __APP_PROFILE__ === 'mock' && import.meta.env.VITE_ENABLE_MOCK === 'true'

const routes = isMockProfile
  ? [
      {
        path: '/',
        name: 's07-demo',
        component: () => import('@/views/S07DemoView.vue'),
        meta: { title: 'S07 开题演示页（Mock）' },
      },
      {
        path: '/scaffold',
        name: 'scaffold',
        component: () => import('@/views/ScaffoldView.vue'),
        meta: { title: 'S03 前端工程骨架回归页' },
      },
    ]
  : [
      {
        path: '/',
        name: 'planning',
        component: () => import('@/views/S14PlanningView.vue'),
        meta: { title: 'S14 M1 真实端到端前端' },
      },
      {
        path: '/scaffold',
        name: 'scaffold',
        component: () => import('@/views/ScaffoldView.vue'),
        meta: { title: 'S03 前端工程骨架回归页' },
      },
    ]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

export default router
