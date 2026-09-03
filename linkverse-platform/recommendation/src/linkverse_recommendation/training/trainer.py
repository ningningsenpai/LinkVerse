"""Trade 多通道召回、双塔、LambdaRank、重排和模型包的可复现训练编排。"""

from __future__ import annotations

import hashlib
import json
import math
import random
import statistics
import subprocess
import time
from bisect import bisect_right
from collections import Counter, defaultdict
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

from linkverse_recommendation.core.metrics import ndcg_at_k, recall_at_k
from linkverse_recommendation.core.model_bundle import sha256_file, write_manifest
from linkverse_recommendation.data.labels import build_trade_training_samples
from linkverse_recommendation.data.split import temporal_split
from linkverse_recommendation.pipeline.fusion import fuse_channels
from linkverse_recommendation.pipeline.ranking.lambda_rank import LambdaRankConfig, LambdaRanker
from linkverse_recommendation.pipeline.reranking.mmr import RankedObject, rerank
from linkverse_recommendation.pipeline.recall.content import TfidfContentRecall
from linkverse_recommendation.pipeline.recall.item_cf import WeightedItemCF
from linkverse_recommendation.pipeline.recall.popularity import newest, time_decay_popularity
from linkverse_recommendation.pipeline.recall.vector import FlatInnerProductRecall
from linkverse_recommendation.training.cuda_check import require_cuda
from linkverse_recommendation.training.tuning import tune_lambda_rank, tune_two_tower
from linkverse_recommendation.training.two_tower import TwoTowerConfig, create_model, save_checkpoint, train_epoch
from linkverse_recommendation.training.vocabulary import build_vocabulary, save_vocabulary


FIXED_SEEDS = (20260903, 20260917, 20261001)


