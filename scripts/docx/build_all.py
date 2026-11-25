# -*- coding: utf-8 -*-
"""生成三份 Word 交付文档。

用法（在仓库任意位置）：
    python scripts/docx/build_all.py
输出：
    docs/word/多智能体协作文旅规划平台-需求分析文档.docx
    docs/word/多智能体协作文旅规划平台-总体架构设计文档.docx
    docs/word/多智能体协作文旅规划平台-项目研究计划与可行性论证.docx
"""

from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
OUT_DIR = REPO / "docs" / "word"

sys.path.insert(0, str(HERE))

import content_architecture  # noqa: E402
import content_plan  # noqa: E402
import content_requirements  # noqa: E402
import diagrams  # noqa: E402


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    assets = OUT_DIR / "assets"
    diagrams.render_all(assets)
    builders = [
        ("需求分析文档", content_requirements.build),
        ("总体架构设计文档", content_architecture.build),
        ("项目研究计划与可行性论证", content_plan.build),
    ]
    locked = []
    for name, fn in builders:
        try:
            p = fn(OUT_DIR)
            print(f"OK  {p}  ({p.stat().st_size:,} bytes)")
        except PermissionError:
            locked.append(name)
            print(f"SKIP {name}：文件被占用（请关闭 Word 中打开的该文档后重跑本脚本）")
    if locked:
        raise SystemExit(f"有 {len(locked)} 份文档因被占用未生成：{'、'.join(locked)}")


if __name__ == "__main__":
    main()
