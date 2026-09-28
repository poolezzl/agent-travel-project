# S03 Gate Docker and CI Revalidation Report

## 1. Scope and baseline

- Repository: `agent-travel-project`
- Branch: `dev-r6-gate-fix`
- Base branch: `origin/dev-r6`
- HEAD before this report: `bf4c2f4044c280099e6ea2cf1af72785563e1e0d`
- S03 acceptance commit: `bb4566515ccb62a6798c032bf0b3dad972ef0420`
- Master: `e34f4bc535bb4180add343fe4eb07c28106de875` (unchanged)
- Revalidation date: 2026-09-28 Asia/Shanghai
- No S04 migration, business table, business API, contract, ADR, or frozen S00-S02 file was changed.

## 2. Docker Engine

Commands:

```text
docker info
docker compose version
```

Results:

```text
Docker Server: 29.7.2
Context: desktop-linux
OS: Docker Desktop / Linux Engine / WSL2
Docker Compose: v5.3.1
docker info exit code: 0
```

## 3. D-04 MySQL and Flyway verification

Command executed:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File scripts\s03_mysql_verify.ps1 `
  -ProjectName s03-gate-d04
```

The script generated temporary credentials in the current process and used the fixed image `mysql:8.4.8`. No credential was written to the repository, command output, or report.

Observed results:

```text
Initial database: tables=0, charset=utf8mb4, collation=utf8mb4_0900_ai_ci
Health: HTTP 200, status=UP, databaseMode=mysql, db.status=UP
First startup tables: flyway_schema_history; flyway_rows=0
Health: HTTP 200, status=UP, databaseMode=mysql, db.status=UP
Repeat startup tables: flyway_schema_history; flyway_rows=0
S03 D-04 checks completed successfully for this isolated run
Process exit code: 0
```

D-04 result: **PASS for this independent local Docker run**.

Verified:

- MySQL 8.4.8 started from the S03 Compose definition.
- Initial database contained zero tables.
- Database charset was `utf8mb4` and collation was `utf8mb4_0900_ai_ci`.
- Backend `local` profile started successfully.
- `/actuator/health` returned HTTP 200 with `status=UP`, `databaseMode=mysql`, and `db.status=UP`.
- The only table after each startup was `flyway_schema_history`.
- Flyway history row count was zero after each startup.
- The same empty database passed a second startup.
- The script stopped the backend and completed `docker compose down -v --remove-orphans`.

## 4. Local dependency Compose verification

Target definition:

```text
deploy/docker-compose.s03-local.yml
```

The file defines only MySQL 8.4.8, Redis 7.4.2, and Chroma 0.6.3. Temporary credentials were injected through the current PowerShell process.

Attempted command:

```powershell
docker compose -p s03-local-gate `
  -f deploy\docker-compose.s03-local.yml up -d
```

Redis 7.4.2 was pulled successfully. Chroma 0.6.3 stalled while downloading at approximately 61.77 MB of 149 MB for more than five minutes. No service health result was produced. The pull was stopped, then cleanup was executed:

```powershell
docker compose -p s03-local-gate `
  -f deploy\docker-compose.s03-local.yml down -v --remove-orphans
```

Cleanup exit code was 0. No `s03-local-gate` container, volume, or network remained. This dependency bundle result is **PENDING**, blocked by the Docker registry/network transfer for Chroma; it is not reported as passed.

Compose syntax was independently checked with temporary process credentials:

```text
docker compose -f deploy/docker-compose.s03.yml config --quiet: 0
docker compose -f deploy/docker-compose.s03-local.yml config --quiet: 0
```

## 5. Java and backend regression

Environment:

```text
java -version: 1.8.0_241 (system PATH)
mvn -version: Maven 3.9.16, Java 21.0.8 via JAVA_HOME
JAVA_HOME: D:\Develope\Java工具\jdk
```

The Maven-selected JDK 21 is the runtime used by the backend build and tests. The system PATH Java 8 mismatch remains a risk and should be normalized separately.

Executed:

```text
mvn -f backend\pom.xml test
```

Result: exit code 0; 5 tests, 0 failures, 0 errors, 0 skipped.

## 6. CI Runner status

The repository remote is Gitee:

```text
origin https://gitee.com/small-soil-ash/agent-travel-project.git
```

The repository contains GitHub Actions workflow definitions under `.github/workflows`, but no GitHub remote or usable authenticated GitHub Actions trigger was available in this environment. No hosted GitHub or Gitee Go Runner execution was performed. Static workflow configuration is not treated as CI execution evidence.

CI result: **PENDING**.

Required follow-up evidence is a real clean Runner execution for backend, frontend, agent-service, contracts, security, and isolated MySQL/Flyway verification, with non-zero failures preserved.

## 7. Cleanup evidence

After both Docker checks:

```text
docker ps -a: no s03-gate-d04 or s03-local-gate resources
docker volume ls: no S03 validation volume
docker network ls: no S03 validation network
```

No project source, database data directory, credentials, logs, or build artifacts were added by the verification.

## 8. Final status and recommendation

- D-04 isolated MySQL/Flyway/local-profile verification: **PASS** for the independent local Docker run.
- S03 local MySQL/Redis/Chroma dependency bundle: **PENDING** because Chroma image transfer stalled before health checks.
- Real hosted CI Runner: **PENDING** because no usable remote Runner execution was available.
- S03 Gate: **not closed**.
- S04 formal implementation: **not started and not authorized by this report**.

This report records only commands executed in this revalidation. It does not claim hosted CI success and does not mark the complete S03 Gate as passed.
