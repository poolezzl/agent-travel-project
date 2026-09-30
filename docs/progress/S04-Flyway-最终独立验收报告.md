# S04 Flyway 最终独立验收报告

## 1. 验收对象

- 仓库：`small-soil-ash/agent-travel-project`
- 分支：`dev-r5`
- 待验收 Commit：`78d0622d44c5846b93566e1ac7e07e685b767c8f`
- 父 Commit：`4b3ef13c4b26bb1de20140a67b090844e42817d9`
- 验收范围：S04 首版 Flyway migration、后端构建测试、MySQL 8.4 独立空库迁移、重复启动、健康检查、Flyway validate、实库结构检查和临时资源清理。

工程师自测材料仅作为待核对线索。本报告结论来自本次独立执行的命令和独立临时 MySQL 实例。

## 2. 分支、Commit 和工作区

✅ 通过：`HEAD`、`origin/dev-r5` 均为 `78d0622d44c5846b93566e1ac7e07e685b767c8f`，当前分支为 `dev-r5`，父 Commit 关系正确。

✅ 通过：`4b3ef13c4b26bb1de20140a67b090844e42817d9..78d0622d44c5846b93566e1ac7e07e685b767c8f` 的提交范围仅包含：

- `backend/src/main/resources/db/migration/V1__initial_s04_schema.sql`
- `backend/src/test/java/com/agenttravel/backend/FlywayConfigurationTests.java`
- `docs/progress/S04-Flyway-实现与验证报告.md`
- `docs/progress/S04-最终数据模型冻结单.md`
- `docs/progress/S04.md`

✅ 通过：`git diff --check 4b3ef13c4b26bb1de20140a67b090844e42817d9..78d0622d44c5846b93566e1ac7e07e685b767c8f` 无输出，未发现空白错误。

⚠️ 风险：工作区不是 Git clean 状态，但未跟踪内容是验收前已存在的用户材料：上一份独立报告、联合评审报告、本地旧版备份目录和 Gitee 教程文件。本次未修改、未删除、未暂存、未提交这些内容。

## 3. S04 阶段边界和冻结一致性

✅ 通过：Commit 未修改 S00-S03 冻结文档、ADR、Schema、OpenAPI、`agent-service`、`frontend` 或业务 Java 代码。

✅ 通过：未发现注册、登录、JWT、业务 Controller/Service/Repository、任务 Worker、Redis Streams、SSE、积分 API、社区 API、导出业务、提醒调度、真实第三方调用或后续阶段业务实现。

✅ 通过：migration 目录只有 `V1__initial_s04_schema.sql`，没有重复 V1、后续版本 migration、Redis 表或初始化业务数据。

✅ 通过：SQL 中未发现明文密码、Token、API Key、原始 Prompt 或完整供应商响应。实库敏感字段检查仅发现哈希字段：`password_hash`、`refresh_token_hash`、`download_token_hash`。

## 4. Migration 静态和实库核验

✅ 通过：正式表数量为 40 张，与 S04 冻结范围一致；不含 `flyway_schema_history` 的业务表均为空。

✅ 通过：所有表均为 `InnoDB`，表字符集为 `utf8mb4`；实库查询得到 41 张表均为 `InnoDB`、`utf8mb4_0900_ai_ci`。

✅ 通过：UUID 主键、外键和关联字段统一使用 `BINARY(16)`；实库查询得到 115 个 `binary(16)` 字段。哈希字段使用 `BINARY(32)`，实库查询得到 14 个 `binary(32)` 字段。

✅ 通过：实库查询得到 103 个 `DATETIME(6)` 字段，满足 UTC 和微秒精度约定；JDBC 连接使用 UTC 参数。

✅ 通过：循环外键采用后置 `ALTER TABLE` 处理，migration 可在空库完成执行。

✅ 通过：已核对的关键约束存在并在实库生效：

- `points_quote.expires_at > created_at`；
- `points_ledger` 的方向、流水类型和余额守恒约束；
- `agent_result.request_id` 唯一；
- Outbox 幂等唯一键；
- `task_event(task_id, sequence_no)` 唯一；
- `trip_version(trip_id, version_no)` 唯一；
- `points_ledger.business_reference` 唯一；
- `preference_summary.eligible = true` 时样本数至少 3。

✅ 通过：实库结构汇总为 72 个外键、47 个唯一约束、83 个 CHECK 约束、133 个非主键索引名；未发现业务数据行。

## 5. Maven 独立验证

✅ 通过：`mvn -f backend/pom.xml test`，6 tests，0 failures，0 errors，Spotless 通过。

✅ 通过：`mvn -f backend/pom.xml verify`，Enforcer、Spotless、编译、测试、打包和 verify 均通过。

✅ 通过：Maven 实际运行 Java `21.0.8`、Maven `3.9.16`，符合 `pom.xml` 的 Java 21 和 Maven 3.9.6+要求。

