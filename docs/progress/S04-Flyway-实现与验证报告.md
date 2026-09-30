# S04 Flyway 实现与验证报告

## 1. 交付上下文

| 项目 | 结果 |
|---|---|
| 仓库 | `small-soil-ash/agent-travel-project` |
| 分支 | `dev-r5` |
| 实现前 HEAD/父提交 | `218a7f4bc66fbccc98693e5466cb77374d0e4b33` |
| 远端跟踪 | `origin/dev-r5` |
| 实现前工作区 | 有用户已有未跟踪评审报告、冻结单、旧备份目录、Gitee 教程和 V1 草稿；无已跟踪修改 |
| 当前修复基线 | `4b3ef13c4b26bb1de20140a67b090844e42817d9` |
| 本轮允许范围 | V1 migration、S04 冻结单/S04 进度/本报告、Spotless 纯格式修复和 Flyway 断言测试修复 |

旧备份目录和 Gitee 教程未读取、未引用、未暂存。真实凭据未读取、未写入命令、日志或仓库。

## 2. 实际交付文件

- `backend/src/main/resources/db/migration/V1__initial_s04_schema.sql`
- `docs/progress/S04-最终数据模型冻结单.md`
- `docs/progress/S04.md`
- `docs/progress/S04-Flyway-实现与验证报告.md`
- `backend/src/main/java/com/agenttravel/backend/BackendApplication.java`
- `backend/src/main/java/com/agenttravel/backend/health/BackendHealthIndicator.java`
- `backend/src/test/java/com/agenttravel/backend/BackendApplicationTests.java`
- `backend/src/test/java/com/agenttravel/backend/DatabaseConfigurationTests.java`
- `backend/src/test/java/com/agenttravel/backend/FlywayConfigurationTests.java`

Java 文件仅做 Spotless 纯格式/换行修复；Flyway 配置测试改为要求唯一批准的 `V1__initial_s04_schema.sql`，并静态确认本轮两条积分约束。未修改生产业务逻辑、配置、Python、前端、S00-S03、ADR 或 contracts。

## 3. 正式表与修订

正式表共 40 张：

`user_account`、`user_session`、`trip`、`trip_city`、`trip_version`、`plan_snapshot`、`change_set`、`source_record`、`source_snapshot`、`trip_version_source`、`planning_task`、`task_step`、`task_event`、`outbox_event`、`agent_result`、`agent_result_source`、`city`、`poi`、`poi_snapshot`、`poi_alias`、`knowledge_entry`、`knowledge_chunk`、`reservation_rule`、`points_account`、`points_quote`、`points_ledger`、`post`、`post_comment`、`interaction`、`moderation_case`、`report`、`ranking_entry`、`user_preference`、`preference_evidence`、`preference_summary`、`object_metadata`、`export_job`、`reminder_candidate`、`notification`、`audit_event`。

与原冻结单的实施前差异：

| 项目 | 原冻结表达 | V1 实现 |
|---|---|---|
| 多城市 | `trip.city_id` | 新增 `trip_city`，按 `sequence_no` 表达城市列表和顺序；正式表 39 -> 40 |
| 提醒来源 | 不完整的 `source_rule_id` | `source_type` + 预约规则/来源快照 FK，并用 CK 保证来源类型匹配 |
| 版本哈希 | 描述存在两种口径 | `(trip_id, content_hash)` 唯一，跨 trip 可重复 |
| 循环 FK | 关系已冻结但建表顺序未落地 | 分阶段 `CREATE TABLE`，使用 4 个 `ALTER TABLE` 延后循环外键 |

以上内容已同步写入冻结单，未修改业务语义。

## 4. 静态结果

- `CREATE TABLE`：40。
- 旧名称 `itinerary`、`itinerary_version`、`community_content`、`community_comment`、`community_interaction`、`community_report`：0 命中。
- 40 张表均声明 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`。
- 时间列使用 `DATETIME(6)`；UUID 主键、外键和关联键使用 `BINARY(16)`；哈希/令牌哈希使用 `BINARY(32)`。
- 检查约束覆盖状态、版本号、顺序、日期/时间范围、积分守恒、来源类型、提醒来源、偏好画像样本数等。
- 未发现真实密码、Token、API Key、原始 Prompt、完整供应商响应、业务测试数据或 Redis 表。
- `git diff --check`：PASS。

## 5. Maven 与应用验证

实际命令及结果：

```text
mvn -f backend/pom.xml test
PASS：6 tests，0 failures；Spotless 检查通过。

mvn -f backend/pom.xml verify
PASS：Spotless、测试、打包和 verify 均通过。
```

mock profile 的既有测试健康检查通过，但其明确禁用 DataSource 和 Flyway，不能作为真实迁移证据。local profile 的测试在无有效实例时按预期无法连接数据库；本轮没有把该结果写成成功。

## 6. MySQL 8 / Flyway 结果

本轮在隔离 Docker Compose 项目 `s04-fix-validation` 中使用固定镜像 `mysql:8.4.8` 和当前 PowerShell 进程生成的临时凭据完成验证；未使用个人常驻数据库，凭据未写入仓库、报告、日志或远端。

| 检查 | 实际结果 |
|---|---|
| 初始空库 | PASS，0 张表；数据库 `utf8mb4` / `utf8mb4_0900_ai_ci` |
| 第一次 `local` 启动 | PASS，健康 HTTP 200；`status=UP`、`databaseMode=mysql`、`db.status=UP` |
| 第一次迁移 | PASS，40 张正式表 + `flyway_schema_history`；history 为 1 条 `V1 / initial s04 schema / success=1` |
| 第二次启动 | PASS，同一数据库健康 HTTP 200；表清单相同，history 仍为 1 条 |
| 实库结构检查 | PASS，72 FK、47 unique、83 CHECK、非主键索引查询通过；`ck_quote_expiry` 与 `ck_ledger_direction` 存在 |
| `flyway validate` | PASS，Flyway API 成功 validated 1 migration |
| 清理 | PASS，`down -v --remove-orphans` 后容器、卷、网络均为 0；临时日志和 classpath 文件已删除 |

可复现入口（凭据必须在当前进程注入，不写入命令历史）：

```text
docker compose -p s04-fix-validation -f deploy/docker-compose.s03.yml up -d
$env:SPRING_PROFILES_ACTIVE='local'
$env:DB_URL='jdbc:mysql://127.0.0.1:3306/<empty_db>?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&serverTimezone=UTC'
$env:DB_USERNAME='<temporary_user>'
$env:DB_PASSWORD='<temporary_password>'
mvn -f backend/pom.xml verify
curl.exe -i http://127.0.0.1:8080/actuator/health
docker compose -p s04-fix-validation -f deploy/docker-compose.s03.yml down -v --remove-orphans
```

## 7. 风险与结论

- 本轮验证是工程师在隔离环境中的可复现证据，不等同于独立验收 Agent 的最终通过。
- 当前仍有独立复验 PENDING；不得标记 S04 Gate 通过或 S04 已完成。
- S04 不包含业务 API、认证、Worker、Redis Streams、SSE、真实第三方服务或前端功能。

结论：代码和 Flyway 验证阻断项已修复；等待独立验收 Agent 对新 Commit 复验。
