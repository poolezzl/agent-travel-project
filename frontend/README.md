# Frontend

S03 的 Vue 3 前端工程骨架，技术基线为 Vue 3、Vite、TypeScript、Pinia、Vue Router、Element Plus 和 Vitest。
本步骤只提供可启动的基础壳、配置和测试示例，不包含登录、行程、地图、任务、余额或业务 API。

## 先决条件

- Node.js `>=22.12.0`
- npm `>=10.9.0`

检查版本：

```powershell
node --version
npm --version
```

## 安装与命令

在仓库根目录执行：

```powershell
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend run format:check
npm --prefix frontend run test:unit
npm --prefix frontend run build:mock
npm --prefix frontend run build
```

开发服务器：

```powershell
# development mode：只启动骨架，不启用 Mock 数据
npm --prefix frontend run dev

# mock mode：显式启用配置标志；当前仍只显示骨架状态页
npm --prefix frontend run dev:mock
```

默认地址为 `http://127.0.0.1:5173`。构建产物在 `frontend/dist/`，预览可使用：

```powershell
npm --prefix frontend exec vite preview -- --host 127.0.0.1 --port 4173
```

## 环境配置

- `frontend/.env.example`：本地配置模板，复制为未跟踪的 `.env.local` 后按需填写。
- `frontend/.env.mock`：可审计的 Mock profile 开关，仅包含占位地址；它不包含接口响应或业务 Fixture。
- Vite mode 使用 `development`/`mock`，应用 profile 显示为 `local`/`mock`。Vite 不允许把 `local` 直接作为 mode，因为它与 `.env.local` 后缀冲突。
- `VITE_ENABLE_MOCK` 只有在 `mock` mode 下才会被应用视为 Mock 已启用。
- 只允许公开的浏览器配置进入 `VITE_*`；服务端密钥、刷新令牌、完整私密行程、余额和供应商原始响应不得进入前端环境变量或浏览器存储。

## 目录

```text
frontend/
├── .env.example
├── .env.mock
├── eslint.config.mjs
├── index.html
├── package.json
├── package-lock.json
├── tsconfig*.json
├── vite.config.ts
├── vitest.config.ts
└── src/
    ├── App.vue
    ├── env.d.ts
    ├── main.ts
    ├── router/index.ts
    ├── stores/app.ts
    ├── stores/app.spec.ts
    ├── styles/index.css
    └── views/
        ├── ScaffoldView.vue
        └── ScaffoldView.spec.ts
```

## S03 边界

- Pinia store 仅示范运行 profile 和 ready 状态，不持久化身份、令牌、行程、偏好或余额。
- Router 仅提供骨架页；没有鉴权守卫、业务路由或 API 客户端。
- Element Plus 在应用入口统一注册，供后续页面复用。
- S02 OpenAPI/Agent Schema 是后续 API 类型的唯一事实来源；本步骤不复制业务 DTO。
- 真实业务页面、REST/SSE、登录态、来源/降级展示、地图和 E2E 属于后续 S07/S14/S21 等步骤。
