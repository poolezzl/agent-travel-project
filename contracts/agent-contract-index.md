# Agent Contract Index (S02)

Contract version: `agent-contract-v1`. Java/Spring Boot is the system of record and the only writer of business data. Python/FastAPI/LangGraph receives a request through the REST contract and returns a validated Agent result; it does not write MySQL directly.

## Runtime rules

- `schema_version`, `task_id`, `trace_id`, `request_id`, and `generated_at` are injected by the gateway. Agents must not invent or overwrite them.
- Every result uses `status`: `ok`, `partial`, `degraded`, or `failed`.
- `partial` and `degraded` require non-empty `message` and `degraded_fields`; `failed` requires stable `error_code` and `message` and must not include fabricated business facts.
- Facts require `source_metadata` (source type, source record id, retrieval time, freshness and estimate flags). Missing facts are represented as empty/unknown fields, never guessed.
- Java owns task/step/version persistence. Python receives immutable context and returns a result plus source references. Retries use `request_id`; duplicate requests must be idempotent.

## Agent map

| Agent | Input | Output | Typical degradation |
|---|---|---|---|
| orchestrator | `agents/01-orchestrator/input.schema.json` | `agents/01-orchestrator/output.schema.json` | `ORCHESTRATION_PARTIAL` |
| route-planner | `agents/02-route-planner/input.schema.json` | `agents/02-route-planner/output.schema.json` | `ROUTE_ESTIMATED` |
| weather | `agents/03-weather/input.schema.json` | `agents/03-weather/output.schema.json` | `WEATHER_UNAVAILABLE` |
| attractions | `agents/04-attractions/input.schema.json` | `agents/04-attractions/output.schema.json` | `KNOWLEDGE_MISS` |
| dining-lodging | `agents/05-dining-lodging/input.schema.json` | `agents/05-dining-lodging/output.schema.json` | `POI_EMPTY` |
| reservation-rules | `agents/06-reservation-rules/input.schema.json` | `agents/06-reservation-rules/output.schema.json` | `POLICY_STALE` |
| revision | `agents/07-revision/input.schema.json` | `agents/07-revision/output.schema.json` | `REVISION_AMBIGUOUS` |

The OpenAPI request/response envelope is in `contracts/openapi/agent-business.yaml`; status fixtures are under `contracts/examples/`.

## S11 task runtime

- External task, cancellation and progress contracts are in `contracts/openapi/s11-tasks.yaml`.
- The task create call uses the authenticated owner only; the client cannot supply identity, price, reserved points or task state.
- Java creates the task, quote, reserve ledger and outbox in one database transaction. Redis only transports the queued command.
- SSE event identifiers are persisted per-task `sequence_no` values. Reconnects use `Last-Event-ID` or `after_sequence_no`.
