# R3 接口表

本表描述 S08 之前已经冻结或需要交接的 R3 数据接口。S15 以后才实现的运行时业务接口先记录逻辑契约，避免在当前步骤越界实现后端业务。

| 编号 | 接口/数据集 | 方向 | 输入 | 输出 | 责任方 | 计划步骤 | 关键约束 |
|---|---|---|---|---|---|---|---|
| R3-DATA-001 | source_registry | R3/R7 → 数据层 | 来源 URL、许可、取得时间、有效期、审核信息 | source_record_id、freshness_status、content_hash | R3维护，R7质检 | S06 | 不保存密钥和完整供应商响应 |
| R3-DATA-002 | poi_import | R3/R7 → 数据层 | poi_id、city_id、名称、别名、坐标、类别、来源 | POI实体草稿和错误清单 | R3定义字段，R7维护数据 | S06/S04 | 同名和坐标冲突进入人工队列 |
| R3-DATA-003 | attraction_import | R3/R7 → 数据层 | knowledge_id、poi_id、fact_type、fact_value、chunk_id、来源 | 景点知识草稿 | R3 | S06/S04 | 未发布内容不能被检索 |
| R3-DATA-004 | reservation_rule_import | R3/R7 → 数据层 | policy_id、poi_id、票种、渠道、时区、价格、生效期、核验时间 | 预约规则草稿 | R3 | S06/S04 | 不得只按景点名称关联，不得由 LLM 生成 |
| R3-AGENT-004 | 景点资讯 Agent | 总控 → R3 Agent | points、preference_tags | cards、knowledge_id、chunk_ids、source_metadata | R3 | S02冻结，S16实现 | 无命中返回 source=unknown；事实逐项可追溯 |
| R3-AGENT-005 | 餐饮住宿 Agent | 总控 → R3 Agent | date、itinerary_draft、taste_preference、budget、companion | meals、lodging、POI、价格口径、detour_min | R3 | S02冻结，S18实现 | 只消费合法 POI；缺价返回区间或 unknown |
| R3-AGENT-006 | 预约规则 Agent | 总控 → R3 Agent | points、date | reservations、timeline、stale、source_metadata | R3 | S02冻结，S17实现 | 规则过期、缺失或冲突时显式降级 |
| R3-CONTRACT-001 | Agent 结果提交 | Python Agent → Java 网关 | taskId、stepId、agent_id、统一封套、result、Idempotency-Key | 202 accepted 或 409 duplicate | R5网关，R3提供 result | S02冻结，S11使用 | Java 是业务数据唯一写入方；运行时按 Agent Schema 二次校验 |

## 契约文件

| 接口 | 输入契约 | 输出契约 | 当前文件 |
|---|---|---|---|
| R3-AGENT-004 | agents/04-attractions/input.schema.json | agents/04-attractions/output.schema.json | 已冻结 |
| R3-AGENT-005 | agents/05-dining-lodging/input.schema.json | agents/05-dining-lodging/output.schema.json | 已冻结 |
| R3-AGENT-006 | agents/06-reservation-rules/input.schema.json | agents/06-reservation-rules/output.schema.json | 已冻结 |
| R3-CONTRACT-001 | contracts/openapi/agent-business.yaml | contracts/openapi/agent-business.yaml | 已冻结 |

## 交接规则

R3 只提交事实、来源和 Agent 结果；R5 负责 Java 业务写入、身份、权限、审计和持久化；R1 负责调度与最终整合；R2 提供路线和绕行计算；R7 负责数据质量与验收。任何一方不能绕过来源和审核状态直接发布事实。
