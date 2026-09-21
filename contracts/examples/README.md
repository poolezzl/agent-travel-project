# Contract fixtures

Fixtures are intentionally provider-free and contain no secrets or real user data. They demonstrate the common envelope and are suitable for Draft-07/OpenAPI validation.

每个 Agent 子目录包含：

- `ok.json`：完整业务载荷；
- `partial.json`：仍可交付但字段不完整，显式列出 `degraded_fields`；
- `degraded.json`：使用降级路径，显式列出 `degraded_fields`；
- `failed.json`：稳定错误码和消息，不伪造成功载荷。

样例是 Agent 原生输出，不含 OpenAPI 跨服务封套专用的 `step_id` 和 `agent_id`。
