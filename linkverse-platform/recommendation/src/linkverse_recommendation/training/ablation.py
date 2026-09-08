"""冻结模型的热门基线与候选池内消融，不使用测试指标选择参数。"""

from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path

import numpy as np

from linkverse_recommendation.pipeline.ranking.features import profile, rank_features
from linkverse_recommendation.pipeline.reranking.mmr import RankedObject, rerank
from linkverse_recommendation.serving.registry import LoadedTradeModel, ServingCandidate
from linkverse_recommendation.training.evaluation import evaluate_models
from linkverse_recommendation.training.recording import RunRecorder, write_json


class FrozenPoolVariant:
    def __init__(self, model, name, *, popularity=False, remove_sources=(), ranking=True, diversity=True):
        self.model = model
        self.object_ids = model.object_ids
        self.user_vocabulary = model.user_vocabulary
        self.model_version = f"{model.model_version}:{name}"
        self.popularity = popularity
        self.remove_sources = set(remove_sources)
        self.ranking = ranking
        self.diversity = diversity

    def recommend(self, user_key, count, *, context=None):
        state = self.model.serving_state
        now = datetime.fromisoformat(state["fit_cutoff"])
        excluded = set(state["users"].get(user_key, {}).get("positive", {}))
        excluded.update((context or {}).get("exclude_ids", "").split(","))
        if self.popularity:
            candidates = [{"object_id": item, "recall_score": 1 / (index + 1)} for index, item in enumerate(self.model.popular)]
        else:
            candidates = [item for item in self.model.hybrid_candidates.get(user_key, [])
                          if not self.remove_sources or set(item["sources"]) - self.remove_sources]
        candidates = [item for item in candidates if str(item["object_id"]) not in excluded
                      and datetime.fromisoformat(state["items"][str(item["object_id"])]["published_at"]) <= now][:500]
        if not candidates:
            return []
        ids = [str(item["object_id"]) for item in candidates]
        if self.ranking and not self.popularity:
            user_profile = profile(state, user_key, context or {})
            scores = self.model.ranker.predict(np.asarray([rank_features(state, user_profile, item, now) for item in ids], dtype="float32"), num_threads=1)
        else:
            scores = [float(item["recall_score"]) for item in candidates]
        order = sorted(range(len(ids)), key=lambda index: (-float(scores[index]), ids[index]))
        ranked = [RankedObject(ids[index], float(scores[index]), state["items"][ids[index]]["category_code"],
                               state["items"][ids[index]]["seller_key"],
                               (now - datetime.fromisoformat(state["items"][ids[index]]["published_at"])).days <= 30, (index,)) for index in order]
        vectors = self.model.object_embeddings[[self.model.object_indexes[item] for item in ids]]
        similarities = vectors @ vectors.T if self.diversity else None
        # 去掉多样性惩罚仍保留相同类目、卖家和新品约束，使业务约束保持可比。
        selected = rerank(ranked, min(20, count), lambda left, right: float(similarities[left[0], right[0]]) if self.diversity else 0.0,
                          mmr_lambda=.8 if self.diversity else 1.0)
        seen = {item.object_id for item in selected}
        ordered = selected + ([item for item in ranked if item.object_id not in seen] if len(selected) == min(20, count) else [])
        return [ServingCandidate(item.object_id, item.score, ("OFFLINE_DIAGNOSTIC",), "POPULAR_OR_NEW") for item in ordered[:count]]


def execute(config):
    output = Path(config["output"])
    with RunRecorder(output, config, Path(__file__).resolve().parents[5]):
        model = LoadedTradeModel(Path(config["bundle"]))
        variants = {
            "popular_quota": FrozenPoolVariant(model, "popular_quota", popularity=True, diversity=False),
            "pool_without_ranker": FrozenPoolVariant(model, "pool_without_ranker", ranking=False),
            "pool_without_mmr": FrozenPoolVariant(model, "pool_without_mmr", diversity=False),
            "pool_without_itemcf": FrozenPoolVariant(model, "pool_without_itemcf", remove_sources=("ITEM_CF",)),
            "pool_without_tower": FrozenPoolVariant(model, "pool_without_tower", remove_sources=("TWO_TOWER",)),
            "pool_reference": FrozenPoolVariant(model, "pool_reference"),
        }
        result = evaluate_models(Path(config["dataset"]), {}, output, Path(config["split_manifest"]), variants)
        result["limitations"] = ["消融只覆盖冻结的混合候选池，不能恢复上游已裁掉的候选，不是端到端召回渠道消融。",
                                  "多渠道对象只移除该来源独占对象，共享对象保留；未重新计算合并召回分数。",
                                  "候选池缺失用户不加额外热门补位，短列表与空列表均进入报告。",
                                  "热门基线取模型训练时排序的前 500 个可用对象，并施加同一业务配额。",
                                  "各项只用于诊断，未据此选择本轮参数或发布模型。"]
        write_json(output / "evaluation.json", result)
        print(json.dumps(result["models"], ensure_ascii=False), flush=True)


def main():
    parser = argparse.ArgumentParser(description="执行冻结候选池消融和热门基线")
    parser.add_argument("--config", required=True, type=Path)
    execute(json.loads(parser.parse_args().config.read_text(encoding="utf-8")))


if __name__ == "__main__":
    main()
