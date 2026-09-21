from __future__ import annotations
import argparse, json, sys
from pathlib import Path
from jsonschema import Draft7Validator, FormatChecker
import yaml

AGENTS = ["01-orchestrator", "02-route-planner", "03-weather", "04-attractions", "05-dining-lodging", "06-reservation-rules", "07-revision"]

def main():
    p = argparse.ArgumentParser(); p.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1]); root = p.parse_args().root
    errors = []
    openapi_path = root / "contracts" / "openapi" / "agent-business.yaml"
    try:
        api = yaml.safe_load(openapi_path.read_text(encoding="utf-8"))
        if api.get("openapi") != "3.0.3": errors.append("OpenAPI: expected version 3.0.3")
        operation = api["paths"]["/v1/tasks/{taskId}/steps/{stepId}/agent-results"]["post"]
        request = api["components"]["schemas"]["AgentResultRequest"]
        required = set(request.get("required", []))
        expected = {"schema_version", "task_id", "step_id", "agent_id", "trace_id", "request_id", "generated_at", "status", "source_metadata"}
        if not expected.issubset(required): errors.append(f"OpenAPI: AgentResultRequest missing {sorted(expected - required)}")
        if "202" not in operation.get("responses", {}): errors.append("OpenAPI: missing 202 response")
        if "409" not in operation.get("responses", {}): errors.append("OpenAPI: missing 409 response")
    except Exception as exc:
        errors.append(f"OpenAPI: invalid YAML/structure: {exc}")
    for agent in AGENTS:
        for kind in ("input", "output"):
            path = root / "agents" / agent / f"{kind}.schema.json"; schema = json.loads(path.read_text(encoding="utf-8"))
            try: Draft7Validator.check_schema(schema)
            except Exception as exc: errors.append(f"{path}: invalid Draft-07 schema: {exc}")
        schema = json.loads((root / "agents" / agent / "output.schema.json").read_text(encoding="utf-8")); validator = Draft7Validator(schema, format_checker=FormatChecker())
        for status in ("ok", "partial", "degraded", "failed"):
            path = root / "contracts" / "examples" / agent / f"{status}.json"; item = json.loads(path.read_text(encoding="utf-8")); problems = sorted(validator.iter_errors(item), key=lambda e: list(e.path))
            errors.extend(f"{path}: {e.message}" for e in problems)
            if item.get("status") != status: errors.append(f"{path}: status mismatch")
    if errors:
        print("\\n".join(errors)); return 1
    print(f"S02 contract validation passed: {len(AGENTS)*2} schemas and {len(AGENTS)*4} fixtures")
    return 0

if __name__ == "__main__": sys.exit(main())
