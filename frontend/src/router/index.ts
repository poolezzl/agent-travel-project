import { createRouter, createWebHistory } from 'vue-router'

import ScaffoldView from '@/views/ScaffoldView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'scaffold',
      component: ScaffoldView,
    },
  ],
})

export default router
