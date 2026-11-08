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
| S05 | 进行中（TripStar/真实来源仍 PENDING） | [S05.md](S05.md) |
| S06 | 进行中（真实来源许可仍 PENDING） | [S06.md](S06.md) |
| S07 | 已完成（模拟演示） | [S07.md](S07.md) |
| S08 | 已完成 | [S08.md](S08.md) |
| S09 | 进行中（待独立整体验收） | [S09.md](S09.md) |
| S10 | 已完成 | [S10.md](S10.md)、[S10-Gate.md](S10-Gate.md) |
| S11 | 已完成 | [S11.md](S11.md)、[S11-Gate.md](S11-Gate.md) |
| S12 | 进行中（真实高德适配器已接入，契约冻结项 PENDING） | [S12.md](S12.md) |
| S13 | 进行中（真实和风天气适配器已接入，契约冻结项 PENDING） | [S13.md](S13.md) |
| S15 | 进行中（Java 编译/19 项 JUnit/Flyway/MySQL 已验证；Chroma 真实运行时、Maven 全量、S06 真实库导入 PENDING） | [S15.md](S15.md) |
| S16 | 进行中（离线 Agent 完成；Chroma/Java 联调、HTTP 测试 PENDING） | [S16.md](S16.md) |
| S17 | 进行中（离线 Agent 完成；Java 规则库联调、HTTP 测试 PENDING） | [S17.md](S17.md) |
| S14、S18-S31 | 未开始 | 执行到对应步骤时创建独立证据文件 |

## 证据文件必备内容

每个 `Sxx.md` 必须记录：依赖检查、需求编号、修改文件、接口/Schema/迁移影响、关键设计、实际执行的命令与结果、边界场景、来源/权限/幂等/并发/降级/隐私/日志/回滚检查、风险和 `PENDING`。测试未运行时必须说明原因。

## R4 合并补充

R4 S03 Python Agent Service 工程骨架已合入当前 `dev-r6`：包含 FastAPI 入口、`/health` 健康检查、Pydantic Settings 配置、固定依赖、pytest/Ruff/MyPy/compileall 质量门禁和 Agent Service CI。S03 仍保持“进行中”，待 R5、R6、共享本地环境和完整三端 Gate 证据统一复核后再关闭。原有 R6 进度记录保留，R4 交付状态以 [S03.md](S03.md) 的 R4 补充章节为准。
