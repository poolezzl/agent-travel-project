# ALG-12 参数决策单（偏好权重、遗忘、TOP-K、置信阈值、统计维度）

| 项目 | 内容 |
| --- | --- |
| 决策单编号 | ALG-12-PARAM-001 |
| 状态 | **已配置化，待 R1/R3 会签（未定稿）** |
| 适用范围 | S24 偏好记忆与偏好召回（ALG-12），主责 R8；注入边界 R1，知识侧配合 R3 |
| 落地方式 | 全部数值落到 `app.preferences.*`（`backend/src/main/resources/application.yml`），代码只读配置，不写死 |
| 依据 | `docs/多智能体协作文旅规划平台-算法设计说明书.md` §4.12；`docs/roles/08-R8-沉淀层与社区规范.md` §3–§4、R8-03；`docs/01-需求与整体设计.md` 2.5.4/2.5.5；`docs/需求追踪矩阵.md` REQ-2.5.4；`docs/progress/S24.md` PENDING；`contracts/openapi/s24-preference.yaml` |
| 会签要求 | 每项参数需 R1（注入边界）、R3（知识侧配合）、R8（主责）三方签字后方可从「已配置化」升级为「已冻结」 |

> 本决策单**不宣布参数已定稿**。在三方签字前，`docs/progress/S24.md` 的 ALG-12 PENDING 条目保持有效，只标注「参数已配置化，待会签」。
> 签字前如需调整数值，直接改配置默认值（`application.yml` + `PreferenceProperties`），再回到本单更新候选值与结论。

## 1. 生效口径（公式定义）

以下口径是本次配置化的前提，会签时若改口径，需同步改契约说明与单元测试。

1. **命中条数 n**：某维度取值在历史行程中每命中一次记 1（去重后的行程集合口径）。
   本次修正了原实现的计数缺陷：原 `merge(value, count+1, Integer::sum)` 会把旧值再叠加一次，同一取值命中 2 次被记成 3 次（1→3→6），导致权重公式失真。
2. **权重公式**：`weight = min(weight-cap, n × weight-increment)`。
3. **遗忘函数（读时折算，不改库）**：
   `effective_weight = decay-enabled ? max(decay-floor, round(weight × 0.5^(Δdays / decay-half-life-days))) : weight`，
   其中 `Δdays = now − last_used_at`。读时折算的好处是改参数即时生效、不回写历史偏好行，与「偏好只追加、不原地改写」口径一致；代价是排序随时间漂移（见 §5 Q3）。
4. **置信度过滤**：`confidence < min-confidence` 的条目不进入召回结果。`confidence` 为 NULL 时按「未知」处理并保留，待会签确认（§5 Q1）。
5. **TOP-K 排序与截断**：`effective_weight DESC → last_used_at DESC（NULL 末尾）→ preference_key ASC`，排序稳定且可复现。
   K 取请求参数 `top_k`；未传时用配置 `top-k`；传入时按 `top-k-max` 截断，且永不超过契约上限 20（`contracts/openapi/s24-preference.yaml`）。
6. **维度分档口径**：
   - `pace`：每日景点数 ≤ `relaxed-max-points-per-day` 为「轻松」，≤ `moderate-max-points-per-day` 为「适中」，其余「紧凑」；
   - `budget`：预算金额 ≤ `economy-max-yuan` 为「经济」，≤ `comfort-max-yuan` 为「舒适」，其余「高端」；
   - `season`：按日历月份划分「冬 12/1/2、春 3-5、夏 6-8、秋 9-11」，属日历事实口径，不作为可调参数（见 §4）。
   边界值归入上一档（≤ 阈值即命中），本次由「< 阈值」改为「≤ 阈值」，会签时需确认。
7. **画像门槛**：历史行程少于 3 条不出画像（已冻结，见 §4），与数据库 `ck_summary_eligible` 同口径。

## 2. 参数决策表

