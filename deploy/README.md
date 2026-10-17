# Deploy

S03 的本地验收环境只包含一个固定版本的 MySQL 8 容器，不包含业务服务、
LLM、地图、天气或其他第三方服务。生产部署仍由后续阶段负责。

## S03 MySQL 空库环境

`docker-compose.s03.yml` 固定使用 `mysql:8.4.8`，使用 Docker named volume，
不挂载个人数据库目录。数据库名、临时账号和密码都必须由当前进程环境变量
注入，Compose 文件不包含真实凭据：

```powershell
$env:S03_MYSQL_ROOT_PASSWORD = "<temporary-root-password>"
$env:S03_MYSQL_PASSWORD = "<temporary-validator-password>"
$env:S03_MYSQL_DATABASE = "s03_empty_validation_local"
$env:S03_MYSQL_USER = "s03_validator"
$env:S03_MYSQL_PORT = "13306"
docker compose -f deploy\docker-compose.s03.yml up -d
docker compose -f deploy\docker-compose.s03.yml ps
docker compose -f deploy\docker-compose.s03.yml down -v --remove-orphans
```

上述命令是独立的手工 Compose 生命周期示例，执行完整脚本前应确保该手工
项目已清理。完整 D-04 验证入口会自行生成或
使用当前进程中的临时变量、启动 Compose、等待健康并在结束时清理：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\s03_mysql_verify.ps1
```

脚本会使用当前进程提供的临时变量，未提供时动态生成凭据和数据库名，启动
`local` Profile，检查 HTTP 200、`databaseMode=mysql`、`db.status=UP`、仅有
`flyway_schema_history` 且历史行数为 0，然后重复启动并再次检查。脚本失败
返回非零退出码，结束时执行：

```powershell
docker compose -f deploy\docker-compose.s03.yml down -v --remove-orphans
```

该命令删除容器和 named volume，不残留验收数据。密码不得写入仓库、日志、
命令历史或报告；不要复用个人 MySQL 服务或个人账号。

CI 入口为 `.github/workflows/backend.yml` 中的
`Verify isolated MySQL 8 empty database with local profile` 步骤。它在干净
Runner 上使用同一 Compose 文件和验证脚本，先完成 Maven `verify`，再执行
健康、表清单、Flyway 行数和重复启动检查，最后清理容器和卷。
