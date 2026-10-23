# 架构决策记录（ADR）

所有 ADR 必须包含状态、上下文、决策、后果、验证和替换/回滚方式。只有状态为 `Accepted` 的 ADR 才能作为实现依据；变更已接受决策时新建 ADR 并将旧记录标记为 `Superseded`，不得原地抹去历史。

| ADR | 决策 | 状态 |
|---|---|---|
| [0001](0001-service-boundary-and-mysql-writer.md) | Java/Python 边界与 MySQL 唯一写方 | Accepted |
| [0002](0002-transactional-outbox-redis-streams.md) | 事务 Outbox 与 Redis Streams | Accepted |
| [0003](0003-sse-progress.md) | REST 命令与 SSE 进度 | Accepted |
| [0004](0004-source-snapshots.md) | 事实来源快照 | Accepted |
| [0005](0005-immutable-trip-versions.md) | 不可变行程版本 | Accepted |
| [0006](0006-cache-ownership.md) | 缓存所有权与新鲜度 | Accepted |
| [0007](0007-points-consistency.md) | 积分一致性与终态结算 | Accepted |
| [0008](0008-chroma-ownership.md) | Chroma 单一所有权 | Accepted |
| [0009](0009-object-storage.md) | 受控对象文件 | Accepted |
| [0010](0010-bare-metal-deployment.md) | 裸机主部署 | Accepted |
| [0011](0011-constraint-and-confirmation-policy.md) | 硬约束与人工确认 | Accepted |
| [0012](0012-python-llm-gateway-provider-boundary.md) | Python 统一 LLM Gateway 的供应商访问边界 | Accepted |