### D-01 权重增幅 `app.preferences.weight-increment`

| 候选值 | 1（现状计数口径） | **3（建议）** | 5 |
| --- | --- | --- | --- |
| 默认值建议 | — | **3** | — |
| 依据 | 原实现每条证据只加 1，权重与命中次数同值，无法区分强度 | 一条历史行程命中该偏好即给出可见但弱于显式输入的强度；命中 4 次得 12，与契约样例 `contracts/examples/s24/recall.json` 的权重量级一致 | 强度过高时可能压过显式输入，违反「显式输入优先」 |
| 影响面 | `user_preference.weight`、偏好画像 `dimensions[].weight`、召回排序与 TOP-K 命中集合、注入 Agent 的软约束强度 | 同左 | 同左 |
| 回滚方式 | 置 `PREFERENCES_WEIGHT_INCREMENT=1` 即恢复计数语义 | — | — |
| 待签字 | R1：☐ 同意 　R3：☐ 同意 　R8：☐ 同意 | | |

### D-02 权重上限 `app.preferences.weight-cap`

| 候选值 | 50 | **100（建议）** | 200 / 不封顶 |
| --- | --- | --- | --- |
| 默认值建议 | — | **100** | — |
| 依据 | 上限用于防止单一偏好在长期使用后垄断 TOP-K；100 与 D-01=3 组合下约 33 次命中即触顶，足以区分强偏好 | 与契约样例权重量级（12、9）同阶，且留出显式输入权重更高的空间 | 不封顶会让长期用户的画像被少数偏好占据 |
| 影响面 | 权重写入、排序、TOP-K 稳定性 | 同左 | 同左 |
| 回滚方式 | 调大上限即可；不封顶可用极大值近似 | — | — |
| 待签字 | R1：☐ 同意 　R3：☐ 同意 　R8：☐ 同意 | | |

### D-03 低置信度阈值 `app.preferences.min-confidence`

| 候选值 | 0.50 | **0.60（建议）** | 0.70 |
| --- | --- | --- | --- |
| 默认值建议 | — | **0.60** | — |
| 依据 | 维度基准置信度为 city 0.80、其余 0.70（§D-09），阈值 0.60 保留全部规则统计维度，同时能过滤掉未来低质量来源或人工写入的低置信条目 | 0.70 会把默认置信度 0.70 的维度按「≥」边界判定，需逐项复核是否误杀，等价于阈值收紧到默认档 | 0.50 几乎不过滤，阈值失去意义 |
| 影响面 | 召回条数、注入 Agent 的软约束条数、显式偏好是否被过滤 | 同左 | 同左 |
| 回滚方式 | 调低阈值即恢复；紧急时置 0 放行全部 | — | — |
| 待签字 | R1：☐ 同意 　R3：☐ 同意 　R8：☐ 同意 | | |

### D-04 遗忘函数与周期 `app.preferences.decay-enabled` / `decay-half-life-days` / `decay-floor`

| 候选半衰期 | 90 天 | **180 天（建议）** | 365 天 |
| --- | --- | --- | --- |
| 默认值建议 | — | **180 天**，`decay-enabled=true`，`decay-floor=1` | — |
| 依据 | 90 天偏激进，半年内的出行习惯会被明显冲淡 | 半年不出现即衰减一半，与「出行偏好半年度更新」的常见节奏一致；保底 1 保证旧偏好仍可见但排序靠后，避免排序抖动 | 365 天几乎等于不遗忘，遗忘机制形同虚设 |
| 影响面 | 召回排序随时间变化、注入偏好的新鲜度 | 同左 | 同左 |
| 回滚方式 | `PREFERENCES_DECAY_ENABLED=false` 立即关闭遗忘，恢复纯权重排序 | — | — |
| 待签字 | R1：☐ 同意 　R3：☐ 同意 　R8：☐ 同意 | | |

