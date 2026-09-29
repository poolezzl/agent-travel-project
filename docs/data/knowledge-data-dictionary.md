# R3 知识库与规则数据字典

## 文档定位

本字典定义 R3 在 S06 形成的数据录入边界、字段命名和来源要求，供 R7 数据质量、R5 数据持久化和后续 Agent 服务共同使用。

本文件是 S04 数据模型设计的 R3 业务字段基线，不替代 Java/Flyway 迁移。字段进入数据库前，必须经过 S04 的表结构、唯一键、索引、保留期和权限评审。

## 适用范围

| 数据对象 | 用途 | R3 责任 |
|---|---|---|
| source_registry | 保存事实来源、许可、取得时间和新鲜度 | 维护来源元数据，不能保存密钥或供应商完整响应 |
| poi | 景点、餐饮和住宿的内部实体 | 维护内部 ID、别名、城市和坐标关联 |
| attraction_knowledge | 景点资讯事实 | 维护事实字段、知识版本和 chunk 引用 |
| reservation_rule | 景点预约规则 | 维护人工核验的预约政策版本和提醒计算字段 |
| import_batch | 导入批次和错误回查 | 记录模板导入的批次、操作者和结果摘要 |

## 统一命名与枚举

- 所有机器字段使用 snake_case；内部 ID 使用大写字母、数字、下划线或连字符。
- 时间统一使用带时区的 ISO 8601 文本；落库按项目基线转换为 UTC DATETIME(6)。
- 城市使用稳定的 city_id，不能用展示名称作为关联键。
- 坐标必须同时保存 longitude、latitude 和 coordinate_system；本项目国内 POI 默认使用 GCJ02。
- 审核状态固定为 draft、review、published、suspended、rolled_back。
- freshness_status 固定为 fresh、stale、unknown。
- 未知事实使用 unknown 或空值，并在降级信息中说明；不能用 0、空字符串或模型猜测代替未知。

## 公共字段

| 字段 | 类型 | 必填 | 约束 |
|---|---|---:|---|
| record_id | string | 是 | 对象内部稳定 ID |
| city_id | string | 视对象 | 使用城市内部 ID，不直接用中文名 |
| source_record_id | string | 是 | 必须能回查 source_registry |
| operator_id | string | 是 | 录入或修改人员的脱敏内部标识 |
| reviewer_id | string | review/published 必填 | 复核人员的脱敏内部标识 |
| reviewed_at | datetime | review/published 必填 | 最后一次复核时间 |
| valid_from | datetime/date | 视对象 | 事实开始生效时间 |
| valid_until | datetime/date/null | 是 | null 只表示没有声明截止时间，不代表永不过期 |
| freshness_status | enum | 是 | 由核验时间和有效期计算 |
| review_status | enum | 是 | draft → review → published → suspended/rolled_back |
| version | integer | 是 | 从 1 开始递增，已发布版本不可原地覆盖 |

## source_registry

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| source_record_id | string | 是 | 来源记录主键 |
| source_type | enum | 是 | knowledge_base、reservation_rule、poi 或 system 等 |
| source_url | string/null | 是 | URL 或受控内部引用；无 URL 必须说明原因 |
| license_type | string | 是 | official_public、licensed_ugc、internal_review 等 |
| license_reference | string/null | 是 | 许可证明编号或审查记录 |
| permitted_use | enum | 是 | fact_only、display_with_attribution、internal_only |
| attribution_required | boolean | 是 | 是否必须展示署名 |
| collected_at | datetime | 是 | 首次收集时间 |
| retrieved_at | datetime | 是 | 最近取得或核验时间 |
| valid_until | datetime/null | 是 | 来源或事实有效期 |
| freshness_status | enum | 是 | fresh/stale/unknown |
| source_status | enum | 是 | pending_review、approved、rejected、revoked |
| content_hash | string | 是 | 脱敏内容摘要，用于回放和去重 |
| operator_id | string | 是 | 录入人 |
| reviewer_id | string/null | 是 | 复核人 |
| reviewed_at | datetime/null | 是 | 复核时间 |
| notes | string/null | 否 | 只记录必要说明，不保存完整原文或密钥 |

