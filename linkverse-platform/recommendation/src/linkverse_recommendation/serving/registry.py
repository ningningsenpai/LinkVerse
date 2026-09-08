"""模型注册表、严格加载与活动模型原子切换。"""

from __future__ import annotations

import json
import threading
import time
from datetime import datetime, timezone
from dataclasses import dataclass
from pathlib import Path

from linkverse_recommendation.core.model_bundle import validate_bundle
from linkverse_recommendation.pipeline.ranking.features import (
    HISTORY_FEATURES, RECALL_FEATURES, profile, rank_features, recall_features, relevance_scores,
)
from linkverse_recommendation.pipeline.reranking.mmr import RankedObject, rerank_with_matrix


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
        self.serving_state = None
        if self.manifest["schema_version"] == 2:
            import lightgbm as lgb
            self.serving_state = json.loads((bundle / "serving-state.json").read_text(encoding="utf-8"))
            if set(self.serving_state["items"]) != catalog:
                raise ValueError("在线特征商品集合与索引不一致")
            self.ranker = lgb.Booster(model_file=str(bundle / "lambda-rank.txt"))
            schema = json.loads((bundle / "feature-schema.json").read_text(encoding="utf-8"))
            self.feature_version = schema.get("version", 2)
            self.score_policy = schema.get("mmr_score_policy", "raw")
            self.score_scale = float(schema.get("mmr_score_scale", 1))
            expected = RECALL_FEATURES if self.feature_version == 3 else HISTORY_FEATURES
            if self.feature_version not in {2, 3} or self.score_policy not in {"raw", "percentile"}:
                raise ValueError("在线精排特征或重排策略版本不支持")
            if not 0 < self.score_scale <= 1:
                raise ValueError("重排分数缩放必须大于零且不超过一")
            if self.feature_version == 3 and schema.get("ranker_features") != expected:
                raise ValueError("在线精排特征顺序不一致")
            if self.ranker.num_feature() != len(expected):
                raise ValueError("在线精排特征维数不一致")
            self.object_embeddings = self.index.reconstruct_n(0, self.index.ntotal)
            self.object_indexes = {item: index for index, item in enumerate(self.object_ids)}
            self.popular_ranks = {item: rank for rank, item in enumerate(self.popular, 1)}
            self.recommend("__warmup__", 20)

    def recommend(self, user_key: str, count: int, *, scene="HOME", context=None, occurred_at=None) -> list[ServingCandidate]:
        import numpy as np

        if self.serving_state is not None:
            return self._recommend_current(user_key, count, scene, context or {}, occurred_at)

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

    def _recommend_current(self, user_key, count, scene, context, occurred_at):
        ids, raw, features, now = self.candidate_features(
            user_key, count, scene=scene, context=context, occurred_at=occurred_at,
        )
        if not ids:
            return []
        scores = self.ranker.predict(features, num_threads=1)
        return self.rank_candidates(ids, raw, scores, now, count, self.score_policy, self.score_scale)

    def candidate_features(self, user_key: str, count: int = 300, *, scene="HOME", context=None,
                           occurred_at=None, feature_version=None):
        """训练回放与在线请求共用候选和特征，调用方不得向候选池注入标签对象。"""
        import numpy as np

        context = context or {}
        version = self.feature_version if feature_version is None else feature_version
        if version not in {2, 3}:
            raise ValueError("在线精排特征版本不支持")
        if scene not in {"HOME", "DETAIL", "CART"}:
            raise ValueError("推荐场景无效")
        state = self.serving_state
        now = occurred_at or datetime.fromisoformat(state["fit_cutoff"])
        if now.tzinfo is None:
            raise ValueError("推荐时间必须携带时区")
        user = state["users"].get(user_key, {})
        excluded = set(user.get("positive", {})) | set(context.get("exclude_ids", "").split(","))
        if scene in {"DETAIL", "CART"}:
            excluded.add(context.get("object_id", ""))
        raw = {str(item["object_id"]): item for item in self.hybrid_candidates.get(user_key, [])}
        pool_size = min(max(count, 300), len(self.object_ids))
        user_index = self.user_vocabulary.get(user_key, 0)
        if user_index:
            _, indexes = self.index.search(self.user_embeddings[user_index].reshape(1, -1), pool_size)
            for index in indexes[0]:
                if index >= 0:
                    raw.setdefault(self.object_ids[index], {"sources": ["TWO_TOWER"], "reason_code": "SIMILAR_ITEM"})
        for item in self.popular + self.object_ids:
            raw.setdefault(item, {"sources": ["POPULARITY"], "reason_code": "POPULAR_OR_NEW"})
            if len(raw) >= min(pool_size * 2, len(self.object_ids)):
                break
        ids = [item for item in raw if item not in excluded and datetime.fromisoformat(state["items"][item]["published_at"]) <= now][:500]
        if not ids:
            return ids, raw, np.empty((0, len(RECALL_FEATURES) if version == 3 else 5)), now
        feature_context = dict(context)
        if scene == "HOME":
            feature_context.pop("object_id", None)
        user_profile = profile(state, user_key, feature_context)
        features = np.asarray([rank_features(state, user_profile, item, now) for item in ids], dtype="float32")
        if version == 3:
            vectors = self.object_embeddings[[self.object_indexes[item] for item in ids]]
            tower_scores = vectors @ self.user_embeddings[user_index]
            extra = [recall_features(raw[item], tower_scores[index], bool(user_index), self.popular_ranks.get(item, 0))
                     for index, item in enumerate(ids)]
            features = np.concatenate((features, np.asarray(extra, dtype="float32")), axis=1)
        return ids, raw, features, now

    def rank_candidates(self, ids, raw, scores, now, count: int, score_policy: str, score_scale: float = 1):
        """最终列表始终应用同一配额和新品约束，相关性尺度由模型包声明。"""
        import numpy as np

        state = self.serving_state
        if not 0 < score_scale <= 1:
            raise ValueError("重排分数缩放必须大于零且不超过一")
        scores = relevance_scores(scores, score_policy) * score_scale
        order = sorted(range(len(ids)), key=lambda index: (-float(scores[index]), ids[index]))
        vectors = self.object_embeddings[[self.object_indexes[item] for item in ids]]
        similarities = vectors @ vectors.T
        ranked = [RankedObject(ids[index], float(scores[index]), state["items"][ids[index]]["category_code"], state["items"][ids[index]]["seller_key"], (now - datetime.fromisoformat(state["items"][ids[index]]["published_at"])).days <= 30, (index,)) for index in order]
        top = rerank_with_matrix(ranked, min(20, count), similarities[np.ix_(order, order)])
        selected_ids = {item.object_id for item in top}
        selected = top + ([item for item in ranked if item.object_id not in selected_ids] if len(top) == min(20, count) else [])
        return [ServingCandidate(item.object_id, item.score, tuple(raw[item.object_id]["sources"]), raw[item.object_id]["reason_code"]) for item in selected[:count]]


