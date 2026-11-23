# ADR-0002：事务 Outbox 与 Redis Streams

- 状态：Accepted
- 日期：2026-09-20
- 决策者：R1，R5

## 上下文

创建任务、预扣积分和发布异步消息若分属不同事务，会出现已扣费未执行或已执行未记账。Redis 不能参与 MySQL 原子事务。

## 决策

任务、积分预扣和 Outbox 在同一 MySQL 事务提交。Relay 至少一次发布到 Redis Streams Consumer Group；Worker 使用租约、心跳、`attempt_no` 和业务幂等键。MySQL 状态是事实，Redis 只负责传递，可重建、可重复。

## 后果与验证

需要 Relay、死信、滞留监控和清理策略。测试提交前崩溃、提交后发布前崩溃、重复发布、消费者崩溃和 Redis 重启，断言任务与积分不丢失、不重复。

## 替换与回滚

替换消息系统必须保留 Outbox 和至少一次语义。Redis 故障时停止新消费并在恢复后重放，不改写 MySQL 事实。

## 增补（S22）：顺序水位与重复/乱序消息

至少一次投递意味着同一消息可能重复或乱序到达。S22 在消息载荷中增加 `sequence_no`（产生该命令的
`task_event.sequence_no`），并在 `planning_task.last_queue_sequence` 持久化已应用水位：

- 消费端丢弃并确认 `sequence_no ≤ last_queue_sequence` 的消息，不重跑步骤；
- 处理成功后以“仅当更大才更新”的条件写推进水位；
- 缺少 `sequence_no` 的历史消息按 0 处理，不参与比较，仍由任务行锁与 `attempt_no` 保证幂等。

该设计不改变“MySQL 为事实来源、Redis 可重建”的前提，只把顺序判定从隐式约定变为可测试的显式状态。
