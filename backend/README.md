# Backend

S03 backend skeleton for the Java business service. The project uses Java 21,
Spring Boot 3.5.16, Maven 3.9.6 or newer, MySQL 8, and Flyway 11.x through
the Spring Boot dependency management.

This module is only an infrastructure boundary. It contains no business API,
business repository, business SQL, business table, agent orchestration, or
real third-party integration. The Java service remains the future sole writer
of business data in MySQL.

## Prerequisites

Verify the toolchain before running Maven:

```powershell
mvn -version
```

The output must show Java 21. The repository does not currently include a
Maven Wrapper, so the commands below use the installed Maven executable.

## Profiles and environment

The default profile is `local`. It expects a reachable MySQL 8 database and
uses only environment-provided connection settings. The following variables
are supported:

| Variable | Default | Purpose |
|---|---|---|
| `MYSQL_HOST` | `127.0.0.1` | MySQL host |
| `MYSQL_PORT` | `3306` | MySQL port |
| `MYSQL_DATABASE` | `trip_planner` | Existing database name |
| `MYSQL_USERNAME` | `trip_planner` | Database username |
| `MYSQL_PASSWORD` | empty | Database password; never commit it |
| `DB_URL` | derived from the MySQL variables | Full JDBC URL override |
| `DB_USERNAME` | derived from `MYSQL_USERNAME` | JDBC username override |
| `DB_PASSWORD` | derived from `MYSQL_PASSWORD` | JDBC password override |
| `DB_CONNECTION_TIMEOUT_MS` | `3000` | Connection timeout |
| `FLYWAY_ENABLED` | `true` | Enable Flyway in the local profile |

The JDBC URL uses UTC, Unicode, and the MySQL `utf8mb4` connection
convention. Database schema creation is outside S03; create the empty
database and account through the local MySQL installation or deployment
procedure. This project does not create accounts or databases.

The `mock` profile disables DataSource and Flyway auto-configuration. It does
not require MySQL, credentials, LLM credentials, map credentials, weather
credentials, or any other external account:

```powershell
$env:SPRING_PROFILES_ACTIVE = "mock"
mvn -f backend\pom.xml spring-boot:run
```

Then check the infrastructure health endpoint:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health | ConvertTo-Json -Depth 5
```

The normal `local` profile fails during startup with a clear database/Flyway
connection error when MySQL is unavailable. The `mock` profile is the
deterministic no-database startup path for development and CI.

## Flyway

Flyway is configured with `classpath:db/migration`, validation enabled,
`baseline-on-migrate=false`, and `clean-disabled=true`. The migration
directory is intentionally empty in S03. No business migration or table is
created. The empty-directory check is covered by the backend tests; a real
empty-database validation requires a running MySQL 8 instance:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
$env:MYSQL_DATABASE = "trip_planner_empty"
mvn -f backend\pom.xml spring-boot:run
```

Successful startup against that empty database is the Flyway empty-database
check. Stop the process and start it again to verify repeat startup behavior.
For a reproducible local check, set the connection variables in the current
PowerShell process without committing the password, then run:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
$env:MYSQL_HOST = "127.0.0.1"
$env:MYSQL_PORT = "3306"
$env:MYSQL_DATABASE = "s03_empty_validation_<timestamp>"
$env:MYSQL_USERNAME = "root"
$env:MYSQL_PASSWORD = "<local-only-password>"
mvn -f backend\pom.xml spring-boot:run '-Dspring-boot.run.arguments=--server.port=18081'
Invoke-WebRequest -UseBasicParsing http://localhost:18081/actuator/health
```

The expected empty-database result is an HTTP 200 health response with
`databaseMode=mysql` and `db.status=UP`. With no versioned migration scripts,
Flyway creates only `flyway_schema_history`; it contains zero migration rows
and no business table is created. Stop the process, run the same command again,
and repeat the health/database checks to verify repeat startup. Do not put a
real password in this README, shell history, logs, or a commit.

## Reproducible S03 environment

The S03-only Docker Compose file is
`deploy/docker-compose.s03.yml`. It uses the fixed `mysql:8.4.8` image, a
temporary database and validator account, and a Docker named volume rather than
any personal local database directory. Credentials are required through the
current process environment and are not stored in the Compose file:

```powershell
$env:S03_MYSQL_ROOT_PASSWORD = "<temporary-root-password>"
$env:S03_MYSQL_PASSWORD = "<temporary-validator-password>"
$env:S03_MYSQL_DATABASE = "s03_empty_validation_local"
$env:S03_MYSQL_USER = "s03_validator"
$env:S03_MYSQL_PORT = "13306"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\s03_mysql_verify.ps1
```

The verification script waits for the MySQL health check, runs the backend with
the `local` profile, checks `/actuator/health`, verifies that the only table is
`flyway_schema_history` with zero rows, repeats the startup and checks the same
conditions again. It exits non-zero on any failed assertion and always attempts
to stop the backend and run `docker compose down -v --remove-orphans`.

The backend workflow runs Maven `verify` and then this same isolated Compose
verification on a clean runner. The CI entry point is
`.github/workflows/backend.yml`; CI supplies temporary runtime variables and
does not use the `mock` profile for D-04. The current status remains
`代码完成，待独立环境复验`; neither D-04 nor the full S03 Gate is marked as
passed here.

## Build and verification

Run from the repository root:

```powershell
mvn -f backend\pom.xml spotless:check
mvn -f backend\pom.xml test
mvn -f backend\pom.xml verify
git diff --check
```

The `spotless:check` goal is also bound to Maven `validate`, so `test` and
`verify` enforce the same whitespace/formatting gate. The test suite loads the Spring context with `mock`, checks the Actuator
health endpoint, verifies that the mock profile does not create a DataSource,
and verifies that the Flyway migration directory has no versioned scripts.

The backend-only CI entry point is
`.github/workflows/backend.yml`. It runs Java 21, Maven `verify`, a dependency
tree check, isolated MySQL/Flyway empty-database verification, and a
committed-secret scan scoped to the S03 validation assets.

## S03 boundary

Created in this step: a minimal Spring Boot application, configuration
profiles, MySQL/Flyway infrastructure configuration, Actuator health, tests,
and backend CI validation. Not created: business tables, Flyway migrations,
business APIs, authentication, task orchestration, Redis Streams, SSE,
agent-service, frontend, or real third-party calls. Those remain pending for
the responsible later step or collaborator.