def train_trade_model(
    dataset: Path,
    output_root: Path,
    two_tower_trials: int = 30,
    ranker_trials: int = 50,
    include_feedback: bool = False,
    tower_parameters: dict[str, object] | None = None,
    ranker_parameters: dict[str, object] | None = None,
) -> dict[str, object]:
    """严格保留最终 15% 测试窗口，调参后以三个固定种子重训并生成候选模型包。"""

    cuda = require_cuda()
    raw_items = pd.read_parquet(dataset / "items.parquet")
    raw_interactions = pd.read_parquet(dataset / "interactions.parquet")
    _validate_data_source(raw_items, raw_interactions)
    data_source = str(raw_interactions["data_source"].iloc[0])
    items = _normalize_items(raw_items)
    interactions = _normalize_interactions(raw_interactions)
    split = temporal_split(interactions.to_dict("records"), key=lambda row: row["event_time"])
    train_rows = list(split.train)
    validation_rows = list(split.validation)
    feedback_rows = list(split.feedback)
    test_rows = list(split.test)
    fit_rows = train_rows + validation_rows + (feedback_rows if include_feedback else [])
    fit_cutoff = max(row["event_time"] for row in fit_rows)
    _validate_item_event_times(items, fit_rows)
    training_items = items[items["published_at"].le(fit_cutoff)].copy()
    published_at_by_object = {
        str(row["listing_id"]): row["published_at"]
        for row in training_items.to_dict("records")
    }

    user_vocabulary = build_vocabulary(str(row["user_key"]) for row in fit_rows)
    object_vocabulary = build_vocabulary(
        str(row["object_id"]) for row in training_items.to_dict("records")
    )
    positives = _positives_by_user(fit_rows)

    started_at = time.perf_counter()
    if tower_parameters is None:
        def tower_objective(parameters: dict[str, object]) -> float:
            model, score, _ = _fit_two_tower(
                train_rows,
                validation_rows,
                user_vocabulary,
                object_vocabulary,
                published_at_by_object,
                parameters,
                FIXED_SEEDS[0],
            )
            del model
            return score

        tower_study = tune_two_tower(tower_objective, trials=two_tower_trials)
        best_tower = dict(tower_study.best_params)
        _, _, selected_epochs = _fit_two_tower(
            train_rows,
            validation_rows,
            user_vocabulary,
            object_vocabulary,
            published_at_by_object,
            {**best_tower, "max_epochs": 40, "patience": 5},
            FIXED_SEEDS[0],
        )
    else:
        best_tower = dict(tower_parameters)
        selected_epochs = int(best_tower.pop("selected_epochs"))
    best_tower["max_epochs"] = 40
    best_tower["patience"] = 5
    best_tower["selected_epochs"] = selected_epochs

    seed_models = []
    seed_metrics: list[float] = []
    for seed in FIXED_SEEDS:
        fixed_parameters = {
            **best_tower,
            "max_epochs": selected_epochs,
            "patience": selected_epochs + 1,
        }
        model, _, _ = _fit_two_tower(
            fit_rows,
            [],
            user_vocabulary,
            object_vocabulary,
            published_at_by_object,
            fixed_parameters,
            seed,
        )
        seed_models.append(model)
        seed_metrics.append(
            _evaluate_tower(model, test_rows, user_vocabulary, object_vocabulary)
        )
    champion = seed_models[0]

    rank_train = _rank_dataset(train_rows, training_items, positives, seed=FIXED_SEEDS[0])
    rank_valid = _rank_dataset(
        validation_rows,
        training_items,
        positives,
        seed=FIXED_SEEDS[0] + 1,
        history_rows=train_rows,
    )

    if ranker_parameters is None:
        def rank_objective(parameters: dict[str, object]) -> float:
            tuning_ranker = LambdaRanker(LambdaRankConfig(**parameters)).fit(
                rank_train[0], rank_train[1], rank_train[2], validation=rank_valid[:3]
            )
            predictions = tuning_ranker.predict(rank_valid[0])
            return _grouped_ndcg(predictions, rank_valid[1], rank_valid[2], 20)

        rank_study = tune_lambda_rank(rank_objective, trials=ranker_trials)
        best_ranker = dict(rank_study.best_params)
        ranker_validation_score = float(rank_study.best_value)
    else:
        best_ranker = dict(ranker_parameters)
        tuning_ranker = LambdaRanker(LambdaRankConfig(**best_ranker)).fit(
            rank_train[0], rank_train[1], rank_train[2], validation=rank_valid[:3]
        )
        ranker_validation_score = _grouped_ndcg(
            tuning_ranker.predict(rank_valid[0]), rank_valid[1], rank_valid[2], 20
        )
    rank_fit = _rank_dataset(fit_rows, training_items, positives, seed=FIXED_SEEDS[0])
    ranker = LambdaRanker(LambdaRankConfig(**best_ranker)).fit(
        rank_fit[0], rank_fit[1], rank_fit[2]
    )

    data_hash = _dataset_hash(dataset)
    model_version = _model_version(data_hash)
    bundle = output_root / model_version
    bundle.mkdir(parents=True, exist_ok=False)
    _write_bundle(
        bundle,
        champion,
        best_tower,
        ranker,
        user_vocabulary,
        object_vocabulary,
        training_items,
        fit_rows,
        positives,
        seed_metrics,
        data_hash,
        cuda,
        data_source,
    )
    return {
        "model_version": model_version,
        "bundle": str(bundle),
        "data_hash": data_hash,
        "cuda": cuda,
        "data_source": data_source,
        "split_rows": {
            "train": len(train_rows),
            "validation": len(validation_rows),
            "feedback": len(feedback_rows),
            "test": len(test_rows),
        },
        "two_tower_best_params": best_tower,
        "two_tower_seed_ndcg_at_20": seed_metrics,
        "two_tower_ndcg_at_20_mean": statistics.fmean(seed_metrics),
        "two_tower_ndcg_at_20_std": statistics.pstdev(seed_metrics),
        "lambda_rank_best_params": best_ranker,
        "lambda_rank_ndcg_at_20": ranker_validation_score,
        "two_tower_trial_count": two_tower_trials if tower_parameters is None else 0,
        "lambda_rank_trial_count": ranker_trials if ranker_parameters is None else 0,
        "elapsed_seconds": time.perf_counter() - started_at,
        "limitations": "合成工程验证，不代表线上推荐质量",
    }