⚠️ 风险：PowerShell 直接执行 `java -version` 显示系统默认 Java 8，但 Maven 使用工具链实际为 Java 21.0.8 并通过 Enforcer。后续 CI 和本地验收应继续显式保证 Java 21。

## 6. MySQL 8.4 首次启动

本次使用独立临时 Docker 资源，不使用个人常驻数据库：

- 镜像：`mysql:8.4.8`
- 容器：`s04-final-independent-mysql`
- 临时卷：`s04-final-independent-volume`
- 数据库：`trip_planner`
- 宿主端口：`33306 -> 3306`

✅ 通过：容器启动后独立查询得到 MySQL `8.4.8`，初始数据库表数量为 `0`。

✅ 通过：使用 `local` Profile 启动后端，首次启动日志显示 Flyway validated 1 migration、创建 `flyway_schema_history`、成功执行 `V1__initial_s04_schema.sql`，并成功应用 1 migration，schema version 为 `v1`。

✅ 通过：首次启动后数据库表数量为 `41`，其中正式表 `40` 张，`flyway_schema_history` `1` 张；业务表没有初始化数据。

✅ 通过：首次 `flyway_schema_history` 查询结果为 1 行：version `1`、description `initial s04 schema`、script `V1__initial_s04_schema.sql`、success `1`。

## 7. 健康检查

✅ 通过：首次启动 `GET http://127.0.0.1:18081/actuator/health` 返回 HTTP `200`。

✅ 通过：健康响应关键字段为：`status=UP`、`backend.status=UP`、`profiles=local`、`databaseMode=mysql`、`externalServices=disabled`、`db.status=UP`。

## 8. 同一数据库重复启动

✅ 通过：停止首次应用后，使用同一 MySQL 容器、同一数据库和同一 migration 再次启动。

✅ 通过：第二次启动成功，未出现重复建表、校验冲突或结构破坏；健康端点再次返回 HTTP `200`，`status=UP`、`databaseMode=mysql`、`db.status=UP`。

✅ 通过：第二次启动后表数量仍为 `41`，其中正式表 `40` 张；Flyway history 仍为 `1` 行且 `success=1`。

## 9. 独立 Flyway validate

✅ 通过：使用项目运行时依赖和当前 migration 通过独立 Flyway API 执行 `validate()`，实际输出为：

```text
Successfully validated 1 migration
FLYWAY_VALIDATE_SUCCESS
```

⚠️ 风险：Flyway 日志提示当前版本对 MySQL 8.4 的支持尚未充分测试，最新支持版本提示为 MySQL 8.1。实际 migrate、重复启动和 validate 均成功，因此本项不判为阻断；后续应在 CI 固定并复核 Flyway 与 MySQL 版本组合。

## 10. 临时资源清理

✅ 通过：已停止两次后端进程。

✅ 通过：已执行并成功完成：

```text
docker rm -f s04-final-independent-mysql
docker volume rm s04-final-independent-volume
```

✅ 通过：验收后 `18081` 和 `33306` 均无监听；临时容器和临时卷不存在。未保留数据库、容器、卷、临时网络、端口或验收日志。

## 11. 实际执行命令摘要

以下命令均由本次独立验收实际执行，临时密码未写入报告：

```text
git status --short --branch
git rev-parse HEAD
git rev-parse origin/dev-r5
git diff --name-status 4b3ef13c4b26bb1de20140a67b090844e42817d9..78d0622d44c5846b93566e1ac7e07e685b767c8f
git diff --check 4b3ef13c4b26bb1de20140a67b090844e42817d9..78d0622d44c5846b93566e1ac7e07e685b767c8f
mvn -f backend/pom.xml test
mvn -f backend/pom.xml verify
mvn -f backend/pom.xml dependency:build-classpath
mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.arguments=--server.port=18081
Invoke-RestMethod http://127.0.0.1:18081/actuator/health
docker exec s04-final-independent-mysql mysql ...
jshell --class-path <project runtime classpath>  # Flyway API validate()
docker rm -f s04-final-independent-mysql
docker volume rm s04-final-independent-volume
Get-NetTCPConnection -LocalPort 18081,33306
```

## 12. 未完成项和风险

⚠️ 风险：工作区包含验收前既有未跟踪材料，因此 `git status` 不是 clean；这些内容未被本次验收修改，也未纳入 Commit。若 R5 要求物理工作区 clean，应由工作区所有者在不删除材料的前提下另行处理。

⏸ PENDING：未执行远程 CI Runner；本报告不把未实际触发的远程 workflow 结果当作独立证据。远程 CI 地址和实际运行结果需由 R5 或仓库维护者补充核对。

## 13. 最终建议

✅ 通过：S04 首版 migration 的空库首次迁移、健康检查、重复启动、独立 Flyway validate、实库结构约束、Maven test/verify 和临时资源清理均已由本次独立验收实际完成并通过。

**建议通过，进入 S04 Gate 裁决。**

本结论不等同于关闭 S04 Gate，不替代 R5 最终裁决，不执行合并、提交或推送，也不授权开始后续 migration 或业务实现。
