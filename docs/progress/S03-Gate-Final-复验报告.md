# S03 Gate Final Revalidation Report

## 1. Acceptance target and baseline

- Repository: `agent-travel-project`
- Branch: `dev-r6-gate-fix`
- HEAD before this report: `71a77cefea32fdba9b465420cdcb8b4795673a59`
- S03 engineering baseline: `bb4566515ccb62a6798c032bf0b3dad972ef0420`
- `master`: `e34f4bc535bb4180add343fe4eb07c28106de875`, unchanged
- Revalidation date: 2026-09-28 Asia/Shanghai
- No S04 migration, business table, business SQL, business API, contract, ADR, or frozen S00-S02 file was changed.

## 2. Docker and fixed image pulls

Environment:

```text
Docker Server: 29.7.2
Docker Compose: v5.3.1
Context: desktop-linux
OS: Docker Desktop Linux Engine on WSL2
docker info exit code: 0
```

Fixed image pulls:

```text
docker pull mysql:8.4.8                  exit 0, image up to date
docker pull redis:7.4.2-alpine           exit 0, image up to date
docker pull chromadb/chroma:0.6.3        exit 0, downloaded successfully
```

Chroma download completed in this final run. Digest observed:
`sha256:e0e78dc7609a599b63c99753442c7d01b1d3d369ce0e3bf3e0540536fec4fa7a`.
No image version was changed.

## 3. Local MySQL, Redis, and Chroma Compose

Command executed with temporary credentials generated in the current PowerShell process:

```powershell
docker compose -p s03-local-gate `
  -f deploy\docker-compose.s03-local.yml up -d --wait --wait-timeout 180
docker compose -p s03-local-gate `
  -f deploy\docker-compose.s03-local.yml down -v --remove-orphans
```

Observed health results:

```text
SERVICE|chroma|running|healthy
SERVICE|mysql|running|healthy
SERVICE|redis|running|healthy
CHROMA_HEARTBEAT|HTTP=200|BODY={"nanosecond heartbeat":1790582393833208112}
S03_LOCAL_DEPENDENCIES_PASS
CLEANUP_EXIT|0
```

The Compose file started only MySQL 8.4.8, Redis 7.4.2, and Chroma 0.6.3. No business service or real third-party service was started. Temporary credentials were not written to the repository, logs, or this report.

Cleanup verification showed no `s03-local-gate` container, volume, or network remained.

## 4. D-04 MySQL and Flyway final run

Command:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts\s03_mysql_verify.ps1 `
  -ProjectName s03-gate-d04-final
```

Observed results:

```text
Initial database: tables=0, charset=utf8mb4, collation=utf8mb4_0900_ai_ci
Health: HTTP 200, status=UP, databaseMode=mysql, db.status=UP
First startup tables: flyway_schema_history; flyway_rows=0
Health: HTTP 200, status=UP, databaseMode=mysql, db.status=UP
Repeat startup tables: flyway_schema_history; flyway_rows=0
Process exit code: 0
```

The script completed `docker compose down -v --remove-orphans` and removed the temporary container, volume, and network. D-04 independent Docker verification: **PASS**.

## 5. Three-stack regression

### Backend

```text
Maven: 3.9.16
Maven runtime Java: 21.0.8 via JAVA_HOME
mvn -f backend\pom.xml test: exit 0, 5 tests, 0 failures, 0 errors, 0 skipped
mvn -f backend\pom.xml verify: exit 0
mvn -f backend\pom.xml dependency:tree: exit 0
```

Resolved relevant versions: Spring Boot 3.5.16, Flyway 11.7.2, MySQL Connector/J 9.7.0.

### Agent service and contracts

Python 3.13.13 was used in an isolated venv with the locked dependencies.

```text
ruff format --check: pass
ruff check: pass
mypy: pass, 6 source files
pytest: pass, 7 tests
compileall: pass
pip check: pass
S02 contract validation: pass, 14 schemas and 28 fixtures
OpenAPI validation: pass
Agent Service quality gate: pass
```

One Starlette/AnyIO deprecation warning was emitted by a dependency; it did not affect the result.

### Frontend

```text
Node: v24.14.0
npm: 11.9.0
npm ci: exit 0, 319 packages
npm run lint: exit 0
npm run format:check: exit 0
npm run test:unit: exit 0, 2 files and 4 tests passed
npm run build:mock: exit 0
npm run build: exit 0
```

Vite emitted the existing large-chunk warning in both builds; no build failed.

## 6. CI Runner result

The only configured Git remote is:

```text
origin https://gitee.com/small-soil-ash/agent-travel-project.git
```

The repository contains GitHub workflow definitions under `.github/workflows`, but no GitHub remote or usable GitHub Actions authentication was available. No `.gitee` or Gitee Go pipeline configuration was present, and no approved clean Linux Runner was available for this task. `gh auth status` reported an invalid stored GitHub token.

No hosted CI job was triggered. Therefore these jobs remain **PENDING**, not passed:

- backend
- frontend
- agent-service
- contracts
- security
- isolated MySQL/Flyway verification

### Gitee Go availability check

The public repository page was checked at `https://gitee.com/small-soil-ash/agent-travel-project`.
The page exposed the following platform state during this revalidation:

```text
gon.gitee_go_active=false
gon.current_user=false
project_id=50431928
default_branch=master
```

This means Gitee Go was not active for the repository in the available project view, and
the current session was unauthenticated. No Gitee Go pipeline could be created or triggered
from this environment. The repository also has no official, confirmed Gitee Go pipeline file
or registered Linux Runner available to this worktree; no guessed `.gitee` configuration was
added. This is an external project-permission/platform blocker, not a CI pass or a source-code
failure.

The configured Git remote is owned by `small-soil-ash`; it is not the `wudiwaigou` profile URL
provided separately. No remote ownership or credentials were changed during this task.

Local command results are not presented as hosted CI results.

## 7. Runtime risk and cleanup

- `java -version` on system PATH reports Java 8.0.241, while Maven uses Java 21.0.8 from `JAVA_HOME`. This is a machine environment risk, not a source change.
- No credentials, token, API key, database file, Docker volume, log, `target`, `node_modules`, Python cache, or build artifact is intended for commit.
- All Docker Compose validation resources were removed with `down -v --remove-orphans`.
- `git diff --check` passed.

## 8. Final decision

- Docker/MySQL/Flyway verification: **PASS**.
- Redis/Chroma local dependency verification: **PASS**.
- Backend, Python, frontend, and contract regression: **PASS**.
- Real hosted CI Runner verification: **PENDING**, because no executable approved Runner/platform was available.
- Gitee Go availability: **PENDING**, because the repository page reported `gitee_go_active=false` and no authenticated project/Runner administration was available.
- S03 Gate: **environment blocked, pending CI revalidation**.
- S04 formal implementation: **not started**.

This report records only commands executed in this final revalidation. It does not claim hosted CI success, does not close S03 Gate, and does not authorize S04 implementation.
