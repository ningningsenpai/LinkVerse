"""在同一测试请求时刻复评冻结候选，保留旧口径结果作为历史记录。"""
import json
import copy
from datetime import datetime
from pathlib import Path

import numpy as np
import lightgbm as lgb

from linkverse_recommendation.pipeline.reranking.mmr import RankedObject, rerank
from linkverse_recommendation.serving.registry import LoadedTradeModel, ServingCandidate
from linkverse_recommendation.training.evaluation import evaluate_models
from linkverse_recommendation.training.recording import RunRecorder, write_json


class PopularModel:
    def __init__(self, model):
        self.object_ids = model.object_ids
        self.user_vocabulary = model.user_vocabulary
        self.serving_state = model.serving_state
        self.model_version = model.model_version + ":popular_quota"
        self.popular = list(dict.fromkeys([*model.popular, *model.object_ids]))

    def recommend(self, user_key, count, *, context, occurred_at):
        state = self.serving_state
        excluded = set(state["users"].get(user_key, {}).get("positive", {}))
        excluded.update(context["exclude_ids"].split(","))
        pool = []
        for rank, item in enumerate(self.popular, 1):
            detail = state["items"][item]
            published = datetime.fromisoformat(detail["published_at"])
            if item not in excluded and published <= occurred_at:
                pool.append(RankedObject(item, 1 / rank, detail["category_code"], detail["seller_key"],
                                         (occurred_at - published).days <= 30, ()))
            if len(pool) == 500:
                break
        top = rerank(pool, min(20, count), lambda a, b: 0, mmr_lambda=1)
        seen = {item.object_id for item in top}
        ordered = top + ([item for item in pool if item.object_id not in seen] if len(top) == min(20, count) else [])
        return [ServingCandidate(item.object_id, item.score, ("POPULARITY",), "POPULAR_OR_NEW") for item in ordered[:count]]


root = Path.cwd()
config = json.loads((root / ".cache/recommendation-ranker-20260908/formal-config.json").read_text(encoding="utf-8"))
selection = json.loads((root / "linkverse-platform/recommendation-experiments/runs/trade-ranker-calibration-20260908/selection.json").read_text(encoding="utf-8"))
previous = root / "linkverse-platform/recommendation/artifacts/formal-20260906T060941Z/after/trade-20260906065816234951-6f240414-4f63f5f4"
output = root / "linkverse-platform/recommendation-experiments/runs/trade-ranker-request-time-20260908"
request_time = datetime.fromisoformat("2026-02-26T15:13:00+00:00")
settings = {"bundle": selection["bundle"], "selected_manifest_sha256": selection["manifest_sha256"],
            "request_time": request_time.isoformat(), "model_selection_frozen": True, "resource_monitor": True,
            "purpose": "HISTORICAL_REGRESSION_COMMON_REQUEST_TIME"}
with RunRecorder(output, settings, root):
    before = LoadedTradeModel(Path(config["base_bundle"]))
    after = LoadedTradeModel(Path(selection["bundle"]))
    models = {"previous_after": LoadedTradeModel(previous), "popular": PopularModel(before), "before": before}
    for index, seed in ((1, 20260917), (2, 20261001)):
        diagnostic = copy.copy(after)
        diagnostic.ranker = lgb.Booster(model_file=str(root / f"linkverse-platform/recommendation-experiments/runs/trade-ranker-calibration-20260908/raw/seed-{seed}-{index}.txt"))
        diagnostic.model_version += f":seed-{seed}"
        models[f"seed_{seed}"] = diagnostic
    models["after"] = after
    result = evaluate_models(Path(config["dataset"]), {}, output, Path(config["split_manifest"]), models,
                             request_time=request_time)
    observations = {name: [json.loads(line) for line in (output / "raw" / f"users-{name}.jsonl").read_text().splitlines()]
                    for name in models}
    comparisons = {}
    for name in ("before", "previous_after", "popular"):
        deltas = [b["ndcg_at_20"] - a["ndcg_at_20"] for a, b in zip(observations[name], observations["after"], strict=True)
                  if a["ndcg_at_20"] is not None and b["ndcg_at_20"] is not None]
        rng = np.random.default_rng(20260908)
        estimates = [float(rng.choice(deltas, len(deltas)).mean()) for _ in range(2000)]
        comparisons[name] = {"users": len(deltas), "ndcg_delta": float(np.mean(deltas)),
                             "bootstrap_95": np.quantile(estimates, [.025, .975]).tolist(), "confirmatory": False}
    write_json(output / "paired-comparisons.json", comparisons)
    print(json.dumps({"metrics": result["models"], "paired": comparisons}, ensure_ascii=False))
