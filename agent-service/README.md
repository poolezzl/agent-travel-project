# TravelStar Agent Service

Python Agent 服务工程骨架，技术基线为 Python 3.11+、FastAPI、LangGraph 和 Pydantic v2。

当前处于 S03，仅提供配置分层、Mock profile、健康检查和质量工具，不包含 LLM 网关、业务 Agent、数据库访问或第三方调用。

## 服务边界

- Spring Boot 是 MySQL 唯一写入方。
- Agent Service 不直连业务数据库。
- 后续 Agent 结果只能通过 S02 REST/OpenAPI 契约回写。
- 默认 `mock` profile 不需要真实 API Key。
- `real` profile 当前只代表配置合法，不代表真实接口已接通。

## 环境要求

- Python 3.11、3.12 或 3.13
- pip
- 基础健康检查不依赖 Docker、MySQL、Redis 或 Chroma

## 本地安装

在仓库根目录创建虚拟环境并安装锁定依赖：

    python -m venv agent-service/.venv
    cd agent-service
    python -m pip install --upgrade pip setuptools wheel
    python -m pip install -r requirements.lock.txt
    python -m pip install --no-deps -e .

Linux/macOS 激活命令：

    source agent-service/.venv/bin/activate

Windows PowerShell 激活命令：

    .\agent-service\.venv\Scripts\Activate.ps1

Windows Git Bash 激活命令：

    source agent-service/.venv/Scripts/activate

需要重新解析开发依赖时执行：

    python -m pip install -e ".[dev]"

## 配置

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `APP_PROFILE` | `mock` | 允许 `mock` 或 `real` |
| `AGENT_SERVICE_NAME` | `travelstar-agent-service` | 服务名称 |
| `AGENT_SERVICE_HOST` | `127.0.0.1` | 监听地址 |
| `AGENT_SERVICE_PORT` | `8000` | 端口范围 1-65535 |
| `AGENT_KNOWLEDGE_CATALOG` | `resources/published_catalog.json` | S15 已发布知识/规则快照 |
| `AGENT_DINING_POI_SOURCE` | `resources/dining_lodging_pois.json` | S18 合法 POI 源（本地合成样例） |
| `KNOWLEDGE_SERVICE_BASE_URL` | `http://127.0.0.1:8080` | Java S15 内部只读接口地址 |

不得提交本地 `.env`、API Key、访问令牌或供应商原始响应。

## S15-S18 R3 Agent API

- `POST /v1/agents/attractions/invoke`：景点资讯 Agent（S16），只读已发布知识快照并返回 `knowledge_id/chunk_ids/facts/source_metadata`。
- `POST /v1/agents/dining-lodging/invoke`：餐饮住宿 Agent（S18），只消费合法 POI 与 `detour_min`，每餐最多 3 个候选并按顺路排序；缺价返回区间/unknown，空/过期/同名/跨城/超时显式降级。
- `POST /v1/agents/reservation-rules/invoke`：预约规则 Agent（S17），只读已发布规则并生成可去重提醒；`stale`、缺规则、已错过放票时间均显式降级。
- `GET /v1/agents/catalog`：仅返回快照版本与数量，不返回事实正文。
- 请求体可直接使用 `input.schema.json`，也可包在 `{task_id, trace_id, request_id, input}` 中；Agent 不会修改网关注入的封套字段。
- 本地离线快照来自 `resources/published_catalog.json`，全部为合成样例，不代表真实票价或政策。生产环境应切换到 Java S15 已发布数据/Chroma 索引。

R3 测试（不依赖 FastAPI/httpx）：

    python -m unittest discover -s tests -p "test_catalog.py"
    python -m unittest discover -s tests -p "test_attractions_agent.py"
    python -m unittest discover -s tests -p "test_dining_lodging_agent.py"
    python -m unittest discover -s tests -p "test_reservation_agent.py"
    python -m unittest discover -s tests -p "test_rag.py"

## R4 一次性真实模型最小调用

真实模型验收使用 `scripts/llm_min_call.py`，只允许在项目负责人批准一次测试预算后执行。脚本要求：

- `LLM_MIN_CALL_APPROVED=true`；
- `LLM_PROVIDER=openai-compatible`；
- `LLM_MAX_RETRIES=0`，禁止失败重试造成重复扣费；
- 脚本第一次尝试前会创建 `agent-service/.llm_min_call.used` 标记，重复执行会阻断；
- 固定短提示、`temperature=0`、最大输出 16 tokens；
- 只输出供应商、模型、状态、时间、token、耗时和费用摘要，不输出 API Key、完整提示词或完整响应。

在 `agent-service` 目录执行：

    python scripts/llm_min_call.py

脚本只提供技术调用上限，不能替代供应商账户余额或项目负责人对费用的批准。

## 启动和健康检查

在 `agent-service` 目录启动；该入口会使用 `AGENT_SERVICE_HOST` 和 `AGENT_SERVICE_PORT`：

    python -m app.main

默认监听 `127.0.0.1:8000`。健康检查：

    GET http://127.0.0.1:8000/health

Mock profile 预期响应：

    {"status":"ok","service":"travelstar-agent-service","profile":"mock","dependencies":{"external_services":"disabled"}}

OpenAPI 页面：

    http://127.0.0.1:8000/docs

## 质量检查

在 `agent-service` 目录执行：

    python -m ruff format --check app scripts tests
    python -m ruff check app scripts tests
    python -m mypy app
    python -m pytest
    python -m compileall -q app tests
    python -m pip check
    python -m build .
    pip-audit -r requirements.lock.txt

## 当前范围

S03 包含 FastAPI 应用骨架、Pydantic Settings 配置层、Mock/real profile 校验、健康检查、质量工具和依赖锁定。

LLM Gateway、模型路由、预算、超时、重试、结构化输出防线和真实 Agent 编排属于 S09 或后续步骤，当前尚未实现。