class ModelRegistry:
    """先完整构造新模型，再在锁内替换引用，加载失败不影响旧模型。"""

    def __init__(self, model_root: Path) -> None:
        self.model_root = model_root.resolve()
        self._lock = threading.RLock()
        self._active: LoadedTradeModel | None = None
        self._pointer_mtime_ns: int | None = None
        self._refresh_lock = threading.Lock()
        self._failed_pointer: int | None = None
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self.last_load_error: str | None = None

    @property
    def ready(self) -> bool:
        with self._lock:
            return self._active is not None

    @property
    def model_version(self) -> str | None:
        with self._lock:
            return self._active.model_version if self._active else None

    def refresh(self) -> bool:
        with self._refresh_lock:
            try:
                return self._refresh()
            except Exception as exception:
                pointer = self.model_root / "active-model.json"
                self._failed_pointer = pointer.stat().st_mtime_ns if pointer.is_file() else None
                self.last_load_error = type(exception).__name__
                raise

    def _refresh(self) -> bool:
        pointer = self.model_root / "active-model.json"
        if not pointer.is_file():
            return False
        mtime = pointer.stat().st_mtime_ns
        if mtime == self._pointer_mtime_ns:
            return False
        if mtime == self._failed_pointer:
            return False
        payload = json.loads(pointer.read_text(encoding="utf-8"))
        relative = Path(str(payload["path"]))
        bundle = (self.model_root / relative).resolve()
        if self.model_root not in bundle.parents:
            raise ValueError("活动模型路径越界")
        try:
            candidate = LoadedTradeModel(bundle)
        except Exception as exception:
            self._failed_pointer = mtime
            self.last_load_error = type(exception).__name__
            raise
        if candidate.model_version != payload.get("model_version"):
            raise ValueError("活动指针与模型包版本不一致")
        if pointer.stat().st_mtime_ns != mtime:
            return False
        with self._lock:
            self._active = candidate
            self._pointer_mtime_ns = mtime
            self.last_load_error = None
        return True

    def start(self, on_failure=None):
        def watch():
            while not self._stop.is_set():
                try:
                    self.refresh()
                except Exception:
                    if on_failure:
                        on_failure()
                self._stop.wait(1)
        self._thread = threading.Thread(target=watch, daemon=True, name="recommendation-model-loader")
        self._thread.start()

    def close(self):
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=5)

    def recommend(self, user_key: str, count: int, **kwargs) -> tuple[str, list[ServingCandidate]]:
        if self._thread is None:
            try:
                self.refresh()
            except Exception:
                if not self.ready:
                    raise RuntimeError("当前模型加载失败") from None
        with self._lock:
            if self._active is None:
                raise RuntimeError("当前没有可用的 Trade 模型")
            active = self._active
        return active.model_version, active.recommend(user_key, count, **kwargs)