> 说明：本项为「读时折算」实现（见 §1.3），不改写 `user_preference.weight`。若会签要求「写时衰减」，需要重新评估与「偏好只追加、不原地改写」的口径冲突（§5 Q3）。

### D-05 TOP-K 的 K `app.preferences.top-k`

| 候选值 | 3 | **5（建议）** | 8 |
| --- | --- | --- | --- |
| 默认值建议 | — | **5** | — |
| 依据 | 3 条偏少，画像维度已有 5 个 | 契约 `contracts/openapi/s24-preference.yaml` 的 `top_k` 默认值即 5；与 R8 规范「注入高权重偏好作为软权重」的多条软约束口径一致，且不显著占用上下文 | 8 条容易稀释高权重偏好的作用，且逼近上下文预算 |
| 影响面 | 召回条数、Agent 上下文软约束条数、画像摘要长度 | 同左 | 同左 |
| 回滚方式 | 改配置即时生效，无需改代码 | — | — |
| 待签字 | R1：☐ 同意 　R3：☐ 同意 　R8：☐ 同意 | | |

### D-06 客户端可覆盖的 K 上限 `app.preferences.top-k-max`

| 候选值 | 5（等于默认 K） | **20（建议，与契约上限一致）** | 不限制 |
| --- | --- | --- | --- |
| 默认值建议 | — | **20** | — |
| 依据 | 客户端不能放大召回量，但运营侧失去了按场景扩召回的余地 | 契约 schema 已声明 `maximum: 20`，取 20 可让「不传=top-k、传入按上限截断」与契约完全一致 | 超过 20 会突破契约约束 |
| 影响面 | 召回条数上限、契约一致性 | 同左 | 同左 |
| 回滚方式 | 下调该值即收紧；不可超过 20（校验会拒绝启动） | — | — |
| 待签字 | R1：☐ 同意 　R3：☐ 同意 　R8：☐ 同意 | | |

### D-07 city 维度取值个数 `app.preferences.city-top-n`

| 候选值 | 3 | **5（建议）** | 8 |
| --- | --- | --- | --- |
| 默认值建议 | — | **5** | — |
| 依据 | 原实现与 TOP-K 共用同一个 5，耦合后调 K 会顺带改画像维度内容 | 城市取值与 K 解耦：城市记忆通常取高频若干个即可，5 与原行为一致 | 取值过多会让画像文案冗长（文案只展示前 3 个） |
| 影响面 | 画像 `dimensions[city].valueJson` 条数、写入的 `city.*` 偏好行数量 | 同左 | 同左 |
| 回滚方式 | 改配置即时生效 | — | — |
| 待签字 | R1：☐ 同意 　R3：☐ 同意 　R8：☐ 同意 | | |

### D-08 统计维度清单 `app.preferences.dimensions`

| 候选 | A：`city/theme/pace/budget/season` | B：A + `companion/transport/accommodation_style` | C：A + `dietary_restriction` 等敏感维度 |
| --- | --- | --- | --- |
| 默认值建议 | **A（建议）** | — | — |
| 依据 | R8 规范 §4 只点名「节奏/口味/预算档」三类稳定偏好，需求 2.5.4 要求偏好来源可解释；A 覆盖需求 2.5.5 的画像口径，且每项都有明确可解释理由 | 同行程维度需新增统计规则与字段来源，当前 `plan_snapshot` 无稳定字段支撑，扩维会引入不可解释的推断 | 忌口等敏感偏好不得进入公共画像，也不得从单次行为过度推断，纳入维度清单会带来合规风险 |
| 影响面 | 画像维度条数、`user_preference` 键空间、召回条目构成、契约样例 | 同左 | 同左 |
| 回滚方式 | 增删配置项即可（不支持的维度名会在启动时被拒绝，避免静默忽略） | — | — |
| 待签字 | R1：☐ 同意 　R3：☐ 同意 　R8：☐ 同意 | | |

