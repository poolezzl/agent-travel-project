# Stable error codes

| Code | Meaning | Retry |
|---|---|---|
| `INVALID_INPUT` | Request failed schema or semantic validation | no |
| `CONTEXT_NOT_FOUND` | Java task/step context is unavailable | no |
| `AGENT_TIMEOUT` | Agent exceeded deadline | yes, with new request id |
| `AGENT_UNAVAILABLE` | Agent runtime or provider unavailable | yes, bounded |
| `SOURCE_UNAVAILABLE` | Required source could not be read | no; return degraded when safe |
| `SOURCE_STALE` | Source exists but is outside freshness window | no; mark stale |
| `CONTRACT_VIOLATION` | Agent response failed schema validation | no |
| `DUPLICATE_REQUEST` | Same request id already has a terminal result | no; return stored result |
| `CANCELLED` | User or system cancelled the step | no |
| `REVISION_BASE_MISMATCH` | Revision base version is not current/available | no |

Codes are stable identifiers only; user-facing `message` remains safe and actionable. Secrets, provider payloads and personal data never appear in errors.
