"""原子激活已校验的候选模型。"""

from __future__ import annotations

import argparse
import json
import os
import tempfile
from pathlib import Path

from linkverse_recommendation.core.model_bundle import validate_bundle, sha256_file


def activate(model_root: Path, bundle: Path, purpose="ENGINEERING", evidence: Path | None = None) -> None:
    """校验成功后在同一目录用 ``os.replace`` 原子替换活动指针。"""

    root = model_root.resolve()
    resolved_bundle = bundle.resolve()
    if root not in resolved_bundle.parents:
        raise ValueError("候选模型必须位于模型根目录内")
    manifest = validate_bundle(resolved_bundle)
    if purpose not in {"ENGINEERING", "REGRESSION_CANDIDATE", "NATURAL_CANDIDATE"}:
        raise ValueError("模型激活用途无效")
    if purpose != "ENGINEERING":
        if evidence is None:
            raise ValueError("候选模型激活需要质量与运行门禁证据")
        gate = json.loads(evidence.read_text(encoding="utf-8"))
        required = {"offline_quality", "data_isolation", "tests", "http_validity", "latency", "rollback"}
        if gate.get("status") != "PASS" or gate.get("manifest_sha256") != sha256_file(resolved_bundle / "manifest.json") or any(gate.get("checks", {}).get(key) is not True for key in required):
            raise ValueError("模型门禁未通过或证据不属于当前模型包")
        if purpose == "NATURAL_CANDIDATE" and (manifest.get("provenance", {}).get("traffic_origin") != "NATURAL" or gate.get("confirmatory") is not True):
            raise ValueError("自然流量候选缺少独立确认性证据")
    if manifest["schema_version"] == 2:
        from linkverse_recommendation.serving.registry import LoadedTradeModel
        LoadedTradeModel(resolved_bundle)
    root.mkdir(parents=True, exist_ok=True)
    payload = {
        "model_version": manifest["model_version"],
        "path": resolved_bundle.relative_to(root).as_posix(),
        "purpose": purpose,
        "evidence_sha256": sha256_file(evidence) if evidence else None,
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
    parser.add_argument("--purpose", choices=("ENGINEERING", "REGRESSION_CANDIDATE", "NATURAL_CANDIDATE"), default="ENGINEERING")
    parser.add_argument("--evidence", type=Path)
    arguments = parser.parse_args()
    activate(arguments.model_root, arguments.bundle, arguments.purpose, arguments.evidence)


if __name__ == "__main__":
    main()
