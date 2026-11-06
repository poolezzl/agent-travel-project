"""校验 S11 契约、样例和关键安全边界。"""

from __future__ import annotations

import json
from pathlib import Path

try:
    import yaml
    from openapi_schema_validator import OAS30Validator
    from openapi_spec_validator import validate_spec
except ImportError:
    yaml = None
    OAS30Validator = None
    validate_spec = None


ROOT = Path(__file__).resolve().parents[1]
OPENAPI = ROOT / "contracts" / "openapi" / "s11-tasks.yaml"
EXAMPLES = ROOT / "contracts" / "examples" / "s11"


def main() -> None:
    samples = {
        "CreateTaskRequest": "create-task-request.json",
        "TaskView": "task-queued.json",
    }
    text = OPENAPI.read_text(encoding="utf-8")
    if validate_spec is not None:
        spec = yaml.safe_load(text)
        validate_spec(spec)
        schemas = spec["components"]["schemas"]
        for schema_name, filename in samples.items():
            instance = json.loads((EXAMPLES / filename).read_text(encoding="utf-8"))
            OAS30Validator(schemas[schema_name]).validate(instance)
        mode = "完整"
    else:
        required = (
            "openapi: 3.0.3",
            "/v1/tasks:",
            "/v1/tasks/{taskId}/cancel:",
            "/v1/tasks/{taskId}/events:",
            "Idempotency-Key",
            "Last-Event-ID",
        )
        if any(value not in text for value in required):
            raise AssertionError("S11 契约缺少关键路径或请求头")
        request = json.loads((EXAMPLES / samples["CreateTaskRequest"]).read_text(encoding="utf-8"))
        task = json.loads((EXAMPLES / samples["TaskView"]).read_text(encoding="utf-8"))
        if set(request) != {"task_type", "plan_count", "request"}:
            raise AssertionError("创建任务样例含客户端越权字段")
        if task.get("status") != "QUEUED" or task.get("reserved_points") != 10:
            raise AssertionError("任务响应样例不符合冻结状态")
        mode = "基础"
    prohibited = ("access_token=", "api_key", "password", "client_secret")
    if any(value in text for value in prohibited):
        raise AssertionError("S11 契约含敏感查询参数或密钥字段")
    event = (EXAMPLES / "progress-event.txt").read_text(encoding="utf-8")
    if "id: " not in event or "event: task-progress" not in event:
        raise AssertionError("进度流样例缺少事件编号或事件名")
    print(f"S11 契约{mode}校验通过：1 个 OpenAPI 文件，2 个 JSON 样例，1 个进度流样例")


if __name__ == "__main__":
    main()