### D-09 维度基准置信度 `app.preferences.default-confidence` / `dimension-confidence`

| 候选 | 统一 0.70 | **city 0.80 + 其余 0.70（建议，沿用现状值）** | 统一 0.60 |
| --- | --- | --- | --- |
| 默认值建议 | — | **city 0.80，其余 0.70** | — |
| 依据 | 城市维度的证据来自结构化 `trip_city`，强于从行程快照中解析出的标签，现状值本就区分对待 | 沿用原实现写入 `user_preference.confidence` 的两个数值，只把它们从代码常量搬到配置，便于按维度单独调 | 统一低值会让城市等强证据维度被阈值误杀 |
| 影响面 | 写入的置信度、召回过滤（配合 D-03）、可解释性展示 | 同左 | 同左 |
| 回滚方式 | 改配置即时生效；维度覆盖项删掉即回落默认 | — | — |
| 待签字 | R1：☐ 同意 　R3：☐ 同意 　R8：☐ 同意 | | |

### D-10 节奏分档阈值 `app.preferences.classification.pace.*`

| 候选（轻松/适中） | 2 / 4 | **2 / 4（建议，沿用现状值）** | 3 / 5 |
| --- | --- | --- | --- |
| 默认值建议 | — | **轻松 ≤ 2、适中 ≤ 4（每日景点数）** | — |
| 依据 | 沿用现状分档口径，只把它参数化 | 与原实现数值一致，避免本次变更改变历史画像分布 | 更宽的分档会让「适中」覆盖绝大多数行程，画像失去区分度 |
| 影响面 | `pace` 维度取值分布、画像文案 | 同左 | 同左 |
| 回滚方式 | 改配置即时生效 | — | — |
| 待签字 | R1：☐ 同意 　R3：☐ 同意 　R8：☐ 同意 | | |

### D-11 预算档位阈值 `app.preferences.classification.budget.*`

| 候选（经济/舒适） | 1000 / 5000 元 | **1000 / 5000 元（建议，沿用现状值）** | 1500 / 8000 元 |
| --- | --- | --- | --- |
| 默认值建议 | — | **经济 ≤ 1000 元、舒适 ≤ 5000 元** | — |
| 依据 | 沿用现状分档口径，只把它参数化；金额口径与行程预算字段 `trip.budget.amount_minor` 同源（分转元） | 同左；更高阈值会把大量中等预算行程归入「经济」，与用户直觉不符 |
| 影响面 | `budget` 维度取值分布、注入的预算软约束 | 同左 | 同左 |
| 回滚方式 | 改配置即时生效 | — | — |
| 待签字 | R1：☐ 同意 　R3：☐ 同意 　R8：☐ 同意 | | |

### D-12 参数版本 `app.preferences.rule-version`

| 候选 | 不记录 | **记录字符串版本（建议）** | 记录数值版本 |
| --- | --- | --- | --- |
| 默认值建议 | — | **`s24-alg12-v1`** | — |
| 依据 | 算法说明书 §4.12 要求聚合规则与权重更新版本化；ALG-15 要求参数变更同步更新配置版本并留审计 | 写入 `preference_summary.summary_json.ruleVersion`，参数变更时同步改版本号即可追溯；沿用既有 `s24-` 前缀风格 | 数值版本需额外维护变更日志，收益有限 |
| 影响面 | 画像可审计性、参数变更追溯 | 同左 | 同左 |
| 回滚方式 | 改回旧版本号即可（不回滚已写数据） | — | — |
| 待签字 | R1：☐ 同意 　R3：☐ 同意 　R8：☐ 同意 | | |

## 3. 配置项总表（`app.preferences.*`）

