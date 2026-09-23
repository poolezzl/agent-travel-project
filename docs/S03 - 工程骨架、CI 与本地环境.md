# S03 - 工程骨架、CI 与本地环境

| 项目 | 内容 |
|---|---|
| 步骤 | S03 - 工程骨架、CI 与本地环境 |
| 阶段 | M0 |
| 前置依赖 | S02 已完成，证据见 [progress/S02.md](progress/S02.md) |
| 主责 | R5（backend）、R4（agent-service）、R6（frontend） |
| 当前状态 | **进行中**：R5 backend 已导入并有独立 MySQL/Flyway 复验；R6 frontend 已补齐；R4 agent-service 未出现在当前基线，完整 S03 Gate 阻塞 |
| 范围 | 仅工程骨架、配置、测试基础、CI 和本地依赖；不实现业务逻辑 |

## 1. S03 概述、依赖关系、角色分工与验收标准

### 1.1 目标与依赖

S03 承接 S02 已冻结的 Agent JSON Schema、OpenAPI、样例和错误码，为 Java、Python、Vue 三端建立可构建、可测试、可启动的工程基础。S03 不新增业务接口、Agent 决策、数据库业务表或前端业务页面。

依赖关系如下：

```text
S02 Schema/OpenAPI 已冻结
        │
        ├── R5：backend（Java 21 / Spring Boot 3 / Maven）
        ├── R4：agent-service（Python 3.11+ / FastAPI / LangGraph / Pydantic v2）
        └── R6：frontend（Vue 3 / Vite / TypeScript / Pinia / Element Plus）
                  │
                  └── 三端构建、CI、本地依赖和新环境验收
```

### 1.2 角色分工

| 角色 | S03 主责 | 本文档确认的当前状态 |
|---|---|---|
| R5 | Spring Boot/Maven 工程、MySQL/Flyway、profile、健康检查、后端测试和后端 CI | 已由 `origin/dev-r5` 导入当前 `dev-r6`；R6 不改写其文件 |
| R4 | FastAPI/LangGraph/Pydantic v2 工程、Python 锁定依赖、ruff/格式化、pytest、Mock profile 和健康检查 | **PENDING-S03-R4-001**：当前可见的 `origin/dev-r4` 无该工程，`agent-service/` 只有 S00 占位 README |
| R6 | Vue 3/Vite/TypeScript/Pinia/Vue Router/Element Plus 工程、ESLint/Prettier、Vitest、Mock profile 和前端 CI | 本次新增/补齐 |
| 协作项 | MySQL/Redis/Chroma 本地依赖、跨栈 CI、完整启动说明和 Gate 证据 | R6 提供配置与文档；R4 缺口使全量 Gate 保持阻塞 |

### 1.3 验收标准与当前结论

| 验收项 | 标准 | 当前结论 |
|---|---|---|
| 三端工程 | Java、Python、Vue 均可锁定依赖、构建、测试、启动和健康检查 | **阻塞**：Java、Vue 已有工程；Python 工程缺失 |
| 格式化/lint/单测 | 三端均有可执行入口 | **部分完成**：Vue 完成；Python 缺失；R5 后端有编译/测试/Enforcer，但尚无独立格式化/lint 插件 |
| profile/Mock | 三端默认和 Mock 行为显式、无真实密钥依赖 | **部分完成**：backend/frontend 已有；agent-service 缺失 |
| 本地依赖 | Compose 只启动 MySQL、Redis、Chroma，并有不依赖 Docker 的说明 | 配置已提供；本机无 Docker，Compose 运行态待有 Docker 环境验证 |
| CI | Java/Python/前端构建与单测、Schema/OpenAPI、Flyway 空库、密钥、依赖安全扫描 | 定义已提供；Python job 显式失败并报告 PENDING，不伪装成绿色 |
| 新环境路径 | 阅读 README 后按一套路径全部构建并启动 | **尚未满足**：R4 工程缺失；当前仅可完整执行 R5+R6 路径 |

因此，本文档是完整的 S03 设计和运行入口，但不能作为“完整 S03 已通过”的证据。全量 Gate 只能在 R4 提供工程并由三端在干净环境实测后关闭。

