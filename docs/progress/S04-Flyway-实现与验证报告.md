# S04 Flyway 实现与验证报告

## 1. 交付上下文

| 项目 | 结果 |
|---|---|
| 仓库 | `small-soil-ash/agent-travel-project` |
| 分支 | `dev-r5` |
| 实现前 HEAD/父提交 | `218a7f4bc66fbccc98693e5466cb77374d0e4b33` |
| 远端跟踪 | `origin/dev-r5` |
| 实现前工作区 | 有用户已有未跟踪评审报告、冻结单、旧备份目录、Gitee 教程和 V1 草稿；无已跟踪修改 |
| 本轮允许范围 | V1 migration、S04 冻结单一致性记录、S04 进度和本报告 |

旧备份目录和 Gitee 教程未读取、未引用、未暂存。真实凭据未读取、未写入命令、日志或仓库。

## 2. 实际交付文件

- `backend/src/main/resources/db/migration/V1__initial_s04_schema.sql`
- `docs/progress/S04-最终数据模型冻结单.md`
- `docs/progress/S04.md`
- `docs/progress/S04-Flyway-实现与验证报告.md`

未修改 Java、配置、测试、Python、前端、S00-S03、ADR 或 contracts。

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
FAIL：Spotless 在既有 Java 文件上报告 CRLF/格式差异；本轮未修改 Java。

mvn -f backend/pom.xml '-Dspotless.check.skip=true' test
FAIL：4 项通过，既有 FlywayConfigurationTests 失败；该测试断言 classpath 中不存在 V*.sql，而本任务要求正式新增 V1。

mvn -f backend/pom.xml '-Dspotless.check.skip=true' '-DskipTests' package
PASS：Spring Boot jar 构建成功。

mvn -f backend/pom.xml verify
FAIL：Spotless 在既有 Java 文件上报告 CRLF/格式差异。

mvn -f backend/pom.xml '-Dspotless.check.skip=true' '-DskipTests' verify
PASS：编译、打包和 verify 完成。
```

mock profile 的既有测试健康检查通过，但其明确禁用 DataSource 和 Flyway，不能作为真实迁移证据。local profile 的测试在无有效实例时按预期无法连接数据库；本轮没有把该结果写成成功。

## 6. MySQL 8 / Flyway 结果

以下均为 `PENDING`，原因是本机 Docker daemon 不可用，且未使用或写入任何个人数据库凭据：

1. MySQL 8 空库首次 Flyway migration。
2. `flyway_schema_history` 首次成功 V1 记录。
3. 同一空库重复启动/重复迁移。
4. `flyway validate`。
5. 实库表、索引、外键、检查约束和只有正式表的核对。
6. 容器、数据库和卷销毁后的残留核对。

可复现命令（需由独立验收环境提供 MySQL 8 和临时凭据，不把凭据写入仓库或命令历史）：

```text
docker compose -f deploy/docker-compose.s03.yml up -d
$env:SPRING_PROFILES_ACTIVE='local'
$env:DB_URL='jdbc:mysql://127.0.0.1:3306/<empty_db>?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&serverTimezone=UTC'
$env:DB_USERNAME='<temporary_user>'
$env:DB_PASSWORD='<temporary_password>'
mvn -f backend/pom.xml verify
curl.exe -i http://127.0.0.1:8080/actuator/health
docker compose -f deploy/docker-compose.s03.yml down -v
```

实际项目当前没有 `deploy/docker-compose.s03.yml` 或本地 S03 验证脚本，因此不能声称这些入口已存在；应由环境验收任务先提供或按其允许范围补齐。该事实也是当前独立验收阻断项。

## 7. 风险与结论

- 真实 MySQL 8 空库、Flyway 首次/重复启动和 validate 尚未完成。
- 既有测试仍保留 S03 阶段“迁移目录为空”的断言，阻止标准 `mvn test` 通过；按本任务禁止修改测试文件，故未绕过或篡改。
- 当前未执行数据库验证，也未生成业务数据；未执行容器清理是因为未启动容器。
- S04 不包含业务 API、认证、Worker、Redis Streams、SSE、真实第三方服务或前端功能。

结论：`S04 migration 实现未完成，存在阻断项`。不得标记 S04 Gate 通过，不得标记 S04 已完成；待独立环境完成 MySQL 8 空库和重复启动复验后重新验收。
