# Frontend

S03 建立的 Vue 3 前端工程骨架（Vue 3、Vite、TypeScript、Pinia、Vue Router、Element Plus、Vitest），
S07 在其上交付「开题演示前端」（明示 Mock 的演示页面）。S14 在 local/production 构建下将 `/` 切换为
真实规划页（登录、报价、建任务、预扣、SSE 进度、取消/退款），mock 构建仍保留 S07 演示页。

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
# development mode：profile = local，`/` 为 S14 规划页，走同源 /api 反代到后端 8080
npm --prefix frontend run dev

# mock mode：profile = mock 且 VITE_ENABLE_MOCK=true，显示 S07 演示页
npm --prefix frontend run dev:mock
```

默认地址为 `http://127.0.0.1:5173`。构建产物在 `frontend/dist/`，预览可使用：

```powershell
npm --prefix frontend exec vite preview -- --host 127.0.0.1 --port 4173
```

## S07 演示页入口与内容

| 路由                         | 视图                            | 说明                                                                                                |
| ---------------------------- | ------------------------------- | --------------------------------------------------------------------------------------------------- |
| `/`（mock 构建）             | `src/views/S07DemoView.vue`     | S07 开题演示页（输入表单、模拟进度、时间轴、地图占位、方案对比、社区静态样张），仅 mock 构建注册    |
| `/`（production/local 构建） | `src/views/S14PlanningView.vue` | S14 真实规划页（认证、报价、建任务、预扣、SSE 进度、取消/退款）；演示路由不注册，fixture 不进入主包 |
| `/scaffold`                  | `src/views/ScaffoldView.vue`    | S03 骨架回归页，保留其单元测试价值                                                                  |

演示页的硬约束：

- 顶部常驻「**演示数据，未接入真实接口**」声明，加载、空、错误、部分完成状态下同样可见。
- 所有景点、POI、时长、天气、票价、政策、社区互动数字均为合成内容，并显式标注
  `Mock / 示例 / 估算 / 待核验 / unknown`；不使用空值或占位数字补齐“看起来完整”的结果。
- 地图区块是静态占位，不加载地图 SDK、瓦片或外部图片，仅列出合成 POI ID 与示意位置。
- 提交表单只驱动页面本地模拟进度，不发起任何网络请求，也不写入浏览器持久化存储。
- 「契约信封（S02 形状）」区块按 S02 公共信封字段展示
  `schema_version/task_id/trace_id/request_id/generated_at/status/source_metadata`，
  并显式展示 `message`、`error_code` 与 `degraded_fields`，不静默吞掉降级信息。

### Mock 状态选择器

- 仅在 `mock` profile（`__APP_PROFILE__ === 'mock'`）且 `VITE_ENABLE_MOCK === 'true'` 时渲染，
  用于演示成功 / 加载 / 空 / 部分完成 / 错误五种状态。
- `production` 与 `local` 构建不注册演示路由（`/` 回到骨架页），且演示页组件与全部 fixture
  通过动态导入 + 编译期常量分支被整体消除，不会出现在生产主包中
  （可用 `grep "北京三日" dist/assets/*.js` 复验，应为 0 命中）。
- 单元测试与 Playwright 用例都覆盖「选择器只在显式 mock profile 下存在」这一约束；
  路由门禁由 `src/router/index.spec.ts` 覆盖两个分支。

## S14 真实端到端前端（local/production）

S14 将 local/production 构建的 `/` 切换为真实规划页 `S14PlanningView`，跑通
「注册/登录 → 填表 → 服务端报价 → 建任务预扣 → SSE 进度 → 终态 → 取消/退款」链路。

- API 层：`src/api/{http,types,auth,points,tasks,taskStatus}.ts`，字段严格 snake_case，对齐
  `contracts/openapi/s08-public.yaml` 与 `s11-tasks.yaml`。
- SSE 用 `fetch + ReadableStream` 手动解析（后端只接受 `Authorization: Bearer` 头，禁用 `EventSource`）。
- 令牌只驻留 Pinia 内存，不写 `localStorage`/`sessionStorage`，刷新页面需重新登录。
- 金额口径：积分用整数 `points`；预算用 `currency` + 整数 `amount_minor`（CNY 分）。

### 本地联调

后端（Java，默认 8080，需 MySQL/Redis）启动后：

```powershell
npm --prefix frontend run dev   # http://127.0.0.1:5173，/api 由 vite server.proxy 反代到 127.0.0.1:8080
```

