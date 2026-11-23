"""校验行程导出与提醒接口及样例。"""

import json
from pathlib import Path

import yaml
from openapi_schema_validator import OAS31Validator
from openapi_spec_validator import validate


root = Path(__file__).resolve().parents[1] / "contracts"
contract = root / "openapi" / "s26-travel-tools.yaml"
spec = yaml.safe_load(contract.read_text(encoding="utf-8"))
validate(spec, base_uri=contract.as_uri())
for schema, sample in {
    "CreateExport": "create-export",
    "Export": "export-pending",
    "Notification": "notification",
}.items():
    instance = json.loads((root / "examples" / "s26" / f"{sample}.json").read_text(encoding="utf-8"))
    OAS31Validator(spec["components"]["schemas"][schema]).validate(instance)

assert spec["security"] == [{"bearer": []}]
assert all("security" not in item for methods in spec["paths"].values()
           for verb, item in methods.items() if verb != "parameters")
validator = OAS31Validator(spec["components"]["schemas"]["CreateExport"])
assert not validator.is_valid({"format": "PDF", "owner_id": "other"})
assert not validator.is_valid({"format": "PNG"})
print("导出与提醒接口及三个样例校验通过")
