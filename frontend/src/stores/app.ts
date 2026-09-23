import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export const useAppStore = defineStore('app', () => {
  const profile = ref(import.meta.env.VITE_APP_PROFILE ?? 'local')
  const isReady = ref(false)

  const isMockProfile = computed(
    () => __APP_PROFILE__ === 'mock' && import.meta.env.VITE_ENABLE_MOCK === 'true',
  )

  function markReady(): void {
    isReady.value = true
  }

  return {
    profile,
    isReady,
    isMockProfile,
    markReady,
  }
})
