"""原子激活已校验的候选模型。"""

from __future__ import annotations

import argparse
import json
import os
import tempfile
from pathlib import Path

from linkverse_recommendation.core.model_bundle import validate_bundle


def activate(model_root: Path, bundle: Path) -> None:
    """校验成功后在同一目录用 ``os.replace`` 原子替换活动指针。"""

    root = model_root.resolve()
    resolved_bundle = bundle.resolve()
    if root not in resolved_bundle.parents:
        raise ValueError("候选模型必须位于模型根目录内")
    manifest = validate_bundle(resolved_bundle)
    root.mkdir(parents=True, exist_ok=True)
    payload = {
        "model_version": manifest["model_version"],
        "path": resolved_bundle.relative_to(root).as_posix(),
    }
    descriptor, temporary_name = tempfile.mkstemp(prefix="active-model-", suffix=".json", dir=root)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(payload, stream, ensure_ascii=False, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_name, root / "active-model.json")
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)


def main() -> None:
    parser = argparse.ArgumentParser(description="原子激活推荐模型")
    parser.add_argument("--model-root", type=Path, required=True)
    parser.add_argument("--bundle", type=Path, required=True)
    arguments = parser.parse_args()
    activate(arguments.model_root, arguments.bundle)


if __name__ == "__main__":
    main()