| 配置键 | 环境变量 | 默认值 | 决策项 | 校验规则 |
| --- | --- | --- | --- | --- |
| `top-k` | `PREFERENCES_TOP_K` | 5 | D-05 | 1..20 且 ≤ `top-k-max` |
| `top-k-max` | `PREFERENCES_TOP_K_MAX` | 20 | D-06 | 1..20（契约上限） |
| `city-top-n` | `PREFERENCES_CITY_TOP_N` | 5 | D-07 | 1..`top-k-max` |
| `min-confidence` | `PREFERENCES_MIN_CONFIDENCE` | 0.6 | D-03 | 0..1 |
| `weight-increment` | `PREFERENCES_WEIGHT_INCREMENT` | 3 | D-01 | ≥1 |
| `weight-cap` | `PREFERENCES_WEIGHT_CAP` | 100 | D-02 | ≥1 |
| `decay-enabled` | `PREFERENCES_DECAY_ENABLED` | true | D-04 | 布尔 |
| `decay-half-life-days` | `PREFERENCES_DECAY_HALF_LIFE_DAYS` | 180 | D-04 | 开启时 ≥1 |
| `decay-floor` | `PREFERENCES_DECAY_FLOOR` | 1 | D-04 | ≥0 |
| `rule-version` | `PREFERENCES_RULE_VERSION` | s24-alg12-v1 | D-12 | 非空 |
| `dimensions` | `PREFERENCES_DIMENSIONS` | city,theme,pace,budget,season | D-08 | 非空且仅支持 city/theme/pace/budget/season |
| `default-confidence` | `PREFERENCES_DEFAULT_CONFIDENCE` | 0.7 | D-09 | 0..1 |
| `dimension-confidence.city` | `PREFERENCES_CITY_CONFIDENCE` | 0.8 | D-09 | 0..1 |
| `classification.pace.relaxed-max-points-per-day` | `PREFERENCES_PACE_RELAXED_MAX_POINTS_PER_DAY` | 2 | D-10 | >0 |
| `classification.pace.moderate-max-points-per-day` | `PREFERENCES_PACE_MODERATE_MAX_POINTS_PER_DAY` | 4 | D-10 | > 轻松阈值 |
| `classification.budget.economy-max-yuan` | `PREFERENCES_BUDGET_ECONOMY_MAX_YUAN` | 1000 | D-11 | ≥0 |
| `classification.budget.comfort-max-yuan` | `PREFERENCES_BUDGET_COMFORT_MAX_YUAN` | 5000 | D-11 | > 经济阈值 |

参数非法时后端**启动即失败**（`PreferenceProperties.validate()`），不做静默兜底，避免错误配置被当成"生效中的默认值"。

## 4. 不纳入本次配置化的项（附理由）

| 项 | 口径 | 不配置化的理由 |
| --- | --- | --- |
| 画像门槛 3 条 | 历史行程 < 3 条不出画像 | 已冻结（算法说明书 §4.12、R8 规范 §3、需求 2.5.5），且数据库 `ck_summary_eligible` 强制 `eligible=TRUE ⇒ sample_count>=3`，属硬约束不是可调参数 |
| 偏好只作软约束、可清空 | 不覆盖当次显式输入；清空写 `status=CLEARED` | 已冻结的硬约束，与参数无关 |
| 不进公共榜单、不未脱敏传给供应商 | 私有数据边界 | 已冻结的合规硬约束 |
| 季节划分（冬 12/1/2 等） | 按日历月份划分 | 属日历事实口径而非可调参数；如需调整应改口径文档而非配置 |
| 画像文案由 LLM 生成、统计维度由规则聚合 | R8 规范 §3 | 分工已冻结；文案模型参数另见 ALG-12 §4.12「画像文案模型参数」PENDING，不在本次范围 |

## 5. 待会签问题（不随本次配置化关闭）

