import { createRouter, createWebHistory } from 'vue-router'

import S07DemoView from '@/views/S07DemoView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 's07-demo',
      component: S07DemoView,
      meta: { title: 'S07 开题演示页（Mock）' },
    },
    {
      path: '/scaffold',
      name: 'scaffold',
      component: () => import('@/views/ScaffoldView.vue'),
      meta: { title: 'S03 前端工程骨架回归页' },
    },
  ],
})

export default router
