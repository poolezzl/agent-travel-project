from __future__ import annotations

import json
import sys
from pathlib import Path


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    try:
        import yaml
        from openapi_spec_validator import validate
    except ImportError as error:
        print(f"完整契约校验依赖缺失: {error}")
        return 2

    checked = 0
    for path in sorted((root / "contracts" / "openapi").glob("*.yaml")):
        with path.open(encoding="utf-8") as source:
            document = yaml.safe_load(source)
        if not isinstance(document, dict):
            raise ValueError(f"契约不是对象: {path}")
        validate(document, base_uri=path.as_uri())
        checked += 1

    for path in sorted((root / "contracts" / "examples").rglob("*.json")):
        with path.open(encoding="utf-8") as source:
            json.load(source)
        checked += 1

    print(f"完整契约校验通过: {checked} 个文件")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"完整契约校验失败: {error}")
        raise SystemExit(1)
