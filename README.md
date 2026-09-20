# 多智能体协作文旅规划平台 - 开发入口

> 当前目录是课程项目的文档与 Agent 契约基线。开发必须从本页指定的唯一顺序进入，不得在多套步骤文档中自行挑选。

## 开发入口

1. 先读 [需求与整体设计](docs/01-需求与整体设计.md)，了解业务范围。
2. 再读 [开发决策基线](docs/02-开发决策基线.md)，确认已冻结的技术、数据、计费、质量和外部账号 Gate。
3. 以 [逐步开发提示词](docs/04-逐步开发提示词.md) 作为唯一全局执行清单，从 S00 顺序执行；只有标记为可并行的步骤可以并行。
4. 每一步同时遵守 [公共工程与协作规范](docs/roles/00-公共工程与协作规范.md) 和对应 R1-R8 专属规范。
5. Agent 接口以 `agents/**/*.schema.json` 为准；跨服务接口在 S02 后以 `contracts/openapi/*.yaml` 为准。

完整文档索引见 [docs/README.md](docs/README.md)。旧 25 步文档、重复压缩包目录和 `.bak` 已删除。

---

## 目录结构

```
zhihuilvyou/
├── backend/                                   # Java 业务后端（S03 初始化）
├── agent-service/                             # Python Agent 服务（S03 初始化）
├── frontend/                                  # Vue 前端（S03 初始化）
├── contracts/                                 # 跨服务契约（S02 冻结）
├── tests/                                     # 跨模块测试与评估入口
├── deploy/                                    # 环境与部署脚本（后续步骤实现）
├── README.md                                    # 本文件
├── docs/                                        # 正式文档
│   ├── 01-需求与整体设计.md
│   ├── 02-开发决策基线.md
│   ├── 03-多智能体系统规范.md
│   ├── 04-逐步开发提示词.md
│   ├── 需求追踪矩阵.md
│   ├── 需求澄清与决策清单.md
│   ├── architecture/                            # S01 起维护架构与 ADR
│   ├── progress/                                # 各步骤 Gate 证据
│   └── roles/                                   # 公共规范与 R1-R8 角色规范
└── agents/                                      # 7 个 Agent 的机器契约与 Prompt
    ├── 01-orchestrator/                         # 总控调度 Agent
    │   ├── input.schema.json                    # 输入：user_request + 结构化字段（可选）
    │   ├── output.schema.json                   # 输出：最终行程 plan（逐日/逐段/餐饮/住宿/天气/预约）
    │   └── prompt.txt
    ├── 02-route-planner/                        # 路线规划 Agent
    │   ├── input.schema.json                    # 输入：当日点位 + 时间窗 + 交通偏好
    │   ├── output.schema.json                   # 输出：排序序列 + 逐段路线
    │   └── prompt.txt
    ├── 03-weather/                              # 气象服务 Agent
    │   ├── input.schema.json                    # 输入：城市 + 日期范围 + 敏感点 + 预报窗口
    │   ├── output.schema.json                   # 输出：逐日天气 + 出行建议 + 备选方案
    │   └── prompt.txt
    ├── 04-attractions/                          # 景点资讯 Agent
    │   ├── input.schema.json                    # 输入：景点列表 + 偏好标签
    │   ├── output.schema.json                   # 输出：资讯卡（玩法/时长/避坑/热度）
    │   └── prompt.txt
    ├── 05-dining-lodging/                       # 餐饮住宿 Agent
    │   ├── input.schema.json                    # 输入：行程草案 + 口味 + 预算 + 同行人
    │   ├── output.schema.json                   # 输出：每餐候选 + 住宿建议
    │   └── prompt.txt
    ├── 06-reservation-rules/                    # 预约规则 Agent
    │   ├── input.schema.json                    # 输入：景点列表 + 日期
    │   ├── output.schema.json                   # 输出：预约卡（票价/免票政策/核验时间）+ 提醒时间线
    │   └── prompt.txt
    └── 07-revision/                             # 交互修正 Agent
        ├── input.schema.json                    # 输入：当前行程 + 修改指令
        ├── output.schema.json                   # 输出：影响范围 + 最小改动 + 变更摘要
        └── prompt.txt
```

## 快速指引

| 我想… | 看这里 |
|---|---|
| 了解 7 个 Agent 的职责划分与降级策略 | [多智能体系统规范](docs/03-多智能体系统规范.md) |
| 了解完整业务需求与里程碑规划 | [需求与整体设计](docs/01-需求与整体设计.md) |
| 查看已冻结决策与阶段 Gate | [开发决策基线](docs/02-开发决策基线.md) |
| 按顺序开始开发 | [逐步开发提示词](docs/04-逐步开发提示词.md) |
| 对接某个 Agent 的输入/输出 | 对应目录下的 `input.schema.json` / `output.schema.json` |
| 调整某个 Agent 的提示词 | 对应目录下的 `prompt.txt` |

## 约定

- **所有 Schema 遵循 JSON Schema Draft-07**。
- **公共封套字段**：每个 Agent 的服务输出顶层必须包含 `schema_version`、`task_id`、`trace_id`、`request_id`、`generated_at`、`status`；`partial/degraded` 必须带非空 `degraded_fields`，`failed` 必须带 `error_code` 和 `message`。
- **日期格式**：统一 `YYYY-MM-DD`，正则 `^\d{4}-\d{2}-\d{2}$`。
- **时间格式**：统一 `HH:MM` 24小时制，正则 `^([01]\d|2[0-3]):[0-5]\d$`。
- **反幻觉红线**：事实数据（时长/票价/预约政策等）只能来自对应数据源，LLM 不得编造。