| 编号 | 问题 | 影响 |
| --- | --- | --- |
| Q1 | `confidence` 为 NULL 的偏好条目应保留还是剔除？ | 决定召回过滤规则是否要区分"未知"与"低置信" |
| Q2 | 权重是否应按置信度加权（如 `n × increment × confidence`）？ | 当前方案不加权，理由是加权后"命中次数"不再直观、可解释性下降 |
| Q3 | 遗忘应为读时折算（当前）还是写时衰减？ | 写时衰减要回写 `user_preference.weight`，与"只追加、不原地改写"口径冲突 |
| Q4 | 客户端是否允许请求超过 `top-k` 的条数（当前允许，上限 `top-k-max`） | 影响契约语义与上下文预算 |
| Q5 | 偏好注入 Agent 的边界、频次与脱敏要求（R1） | 本次只做参数与召回口径，注入链路未实现 |
| Q6 | 知识侧（R3）如何使用偏好（是否参与知识召回排序） | 影响 ALG-08 与 ALG-12 的边界 |
| Q7 | `preference_evidence` 落库（`weight_delta`、`evidence_hash` 幂等）仍未实现 | 当前权重由聚合计数直接物化到 `user_preference.weight`，缺证据流水 |
| Q8 | 历史行程条数上限 H 未冻结 | 影响聚合成本与"高样本"判定 |

## 6. 变更、审计与回滚

- 每次调整参数：改 `application.yml` 默认值 → 同步改 `PreferenceProperties` 默认值 → 更新本决策单 → 递增 `app.preferences.rule-version` → 在 `docs/progress/S24.md` 追加验证证据。
- 代码侧不保留第二份默认值：`PreferenceService` 只读 `PreferenceProperties`，`PreferenceProperties` 只保留本决策单登记的默认值。
- 回滚：逐项把配置改回上一版本号即可，不需要回滚代码；`user_preference` / `preference_summary` 为追加写，旧数据保留不影响新口径。

## 7. 验证证据（2026-11-25）

| 验证项 | 方法 | 实际结果 |
| --- | --- | --- |
| 默认值与本单一致 | `PreferenceParameterTests.defaultParametersMatchDecisionSheet` | 通过 |
| 改配置改变召回结果（K / 阈值 / 半衰期 / 保底） | `PreferenceParameterTests`（12 项） | 通过：K=5→2 条、阈值 0.6→0.4 多召回 1 条、半衰期 180→30 天权重 6→1、关闭遗忘权重恢复原值 |
| 改配置改变画像权重与维度 | `PreferenceParameterTests`（增幅 3→5、上限 4、维度清单裁剪、city 取值个数） | 通过 |
| 非法配置启动即失败 | `PreferenceParameterTests.invalidParametersAreRejected` | 通过（K、阈值、增幅、维度名、分档阈值 8 类） |
| 命中计数缺陷回归 | `PreferenceParameterTests.repeatedThemeValueIsCountedOncePerOccurrence` | 通过（同值命中 2 次记 2，不再记 3） |
| 真实 MySQL 召回 | `trip_planner_it` 临时探针（写入 4 条偏好后按 5 组配置召回并清理） | 通过：阈值过滤、遗忘折算（30→6、12→11）、K 截断、来源类型透传（EXPLICIT）均符合预期，探针数据已清零 |
| 真实 MySQL 接口级回归 | `S24_IT=true` + 一次性库 `trip_planner_alg12`，`S24PreferenceIntegrationTests`（5 项）、`S24PreferenceConfigOverrideIntegrationTests`（1 项）、`S24VersionIntegrationTests`（6 项） | `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`；其中配置覆盖用例证明改 `app.preferences.*` 会直接改变接口召回结果 |
| 前端与 Agent 侧基线 | `npm run test:unit`（156 通过）、`npx vue-tsc --noEmit`（退出码 0）、agent-service `pytest`（294 passed） | 通过，本次未改动前端与 `agent-service/` |
| 契约校验 | `validate_s02_contracts.py` / `validate_s08_contracts.py` / `validate_s11_contracts.py` / `validate_s26_contract.py` | 全部通过 |

后端全量测试与本次改动的详细命令输出见 `docs/progress/S24.md` 的「补充验证」小节。
