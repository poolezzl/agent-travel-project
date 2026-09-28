"""Run the Agent Service S03 quality gate."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

SERVICE_ROOT = Path(__file__).resolve().parents[1]

COMMANDS = (
    (sys.executable, "-m", "ruff", "format", "--check", "app", "scripts", "tests"),
    (sys.executable, "-m", "ruff", "check", "app", "scripts", "tests"),
    (sys.executable, "-m", "mypy", "app"),
    (sys.executable, "-m", "compileall", "-q", "app", "tests"),
    (sys.executable, "-m", "pip", "check"),
    (sys.executable, "-m", "pytest"),
)


def main() -> int:
    """Run each check in sequence and stop on the first failure."""
    for command in COMMANDS:
        printable = " ".join(command)
        print(f":: running {printable}", flush=True)
        completed = subprocess.run(command, cwd=SERVICE_ROOT, check=False)
        if completed.returncode != 0:
            print(
                f":: failed with exit code {completed.returncode}: {printable}",
                file=sys.stderr,
            )
            return completed.returncode

    print(":: Agent Service quality gate passed", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
