from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import yaml
from jsonschema import Draft7Validator, FormatChecker

AGENTS = [
    "01-orchestrator",
    "02-route-planner",
    "03-weather",
    "04-attractions",
    "05-dining-lodging",
    "06-reservation-rules",
    "07-revision",
]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    root = p.parse_args().root
    errors = []
    openapi_path = root / "contracts" / "openapi" / "agent-business.yaml"
    try:
        api = yaml.safe_load(openapi_path.read_text(encoding="utf-8"))
        if api.get("openapi") != "3.0.3":
            errors.append("OpenAPI: expected version 3.0.3")
        operation = api["paths"]["/v1/tasks/{taskId}/steps/{stepId}/agent-results"]["post"]
        request = api["components"]["schemas"]["AgentResultRequest"]
        required = set(request.get("required", []))
        expected = {
            "schema_version",
            "task_id",
            "step_id",
            "agent_id",
            "trace_id",
            "request_id",
            "generated_at",
            "status",
            "source_metadata",
        }
        if not expected.issubset(required):
            errors.append(f"OpenAPI: AgentResultRequest missing {sorted(expected - required)}")
        if "202" not in operation.get("responses", {}):
            errors.append("OpenAPI: missing 202 response")
        if "409" not in operation.get("responses", {}):
            errors.append("OpenAPI: missing 409 response")
    except Exception as exc:
        errors.append(f"OpenAPI: invalid YAML/structure: {exc}")
    for agent in AGENTS:
        for kind in ("input", "output"):
            path = root / "agents" / agent / f"{kind}.schema.json"
            schema = json.loads(path.read_text(encoding="utf-8"))
            try:
                Draft7Validator.check_schema(schema)
            except Exception as exc:
                errors.append(f"{path}: invalid Draft-07 schema: {exc}")
        schema = json.loads(
            (root / "agents" / agent / "output.schema.json").read_text(encoding="utf-8")
        )
        validator = Draft7Validator(schema, format_checker=FormatChecker())
        for status in ("ok", "partial", "degraded", "failed"):
            path = root / "contracts" / "examples" / agent / f"{status}.json"
            item = json.loads(path.read_text(encoding="utf-8"))
            problems = sorted(validator.iter_errors(item), key=lambda e: list(e.path))
            errors.extend(f"{path}: {e.message}" for e in problems)
            if item.get("status") != status:
                errors.append(f"{path}: status mismatch")
    try:
        plan_schema = json.loads(
            (root / "contracts" / "s19" / "plan.schema.json").read_text(encoding="utf-8")
        )
        Draft7Validator.check_schema(plan_schema)
        plan_validator = Draft7Validator(plan_schema, format_checker=FormatChecker())
        for name in ("plan-ok",):
            path = root / "contracts" / "examples" / "s19" / f"{name}.json"
            item = json.loads(path.read_text(encoding="utf-8"))
            errors.extend(
                f"{path}: {e.message}"
                for e in sorted(plan_validator.iter_errors(item), key=lambda e: list(e.path))
            )
        view_path = root / "contracts" / "examples" / "s19" / "plan-view.json"
        view = json.loads(view_path.read_text(encoding="utf-8"))
        errors.extend(
            f"{view_path}.plan: {e.message}"
            for e in sorted(
                plan_validator.iter_errors(view.get("plan")), key=lambda e: list(e.path)
            )
        )
        task_api = yaml.safe_load(
            (root / "contracts" / "openapi" / "s11-tasks.yaml").read_text(encoding="utf-8")
        )
        if "get" not in task_api["paths"]["/v1/tasks/{taskId}/plan"]:
            errors.append("OpenAPI: missing GET /v1/tasks/{taskId}/plan")
        plan_view_fields = set(task_api["components"]["schemas"]["PlanView"].get("required", []))
        expected_fields = {
            "task_id",
            "status",
            "trip_id",
            "version_id",
            "version_no",
            "etag",
            "schema_version",
            "generated_at",
            "plan",
            "source_metadata",
            "degraded_fields",
        }
        if not expected_fields.issubset(plan_view_fields):
            errors.append(f"OpenAPI: PlanView missing {sorted(expected_fields - plan_view_fields)}")
    except Exception as exc:
        errors.append(f"S19 plan contract: {exc}")
    try:
        weather_schema = json.loads(
            (root / "agents" / "03-weather" / "output.schema.json").read_text(encoding="utf-8")
        )
        weather_validator = Draft7Validator(weather_schema, format_checker=FormatChecker())
        advice_path = root / "contracts" / "examples" / "03-weather" / "travel-advice-ok.json"
        advice_item = json.loads(advice_path.read_text(encoding="utf-8"))
        errors.extend(
            f"{advice_path}: {e.message}"
            for e in sorted(weather_validator.iter_errors(advice_item), key=lambda e: list(e.path))
        )
        if advice_item.get("status") != "ok":
            errors.append(f"{advice_path}: status mismatch")
        advice = advice_item.get("travel_advice", {})
        if set(advice) != {"sensitive_points", "risks", "alternatives", "clothing"}:
            errors.append(f"{advice_path}: travel_advice must freeze four fields")
    except Exception as exc:
        errors.append(f"S13 travel advice fixture: {exc}")
    try:
        poi_schema = json.loads(
            (root / "contracts" / "s12" / "poi-lookup.response.schema.json").read_text(
                encoding="utf-8"
            )
        )
        Draft7Validator.check_schema(poi_schema)
        poi_validator = Draft7Validator(poi_schema, format_checker=FormatChecker())
        for name in ("poi-lookup-ok", "poi-lookup-unknown-category"):
            path = root / "contracts" / "examples" / "s12" / f"{name}.json"
            item = json.loads(path.read_text(encoding="utf-8"))
            errors.extend(
                f"{path}: {e.message}"
                for e in sorted(poi_validator.iter_errors(item), key=lambda e: list(e.path))
            )
        for name, expected_code in (
            ("problem-poi-not-found", "POI_NOT_FOUND"),
            ("problem-rate-limited", "RATE_LIMITED"),
        ):
            path = root / "contracts" / "examples" / "s12" / f"{name}.json"
            problem = json.loads(path.read_text(encoding="utf-8"))
            missing = {
                "type",
                "title",
                "status",
                "detail",
                "code",
                "traceId",
                "timestamp",
            } - set(problem)
            if missing:
                errors.append(f"{path}: problem-detail missing {sorted(missing)}")
            if problem.get("code") != expected_code:
                errors.append(f"{path}: expected code {expected_code}")
    except Exception as exc:
        errors.append(f"S12 POI lookup contract: {exc}")
    if errors:
        print("\\n".join(errors))
        return 1
    print(
        f"S02 contract validation passed: {len(AGENTS) * 2} schemas and "
        f"{len(AGENTS) * 4} fixtures; S19 plan schema and 2 fixtures"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
