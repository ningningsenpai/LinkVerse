"""不可变模型包生成与严格一致性校验。"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path


REQUIRED_FILES = {
    "two_tower.pt",
    "user-vocabulary.json",
    "object-vocabulary.json",
    "feature-schema.json",
    "scaler.json",
    "index.faiss",
    "object-ids.json",
    "user-embeddings.npy",
    "popular.json",
    "hybrid-candidates.json",
    "lambda-rank.txt",
    "metrics.json",
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_manifest(
    bundle: Path,
    model_version: str,
    data_hash: str,
    dependencies_lock_hash: str,
) -> dict[str, object]:
    """对模型包每个组成文件写入哈希，manifest 自身不参与递归哈希。"""

    missing = sorted(name for name in REQUIRED_FILES if not (bundle / name).is_file())
    if missing:
        raise ValueError(f"模型包缺少文件：{missing}")
    feature_schema_hash = sha256_file(bundle / "feature-schema.json")
    manifest = {
        "schema_version": 1,
        "domain": "trade",
        "model_version": model_version,
        "oov_index": 0,
        "data_hash": data_hash,
        "feature_schema_hash": feature_schema_hash,
        "dependencies_lock_hash": dependencies_lock_hash,
        "files": {name: sha256_file(bundle / name) for name in sorted(REQUIRED_FILES)},
    }
    (bundle / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8"
    )
    return manifest


def validate_bundle(bundle: Path) -> dict[str, object]:
    """校验 Schema、词表、权重、文件哈希与 Faiss ID 数量，不一致时拒绝加载。"""

    manifest_path = bundle / "manifest.json"
    if not manifest_path.is_file():
        raise ValueError("模型包缺少 manifest.json")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schema_version") != 1 or manifest.get("domain") != "trade":
        raise ValueError("模型包 Schema 或领域不匹配")
    if manifest.get("oov_index") != 0:
        raise ValueError("模型包 OOV 索引必须为 0")
    files = manifest.get("files")
    if not isinstance(files, dict) or set(files) != REQUIRED_FILES:
        raise ValueError("模型包文件清单不完整")
    for name, expected_hash in files.items():
        path = bundle / name
        if not path.is_file() or sha256_file(path) != expected_hash:
            raise ValueError(f"模型包文件哈希不匹配：{name}")
    if sha256_file(bundle / "feature-schema.json") != manifest.get("feature_schema_hash"):
        raise ValueError("特征 Schema 哈希不匹配")
    _validate_vocabulary(bundle / "user-vocabulary.json")
    object_vocabulary = _validate_vocabulary(bundle / "object-vocabulary.json")
    object_ids = json.loads((bundle / "object-ids.json").read_text(encoding="utf-8"))
    expected_object_ids = [
        object_id for object_id, _ in sorted(object_vocabulary.items(), key=lambda pair: pair[1])
    ]
    if object_ids != expected_object_ids:
        raise ValueError("对象词表与索引 ID 不一致")
    try:
        import faiss

        index = faiss.read_index(str(bundle / "index.faiss"))
        if index.ntotal != len(object_ids):
            raise ValueError("Faiss 索引数量与对象 ID 不一致")
    except ImportError as exception:
        raise RuntimeError("校验模型包需要 faiss-cpu") from exception
    return manifest


def _validate_vocabulary(path: Path) -> dict[str, int]:
    vocabulary = json.loads(path.read_text(encoding="utf-8"))
    indexes = sorted(vocabulary.values())
    if indexes != list(range(1, len(indexes) + 1)):
        raise ValueError(f"词表与 OOV 约定冲突：{path.name}")
    return vocabulary