def _normalize_interactions(frame: pd.DataFrame) -> pd.DataFrame:
    result = frame.copy()
    if "object_id" not in result:
        result["object_id"] = result["listing_id"].astype(str)
    result["event_time"] = pd.to_datetime(result["event_time"], utc=True)
    if "legacy_buy_signal" in result:
        result["gain"] = np.select(
            [result["legacy_buy_signal"].eq(1), result["cart"].eq(1), result["click"].eq(1)],
            [7, 3, 1],
            default=0,
        ).astype("int8")
        result["event_type"] = np.select(
            [result["legacy_buy_signal"].eq(1), result["cart"].eq(1)],
            ["LEGACY_BUY_SIGNAL", "CART_ADD"],
            default="DETAIL_OPEN",
        )
        result["preference_negative"] = False
    else:
        result = build_trade_training_samples(result)
    result["gain"] = result["gain"].astype("int8")
    result["preference_negative"] = result["preference_negative"].astype(bool)
    return result


def _validate_data_source(items: pd.DataFrame, interactions: pd.DataFrame) -> None:
    """禁止 REAL 与 SYNTHETIC 在同一模型训练或评估中混合。"""

    item_sources = set(items.get("data_source", pd.Series(dtype="object")).dropna().unique())
    event_sources = set(
        interactions.get("data_source", pd.Series(dtype="object")).dropna().unique()
    )
    if len(item_sources) != 1 or len(event_sources) != 1 or item_sources != event_sources:
        raise ValueError("商品与行为必须来自同一个且唯一的 REAL 或 SYNTHETIC 数据源")
    if not item_sources.issubset({"REAL", "SYNTHETIC"}):
        raise ValueError("数据来源只能是 REAL 或 SYNTHETIC")


def _validate_item_event_times(items: pd.DataFrame, rows) -> None:
    published = {
        str(row["listing_id"]): row["published_at"] for row in items.to_dict("records")
    }
    for row in rows:
        object_id = str(row["object_id"])
        if object_id not in published:
            raise ValueError(f"行为引用了商品快照中不存在的对象：{object_id}")
        if published[object_id] > row["event_time"]:
            raise ValueError(f"行为时间早于商品发布时间：{object_id}")


def _normalize_items(frame: pd.DataFrame) -> pd.DataFrame:
    result = frame.copy()
    if "object_id" not in result:
        if "listing_id" not in result:
            raise ValueError("商品快照缺少 object_id 或 listing_id")
        result["object_id"] = result["listing_id"].astype(str)
    if "listing_id" not in result:
        result["listing_id"] = result["object_id"].astype(str)
    result["object_id"] = result["object_id"].astype(str)
    result["listing_id"] = result["listing_id"].astype(str)
    if "unit_price" not in result:
        result["unit_price"] = result["price"]
    if "published_at" not in result:
        raise ValueError("商品快照缺少 published_at")
    result["published_at"] = pd.to_datetime(result["published_at"], utc=True)
    if "seller_key" not in result:
        result["seller_key"] = "UNKNOWN"
    return result


def _fit_two_tower(
    rows,
    evaluation_rows,
    user_vocabulary,
    object_vocabulary,
    published_at_by_object,
    parameters,
    seed,
):
    import torch
    from torch.utils.data import DataLoader, TensorDataset

    _seed_everything(seed)
    hidden_dims = tuple(int(value) for value in str(parameters["hidden_dims"]).split("-"))
    config = TwoTowerConfig(
        user_count=len(user_vocabulary),
        object_count=len(object_vocabulary),
        embedding_dim=int(parameters["embedding_dim"]),
        hidden_dims=hidden_dims,
        dropout=float(parameters["dropout"]),
        temperature=float(parameters["temperature"]),
    )
    model = create_model(config).to("cuda")
    users, positives, negatives = _training_tensors(
        rows,
        user_vocabulary,
        object_vocabulary,
        int(parameters["negative_count"]),
        seed,
        published_at_by_object,
    )
    loader = DataLoader(
        TensorDataset(users, positives, negatives),
        batch_size=int(parameters["batch_size"]),
        shuffle=True,
        generator=torch.Generator().manual_seed(seed),
        pin_memory=True,
    )
    optimizer = torch.optim.AdamW(
        model.parameters(),
        lr=float(parameters["learning_rate"]),
        weight_decay=float(parameters["weight_decay"]),
    )
    scaler = torch.amp.GradScaler("cuda")
    best_score = -1.0
    best_state = None
    best_epoch = 0
    stale_epochs = 0
    for epoch in range(1, int(parameters.get("max_epochs", 40)) + 1):
        train_epoch(model, loader, optimizer, scaler, "cuda")
        if not evaluation_rows:
            best_epoch = epoch
            continue
        score = _evaluate_tower(model, evaluation_rows, user_vocabulary, object_vocabulary)
        if score > best_score + 1e-6:
            best_score = score
            best_state = {name: value.detach().cpu().clone() for name, value in model.state_dict().items()}
            best_epoch = epoch
            stale_epochs = 0
        else:
            stale_epochs += 1
            if stale_epochs >= int(parameters.get("patience", 5)):
                break
    if best_state is not None:
        model.load_state_dict(best_state)
    return model, max(0.0, best_score), best_epoch


