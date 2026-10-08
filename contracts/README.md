# Contracts

跨服务契约目录。Agent 输入输出以 `../agents/` 下的 Draft-07 JSON Schema 为事实来源；Java 与 Python 的 REST 边界以 `openapi/agent-business.yaml` 为事实来源。S08 公共认证与积分接口见 `openapi/s08-public.yaml`，版本为 `s08-v1`；Agent 契约版本为 `agent-contract-v1`。

计划结构：

```text
contracts/
├── openapi/                  # OpenAPI 文档
├── examples/                 # 7 个 Agent 各自的 ok/partial/degraded/failed 示例
├── error-codes/              # 稳定错误码目录
└── agent-contract-index.md   # 契约版本与消费者索引
```

运行 `python scripts/validate_s02_contracts.py` 可校验 Schema、28 个样例、OpenAPI 结构与消费者兼容规则。系统字段由网关注入，模型不得生成；Java 是业务数据唯一写入方。
