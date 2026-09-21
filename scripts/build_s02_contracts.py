"""Build and validate the S02 contract fixtures from the checked-in schemas."""
from __future__ import annotations
import argparse, json
from pathlib import Path

AGENTS = [
    "01-orchestrator", "02-route-planner", "03-weather", "04-attractions",
    "05-dining-lodging", "06-reservation-rules", "07-revision",
]
PAYLOAD_REQUIRED = {
    "01-orchestrator": ["plan"], "02-route-planner": ["route"],
    "03-weather": ["daily", "travel_advice"], "04-attractions": ["cards"],
    "05-dining-lodging": ["meals", "lodging"],
    "06-reservation-rules": ["reservations", "timeline"], "07-revision": ["intent"],
}
REPLACEMENTS = {
    "姝ヨ": "步行", "鍏氦": "公交", "鍦伴搧": "地铁", "鎵撹溅": "打车", "娣峰悎": "混合",
}

def replace_text(value):
    if isinstance(value, dict): return {k: replace_text(v) for k, v in value.items()}
    if isinstance(value, list): return [replace_text(v) for v in value]
    if isinstance(value, str):
        for old, new in REPLACEMENTS.items(): value = value.replace(old, new)
    return value

def harden_schema(data, agent, kind):
    props = data.setdefault("properties", {})
    if kind == "input" and agent == "02-route-planner":
        point = {"type": "object", "additionalProperties": False, "required": ["poi_id", "name", "city_id", "latitude", "longitude", "coordinate_system"], "properties": {
            "poi_id": {"type": "string", "minLength": 1}, "name": {"type": "string", "minLength": 1}, "city_id": {"type": "string", "minLength": 1},
            "latitude": {"type": "number", "minimum": -90, "maximum": 90}, "longitude": {"type": "number", "minimum": -180, "maximum": 180}, "coordinate_system": {"type": "string", "enum": ["GCJ02"]}}}
        props["points"] = {"type": "array", "minItems": 1, "items": point}; props["origin"] = point; props["destination"] = point
    if kind == "output" and agent == "02-route-planner":
        props["route"] = {"type": "object", "additionalProperties": False, "required": ["ordered_points", "segments", "is_estimated"], "properties": {
            "ordered_points": {"type": "array", "minItems": 1, "items": {"type": "string", "minLength": 1}}, "segments": {"type": "array"}, "is_estimated": {"type": "boolean"}}}
    if kind == "output" and agent == "03-weather":
        props["daily"] = {"type": "array", "items": {"type": "object", "additionalProperties": False, "required": ["date", "temp_min", "temp_max", "precip_prob", "condition", "data_type", "source_record_id", "retrieved_at", "valid_until"], "properties": {"date": {"type": "string", "pattern": r"^\d{4}-\d{2}-\d{2}$"}, "temp_min": {"type": "number"}, "temp_max": {"type": "number"}, "precip_prob": {"type": ["number", "null"], "minimum": 0, "maximum": 1}, "condition": {"type": "string"}, "data_type": {"type": "string", "enum": ["forecast", "climate_statistics"]}, "source_record_id": {"type": "string", "minLength": 1}, "retrieved_at": {"type": "string", "format": "date-time"}, "valid_until": {"type": ["string", "null"], "format": "date-time"}}}}
    if kind == "output" and agent == "06-reservation-rules":
        props["reservations"] = {"type": "array", "items": {"type": "object", "additionalProperties": False, "required": ["point_id", "policy_id", "ticket_type", "effective_from", "effective_until", "timezone", "last_verified", "stale"], "properties": {"point_id": {"type": "string"}, "policy_id": {"type": "string"}, "ticket_type": {"type": "string"}, "currency": {"type": "string", "pattern": "^[A-Z]{3}$"}, "amount_minor": {"type": "integer", "minimum": 0}, "effective_from": {"type": "string", "format": "date-time"}, "effective_until": {"type": ["string", "null"], "format": "date-time"}, "timezone": {"type": "string"}, "last_verified": {"type": "string", "format": "date-time"}, "stale": {"type": "boolean"}}}}
    return data

def payload(agent):
    if agent == "01-orchestrator": return {"plan": {"days": []}}
    if agent == "02-route-planner": return {"route": {"ordered_points": ["poi-1"], "segments": [], "is_estimated": False}}
    if agent == "03-weather": return {"daily": [], "travel_advice": {}}
    if agent == "04-attractions": return {"cards": [{"point_id": "poi-1", "source": "unknown"}]}
    if agent == "05-dining-lodging": return {"meals": [], "lodging": []}
    if agent == "06-reservation-rules": return {"reservations": [], "timeline": []}
    return {"intent": "query", "base_version_id": "version-demo", "summary": "查询当前行程"}

def fixture(agent, status):
    item = {"schema_version": "agent-contract-v1", "task_id": "task-demo",
            "trace_id": "trace-demo", "request_id": f"req-{agent[:2]}-{status}",
            "generated_at": "2026-09-21T08:00:00Z", "status": status, "source_metadata": []}
    if status == "failed": item.update(error_code="AGENT_UNAVAILABLE", message="Agent service is unavailable")
    else:
        item.update(payload(agent))
        if status in ("partial", "degraded"):
            item.update(message="Some provider-backed fields are unavailable", degraded_fields=["provider_fields"])
    return item

def main():
    p = argparse.ArgumentParser(); p.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1]); root = p.parse_args().root
    for agent in AGENTS:
        for kind in ("input", "output"):
            path = root / "agents" / agent / f"{kind}.schema.json"
            data = replace_text(json.loads(path.read_text(encoding="utf-8-sig")))
            data = harden_schema(data, agent, kind)
            data["$id"] = f"agents/{agent}/{kind}.schema.json"
            if kind == "output":
                required = list(dict.fromkeys(data.get("required", []) + ["source_metadata"]))
                data["required"] = required
                data.setdefault("properties", {}).setdefault("source_metadata", {"$ref": "#/definitions/source_metadata"})
                conditions = data.setdefault("allOf", [])
                success_rule = {"if": {"properties": {"status": {"enum": ["ok", "partial", "degraded"]}}, "required": ["status"]}, "then": {"required": PAYLOAD_REQUIRED[agent]}}
                if success_rule not in conditions: conditions.append(success_rule)
            path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        for status in ("ok", "partial", "degraded", "failed"):
            path = root / "contracts" / "examples" / agent / f"{status}.json"; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(fixture(agent, status), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"built {len(AGENTS)} agents and {len(AGENTS) * 4} fixtures")

if __name__ == "__main__": main()
