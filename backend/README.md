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

## Build and verification

Run from the repository root:

```powershell
mvn -f backend\pom.xml test
mvn -f backend\pom.xml verify
git diff --check
```

The test suite loads the Spring context with `mock`, checks the Actuator
health endpoint, verifies that the mock profile does not create a DataSource,
and verifies that the Flyway migration directory has no versioned scripts.

The backend-only CI entry point is
`.github/workflows/backend.yml`. It runs Java 21, Maven `verify`, a dependency
tree check, and a committed-secret scan scoped to `backend/`.

## S03 boundary

Created in this step: a minimal Spring Boot application, configuration
profiles, MySQL/Flyway infrastructure configuration, Actuator health, tests,
and backend CI validation. Not created: business tables, Flyway migrations,
business APIs, authentication, task orchestration, Redis Streams, SSE,
agent-service, frontend, or real third-party calls. Those remain pending for
the responsible later step or collaborator.
