# 开发步骤 Gate 索引

本目录是 S00-S31 实际完成证据的唯一入口。没有真实验证结果的步骤不得标记为完成。

## 状态定义

| 状态 | 含义 |
|---|---|
| 未开始 | 尚未执行该步骤 |
| 进行中 | 已开始，但仍有验收项或阻断项未关闭 |
| 已完成 | 依赖满足、交付齐全、适用检查真实通过且无阻断 `PENDING` |
| 已阻塞 | 依赖 Gate、外部账号或必须由负责人决策的事项未解决 |

## 当前索引

| 步骤 | 状态 | 证据 |
|---|---|---|
| S00 | 已完成 | [S00.md](S00.md) |
| S01 | 已完成 | [S01.md](S01.md) |
| S02 | 已完成 | [S02.md](S02.md) |
| S03 | 已完成（Hosted CI 环境豁免） | [S03.md](S03.md)、[S03-R6.md](S03-R6.md)、[S03-Gate-Final-复验报告.md](S03-Gate-Final-复验报告.md)、[S03-Gate-R5-环境豁免裁决.md](S03-Gate-R5-环境豁免裁决.md) |
| S04 | 已完成 | [S04.md](S04.md)、[S04-最终数据模型冻结单.md](S04-最终数据模型冻结单.md)、[S04-Flyway-实现与验证报告.md](S04-Flyway-实现与验证报告.md)、[S04-Flyway-最终独立验收报告.md](S04-Flyway-最终独立验收报告.md) |
| S05 | 已完成（课程口径：30 案例 ready；TripStar 不纳入课程；组长验收） | [S05.md](S05.md) |
| S06 | 已完成（课程口径：模板/校验 + 天聚北京样例；生产许可不在课程范围；组长验收） | [S06.md](S06.md) |
| S07 | 已完成（模拟演示） | [S07.md](S07.md) |
| S08 | 已完成 | [S08.md](S08.md) |
| S09 | 已完成（真实 LLM 联通 4.1s/250 tokens；组长验收） | [S09.md](S09.md) |
| S10 | 已完成 | [S10.md](S10.md)、[S10-Gate.md](S10-Gate.md) |
| S11 | 已完成 | [S11.md](S11.md)、[S11-Gate.md](S11-Gate.md) |
| S12 | 已完成（课程口径：真实高德 driving/walking/transit；契约冻结、PoiPort.get、category 关闭；组长验收） | [S12.md](S12.md) |
| S13 | 已完成（课程口径：真实和风 7d；travel_advice/阈值/forecast_published_at 冻结；组长验收） | [S13.md](S13.md) |
| S15 | 已完成（R3 已审核 2026-11-11；Maven 136 项测试/Flyway/MySQL/S06 导入/Chroma 运行时/bge 索引/Python-Java 联调均通过） | [S15.md](S15.md)、[人工审核清单](S15-人工审核清单.md) |
| S16 | 已完成（R3 已审核 2026-11-11；离线 Agent + Java 发布快照联调通过） | [S16.md](S16.md) |
| S17 | 已完成（R3 已审核 2026-11-11；离线 Agent + Java 规则快照路径通过） | [S17.md](S17.md) |
| S18 | 已完成（R3，2026-11-11；离线 Agent + 合法 POI 端口；本地合成样例） | [S18.md](S18.md) |
| S14 | 已完成（课程口径：前端 + 真实供应商；10 并发探针 + 浏览器 e2e 4/4 mock + 1/1 真实；组长验收） | [S14.md](S14.md) |
| S19 | 已完成（固定 DAG + 最终 Plan + `/plan`；真实 MySQL/Redis 9/9；10 并发 M1 探针 + 真实链路；高德 exact 1/1、和风 3/3；组长验收） | [S19.md](S19.md) |
| S20 | 已完成（R4/R5，Java、Python、MySQL 与 Redis 联调） | [S20.md](S20.md) |
| S21-S31 | 未开始 | 执行到对应步骤时创建独立证据文件 |

## 证据文件必备内容

每个 `Sxx.md` 必须记录：依赖检查、需求编号、修改文件、接口/Schema/迁移影响、关键设计、实际执行的命令与结果、边界场景、来源/权限/幂等/并发/降级/隐私/日志/回滚检查、风险和 `PENDING`。测试未运行时必须说明原因。

## R3 合并后复核

2026-11-11 在本地 `.venv` 中执行 `python -m pytest tests/ --disable-warnings`：**235 passed**（S12/S13 原有 166 项 + R3 新增 69 项，其中 5 项真实 FastAPI/TestClient HTTP、8 项 Python→Java 内部客户端）。该结果只证明当前 Python 套件通过，不改变 S12-S18 各自的冻结项或 PENDING。

2026-11-10 在 JDK 21 + Maven 3.9.9 下执行 `mvn test`：**136 tests, 0 failures, 12 skipped**；另跑 S11 MySQL 6/6、Redis 2/2、S15 真实库导入 1/1。Chroma 运行时与 bge 生产索引（8 chunks）均已真实验证；Java 发布快照已导出并被 S16 加载。

## R4 合并补充

R4 S03 Python Agent Service 工程骨架已合入当前 `dev-r6`：包含 FastAPI 入口、`/health` 健康检查、Pydantic Settings 配置、固定依赖、pytest/Ruff/MyPy/compileall 质量门禁和 Agent Service CI。S03 仍保持“进行中”，待 R5、R6、共享本地环境和完整三端 Gate 证据统一复核后再关闭。原有 R6 进度记录保留，R4 交付状态以 [S03.md](S03.md) 的 R4 补充章节为准。