def _training_tensors(
    rows,
    user_vocabulary,
    object_vocabulary,
    negative_count,
    seed,
    published_at_by_object=None,
):
    import torch

    preference_negative_by_user: dict[int, set[int]] = defaultdict(set)
    explicit_negative_by_user: dict[int, set[int]] = defaultdict(set)
    for row in rows:
        user = user_vocabulary.get(str(row["user_key"]))
        item = object_vocabulary.get(str(row["object_id"]))
        if user is None or item is None:
            continue
        if bool(row.get("preference_negative", False)):
            preference_negative_by_user[user].add(item)
        if row.get("negative_source") in {"REAL_EXPOSURE", "REAL_REFUND"}:
            explicit_negative_by_user[user].add(item)
    pairs = [
        (
            user_vocabulary[str(row["user_key"])],
            object_vocabulary[str(row["object_id"])],
            row.get("event_time"),
        )
        for row in rows
        if row["gain"] > 0 and str(row["user_key"]) in user_vocabulary
        and object_vocabulary[str(row["object_id"])]
        not in preference_negative_by_user[user_vocabulary[str(row["user_key"])]]
    ]
    positive_by_user: dict[int, set[int]] = defaultdict(set)
    for user, item, _ in pairs:
        positive_by_user[user].add(item)
    universe_size = len(object_vocabulary)
    if published_at_by_object is None:
        publication_order = [(pd.Timestamp.min.tz_localize("UTC"), index) for index in range(1, universe_size + 1)]
    else:
        publication_order = sorted(
            (pd.to_datetime(published_at_by_object[object_id], utc=True), index)
            for object_id, index in object_vocabulary.items()
        )
    publication_times = [entry[0] for entry in publication_order]
    published_at_by_index = {index: published_at for published_at, index in publication_order}
    generator = random.Random(seed)
    negatives = []
    usable_pairs = []
    for user, positive, event_time in pairs:
        positive_ids = positive_by_user[user]
        cutoff = (
            len(publication_order)
            if event_time is None
            else bisect_right(publication_times, pd.to_datetime(event_time, utc=True))
        )
        event_timestamp = (
            pd.Timestamp.max.tz_localize("UTC")
            if event_time is None
            else pd.to_datetime(event_time, utc=True)
        )
        available_positive_count = sum(
            published_at_by_index[index] <= event_timestamp for index in positive_ids
        )
        if cutoff <= available_positive_count:
            continue
        hard_negatives = sorted(
            index
            for index in explicit_negative_by_user[user] - positive_ids
            if published_at_by_index[index] <= event_timestamp
        )
        generator.shuffle(hard_negatives)
        sampled = hard_negatives[:negative_count]
        require_distinct = cutoff - available_positive_count >= negative_count
        while len(sampled) < negative_count:
            candidate = publication_order[generator.randrange(cutoff)][1]
            if candidate not in positive_ids and (not require_distinct or candidate not in sampled):
                sampled.append(candidate)
        negatives.append(sampled)
        usable_pairs.append((user, positive))
    if not usable_pairs:
        raise ValueError("训练窗口没有可构造负样本的正行为")
    return (
        torch.tensor([pair[0] for pair in usable_pairs], dtype=torch.long),
        torch.tensor([pair[1] for pair in usable_pairs], dtype=torch.long),
        torch.tensor(negatives, dtype=torch.long),
    )


