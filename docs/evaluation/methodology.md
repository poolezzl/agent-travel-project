# S05 评估方法与脱敏样例

## 目标与边界

本评估集用于验证事实可追溯、硬约束处理、降级可见性和局部修改保真；它不是用户数据集，也不是对任何供应商或 TripStar 的功能复刻。所有日期、用户描述、POI ID、来源 ID、耗时和费用均为合成或脱敏值。

固定案例要求至少 30 个；当前包含 10 个已填充的北京场景和 20 个结构化模板。每个案例必须有版本号，运行记录必须引用案例版本，避免规则或 Schema 变更后混淆历史结果。

## 案例契约

案例 fixture 的核心字段如下：

- `case_id`、`case_version`、`status`：稳定身份和 `draft`/`ready` 状态。
- `city_code`、固定合成日期、脱敏 `user_request`：不可包含真实身份或私密行程。
- `allowed_source_record_ids`：允许使用的来源白名单；事实不能超出白名单。
- `hard_constraints`、`soft_preferences`：硬约束违反必须计数，偏好不能覆盖硬约束。
- `expected_behaviors`：闭馆、同名、天气窗口、`stale`、`unknown`、失败/降级等确定预期。
- `local_modification`：`base_version_id`、影响范围和未影响区块哈希，用于保真率计算。
- `expected_evidence`、`manual_score`、`runs`：来源证据、人工评分和运行摘要占位。

## 机器指标

指标脚本 `tests/evaluation/metrics.py` 只消费脱敏运行记录，固定输出 JSON，并在输入契约错误时失败：

| 指标 | 口径 |
|---|---|
| 硬约束满足率/违反数 | 所有 `hard_constraints.satisfied` 聚合 |
| 事实来源完整率 | 每条事实必须有 `source_record_id` |
| 过期标示率 | `freshness_status=stale` 的事实必须显示 `displayed_status=stale` |
| 路线可行率 | 已知布尔 `route.feasible` 中为真的比例；未知不当作通过 |
| 局部修改保真率 | 未影响区块 `before_hash == after_hash` 的比例 |
| Schema 合法率 | 运行记录 `schema_valid` 为真的比例 |
| 降级可见率 | 失败/降级字段为空，或非空时有 `degradation_explained=true` |
| 时延 | 固定运行集的线性插值 P50/P95，单位毫秒 |
| 成本 | token 总量/均值与脱敏的最小货币单位 API 成本总量/均值 |

不能把缺少来源、未知路线或未解释降级静默计为成功。

## 人工评分表（每项 0-4 分）

每个运行由两名评审独立评分，记录评分者代号而不记录真实身份：

1. 硬约束与时间安排清楚度（0=违反/无法理解，4=全部清楚）。
2. 来源、更新时间、`stale`/估算/未知标识清楚度。
3. 路线与开放时间冲突解释质量。
4. 降级、失败和下一步建议的可理解性。
5. 局部修改范围说明与未影响区块保真。
6. 输出对用户的可执行性（不以文案华丽替代事实正确）。

记录 `score_total`、逐项分数、`disagreements` 和裁决说明；禁止把 TripStar 的代码、Prompt、图片或原始数据带入评分材料。

## 固定运行条件

- 固定时区：`Asia/Shanghai`；固定评估参考时间：`2026-10-01T00:00:00Z`。
- 使用仓库锁定的 Schema、Prompt/算法版本和脱敏 fixture；固定模型别名、采样参数、超时、重试上限、缓存状态和供应商 Mock 响应。
- 网络/真实 API 不属于指标脚本运行条件；真实联调须另有账号 Gate 和预算记录。
- 运行保存 `case_id/version`、输出摘要哈希、证据 ID、状态、时延、token、费用和错误/降级摘要；禁止保存原始 Prompt、完整私密行程、供应商原始响应或密钥。

## TripStar 对照计划（PENDING）

当前工作区未提供可验证的 TripStar 安装、版本或合规隔离运行证据，因此不执行、不复制、不宣称基线结果。项目负责人/R7 后续应在隔离目录中：

1. 使用相同脱敏北京案例、固定日期/时区和人工记录条件；
2. 单独记录 TripStar 版本、运行环境、成功/失败摘要、时延和人工评分；
3. 只保存脱敏摘要、证据和坏案例编号，不保存其代码、Prompt、图片或原始数据；
4. 由两名评审按同一评分表复核，报告差异而非拼接实现。

## 覆盖矩阵

案例索引将场景映射到需求和预期行为。S22 运行前必须补齐 30 个 `ready` 案例，并在 Schema、数据、算法或模型版本变化后回归。

## S22 执行口径

S22 用 `tests/evaluation/s22_gate.py` 对 30 个固定案例跑真实编排代码，输出
`docs/progress/s22-artifacts/{runs,metrics,m2-gate}.json`：

- 每个案例使用一份由案例契约确定性生成的**合成已发布快照**（`sample_only=true`），来源 ID 只取自
  该案例 `allowed_source_record_ids`；因此结论只证明流水线行为（来源传播、鲜度显隐、降级可见、
  局部保真），不代表真实城市或供应商数据。
- 通过定义：固定 DAG 无步骤失败、Plan 通过 `contracts/s19/plan.schema.json`、全部
  `expected_behaviors` 成立、全部硬约束成立、事实来源均在白名单内。阻断冲突导致
  `plan.quality.status=fail` 属契约期望，不计为流水线失败。
- 局部修改使用 S20 `revision_execution.apply` 执行已确认变更，按未影响日期的规范化哈希比较保真。
- 时延与成本口径：只覆盖编排与修订核心执行段，不含网络、数据库往返与模型调用；真实 P95 与费用
  在 S29 部署环境按 10 并发实测（PENDING-S22-01）。
