import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useAppStore } from './app'

vi.stubGlobal('__APP_PROFILE__', 'mock')

beforeEach(() => {
  setActivePinia(createPinia())
})

afterEach(() => {
  vi.unstubAllEnvs()
})

describe('app store scaffold', () => {
  it('tracks application readiness', () => {
    const store = useAppStore()

    expect(store.isReady).toBe(false)

    store.markReady()

    expect(store.isReady).toBe(true)
  })

  it('requires the explicit mock mode and flag', () => {
    vi.stubEnv('VITE_ENABLE_MOCK', 'true')
    const store = useAppStore()

    expect(store.isMockProfile).toBe(true)
  })

  it('does not enable mock when the flag is absent', () => {
    const store = useAppStore()

    expect(store.isMockProfile).toBe(false)
  })
})