def _evaluate_tower(model, rows, user_vocabulary, object_vocabulary) -> float:
    import torch

    relevant: dict[str, dict[str, float]] = defaultdict(dict)
    for row in rows:
        if row["gain"] > 0 and str(row["user_key"]) in user_vocabulary:
            relevant[str(row["user_key"])][str(row["object_id"])] = float(row["gain"])
    if not relevant:
        return 0.0
    object_ids = [item for item, _ in sorted(object_vocabulary.items(), key=lambda pair: pair[1])]
    with torch.no_grad():
        encoded_objects = model.encode_objects(
            torch.arange(1, len(object_ids) + 1, device="cuda")
        )
        scores = []
        user_keys = sorted(relevant)
        for start in range(0, len(user_keys), 512):
            batch_keys = user_keys[start : start + 512]
            user_ids = torch.tensor(
                [user_vocabulary[user_key] for user_key in batch_keys],
                device="cuda",
            )
            users = model.encode_users(user_ids)
            orders = torch.topk(
                users @ encoded_objects.T,
                k=min(20, len(object_ids)),
                dim=1,
            ).indices.cpu().tolist()
            for user_key, order in zip(batch_keys, orders, strict=True):
                scores.append(
                    ndcg_at_k(
                        [object_ids[index] for index in order],
                        relevant[user_key],
                        20,
                    )
                )
    return statistics.fmean(scores)


def _rank_dataset(rows, items, positives_by_user, seed, history_rows=()):
    object_rows = {str(row["listing_id"]): row for row in items.to_dict("records")}
    prices = items["unit_price"].astype(float)
    price_mean = float(prices.mean())
    price_std = float(prices.std()) or 1.0
    generator = random.Random(seed)
    features: list[list[float]] = []
    labels: list[int] = []
    groups: list[int] = []
    publication_order = sorted(
        (pd.to_datetime(row["published_at"], utc=True), str(row["listing_id"]))
        for row in items.to_dict("records")
    )
    publication_times = [entry[0] for entry in publication_order]
    publication_ids = [entry[1] for entry in publication_order]
    published_at_by_object = {object_id: published_at for published_at, object_id in publication_order}
    popularity: Counter[str] = Counter()
    category_history: dict[str, Counter[str]] = defaultdict(Counter)
    author_history: dict[str, Counter[str]] = defaultdict(Counter)
    price_history: dict[str, list[float]] = defaultdict(list)
    explicit_negative_history: dict[str, set[str]] = defaultdict(set)
    for historical in sorted(history_rows, key=lambda row: row["event_time"]):
        if historical.get("negative_source") in {"REAL_EXPOSURE", "REAL_REFUND"}:
            explicit_negative_history[str(historical["user_key"])].add(
                str(historical["object_id"])
            )
        if historical["gain"] <= 0:
            continue
        user_key = str(historical["user_key"])
        object_id = str(historical["object_id"])
        if object_id not in positives_by_user.get(user_key, set()):
            continue
        item = object_rows[object_id]
        popularity[object_id] += 1
        category_history[user_key][str(item["category_code"])] += 1
        author_history[user_key][str(item["author"])] += 1
        price_history[user_key].append(float(item["unit_price"]))
    ordered_rows = sorted(rows, key=lambda row: row["event_time"])
    for row in ordered_rows:
        if row["gain"] <= 0:
            if row.get("negative_source") in {"REAL_EXPOSURE", "REAL_REFUND"}:
                explicit_negative_history[str(row["user_key"])].add(str(row["object_id"]))
            continue
        user_key = str(row["user_key"])
        positive_id = str(row["object_id"])
        if positive_id not in positives_by_user.get(user_key, set()):
            continue
        available_count = bisect_right(
            publication_times, pd.to_datetime(row["event_time"], utc=True)
        )
        user_positives = positives_by_user.get(user_key, set())
        available_positive_count = sum(
            published_at_by_object[item] <= row["event_time"] for item in user_positives
        )
        available_negative_count = available_count - available_positive_count
        hard_negatives = sorted(
            item
            for item in explicit_negative_history[user_key] - user_positives
            if published_at_by_object[item] <= row["event_time"]
        )
        generator.shuffle(hard_negatives)
        negative_ids = hard_negatives[:4]
        while len(negative_ids) < min(4, available_negative_count):
            candidate = publication_ids[generator.randrange(available_count)]
            if candidate not in user_positives and candidate not in negative_ids:
                negative_ids.append(candidate)
        candidate_ids = [positive_id, *negative_ids]
        maximum_popularity = max(popularity.values(), default=1)
        for object_id in candidate_ids:
            item = object_rows[object_id]
            prior_count = sum(category_history[user_key].values())
            category_affinity = category_history[user_key][str(item["category_code"])] / max(1, prior_count)
            author_affinity = author_history[user_key][str(item["author"])] / max(1, prior_count)
            preferred_price = statistics.fmean(price_history[user_key]) if price_history[user_key] else price_mean
            published = pd.Timestamp(item["published_at"], tz="UTC") if pd.Timestamp(item["published_at"]).tz is None else pd.Timestamp(item["published_at"])
            age_days = max(0.0, (row["event_time"] - published).total_seconds() / 86_400)
            features.append(
                [
                    popularity[object_id] / maximum_popularity,
                    category_affinity,
                    author_affinity,
                    abs(float(item["unit_price"]) - preferred_price) / price_std,
                    math.exp(-age_days / 30.0),
                ]
            )
            labels.append(int(row["gain"]) if object_id == positive_id else 0)
        groups.append(len(candidate_ids))
        item = object_rows[positive_id]
        popularity[positive_id] += 1
        category_history[user_key][str(item["category_code"])] += 1
        author_history[user_key][str(item["author"])] += 1
        price_history[user_key].append(float(item["unit_price"]))
    return np.asarray(features, dtype="float32"), np.asarray(labels, dtype="int32"), groups, []


