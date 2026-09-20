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
