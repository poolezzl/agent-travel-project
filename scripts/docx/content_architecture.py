# -*- coding: utf-8 -*-
"""文档二：总体架构设计文档。"""

from builder import Builder
from diagrams import generation_dag, layered_architecture, revision_dag

TITLE = "多智能体协作文旅规划平台"
SUBTITLE = "总体架构设计文档"


def build(out_dir):
    b = Builder(
        title=TITLE,
        subtitle=SUBTITLE,
        meta_rows=[
            ("文档名称", "总体架构设计文档"),
            ("文档版本", "V1.0"),
            ("编制日期", "2026-10-02"),
            ("文档定位", "系统技术架构与技术路线的正式设计文档，覆盖分层架构、多智能体协作、数据与契约、关键机制与部署安全"),
            ("编制依据", "docs/architecture/（总体架构设计、任务状态机、冲突消解与确认规则、ADR 0001-0011）、docs/02-开发决策基线.md V2.0、docs/03-多智能体系统规范.md"),
            ("适用范围", "P0 主链路及 P1/P2 的边界预留；M0-M4 各阶段实现约束"),
        ],
        out_path=out_dir / "多智能体协作文旅规划平台-总体架构设计文档.docx",
    )
    b.cover()
    b.toc()

    # ================= 1 =================
    b.h1("1  引言")
    b.h2("1.1  编写目的")
    b.p("本文档阐述多智能体协作文旅规划平台的总体架构与技术路线，回答“系统如何构造、关键技术如何取舍、质量属性如何保障”三个问题。文档内容与仓库内 ADR（架构决策记录）一一对应：本文给出整体视图与设计论证，ADR 冻结各单项决策，两者共同作为各角色并行开发的对齐基线。")
    b.h2("1.2  读者对象")
    b.p("读者为项目全体开发人员（R1-R8）、测试与部署人员，以及课程/课题评审专家。阅读本文前建议先读《需求分析文档》了解功能与非功能需求。")
    b.h2("1.3  术语")
    b.p("涉及的关键术语（来源快照、封套、预扣/确认/退款、Gate 等）定义见《需求分析文档》1.3 节；本文中“容器”指可独立部署的运行单元（进程/服务），“契约”特指 JSON Schema 与 OpenAPI 文档。")

    # ================= 2 =================
    b.h1("2  设计原则与技术路线")
    b.h2("2.1  技术路线总述")
    b.p("本项目的总体技术路线可概括为：**“事实靠数据、计算靠算法、理解与表达靠模型；契约先行、编排中心化、版本不可变、降级透明化”**。具体拆解为六条主线：")
    b.numbers([
        "**反幻觉事实主线**：一切事实性字段（时长、票价、开放时间、预约政策、天气等）只能来自地图 API、天气 API、人工规则库、已发布知识库四类规定数据源，并附来源快照（来源标识、取得时间、有效期、新鲜度、估算标识）；LLM 只做需求理解、柔性偏好转译、排序加权与自然语言表达，不得生成、补写或改写事实。无来源、过期、冲突、估算与失败必须显式呈现给用户。",
        "**算法计算主线**：带时间窗的点位排序与通勤计算由确定性算法求解（贪心 + 2-opt，复杂度上升后引入 OR-Tools），行程合理性由规则化打分评估；LLM 输出的柔性偏好（“日落时分要在江边”）先转译为时间窗/权重约束再进算法。",
        "**契约工程主线**：7 个 Agent 的输入输出以 JSON Schema（Draft-07）冻结，双栈（Java/Python）之间以 OpenAPI 契约对接；先改契约、示例与契约测试，再改实现与消费者；Schema 是团队并行开发的接口协议。",
        "**中心化编排主线**：采用 Orchestrator-Worker 模式——总控 Agent 持有全局状态、唯一面向用户，6 个专业 Agent 无状态、彼此不直连；任务以状态机驱动，事件只追加留痕，全流程可回放、可断点续跑。",
        "**版本不可变主线**：每次生成或局部修改原子创建不可变行程版本（含内容哈希、来源引用、change_set）；局部修改只重跑白名单范围，未受影响区块保真；回滚以历史版本为内容来源创建新版本，不改写历史。",
        "**双栈分工主线**：Java Spring Boot 是业务数据（身份、任务、版本、积分、审计、MySQL）唯一写入方；Python FastAPI + LangGraph 负责智能体编排与计算，只经 REST/OpenAPI 获取任务上下文并回写步骤结果——两栈职责清晰、可独立演进，代价（跨栈契约维护）由 M0 冻结契约与契约测试控制。",
    ])

    b.h2("2.2  架构原则")
    b.bullets([
        "总控 Agent 是唯一面向用户意图的 Agent；六个专业 Agent 只接受编排调用，彼此不直连。",
        "Java 业务后端是身份、任务、版本、积分、审计和 MySQL 的唯一写方。",
        "Python Agent 服务执行编排和计算，只经 OpenAPI 获取上下文、回写步骤结果。",
        "MySQL 保存业务事实；Redis Streams 只负责投递，Redis Cache、Chroma 和对象文件都不能替代业务事实。",
        "所有事实带来源快照；LLM 只做理解、排序与表达，不能补事实。",
        "命令走 REST，进度走 SSE；所有重试、回放和结算必须幂等。",
        "外部系统（含用户输入、UGC、供应商返回）一律按不可信输入处理。",
    ])

    b.h2("2.3  总体技术路线图")
    b.p("自顶向下，平台划分为六层：前端交互层、业务服务层、智能体编排层、第三方适配层、数据存储层，以及被各 Agent 按需调用的大模型层。一次完整生成的主干路线为：")
    b.quote("鉴权与余额校验 → 报价预扣 → 需求解析与可行性预检 → 并行采集（资讯/天气/预约）→ 日级分配 → 逐日路线并行 → 餐住注入 → 冲突消解与质量校验 → 原子创建不可变版本 → 流式推送与结算（确认预扣/失败退款）")
    b.p("局部修改主干路线为：读取当前版本与 ETag → 意图分类（query/change/ambiguous）→ 影响范围与锁定项计算 → 确认（如需）→ 报价预扣 → 只调度白名单 Agent → 复用未影响区块与新鲜快照 → 质量校验 → 原子创建子版本与 change_set → 结构化 diff 与终态结算。")

    # ================= 3 =================
    b.h1("3  总体架构")
    b.h2("3.1  分层架构")
    b.image(layered_architecture(out_dir / "assets"), width_cm=15.2)
    b.caption("图 3-1  分层架构图")

    b.h2("3.2  系统上下文")
    b.p("平台的外部交互者与外部系统包括：用户（规划、修改、确认、查询）、管理员（治理与运营）、内容运营人员（核验知识与规则），以及三类外部服务——高德 Web 服务（路线与 POI）、和风天气（天气）、OpenAI 兼容模型服务（结构化推理）。外部系统返回均是不可信输入，必须经过适配、校验、来源摘要和时效处理后才可进入计划；平台通过站内进度、计划与提醒向用户反馈。")

    b.h2("3.3  容器视图与双栈边界")
    b.table(
        ["容器", "技术", "职责", "外部依赖"],
        [
            ["Vue Web", "Vue 3 + Vite + TS + Pinia + Element Plus", "表单输入、对话修正、行程展示、榜单社区、管理后台；只消费后端状态和 SSE", "Spring Boot（REST/SSE）；高德 JS API（受限 Key）"],
            ["Spring Boot 业务后端", "Java 21 / Spring Boot 3 / Maven", "身份、任务、版本、积分、审计与 MySQL 唯一写方；Outbox 发布；SSE 推送", "MySQL、Redis、对象目录、必要的业务适配器"],
            ["FastAPI + LangGraph Agent 服务", "Python 3.11+ / Pydantic v2", "总控编排、7 类 Agent、算法层、LLM 网关、RAG 检索", "MySQL 只经 OpenAPI、Redis Streams、Chroma、供应商适配器"],
            ["MySQL 8", "InnoDB / utf8mb4 / Flyway", "业务事实唯一持久层", "—"],
            ["Redis", "Streams / Cache / 限流", "任务投递、进度缓存、API 结果缓存与限流（key 前缀隔离）", "—"],
            ["Chroma", "持久化模式", "景点知识库语义检索，由单一 Agent 服务拥有", "—"],
            ["受控对象目录", "本地磁盘（预留 MinIO）", "导出文件、UGC 图片（元数据在 MySQL）", "—"],
        ],
        widths=[3.0, 3.2, 5.4, 3.4],
    )
    b.caption("表 3-1  容器视图")
    b.p("双栈边界的关键约定：Spring Boot 是业务数据唯一写入方，FastAPI/LangGraph 只经 REST/OpenAPI 读取任务上下文和回写步骤结果；两栈不直连同库，避免隐性耦合。逻辑上的 Outbox Relay 可部署在 Java 进程内，但它只能读取已提交 Outbox 并发布，不能绕过数据库事务创建任务消息。")

    b.h2("3.4  部署视图（概要）")
    b.p("交付部署以 Linux + Nginx + systemd 为主（部署目标机无虚拟化条件，裸机脚本部署是完整可行路径而非降级），同时提供 Windows PowerShell 等效启动脚本。Nginx 承接 TLS、静态资源与反向代理；MySQL/Redis/Java 后端/Python Agent 服务/前端静态站由统一启动脚本拉起，配套端口与进程清单、健康检查、开机自启与日志轮转。开发环境允许用 Docker Compose 仅启动 MySQL/Redis/Chroma 共享依赖，但不得作为唯一部署方案。详见第 9 章。")

    # ================= 4 =================
    b.h1("4  多智能体协作架构")
    b.h2("4.1  Agent 职责与通信模型")
    b.p("一条主线：总控对外，6 个专业 Agent 对内。职责划分遵循五条划分线：对外/对内（只有总控对外）、事实数据不跨源、所有 Agent 都不能编造事实、每个 Agent 都有明确降级策略且不阻塞整体、交互修正只做最小改动不全量重生成。各 Agent 的职责、数据源与禁止编造范围见《需求分析文档》表 4-1。")
    b.p("通信格式为结构化 JSON 契约，自然语言仅出现在面向用户的表达层。Agent 间消息统一套用公共封套（Envelope）：")
    b.table(
        ["字段", "必填", "说明"],
        [
            ["schema_version", "是", "契约版本，由服务封装层注入"],
            ["task_id / trace_id / request_id", "是", "任务、链路和请求标识，由服务端注入"],
            ["generated_at", "是", "服务输出生成时间，由服务端注入"],
            ["status", "是", "ok=完整成功 / partial=部分子项缺失 / degraded=触发降级 / failed=失败"],
            ["message", "条件必填", "partial/degraded/failed 时必填的人类可读说明"],
            ["error_code", "条件必填", "failed 时必填的稳定机器错误码"],
            ["degraded_fields", "条件必填", "partial/degraded 时必须为非空数组"],
        ],
        widths=[4.2, 2.2, 8.6],
    )
    b.caption("表 4-1  公共消息封套（内联于各 Agent output.schema.json 顶层）")
    b.p("模型只生成状态和专属载荷；服务端注入系统字段后执行最终 Schema 校验。failed 不得被迫生成成功载荷。")

    b.h2("4.2  首次生成 DAG")
    b.image(generation_dag(out_dir / "assets"), width_cm=14.2)
    b.caption("图 4-1  首次生成主链路 DAG")
    b.p("多方案模式下，步骤 [5] 分叉出 2~3 个编排策略，[6][7] 各自执行，[4] 的采集数据全程共享（同一新鲜快照），只重跑真正差异部分，控制 API 与 token 成本。并行节点只共享最小必要上下文；每一步记录 input_hash、attempt_no、输出引用和来源新鲜度。")

    b.h2("4.3  局部修改 DAG")
    b.image(revision_dag(out_dir / "assets"), width_cm=14.2)
    b.caption("图 4-2  局部修改链路")
    b.p("确认前不创建新版本。回滚也是以目标历史版本为内容来源创建新版本，不修改或删除历史版本；版本保存 parent、变更集、来源、schema/model/prompt 版本与内容 hash，保留上限 10 个/行程。")

    b.h2("4.4  任务状态机")
    b.p("任务以 MySQL planning_task 表为事实来源，状态迁移采用乐观锁 + 条件更新，事件只追加。状态定义如下（阶段时限与全局 Deadline 取更早者；首次生成 operation_deadline 默认创建后 180 秒，局部修改默认执行开始后 60 秒；确认等待暂停执行 Deadline，独立 24 小时确认期限）：")
    b.table(
        ["状态", "含义", "默认阶段时限", "可否取消"],
        [
            ["CREATED", "请求已校验并创建任务，尚未完成预扣", "10 秒", "是"],
            ["BALANCE_RESERVED", "服务端报价对应积分已幂等预扣", "10 秒", "是"],
            ["QUEUED", "Outbox 已提交，等待 Worker 租约", "20 秒", "是"],
            ["PARSING", "解析需求并执行可行性预检", "30 秒", "是"],
            ["COLLECTING", "并行获取资讯、天气、预约等来源数据", "60 秒", "是"],
            ["SCHEDULING", "日级分配和硬约束初检", "30 秒", "是"],
            ["ROUTING", "逐日路线并行计算", "60 秒", "是"],
            ["ASSEMBLING", "餐住、质量校验、整合和版本候选生成", "30 秒", "是（版本事务开始后 CAS 决胜）"],
            ["AWAITING_CONFIRMATION", "已持久化冲突和选项，等待用户决定", "24 小时", "是"],
            ["SUCCEEDED", "完整可用版本已原子创建，预扣已确认", "终态", "否"],
            ["PARTIAL", "可用且可追溯版本已创建，缺失项显式可见", "终态", "否"],
            ["FAILED", "无可用版本且执行不可继续", "有预扣时等待退款", "否"],
            ["CANCELLED", "用户拒绝/取消或确认超时，未发布新版本", "有预扣时等待退款", "否"],
            ["REFUNDED", "FAILED/CANCELLED 对应预扣已幂等全额退款", "终态", "否"],
        ],
        widths=[3.6, 6.0, 3.0, 2.4],
    )
    b.caption("表 4-2  planning_task 状态定义")
    b.p("终态幂等：SUCCEEDED/PARTIAL/FAILED/CANCELLED/REFUNDED 重复回调不产生第二次结算、第二个版本或重复通知。Worker 以租约和心跳领取步骤，租约过期只增加 attempt_no；恢复以 MySQL 状态和成功步骤为准，来源仍新鲜则复用，否则从最早受影响步骤重跑。")

    b.h2("4.5  冲突消解与确认机制")
    b.p("硬约束优先级由 ADR-0011 固定：用户显式必去点与锁定项 > 开放时间/预约等客观约束 > 时间窗可行性 > 用户柔性偏好 > 系统优化目标。冲突消解遵循三条规则：")
    b.numbers([
        "可自动消解的冲突（闭馆日改期、天气不佳换室内方案前置、时长超限压缩游览时间）由总控裁决并在变更摘要中说明；",
        "影响必去点或引发连锁调整的阻断性冲突转入 AWAITING_CONFIRMATION，携带选项询问用户，不得私删必去点；",
        "拒绝或超时则 CANCELLED，有预扣则幂等退款。",
    ])

    b.h2("4.6  失败与降级策略")
    b.table(
        ["场景", "策略"],
        [
            ["单个 Agent 超时/失败", "重试 1 次 → 仍失败则降级：该维度信息缺省并明确标注（如“天气服务暂不可用，建议出行前自查”），不阻塞整体产出"],
            ["地图 API 失败", "路线是行程骨架，降级空间小：优先读缓存 → 直线距离估算并显著标注 is_estimated → 不可用；估算不能通过硬可行性 Gate"],
            ["天气服务失败", "标注“天气服务暂不可用”；预报窗口外只能给气候统计并标注 data_type；失败不得默认晴天"],
            ["知识/规则缺失", "返回 unknown 空卡或“暂未收录/请以官方为准”，不得由模型补写"],
            ["LLM 输出不合 Schema", "JSON 提取→解析→校验→有限格式修复→携带错误重试一次→failed；不得补事实值或猜枚举"],
            ["全链路失败", "任务失败对用户可见、可一键重试，保留已采集数据避免重复 API 消耗；预扣积分幂等全额退回"],
            ["鉴权失败", "不得作为业务空结果缓存；按错误码显式报错"],
        ],
        widths=[3.8, 11.2],
    )
    b.caption("表 4-3  失败与降级策略")
    b.p("降级决策的统一规则：调用失败 → 查同参数新鲜缓存（保留原来源时间）→ 路线允许显式估算则直线估算 → 判断缺失是否破坏硬可行性：不破坏则 PARTIAL 并列出 degraded_fields；可由用户选择则 AWAITING_CONFIRMATION；不可绕过则 FAILED → 幂等退款。任何估算、过期、冲突和失败都必须进入结构化状态并对用户可见。")

    # ================= 5 =================
    b.h1("5  技术栈选型")
    b.h2("5.1  选型原则")
    b.p("选型遵循四项原则：适配本地部署（单机可跑、运维极轻）；团队上手成本与开发效率优先；生态成熟、资料充足；已被验证的方案优先复用其思路（如 TripStar 验证的异步任务 + 进度推送、双地图引擎回退、Vue 3 + 高德 JS 组合）。")
    b.h2("5.2  选型清单")
    b.table(
        ["层", "选定", "理由", "备选"],
        [
            ["大模型层", "OpenAI 兼容云端 API（配置化）", "统一 LLM 网关按任务路由、模型可插拔；先云后混合把“本地模型效果不足”移出关键路径；供应商/Base URL/模型名由部署配置注入", "vLLM 本地推理（有 GPU 时替代 Ollama）"],
            ["智能体框架层", "LangGraph（Python）", "图式编排与中心化 DAG 天然吻合；状态持久化、断点续跑、human-in-the-loop 开箱即用；迭代修改/版本/降级需要真正的状态机支撑", "自研轻量编排；CrewAI/AutoGen（与强控制流需求不匹配）"],
            ["业务后端", "Java Spring Boot 3（Java 21/Maven）", "团队 Java 储备强，用户/行程/版本/榜单/积分等业务侧开发效率与质量有保障", "—"],
            ["Agent 服务", "Python FastAPI + LangGraph + Pydantic v2", "LangGraph 与 LLM 库均为 Python 原生；Schema 校验与算法层同生态", "—"],
            ["登录鉴权", "Spring Security + JWT（USER/ADMIN 两级）", "与 Spring Boot 天然契合；JWT 无状态便于 SSE 长连接配合", "Session 集中会话"],
            ["前端", "Vue 3 + Vite + TypeScript + Pinia + Vue Router + Element Plus", "国内生态与团队技能匹配；SSE 流式渲染成熟；Vue 3 + 高德 JS API 组合已被参考项目验证", "React + Antd"],
            ["业务库", "MySQL 8（Flyway）", "团队经验可复用；单机本地部署运维极轻", "PostgreSQL"],
            ["缓存/队列", "Redis（Streams + Cache + 限流，key 前缀隔离）", "任务队列、进度、缓存统一设施；禁止把缓存当业务事实来源", "RabbitMQ（不引入额外组件）"],
            ["向量检索", "Chroma 持久化模式（单一 Agent 服务拥有）", "轻量本地化；Embedding 默认 bge-small-zh-v1.5，可配置替换", "FAISS/Milvus"],
            ["对象文件", "受控本地目录（元数据在 MySQL）", "起步简单；预留 MinIO 升级位；文件名使用随机对象键", "MinIO"],
            ["地图/POI", "高德 Web 服务 API", "国内数据质量与免费配额均衡；适配层保留换源能力", "百度地图"],
            ["天气", "和风天气（主）/高德天气（备）", "和风支持逐小时与 7~15 天预报，更匹配行程规划", "彩云天气"],
            ["路径算法", "确定性贪心 + 2-opt（点位数少足够）", "可解释、可测试；复杂度上来再引 OR-Tools（TSP/VRP 带时间窗）", "OR-Tools"],
            ["部署", "Linux + Nginx + systemd 裸机脚本（含 Windows 等效脚本）", "部署目标机无虚拟化条件，装不了 Docker；一键启停与端口清单文档化", "Docker Compose（换机备选）"],
        ],
        widths=[2.2, 3.4, 6.4, 3.0],
    )
    b.caption("表 5-1  技术栈选型清单")

    b.h2("5.3  LLM 网关标准能力")
    b.bullets([
        "统一 OpenAI 兼容协议接入；Key 仅从环境变量读取，不入库、不进日志。",
        "按任务路由模型（简单任务小模型），超时、并发、预算控制与费用统计。",
        "输出处理固定管线：JSON 提取 → 解析 → Schema 校验 → 有限格式修复（清杂字符→修引号→补括号→提取）→ 携带错误重试一次 → failed；不能补事实值或猜枚举。",
        "用户内容只能作为 data（防 Prompt 注入），工具结果与供应商返回同为不可信输入。",
        "调用留痕只保存脱敏元数据（模型、状态、耗时、token/费用汇总），不记录原始 Prompt。",
    ])

    b.h2("5.4  算法层")
    b.p("路径与顺序优化采用确定性贪心 + 2-opt（输入必须是 poi_id、城市、坐标和坐标系；同名/缺坐标/跨城市不猜测，进入消歧或人工队列）；通勤、游玩、排队和用餐时间分离计算。榜单推荐采用五维度加权评分（热度/评分/季节适配/行程合理性/人流量）+ 时间衰减，规则可配置，不上机器学习模型。行程合理性规则化打分（通勤占比、节奏方差、冲突计数），同时服务生成质量自检与榜单排序。语义检索采用 Embedding + Chroma 服务景点知识库 RAG，只检索已发布、城市匹配、未过期内容。")

    # ================= 6 =================
    b.h1("6  数据架构")
    b.h2("6.1  数据域划分")
    b.table(
        ["数据域", "核心表/对象", "要点"],
        [
            ["账号与会话", "user、session/refresh_token、audit_event", "密码哈希、令牌吊销、刷新令牌 ≤30 天"],
            ["行程与版本", "trip、trip_version、change_set、plan_snapshot", "版本不可变、内容 hash、parent 链、ETag 乐观锁"],
            ["任务与事件", "planning_task、task_step、task_event、outbox", "状态机事实来源；事件只追加、sequence_no 单调"],
            ["Agent 结果与来源", "agent_result、source_snapshot", "输入输出引用、来源/取得时间/有效期/估算标识"],
            ["POI/知识/规则", "city、poi、knowledge、chunk、reservation_rule、source", "草稿→复核→发布→停用→回滚全流程审计；stale 阈值 30 天"],
            ["积分账务", "points_account、points_ledger、business_reference", "整数积分、只追加流水、唯一业务引用、终态一次结算"],
            ["社区与治理", "post、interaction、report、ranking、moderation", "先发后审；下架后公开 API/搜索/缓存/榜单同时不可见"],
            ["偏好", "user_preference、preference_evidence、preference_summary", "带来源/置信度/权重/最后使用时间；用户可清空"],
            ["通知与导出", "reminder、notification、export_job、object_metadata", "站内提醒按时区去重；导出 24 小时过期"],
        ],
        widths=[2.8, 5.6, 6.6],
    )
    b.caption("表 6-1  数据域划分（详细字段以 S04 冻结的数据字典与 Flyway 为准）")

    b.h2("6.2  关键数据设计")
    b.lead("来源快照：", "所有事实字段引用 source_snapshot，记录来源标识（如供应商记录 ID / knowledge_id / 规则版本）、取得时间 retrieved_at、有效期 valid_until、data_type（forecast / climate_statistics / rule / estimated / unknown）与估算标识 is_estimated。前端展示“数据更新于 X”，超过有效期的内容不得作为确定性硬约束。")
    b.lead("不可变版本：", "trip_version 只增不改，含内容哈希、schema/model/prompt 版本与来源引用；局部修改产生带 parent 与 change_set 的子版本；回滚创建新版本。")
    b.lead("积分流水：", "points_ledger 只追加，每条流水带唯一 business_reference 与幂等键；预扣/确认/退款三动作幂等守恒；崩溃恢复后终态只结算一次。")
    b.lead("货币与积分：", "货币统一 ISO 4217 currency + 最小货币单位整数 amount_minor；积分为整数 points；二者不得混用。")

    b.h2("6.3  缓存与对象文件")
    b.p("缓存按域划分所有权与 TTL：天气按小时、路线按周（缓存键包含坐标系/起终点/方式/时段，禁止仅按中文名缓存）、景点基础信息按月、预约规则按人工核验周期。失败鉴权不得缓存为业务空结果（负缓存仅针对合法的空业务结果）。对象文件写入受控本地目录，元数据写 MySQL，文件名使用随机对象键；备份默认写入部署机外的受限目录，MySQL 与对象文件每日一次、保留 7 个日备份，M4 完成隔离恢复演练。")

    b.h2("6.4  数据库工程规范")
    b.p("数据库变更只允许新增 Flyway 迁移，已发布迁移不得改写；空库升级、重复启动与从上一版升级均须可重复验证。时间统一 UTC DATETIME(6)，InnoDB、utf8mb4；主键、owner、唯一键、乐观锁、软删除/保留期、索引与敏感字段在数据字典中显式定义；可查询字段不能只藏在 JSON。")

    # ================= 7 =================
    b.h1("7  接口契约体系")
    b.h2("7.1  契约分层")
    b.table(
        ["契约", "位置", "约束对象"],
        [
            ["Agent 输入/输出 Schema", "agents/01~07/*/input.schema.json、output.schema.json（JSON Schema Draft-07）", "7 个 Agent 的消息格式；封套内联顶层"],
            ["双栈 OpenAPI", "contracts/openapi/agent-business.yaml", "Java 业务后端与 Python Agent 服务之间的任务上下文与步骤结果回写"],
            ["示例", "contracts/examples/（每 Agent 的 success/partial/degraded/failed）", "契约测试夹具与前端联调样例"],
            ["错误码目录", "contracts/error-codes/error-codes.md", "稳定机器错误码，与封套 error_code 对齐"],
            ["契约索引", "contracts/agent-contract-index.md", "全部契约的清单与版本状态"],
        ],
        widths=[3.4, 6.4, 5.2],
    )
    b.caption("表 7-1  契约分层")

    b.h2("7.2  契约约束要点")
    b.bullets([
        "路线数据使用 poi_id、城市、经纬度和坐标系；天气带来源与取得时间；景点带知识条目/chunk/时效；餐住带 poi_id/坐标/价格口径；预约带币种、生效期、时区和 stale 标识；修正带 intent、base_version_id 和确认信息。",
        "系统字段（schema_version、task_id、trace_id、request_id、generated_at）由网关注入，模型不得生成。",
        "日期格式 YYYY-MM-DD，时间格式 HH:MM（24 小时制），以正则强制。",
        "建立 Draft-07、OpenAPI、示例与 Java/Python 消费者契约测试，纳入 CI；契约测试不通过即构建失败。",
        "契约变更流程：Schema 与示例 → 契约测试 → prompt → 实现与消费者；破坏性变更必须在契约索引中说明并走评审。",
    ])

    # ================= 8 =================
    b.h1("8  关键机制设计")
    b.h2("8.1  异步任务：事务 Outbox + Redis Streams")
    b.p("业务后端创建任务、报价预扣和 Outbox 写入必须处于同一数据库事务；Outbox Relay 将已提交消息发布到 Redis Streams Consumer Group，消费者至少一次投递、业务幂等（attempt_no + 幂等键）。Worker 租约心跳领取步骤，死信与超时独立处理。MySQL 任务表是事实来源，Redis Streams 只负责投递——即使 Redis 重启，恢复也以 MySQL 状态为准。")

    b.h2("8.2  SSE 进度与事件补拉")
    b.p("进度统一使用 SSE，命令一律走 REST。任务事件持久化并以 task_id + sequence_no 单调递增；SSE 鉴权后推送，断线从最后序号补拉，客户端按任务和序号去重；终态事件关闭连接。前端不得直连 Agent 服务、Redis 或供应商。")

    b.h2("8.3  幂等与并发控制")
    b.bullets([
        "所有写操作校验身份、资源归属、状态、乐观锁（版本 ETag）和幂等键；不信任前端提交的 user、role、balance、price、version、source。",
        "重复预扣/重复退款/重复确认在唯一 business_reference 约束下收敛为一次；并发临界余额由数据库原子条件更新保证。",
        "局部修改以 ETag 乐观锁防并发覆盖：版本已变更则返回 409，不写版本、退款预扣。",
        "终态幂等：终态只结算一次，重复回调不产生第二版本、第二次结算或重复通知。",
    ])

    b.h2("8.4  版本、diff 与回滚")
    b.p("版本保存 parent、变更集（change_set）、来源引用、schema/model/prompt 版本与内容 hash；确认前不写新版本；diff 为结构化对比（逐区块新增/修改/删除）供前端渲染；回滚以目标历史版本为内容来源创建新版本。多方案共享同一新鲜采集快照，只重跑真正差异部分。")

    b.h2("8.5  积分一致性")
    b.p("积分账务以“只追加流水 + 幂等动作”实现守恒：报价（quote）→ 预扣（reserve）→ 确认（confirm）/ 退款（refund）。任务创建与预扣同事务；失败/取消路径幂等全额退款并落 REFUNDED 终态；定时守恒对账任务核对账户余额与流水汇总，异常告警并留审计。前端传入的金额一律不可信，以服务端 quote 为准。")

    b.h2("8.6  降级透明化与可观测")
    b.p("request_id 关联同步请求、trace_id 关联调用链、task_id 关联异步任务；每次生成可回放各 Agent 输入输出（只保存脱敏摘要、哈希、状态、耗时与费用汇总）。原始用户 Prompt、完整供应商响应和敏感字段不进入事件或普通日志。结构化日志 + Agent 链路留痕落库为基础可观测方案，初期不上 Prometheus 全家桶。")

    # ================= 9 =================
    b.h1("9  部署架构")
    b.h2("9.1  运行拓扑")
    b.table(
        ["组件", "运行方式", "说明"],
        [
            ["Nginx", "systemd 服务", "TLS 终止、静态资源、反向代理至 Java 后端；SSE 需关闭缓冲"],
            ["Spring Boot 后端", "systemd 服务（受限账号）", "业务 API、Outbox Relay、SSE 推送"],
            ["FastAPI Agent 服务", "systemd 服务（受限账号）", "编排 Worker、LLM 网关、适配器"],
            ["MySQL 8 / Redis", "系统服务", "独立数据目录；不暴露公网端口"],
            ["前端静态站", "Nginx 托管", "Vite 构建产物；高德 JS Key 为受限 Key + 安全密钥"],
        ],
        widths=[3.4, 4.4, 7.2],
    )
    b.p("配套交付：一键启动/停止脚本（Linux systemd 单元 + Windows PowerShell 等效脚本）、端口与进程清单、健康检查、开机自启、TLS、日志轮转、密钥文件权限（受限账号 + 独立目录）。开发 Compose 仅用于拉起 MySQL/Redis/Chroma 共享依赖。")

    b.h2("9.2  环境配置与密钥管理")
    b.p("外部账号（LLM_BASE_URL/LLM_API_KEY/LLM_MODEL、AMAP_WEB_KEY、VITE_AMAP_JS_KEY、VITE_AMAP_SECURITY_CODE、QWEATHER_API_HOST/QWEATHER_API_KEY）通过部署配置注入，不写入仓库、不进日志。真实联调前必须完成最小调用验证（外部账号 Gate）；账号未就绪时允许继续开发 Mock、契约和算法，但不得把 Mock 结果记为真实 API 验收通过。Mock 开关不可在生产配置误启用。")

    b.h2("9.3  备份与恢复")
    b.p("备份对象为 MySQL、对象文件与必要配置，默认写入部署机外的受限目录，每日一次、保留 7 个日备份；M4 必须完成隔离恢复演练，校验任务、版本、来源、积分守恒与文件授权可正确恢复。")

    # ================= 10 =================
    b.h1("10  安全与合规设计")
    b.table(
        ["领域", "设计要点"],
        [
            ["认证授权", "JWT + USER/ADMIN 两级角色；逐接口校验身份/角色/资源归属；前端隐藏入口不等于授权；令牌吊销、登录限流锁定、并发刷新防护"],
            ["输入安全", "UGC、上传文件与供应商返回均按不可信输入处理；参数化查询防注入；URL/Markdown 白名单；禁用 v-html 渲染 UGC；防 XSS/SSRF/路径穿越"],
            ["Prompt 安全", "用户内容只作为 data 进入 LLM；工具结果同为不可信输入；不因注入文本改变事实来源与权限边界；不记录原始 Prompt"],
            ["文件安全", "上传图片剥离 EXIF、记录许可与来源、校验文件合法性；恶意图片检测；许可不明只存外链文字引用"],
            ["数据隐私", "日志脱敏（不记凭证、手机号、完整私密行程）；偏好画像仅本人可见可清空；忌口等敏感偏好不进公共画像；复用/公开内容去除私密偏好与精确住宿"],
            ["账务安全", "积分不可提现、不对应法定货币价值；不接真实支付；流水只追加、管理员不能删除审计或流水；高风险管理操作二次确认"],
            ["传输与部署", "TLS、受限账号、密钥文件权限、依赖与许可证扫描、密钥扫描入 CI"],
        ],
        widths=[2.8, 12.2],
    )
    b.caption("表 10-1  安全与合规设计要点")

    # ================= 11 =================
    b.h1("11  架构演进与扩展预留")
    b.p("架构为后续扩展预留了明确的升级位，均不在本期实施：行中模式（实时定位 + 动态重排）在任务状态机上扩展实时输入源；多端形态复用 OpenAPI；预订闭环在钱包体系上接真实支付网关（需补合规评估）；Agent 能力开放（MCP 等协议）把 7 类 Agent 封装为标准工具；境外目的地复用适配层多源切换（双地图引擎回退）；本地模型混合经 LLM 网关路由切换，无需改动业务代码。")

    # 附录
    b.h1("附录 A  架构决策记录（ADR）索引")
    b.table(
        ["编号", "决策", "状态"],
        [
            ["ADR-0001", "服务边界与 MySQL 唯一写方（Java 写、Python 经 OpenAPI）", "Accepted"],
            ["ADR-0002", "事务 Outbox + Redis Streams 投递", "Accepted"],
            ["ADR-0003", "SSE 进度通道与事件补拉", "Accepted"],
            ["ADR-0004", "来源快照（来源/取得时间/有效期/估算标识）", "Accepted"],
            ["ADR-0005", "不可变行程版本与 change_set", "Accepted"],
            ["ADR-0006", "缓存所有权与 TTL/负缓存规则", "Accepted"],
            ["ADR-0007", "积分一致性（只追加流水 + 幂等三动作）", "Accepted"],
            ["ADR-0008", "Chroma 归属与知识检索边界", "Accepted"],
            ["ADR-0009", "对象文件受控目录与 MinIO 预留", "Accepted"],
            ["ADR-0010", "裸机部署（Linux/Nginx/systemd + Windows 等效脚本）", "Accepted"],
            ["ADR-0011", "硬约束优先级与确认条件", "Accepted"],
        ],
        widths=[2.4, 10.2, 2.4],
    )
    b.note("注：ADR 全文见 docs/architecture/ADR/ 目录；变更 ADR 需先更新决策基线与影响分析。")
    return b.save()