def _grouped_ndcg(predictions, labels, groups, k):
    offset = 0
    scores = []
    for size in groups:
        indexes = sorted(range(size), key=lambda index: -float(predictions[offset + index]))
        ideal = sorted((int(labels[offset + index]) for index in range(size)), reverse=True)
        dcg = sum((2 ** int(labels[offset + index]) - 1) / math.log2(rank + 2) for rank, index in enumerate(indexes[:k]))
        ideal_dcg = sum((2**gain - 1) / math.log2(rank + 2) for rank, gain in enumerate(ideal[:k]))
        scores.append(dcg / ideal_dcg if ideal_dcg else 0.0)
        offset += size
    return statistics.fmean(scores) if scores else 0.0


def _write_bundle(
    bundle,
    model,
    tower_parameters,
    ranker,
    user_vocabulary,
    object_vocabulary,
    items,
    fit_rows,
    positives,
    seed_metrics,
    data_hash,
    cuda,
    data_source,
):
    import faiss
    import torch

    object_ids = [item for item, _ in sorted(object_vocabulary.items(), key=lambda pair: pair[1])]
    model.eval()
    with torch.no_grad():
        object_embeddings = model.encode_objects(
            torch.arange(1, len(object_ids) + 1, device="cuda")
        ).cpu().numpy().astype("float32")
        user_embeddings = np.zeros((len(user_vocabulary) + 1, object_embeddings.shape[1]), dtype="float32")
        if user_vocabulary:
            user_embeddings[1:] = model.encode_users(
                torch.arange(1, len(user_vocabulary) + 1, device="cuda")
            ).cpu().numpy().astype("float32")
    config = TwoTowerConfig(
        len(user_vocabulary),
        len(object_vocabulary),
        int(tower_parameters["embedding_dim"]),
        tuple(int(value) for value in str(tower_parameters["hidden_dims"]).split("-")),
        float(tower_parameters["dropout"]),
        float(tower_parameters["temperature"]),
    )
    save_checkpoint(bundle / "two_tower.pt", model, config)
    save_vocabulary(bundle / "user-vocabulary.json", user_vocabulary)
    save_vocabulary(bundle / "object-vocabulary.json", object_vocabulary)
    (bundle / "object-ids.json").write_text(json.dumps(object_ids), encoding="utf-8")
    np.save(bundle / "user-embeddings.npy", user_embeddings, allow_pickle=False)
    index = faiss.IndexFlatIP(object_embeddings.shape[1])
    index.add(object_embeddings)
    faiss.write_index(index, str(bundle / "index.faiss"))
    ranker.save(bundle / "lambda-rank.txt")

    now = max(row["event_time"] for row in fit_rows).to_pydatetime()
    events = [
        {"object_id": row["object_id"], "event_type": row["event_type"], "event_time": row["event_time"].to_pydatetime()}
        for row in fit_rows
    ]
    popular_hits = time_decay_popularity(events, now, limit=len(object_ids))
    popular = [hit.object_id for hit in popular_hits]
    (bundle / "popular.json").write_text(json.dumps(popular), encoding="utf-8")
    hybrid = _hybrid_candidates(
        items, fit_rows, positives, model, ranker, user_vocabulary,
        object_ids, object_embeddings, now
    )
    (bundle / "hybrid-candidates.json").write_text(
        json.dumps(hybrid, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
    )
    feature_schema = {
        "version": 1,
        "ranker_features": [
            "popularity_recall_score",
            "category_affinity",
            "author_affinity",
            "price_distance",
            "freshness",
        ],
        "time_semantics": "point_in_time",
    }
    (bundle / "feature-schema.json").write_text(
        json.dumps(feature_schema, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (bundle / "scaler.json").write_text(
        json.dumps({"type": "standard", "price": "fit-window-only"}, ensure_ascii=False), encoding="utf-8"
    )
    metrics = {
        "ndcg_at_20_by_seed": seed_metrics,
        "ndcg_at_20_mean": statistics.fmean(seed_metrics),
        "ndcg_at_20_std": statistics.pstdev(seed_metrics),
        "cuda": cuda,
        "data_source": data_source,
        "quality_claim_allowed": False,
    }
    (bundle / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    dependency_hash = _dependency_hash(Path(__file__).resolve().parents[3])
    write_manifest(bundle, bundle.name, data_hash, dependency_hash)


def _hybrid_candidates(
    items, rows, positives, model, ranker, user_vocabulary, object_ids, object_embeddings, now
):
    item_rows = []
    for row in items.to_dict("records"):
        item_rows.append(
            {
                "object_id": str(row["listing_id"]),
                "title": row["title"],
                "author": row["author"],
                "description": row["description"],
                "category_code": row["category_code"],
                "seller_key": row.get("seller_key", "SYNTHETIC_UNKNOWN"),
                "unit_price": float(row["unit_price"]),
                "published_at": row["published_at"].to_pydatetime(),
            }
        )
    positive_rows = [
        row
        for row in rows
        if row["gain"] > 0
        and str(row["object_id"]) in positives.get(str(row["user_key"]), set())
    ]
    item_cf = WeightedItemCF().fit(
        {"user_key": row["user_key"], "object_id": row["object_id"], "weight": row["gain"]}
        for row in positive_rows
    )
    content = TfidfContentRecall().fit(item_rows)
    vector = FlatInnerProductRecall(object_ids, object_embeddings)
    popular = time_decay_popularity(
        ({"object_id": row["object_id"], "event_type": row["event_type"], "event_time": row["event_time"].to_pydatetime()} for row in positive_rows),
        now,
        limit=300,
    )
    new_hits = newest(item_rows, limit=300)
    histories: dict[str, list[tuple[str, float]]] = defaultdict(list)
    item_by_id = {str(row["object_id"]): row for row in item_rows}
    embedding_by_id = {object_id: object_embeddings[index] for index, object_id in enumerate(object_ids)}
    for row in positive_rows:
        histories[str(row["user_key"])].append((str(row["object_id"]), float(row["gain"])))
    content_hits = content.recall_many(histories, 300)
    result = {}
    import torch

    with torch.no_grad():
        for user_key, history in histories.items():
            if user_key not in user_vocabulary:
                continue
            user_vector = model.encode_users(torch.tensor([user_vocabulary[user_key]], device="cuda"))[0].cpu().numpy()
            channels = {
                "POPULARITY": popular,
                "NEW": new_hits,
                "ITEM_CF": item_cf.recall(history, 300),
                "TFIDF": content_hits.get(user_key, []),
                "TWO_TOWER": vector.recall(user_vector, 300),
            }
            fused = fuse_channels(channels, limit=300)
            seen = positives.get(user_key, set())
            available = [candidate for candidate in fused if candidate.object_id not in seen]
            maximum_popularity_score = max(
                (candidate.channel_scores.get("POPULARITY", 0.0) for candidate in available),
                default=1.0,
            )
            category_counts = Counter(
                item_by_id[item]["category_code"] for item, weight in history if item in item_by_id and weight > 0
            )
            author_counts = Counter(
                item_by_id[item]["author"] for item, weight in history if item in item_by_id and weight > 0
            )
            price_values = [
                item_by_id[item]["unit_price"] for item, weight in history if item in item_by_id and weight > 0
            ]
            total = max(1, sum(category_counts.values()))
            preferred_price = statistics.fmean(price_values) if price_values else float(items["unit_price"].mean())
            price_std = float(items["unit_price"].astype(float).std()) or 1.0
            rank_features = []
            for candidate in available:
                item = item_by_id[candidate.object_id]
                age_days = max(0.0, (now - item["published_at"]).total_seconds() / 86_400)
                rank_features.append(
                    [
                        candidate.channel_scores.get("POPULARITY", 0.0)
                        / max(maximum_popularity_score, 1e-12),
                        category_counts[item["category_code"]] / total,
                        author_counts[item["author"]] / total,
                        abs(item["unit_price"] - preferred_price) / price_std,
                        math.exp(-age_days / 30.0),
                    ]
                )
            ranking_scores = ranker.predict(np.asarray(rank_features, dtype="float32")) if rank_features else []
            source_by_id = {candidate.object_id: candidate.sources for candidate in available}
            ranked_candidates = sorted(
                zip(available, ranking_scores, strict=True),
                key=lambda pair: (-float(pair[1]), pair[0].object_id),
            )[:50]
            ranked_objects = [
                RankedObject(
                    candidate.object_id,
                    float(ranking_score),
                    str(item_by_id[candidate.object_id]["category_code"]),
                    str(item_by_id[candidate.object_id]["seller_key"]),
                    (now - item_by_id[candidate.object_id]["published_at"]).days <= 30,
                    embedding_by_id[candidate.object_id],
                )
                for candidate, ranking_score in ranked_candidates
            ]
            selected = rerank(ranked_objects, 50, _cosine_similarity)
            result[user_key] = [
                {
                    "object_id": candidate.object_id,
                    "score": candidate.score,
                    "sources": list(source_by_id[candidate.object_id]),
                    "reason_code": _reason_code(source_by_id[candidate.object_id]),
                }
                for candidate in selected
            ]
    return result


def _reason_code(sources):
    if "ITEM_CF" in sources or "TWO_TOWER" in sources:
        return "SIMILAR_ITEM"
    if "TFIDF" in sources:
        return "CATEGORY_AFFINITY"
    if "NEW" in sources:
        return "NEW_EXPLORATION"
    return "POPULAR_OR_NEW"


def _cosine_similarity(left, right):
    denominator = float(np.linalg.norm(left) * np.linalg.norm(right))
    return float(np.dot(left, right) / denominator) if denominator else 0.0


def _positives_by_user(rows):
    result: dict[str, set[str]] = defaultdict(set)
    preference_negatives: dict[str, set[str]] = defaultdict(set)
    for row in rows:
        if bool(row.get("preference_negative", False)):
            preference_negatives[str(row["user_key"])].add(str(row["object_id"]))
        if row["gain"] > 0:
            result[str(row["user_key"])].add(str(row["object_id"]))
    for user_key, object_ids in preference_negatives.items():
        result[user_key].difference_update(object_ids)
    return result


def _dataset_hash(dataset):
    digest = hashlib.sha256()
    for name in ("items.parquet", "personas.parquet", "interactions.parquet", "manifest.json"):
        if not (dataset / name).is_file():
            if name == "personas.parquet":
                continue
            raise ValueError(f"训练数据集缺少文件：{name}")
        digest.update(sha256_file(dataset / name).encode())
    return digest.hexdigest()


def _dependency_hash(project_root):
    digest = hashlib.sha256()
    for name in ("pyproject.toml", "environment.yml", "conda-lock.yml"):
        path = project_root / name
        if path.is_file():
            digest.update(path.read_bytes())
    return digest.hexdigest()


def _model_version(data_hash):
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    try:
        git_hash = subprocess.run(
            ["git", "rev-parse", "--short=8", "HEAD"], check=True, capture_output=True, text=True
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        git_hash = "nogit"
    return f"trade-{timestamp}-{git_hash}-{data_hash[:8]}"


def _seed_everything(seed):
    random.seed(seed)
    np.random.seed(seed)
    import torch

    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)
    torch.use_deterministic_algorithms(True)