## 2. 三端工程骨架现状

### 2.1 R5 已完成并导入：backend

R5 已完成并由独立提交保留的主要文件：

- `backend/pom.xml`
- `backend/src/main/java/com/agenttravel/backend/BackendApplication.java`
- `backend/src/main/java/com/agenttravel/backend/health/BackendHealthIndicator.java`
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/application-local.yml`
- `backend/src/main/resources/application-mock.yml`
- `backend/src/main/resources/db/migration/.gitkeep`
- `backend/src/test/java/com/agenttravel/backend/*Tests.java`
- `.github/workflows/backend.yml`
- `deploy/docker-compose.s03.yml`
- `scripts/s03_mysql_verify.ps1`
- `docs/progress/S03.md`
- `docs/progress/S03-D04-独立复验报告.md`

上述文件由 R5 负责，本次 R6 未修改。详细命令和独立复验结果以 [backend/README.md](../backend/README.md) 及 R5 进度证据为准。

### 2.2 R4 当前缺失：agent-service

当前 `agent-service/` 只有 S00 创建的 `README.md`，不存在以下 S03 必需资产：

- `pyproject.toml` 和锁定依赖文件；
- FastAPI 应用入口和健康检查；
- LangGraph/Pydantic v2 基础包结构；
- local/mock 配置；
- ruff/格式检查入口；
- pytest 测试；
- Python CI 可执行入口。

R6 不代写这些文件，也不把占位目录描述为已完成。跨栈 CI 的 `agent-service` job 会先检查上述前置文件；缺失时以非零状态输出 `PENDING`，以便完整 S03 Gate 保持真实阻塞。

### 2.3 R6 本次新增/补全：frontend

R6 新增 Vue 工程、TypeScript/Vite 配置、Pinia 示例、Element Plus 注册、Router 骨架、ESLint/Prettier、Vitest、显式 Mock profile、锁文件和前端说明。骨架页面只显示工程状态，不提供 Fixture、业务 API、登录、行程、地图、任务、钱包或 Agent 功能。

R6 还新增：

- `deploy/docker-compose.s03-local.yml`：MySQL、Redis、Chroma 开发依赖，仅绑定宿主机 `127.0.0.1`；
- `.github/workflows/s03-cross-stack.yml`：三端和质量门禁的统一入口；
- 本文档及 `docs/progress/S03-R6.md`。

## 3. backend：Java 21 / Spring Boot 3 / Maven

### 3.1 工程骨架要点

- Spring Boot `3.5.16`，Java `21`，Maven `>=3.9.6`。
- 最小启动类为 `BackendApplication`；未包含业务 Controller、Service、Repository 或数据库业务表。
- 依赖包括 Web、Actuator、JDBC、Flyway、MySQL Connector/J 和 Spring Boot Test。
- Java 业务服务保持 MySQL 唯一写入方；Python 和前端不得直连业务表。

### 3.2 格式化、lint、单测与依赖锁定

- Maven Enforcer 固定 Java `[21,22)` 和 Maven `[3.9.6,)`。
- Spring Boot Parent/BOM、显式父版本和插件版本提供依赖管理；Maven 没有类似 `package-lock.json` 的完整解析锁文件。
- 当前测试覆盖 Mock context、Actuator 健康、Mock 禁用 DataSource/Flyway、不可用 MySQL 失败和空迁移目录。
- 构建命令：

  ```powershell
  mvn -f backend\pom.xml test
  mvn -f backend\pom.xml verify
  ```

- **已知缺口**：R5 当前 `pom.xml` 未配置 Spotless、Checkstyle 或同类独立格式化/lint 插件。R6 遵守边界不修改 R5 配置；此项需 R5 补齐或由负责人明确接受现状后才能关闭三端统一 lint 验收。

### 3.3 配置分层、profile/Mock 与健康检查

- `application.yml`：公共配置，默认 `local`，数据库连接只从环境变量读取。
- `application-local.yml`：连接 MySQL，启用 Flyway，外部供应商保持关闭。
- `application-mock.yml`：排除 DataSource/Flyway 自动配置，不要求数据库或任何外部 Key。
- Mock 启动：

  ```powershell
  $env:SPRING_PROFILES_ACTIVE = "mock"
  mvn -f backend\pom.xml spring-boot:run
  Invoke-RestMethod http://127.0.0.1:8080/actuator/health
  ```

- 健康检查返回应用名、profile、数据库模式和外部服务状态；Mock 下不伪造数据库健康。

### 3.4 Flyway 空库

- 迁移目录在 S03 有意为空，不创建任何业务表；S04 才新增首个迁移。
- `scripts/s03_mysql_verify.ps1` 使用独立 MySQL 8.4.8、启动两次 local profile，并断言只存在空的 `flyway_schema_history`。
- R5 的独立复验报告显示 D-04 通过；完整 S03 Gate 仍不因此自动通过。

## 4. agent-service：Python 3.11+ / FastAPI / LangGraph / Pydantic v2

### 4.1 S03 目标骨架

R4 提交后应至少具备：

```text
agent-service/
├── pyproject.toml
├── <依赖锁定文件>
├── src/<package>/
│   ├── main.py               # FastAPI 入口
│   ├── config.py             # local/mock 配置
│   └── health.py             # 不泄露凭据的健康检查
└── tests/
```

要求：

- Python `3.11+`；FastAPI、LangGraph、Pydantic v2；
- `ruff format --check .`、`ruff check .`；
- `pytest -q`；
- 依赖版本和锁定文件可用于干净环境复现；
- Mock profile 不调用真实 LLM、地图、天气、MySQL 或业务 API；
- `pip-audit -r requirements.txt` 需要 R4 提供涵盖运行时、ruff 和 pytest 的锁定版本；当前跨栈 CI 使用 `requirements.txt` + `pyproject.toml`（`pip install -r requirements.txt` 和 `pip install --no-deps -e .`），R4 若选用 uv/Poetry 需在合入时对齐该 CI 入口，而不是把安装失败记为通过。
- Python 只能按 S02 OpenAPI 获取上下文/回写步骤结果，不得配置业务 MySQL 凭据；
- 健康检查只报告服务/profile/依赖状态，不输出 Key、Prompt、用户数据或供应商响应。

### 4.2 当前状态

**PENDING-S03-R4-001**：上述文件尚未出现在当前 `dev-r6` 或可见 `origin/dev-r4`。因此以下命令目前不可执行：Python 安装、ruff、pytest、FastAPI 启动和 Agent 健康检查。R4 合入后必须更新本文、`docs/progress/S03-R6.md` 或统一 S03 证据，并在干净环境重跑跨栈 CI。

## 5. frontend：Vue 3 / Vite / TypeScript / Pinia / Element Plus

### 5.1 完整骨架结构

```text
frontend/
├── .env.example
├── .env.mock
├── .prettierignore
├── .prettierrc.json
├── eslint.config.mjs
├── index.html
├── package.json
├── package-lock.json
├── tsconfig.json
├── tsconfig.app.json
├── tsconfig.node.json
├── vite.config.ts
├── vitest.config.ts
└── src/
    ├── App.vue
    ├── env.d.ts
    ├── main.ts
    ├── router/index.ts
    ├── stores/
    │   ├── app.ts
    │   └── app.spec.ts
    ├── styles/index.css
    └── views/
        ├── ScaffoldView.vue
        └── ScaffoldView.spec.ts
```

### 5.2 ESLint、Prettier、TypeScript 与 Vite

- ESLint flat config：JavaScript、TypeScript、Vue recommended 规则，Prettier 冲突规则关闭；禁止显式 `any`，防止组件改写 props。
- Prettier：无分号、单引号、尾逗号、100 列；`node_modules`、`dist`、coverage 不参与格式化。
- TypeScript：strict、未使用符号检查、索引访问检查、`@/` 别名和 Vue 类型检查。
- Vite：只绑定 `127.0.0.1`，固定开发/预览端口，生产构建目标 ES2022；mock 构建保留 source map，production 不保留。

命令：

```powershell
npm --prefix frontend ci
npm --prefix frontend run lint
npm --prefix frontend run format:check
npm --prefix frontend run test:unit
npm --prefix frontend run test:coverage
npm --prefix frontend run build:mock
npm --prefix frontend run build
```

### 5.3 Pinia、Router 与 Element Plus

- `main.ts` 统一安装 Pinia、Vue Router 和 Element Plus。
- `stores/app.ts` 只示范运行 profile、ready 状态和显式 Mock 标志；不存储访问/刷新令牌、完整行程、偏好、余额或角色。
- Router 只有 `/` 骨架路由；鉴权守卫和业务路由不属于 S03。
- `ScaffoldView.vue` 只显示工程组件和 profile 状态，不包含业务数据。

### 5.4 Mock profile

- `npm run dev` 使用 Vite `development` mode，应用 profile 为 `local`，Mock 关闭。
- `npm run dev:mock` 使用 `mock` mode，并加载已提交的 `.env.mock`。
- Mock 只有在 mode 为 `mock` 且 `VITE_ENABLE_MOCK=true` 时才显示为启用；默认/生产构建不能误启用。
- `.env.mock` 没有 Fixture 或真实 Key；本步骤不伪造 API 响应。
- Vite 保留 `development` 作为 mode 名，因为 `local` 与 Vite 的 `.env.local` 保留后缀冲突；应用层仍使用 `local` profile 名。

### 5.5 单元测试基础

- Vitest + jsdom + Vue Test Utils；coverage provider 为 V8。
- store 测试验证初始/ready 状态和显式 Mock 防线。
- view 测试验证 Pinia、Element Plus 和骨架页面可挂载。
- E2E、SSE、弱网、地图和业务状态测试从 S07/S14 以后按步骤加入。

## 6. 本地环境

### 6.1 Docker Compose：只启动 MySQL、Redis、Chroma

`deploy/docker-compose.s03-local.yml` 仅包含以下三个服务：

| 服务 | 固定镜像 | 默认宿主端口 | 持久化与健康检查 |
|---|---|---:|---|
| MySQL | `mysql:8.4.8` | 13306 | named volume；utf8mb4/UTC；`mysqladmin ping` |
| Redis | `redis:7.4.2-alpine` | 16379 | AOF named volume；`redis-cli ping` |
| Chroma | `chromadb/chroma:0.6.3` | 18000 | persistent named volume；v2 heartbeat |

它不启动 Java、Python、Vue、Nginx、LLM、地图或天气服务。R5 的 `deploy/docker-compose.s03.yml` 是 D-04 MySQL 空库专项文件，不能与本地三依赖文件混用。

PowerShell：

```powershell
$env:S03_LOCAL_MYSQL_ROOT_PASSWORD = "<local-only-root-password>"
$env:S03_LOCAL_MYSQL_DATABASE = "trip_planner"
$env:S03_LOCAL_MYSQL_USER = "trip_planner"
$env:S03_LOCAL_MYSQL_PASSWORD = "<local-only-app-password>"
$env:S03_LOCAL_MYSQL_PORT = "13306"
$env:S03_LOCAL_REDIS_PORT = "16379"
$env:S03_LOCAL_CHROMA_PORT = "18000"

docker compose -f deploy\docker-compose.s03-local.yml up -d
docker compose -f deploy\docker-compose.s03-local.yml ps
```

检查：

```powershell
docker compose -f deploy\docker-compose.s03-local.yml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysqladmin ping -h127.0.0.1 -u"$MYSQL_USER"'
docker compose -f deploy\docker-compose.s03-local.yml exec -T redis redis-cli ping
Invoke-RestMethod http://127.0.0.1:18000/api/v2/heartbeat
```

停止但保留数据：

```powershell
docker compose -f deploy\docker-compose.s03-local.yml down
```

只有明确要删除本地开发数据时才执行以下破坏性命令：

```powershell
docker compose -f deploy\docker-compose.s03-local.yml down -v
```

### 6.2 不依赖 Docker 的启动步骤

#### Linux（推荐的裸机开发路径）

1. 安装 MySQL 8 和 Redis 7，启动服务并设置开机自启。
2. 创建 `trip_planner` 数据库和最小权限用户；数据库使用 `utf8mb4`，服务时区设置为 UTC。凭据只放进当前进程或受限本地环境文件。
3. 检查：

   ```bash
   mysqladmin --host=127.0.0.1 --port=3306 --user=trip_planner --password ping
   redis-cli -h 127.0.0.1 -p 6379 ping
   ```

4. 为 Chroma 建独立 Python 3.11 虚拟环境并固定与 Compose 相同的版本：

   ```bash
   python3.11 -m venv ~/.venvs/agent-travel-chroma
   source ~/.venvs/agent-travel-chroma/bin/activate
   python -m pip install chromadb==0.6.3
   chroma run --host 127.0.0.1 --port 8000 --path "$HOME/.local/share/agent-travel/chroma"
   ```

5. 另一个终端检查 `curl --fail http://127.0.0.1:8000/api/v2/heartbeat`。

#### Windows（无 Docker）

1. 使用 MySQL 8 官方安装程序启动本机服务，并按上文创建 UTF-8/UTC 数据库与最小权限用户。
2. Redis 官方不提供原生 Windows 生产版本；开发机可在不使用 Docker 的 WSL 环境安装 Redis 7，或由团队提供受控的远程/本地 Redis 7 实例。不得用行为不明的兼容实现冒充已验收 Redis。
3. 用 Python 3.11 虚拟环境安装 `chromadb==0.6.3`，执行相同 `chroma run` 命令（路径改为受限 Windows 本地目录）。
4. 用 `mysqladmin ping`、`redis-cli ping` 和 Chroma heartbeat 分别验收。若 WSL/Redis 条件不具备，应记录为环境阻塞，不把它写成通过。

## 7. CI 流水线

### 7.1 入口

- `.github/workflows/backend.yml`：R5 的后端专项流水线，本次未修改。
- `.github/workflows/s03-cross-stack.yml`：R6 新增的跨栈流水线。

### 7.2 必需检查映射

| S03 要求 | CI job/步骤 | 当前状态 |
|---|---|---|
| Java 构建与单测 | `backend` / Maven `verify` | 已定义；R5 有历史实测证据 |
| Flyway 空库 | `backend` / `s03_mysql_verify.ps1` | 已定义；R5 D-04 独立复验通过 |
| Python 构建与单测 | `agent-service` / manifest、安装、ruff、pytest | **阻塞**：R4 工程文件缺失，job 明确失败 |
| 前端构建与单测 | `frontend` / npm ci、lint、format、Vitest、两种 build | 已定义，本地已实测 |
| JSON Schema | `contracts` / `validate_s02_contracts.py` | 已定义 |
| OpenAPI | `contracts` / `openapi-spec-validator` | 已定义 |
| 密钥扫描 | `security` / 高置信私钥和云凭据模式 | 已定义；不输出密钥值 |
| 依赖安全扫描 | npm audit（含开发依赖）、OWASP Dependency-Check、Python pip-audit（R4 合入后） | 前端本地审计 0 漏洞；Java/Python 需在 CI 真正执行 |

流水线故意不使用 `continue-on-error` 隐藏 Python 缺口。只有所有 job 均绿色且有对应运行记录，才可声称 S03 CI 通过。

## 8. 新环境完整启动验收路径

### 8.1 当前可执行的 R5 + R6 路径

从仓库根目录开始：

1. 检查分支和工具：

   ```powershell
   git branch --show-current
   # 预期 dev-r6
   java -version
   mvn -version
   python --version
   node --version
   npm --version
   ```

2. 校验 S02 契约：

   ```powershell
   python -m venv .venv-s03
   .\.venv-s03\Scripts\python -m pip install jsonschema PyYAML openapi-spec-validator
   .\.venv-s03\Scripts\python scripts\validate_s02_contracts.py
   .\.venv-s03\Scripts\openapi-spec-validator contracts\openapi\agent-business.yaml
   ```

3. 按第 6 节启动 MySQL、Redis、Chroma；无 Docker 时按裸机路径。
4. 构建并测试后端：

   ```powershell
   mvn -f backend\pom.xml verify
   ```

5. 无数据库启动后端 Mock profile并检查健康：

   ```powershell
   $env:SPRING_PROFILES_ACTIVE = "mock"
   mvn -f backend\pom.xml spring-boot:run
   # 新终端：Invoke-RestMethod http://127.0.0.1:8080/actuator/health
   ```

6. 安装、检查和构建前端：

   ```powershell
   npm --prefix frontend ci
   npm --prefix frontend run check
   npm --prefix frontend run build
   npm --prefix frontend run dev:mock
   # 浏览器访问 http://127.0.0.1:5173
   ```

7. 运行 R5 的隔离 Flyway 空库复验（需要 Java 21、Maven 和 Docker）：

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\s03_mysql_verify.ps1
   ```

### 8.2 完整三端路径的当前阻塞

第 8.1 节不能启动 Python Agent 服务，因为当前没有 R4 工程或命令。R4 合入后必须追加并实测以下步骤，而不能由本文猜测具体包名/启动命令：

1. 按 R4 提供的锁文件安装 Python 依赖；
2. 执行 `ruff format --check .`、`ruff check .` 和 `pytest -q`；
3. 使用明确的 Mock profile 启动 FastAPI；
4. 检查 Agent Service 健康接口；
5. 重跑 `.github/workflows/s03-cross-stack.yml`，要求所有 job 绿色；
6. 更新统一 S03 Gate 证据。

因此，当前必须写为“R5+R6 路径通过/待本机条件验证，完整三端路径阻塞”，不得写成“新环境全部启动通过”。

## 9. 风险、已知限制、回滚与后续依赖

### 9.1 风险与限制

- **PENDING-S03-R4-001（阻断）**：Agent Service 工程缺失，Python build/test/health 无法执行。
- **PENDING-S03-R5-LINT（阻断统一 lint 口径）**：后端无专用格式化/lint 插件；R6 不越界改写 R5 配置。
- 当前开发机的 Bash PATH 暴露 Java 17 且没有 Maven/Docker；R5 已有独立 Java 21/MySQL 复验记录，但 R6 本轮不能把当前机的后端/Compose 未运行项重新标为通过。
- 本地 npm 配置的镜像不实现 audit API；本轮切换官方 registry 后 `npm audit --audit-level=high` 实测 0 漏洞。CI 固定使用官方 registry；
- Element Plus 全量注册使最小骨架 bundle 出现大 chunk 警告；S03 不做业务层按需拆包，后续页面开发时应引入按需组件或代码分割并重测。
- Windows 无 Docker 时的 Redis 官方支持有限；应使用 WSL 中的 Redis 7 或团队受控实例并明确环境边界。
- Compose 是开发依赖入口，不是 S30 的生产部署方案；生产仍以 Linux/Nginx/systemd 和 Windows PowerShell 等效脚本为准。

### 9.2 S04 及以后依赖

- S04 只能新增 Flyway 迁移，不得改写 S03 已发布迁移；当前迁移目录为空是预期状态。
- S07 在本前端骨架上实现明示 Mock 的演示页，并继续禁止 Mock 冒充真实 API。
- S08/S09/S10 分别依赖稳定的后端、Agent Service 和适配器/Mock 基础；R4 缺口未关闭前不能宣称 S09 可执行。
- S11/S14 依赖 REST/SSE、状态和真实服务联调；前端不得直连 Redis、Agent Service 或供应商。
- S30 才建立完整裸机部署、Nginx、systemd、Windows 脚本、备份和恢复演练。

### 9.3 回滚

- R6 前端、跨栈 CI、本地三依赖 Compose 和文档可作为单一 R6 提交回滚；无数据库迁移或运行数据需要迁移。
- R5 导入历史保持独立，不通过破坏性 `reset` 或重写提交回滚。
- 删除 Compose named volume 会删除本地开发数据，必须由操作者明确确认；普通代码回滚不应执行 `down -v`。
