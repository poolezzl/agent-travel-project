/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_APP_PROFILE?: 'local' | 'mock'
  readonly VITE_API_BASE_URL?: string
  readonly VITE_ENABLE_MOCK?: 'true' | 'false'
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare const __APP_PROFILE__: string
