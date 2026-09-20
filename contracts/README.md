# Contracts

跨服务契约目录。Agent 输入输出以 `../agents/` 下的 JSON Schema 为当前基线；Java 与 Python 的 REST/OpenAPI 契约将在 S02 创建并冻结。

计划结构：

```text
contracts/
├── openapi/                  # OpenAPI 文档
├── examples/                 # success/partial/degraded/failed 示例
├── error-codes/              # 稳定错误码目录
└── agent-contract-index.md   # 契约版本与消费者索引
```

当前 S00 不提前定义接口语义。