生产构建使用相对 `/api`，由部署层反代；前端不直连跨域 `http://127.0.0.1:8080`（后端无 CORS 配置）。

### 环境变量

| 变量                | 说明                                                                              |
| ------------------- | --------------------------------------------------------------------------------- |
| `VITE_APP_PROFILE`  | `local` / `mock`（与 `--mode` 对应）                                              |
| `VITE_API_BASE_URL` | 为空时走同源 `/api`；`.env.mock` 中为占位 `http://127.0.0.1:8080`，仅供 mock 构建 |
| `VITE_ENABLE_MOCK`  | 仅 `mock` mode 下为 `'true'` 时启用 S07 演示页                                    |

### 已知 PENDING

- `PENDING-S14-01`：后端未提供行程结果查询接口（`GET /v1/tasks/{id}` 只返回 TaskView），结果区为显式空态，不渲染 fixture。
- `PENDING-002`（高德 Key）、`PENDING-003`（和风天气 Key）未关闭；本地后端用 `MockAgentExecutor`，
  因此只能称「本地 Mock 供应商端到端」，未配置真实供应商时不宣称真实端到端通过。

## Playwright 演示证据

首次使用需要安装浏览器（国内网络建议指定镜像）：

```powershell
npm --prefix frontend exec playwright install chromium
# 或使用镜像：
# $env:PLAYWRIGHT_DOWNLOAD_HOST='https://cdn.npmmirror.com/binaries/playwright'
# npm --prefix frontend exec playwright install chromium
```

运行：

```powershell
npm --prefix frontend run test:e2e
npm --prefix frontend run test:e2e:report
```

- 配置：`frontend/playwright.config.ts`；用例：`frontend/e2e/s07-demo.spec.ts`。
- 覆盖 `desktop`（1440×900）与 `mobile`（Pixel 5）两个项目，并额外断言 320px 窄屏无横向溢出。
- 截图证据提交到 `docs/progress/s07-artifacts/`（桌面/移动 × 成功/加载/空/部分/错误），
  演示动线见 `docs/progress/s07-artifacts/demo-flow.md`。
- 截图只代表当前合成 UI 的证据，不构成真实接口或 S14 端到端验收。
- Playwright 会以 `npm run dev:mock` 自动拉起开发服务器；若已有 `127.0.0.1:5173` 在运行则复用。

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
├── e2e/
│   └── s07-demo.spec.ts
├── eslint.config.mjs
├── index.html
├── package.json
├── package-lock.json
├── playwright.config.ts
├── tsconfig*.json
├── vite.config.ts
├── vitest.config.ts
└── src/
    ├── App.vue
    ├── env.d.ts
    ├── main.ts
    ├── api/
    │   ├── auth.ts
    │   ├── http.ts
    │   ├── points.ts
    │   ├── taskStatus.ts
    │   ├── tasks.ts
    │   └── types.ts
    ├── components/
    │   ├── s07/
    │   │   └── ...（S07 演示组件）
    │   └── planning/
    │       ├── AuthPanel.vue
    │       ├── PlanningForm.vue
    │       ├── QuoteSummary.vue
    │       ├── TaskStatusBanner.vue
    │       ├── TaskProgress.vue
    │       ├── PlanResultPanel.vue
    │       ├── SourceBadges.vue
    │       └── ErrorPanel.vue
    ├── composables/
    │   ├── sseParser.ts
    │   └── useTaskStream.ts
    ├── fixtures/
    │   ├── s02.ts        # 与 S02 公共信封一致的合成 envelope
    │   └── s07.ts        # S07 演示组合 view model 与默认场景
    ├── router/index.ts
    ├── stores/
    │   ├── app.ts
    │   ├── planning.ts
    │   └── session.ts
    ├── styles/index.css
    └── views/
        ├── ScaffoldView.vue
        ├── S07DemoView.vue
        └── S14PlanningView.vue
```

## 边界

- Pinia store 只示范运行 profile 与 ready 状态，不持久化身份、令牌、行程、偏好或余额。
- `src/fixtures/s02.ts` 与 `src/fixtures/s07.ts` 是**前端本地合成 fixture**：前者复刻 S02 公共信封形状，
  后者是仅供页面组合渲染的 view model；两者都不冒充后端最终 DTO，也不修改冻结的 S02 Schema/OpenAPI。
- S02 OpenAPI/Agent Schema 仍是后续 API 类型的唯一事实来源；S14 将把本页 fixture 切换为 S08-S13 真实接口。
- 不使用 `v-html`、浏览器持久化、真实用户数据、外链图片或未授权内容。