## poi

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| poi_id | string | 是 | 系统内部 POI ID，不能由名称临时生成 |
| city_id | string | 是 | 所属城市 |
| name | string | 是 | 展示名称 |
| alias_names | string[] | 否 | 别名列表，不能据此自动合并实体 |
| longitude | number | 是 | -180 到 180 |
| latitude | number | 是 | -90 到 90 |
| coordinate_system | enum | 是 | GCJ02 |
| district | string/null | 否 | 行政区 |
| poi_category | enum | 是 | attraction、restaurant、lodging |
| merge_group_id | string/null | 否 | 人工确认的合并组 |
| source_record_id | string | 是 | POI 坐标和名称来源 |
| review_status | enum | 是 | 当前运营状态 |
| version | integer | 是 | 实体版本 |

同名、坐标冲突或疑似拆分的 POI 必须进入人工队列。embedding 只能用于召回，不能决定实体合并。

## attraction_knowledge

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| knowledge_id | string | 是 | 景点知识条目 ID |
| poi_id | string | 是 | 关联已审核的景点 POI |
| fact_type | enum | 是 | opening_hours、highlights、avoid_tips、suggested_duration、accessibility、best_time、popularity |
| fact_value | string/object | 是 | 事实内容；每条事实只表达一个主题 |
| chunk_id | string | 是 | 向量检索和引用使用的切片 ID |
| source_record_id | string | 是 | 事实来源 |
| published_at | datetime/null | 是 | 发布时刻 |
| last_verified_at | datetime | 是 | 最近人工核验时刻 |
| valid_until | datetime/null | 是 | 事实有效期 |
| freshness_status | enum | 是 | fresh/stale/unknown |
| review_status | enum | 是 | 仅 published 内容允许被 RAG 召回 |
| version | integer | 是 | 知识版本 |

RAG 召回必须再以 poi_id、城市、review_status 和有效期回查事实库。低相似度、过期或索引与事实库不一致时返回 unknown。

## reservation_rule

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| policy_id | string | 是 | 预约规则版本 ID |
| poi_id | string | 是 | 景点内部 POI ID，不能只凭名称关联 |
| ticket_type | string | 是 | 票种或适用票类 |
| applicable_group | string | 是 | 适用人群 |
| reservation_required | boolean | 是 | 是否要求预约 |
| reservation_channel | string | 是 | 官方渠道或受控跳转标识 |
| release_time | time | 否 | 放票时间 |
| release_timezone | string | 否 | 放票时区 |
| advance_days | integer | 否 | 最少提前天数 |
| currency | string | 否 | ISO 4217；免费时可为 null |
| amount_minor | integer | 否 | 最小货币单位，不能与积分混用 |
| free_condition | string/null | 是 | 免票条件；来源必须可追溯 |
| valid_from | date | 是 | 规则生效日期 |
| valid_until | date/null | 是 | 规则失效日期 |
| holiday_exception | string/null | 是 | 节假日或闭馆例外 |
| last_verified_at | datetime | 是 | 最近人工核验 |
| stale_after_days | integer | 是 | 默认 30 |
| stale | boolean | 是 | 由核验时间计算，不能由 LLM 决定 |
| source_record_id | string | 是 | 政策来源 |
| review_status | enum | 是 | 只读 published 规则 |
| version | integer | 是 | 规则版本 |

预约规则只允许人工或半自动维护。缺规则、过期规则和冲突规则必须显式提示“暂未收录/请以官方渠道为准”。

## 生命周期与历史引用

1. 导入文件进入 draft，完成字段、来源和许可检查后进入 review。
2. 只有人工复核通过的记录才能进入 published，发布操作必须审计。
3. 停用或回滚不能物理删除被历史行程版本引用的来源和知识记录。
4. 历史行程保存不可变来源快照；后续规则更新只能影响新规划，不能改变已发布版本的解释依据。

## 禁止来源与禁止操作

- 禁止内容平台未授权爬取、无法证明许可的图片和完整供应商响应入库。
- 禁止使用 LLM 生成景点事实、价格或预约政策。
- 禁止把用户未审查 UGC 直接发布到知识库。
- 禁止仅按名称关联政策。
- 禁止用缓存、向量相似度或展示名称替代事实库审核状态。
