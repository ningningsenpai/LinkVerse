"""模型注册表、严格加载与活动模型原子切换。"""

from __future__ import annotations

import json
import threading
from dataclasses import dataclass
from pathlib import Path

from linkverse_recommendation.core.model_bundle import validate_bundle


@dataclass(frozen=True, slots=True)
class ServingCandidate:
    """在线接口返回的统一候选。"""

    object_id: str
    score: float
    sources: tuple[str, ...]
    reason_code: str


class LoadedTradeModel:
    """加载用户向量、CPU IndexFlatIP 与热门补位列表。"""

    def __init__(self, bundle: Path) -> None:
        import faiss
        import numpy as np

        self.manifest = validate_bundle(bundle)
        self.model_version = str(self.manifest["model_version"])
        self.object_ids = json.loads((bundle / "object-ids.json").read_text(encoding="utf-8"))
        self.user_vocabulary = json.loads((bundle / "user-vocabulary.json").read_text(encoding="utf-8"))
        self.user_embeddings = np.load(bundle / "user-embeddings.npy", allow_pickle=False)
        if self.user_embeddings.shape[0] != len(self.user_vocabulary) + 1:
            raise ValueError("用户词表与用户向量数量不一致")
        self.index = faiss.read_index(str(bundle / "index.faiss"))
        if self.user_embeddings.ndim != 2 or self.user_embeddings.shape[1] != self.index.d:
            raise ValueError("用户向量维度与 Faiss 索引不一致")
        self.popular = json.loads((bundle / "popular.json").read_text(encoding="utf-8"))
        self.hybrid_candidates = json.loads(
            (bundle / "hybrid-candidates.json").read_text(encoding="utf-8")
        )
        catalog = set(self.object_ids)
        if any(str(object_id) not in catalog for object_id in self.popular):
            raise ValueError("热门补位列表包含未知对象")
        if any(
            str(item.get("object_id")) not in catalog
            for candidates in self.hybrid_candidates.values()
            for item in candidates
        ):
            raise ValueError("混合候选列表包含未知对象")

    def recommend(self, user_key: str, count: int) -> list[ServingCandidate]:
        import numpy as np

        results: list[ServingCandidate] = []
        seen: set[str] = set()
        for raw in self.hybrid_candidates.get(user_key, []):
            object_id = str(raw["object_id"])
            if object_id in seen:
                continue
            results.append(
                ServingCandidate(
                    object_id,
                    float(raw["score"]),
                    tuple(raw["sources"]),
                    str(raw["reason_code"]),
                )
            )
            seen.add(object_id)
            if len(results) >= count:
                return results
        user_index = self.user_vocabulary.get(user_key, 0)
        if user_index and np.linalg.norm(self.user_embeddings[user_index]) > 0:
            scores, indexes = self.index.search(
                self.user_embeddings[user_index].astype("float32").reshape(1, -1),
                min(count, len(self.object_ids)),
            )
            for score, index in zip(scores[0], indexes[0], strict=True):
                if index < 0:
                    continue
                object_id = self.object_ids[int(index)]
                if object_id in seen:
                    continue
                results.append(ServingCandidate(object_id, float(score), ("TWO_TOWER",), "SIMILAR_ITEM"))
                seen.add(object_id)
        for rank, object_id in enumerate(self.popular, 1):
            if object_id not in seen:
                results.append(ServingCandidate(str(object_id), 1.0 / rank, ("POPULARITY",), "POPULAR_OR_NEW"))
                seen.add(str(object_id))
            if len(results) >= count:
                break
        return results[:count]


class ModelRegistry:
    """先完整构造新模型，再在锁内替换引用，加载失败不影响旧模型。"""

    def __init__(self, model_root: Path) -> None:
        self.model_root = model_root.resolve()
        self._lock = threading.RLock()
        self._active: LoadedTradeModel | None = None
        self._pointer_mtime_ns: int | None = None

    @property
    def ready(self) -> bool:
        with self._lock:
            return self._active is not None

    @property
    def model_version(self) -> str | None:
        with self._lock:
            return self._active.model_version if self._active else None

    def refresh(self) -> bool:
        pointer = self.model_root / "active-model.json"
        if not pointer.is_file():
            return False
        mtime = pointer.stat().st_mtime_ns
        if mtime == self._pointer_mtime_ns:
            return False
        payload = json.loads(pointer.read_text(encoding="utf-8"))
        relative = Path(str(payload["path"]))
        bundle = (self.model_root / relative).resolve()
        if self.model_root not in bundle.parents:
            raise ValueError("活动模型路径越界")
        candidate = LoadedTradeModel(bundle)
        if candidate.model_version != payload.get("model_version"):
            raise ValueError("活动指针与模型包版本不一致")
        with self._lock:
            self._active = candidate
            self._pointer_mtime_ns = mtime
        return True

    def recommend(self, user_key: str, count: int) -> tuple[str, list[ServingCandidate]]:
        self.refresh()
        with self._lock:
            if self._active is None:
                raise RuntimeError("当前没有可用的 Trade 模型")
            active = self._active
        return active.model_version, active.recommend(user_key, count)
