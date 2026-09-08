import json
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import faiss
import jwt
import numpy as np
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import HTTPException

from linkverse_recommendation.api.security import ServiceTokenVerifier
from linkverse_recommendation.core.model_bundle import REQUIRED_FILES, validate_bundle, write_manifest
from linkverse_recommendation.observability.metrics import RecommendationMetrics
from linkverse_recommendation.serving.activate import activate
from linkverse_recommendation.serving.registry import ModelRegistry
from linkverse_recommendation.training.serving_benchmark import benchmark_loaded_model


def _write_bundle(root, version, first_object="a"):
    bundle = root / version
    bundle.mkdir()
    user_vocabulary = {"u": 1}
    object_vocabulary = {"a": 1, "b": 2}
    object_ids = ["a", "b"]
    (bundle / "two_tower.pt").write_bytes(b"registered-state-dict")
    (bundle / "user-vocabulary.json").write_text(json.dumps(user_vocabulary), encoding="utf-8")
    (bundle / "object-vocabulary.json").write_text(
        json.dumps(object_vocabulary), encoding="utf-8"
    )
    (bundle / "object-ids.json").write_text(json.dumps(object_ids), encoding="utf-8")
    np.save(
        bundle / "user-embeddings.npy",
        np.asarray([[0.0, 0.0], [1.0, 0.0]], dtype="float32"),
        allow_pickle=False,
    )
    index = faiss.IndexFlatIP(2)
    index.add(np.asarray([[1.0, 0.0], [0.0, 1.0]], dtype="float32"))
    faiss.write_index(index, str(bundle / "index.faiss"))
    (bundle / "popular.json").write_text(json.dumps(["a", "b"]), encoding="utf-8")
    (bundle / "hybrid-candidates.json").write_text(
        json.dumps(
            {
                "u": [
                    {
                        "object_id": first_object,
                        "score": 2.0,
                        "sources": ["ITEM_CF"],
                        "reason_code": "SIMILAR_ITEM",
                    }
                ]
            }
        ),
        encoding="utf-8",
    )
    (bundle / "lambda-rank.txt").write_text("tree", encoding="utf-8")
    (bundle / "feature-schema.json").write_text('{"version": 1}', encoding="utf-8")
    (bundle / "scaler.json").write_text('{"type": "standard"}', encoding="utf-8")
    (bundle / "metrics.json").write_text('{"ndcg_at_20": 0.1}', encoding="utf-8")
    assert {path.name for path in bundle.iterdir()} == REQUIRED_FILES
    write_manifest(bundle, version, "data-hash", "lock-hash")
    return bundle


def test_bundle_rejects_tampered_weight_vocabulary_and_index(tmp_path):
    bundle = _write_bundle(tmp_path, "trade-valid")
    validate_bundle(bundle)

    for name, replacement in (
        ("two_tower.pt", b"tampered"),
        ("object-vocabulary.json", b'{"a": 2, "b": 1}'),
        ("index.faiss", b"invalid-index"),
    ):
        original = (bundle / name).read_bytes()
        (bundle / name).write_bytes(replacement)
        with pytest.raises(ValueError, match="哈希不匹配"):
            validate_bundle(bundle)
        (bundle / name).write_bytes(original)

    (bundle / "object-ids.json").write_text(json.dumps(["b", "a"]), encoding="utf-8")
    write_manifest(bundle, "trade-valid", "data-hash", "lock-hash")
    with pytest.raises(ValueError, match="对象词表与索引 ID 不一致"):
        validate_bundle(bundle)


def test_registry_atomically_switches_model_and_does_not_return_duplicates(tmp_path):
    first = _write_bundle(tmp_path, "trade-first", "a")
    second = _write_bundle(tmp_path, "trade-second", "b")
    registry = ModelRegistry(tmp_path)

    activate(tmp_path, first)
    assert registry.refresh()
    version, candidates = registry.recommend("u", 2)
    assert version == "trade-first"
    assert [item.object_id for item in candidates] == ["a", "b"]

    activate(tmp_path, second)
    with ThreadPoolExecutor(max_workers=8) as executor:
        results = list(executor.map(lambda _: registry.recommend("u", 2), range(32)))
    assert all(version == "trade-second" for version, _ in results)
    assert all(len({item.object_id for item in candidates}) == 2 for _, candidates in results)


def test_registry_keeps_previous_model_when_new_bundle_is_invalid(tmp_path):
    first = _write_bundle(tmp_path, "trade-first")
    broken = _write_bundle(tmp_path, "trade-broken")
    registry = ModelRegistry(tmp_path)
    activate(tmp_path, first)
    registry.refresh()
    (broken / "index.faiss").write_bytes(b"invalid")
    (tmp_path / "active-model.json").write_text(
        json.dumps({"model_version": "trade-broken", "path": "trade-broken"}),
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="哈希不匹配"):
        registry.refresh()
    assert registry.model_version == "trade-first"
    for _ in range(3):
        version, candidates = registry.recommend("u", 2)
        assert version == "trade-first"
        assert len(candidates) == 2


def test_loaded_model_benchmark_records_scope_and_thresholds(tmp_path):
    bundle = _write_bundle(tmp_path, "trade-benchmark")

    result = benchmark_loaded_model(bundle, concurrency=1, calls=2, candidate_count=2)

    assert result["scope"] == "PYTHON_LOADED_MODEL_CPU_IN_PROCESS"
    assert result["http_executed"] is False
    assert result["success_rate"] == 1.0
    assert result["failure_count"] == 0
    assert result["latency_ms"]["p95"] >= 0


@pytest.mark.parametrize("scope", ["recommendation.internal", ["recommendation.internal"]])
def test_service_token_requires_audience_scope_type_and_client(scope):
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    verifier = ServiceTokenVerifier("https://invalid.example/jwks", "https://issuer.example")
    verifier.jwk_client.get_signing_key_from_jwt = lambda _: SimpleNamespace(
        key=private_key.public_key()
    )
    base_claims = {
        "iss": "https://issuer.example",
        "sub": "linkverse-trade-recommendation",
        "client_id": "linkverse-trade-recommendation",
        "aud": "linkverse-recommendation",
        "scope": scope,
        "token_use": "service",
        "iat": 1_700_000_000,
        "exp": 2_000_000_000,
    }
    token = jwt.encode(base_claims, private_key, algorithm="RS256")
    assert verifier.verify(token)["scope"] == scope

    bad_claims = {**base_claims, "scope": "trade.read"}
    bad_token = jwt.encode(bad_claims, private_key, algorithm="RS256")
    with pytest.raises(HTTPException) as exception:
        verifier.verify(bad_token)
    assert exception.value.status_code == 403


def test_metrics_only_emit_bounded_domain_and_outcome_labels():
    metrics = RecommendationMetrics()
    metrics.observe_request("trade", "success", 0.02)
    metrics.observe_request("private-user-key", "request-123", 0.5)
    metrics.record_model_load_failure()

    rendered = metrics.render()

    assert 'domain="trade",outcome="success"' in rendered
    assert 'domain="unknown",outcome="unknown"' in rendered
    assert "private-user-key" not in rendered
    assert "request-123" not in rendered
    assert "linkverse_recommendation_model_load_failures_total 1" in rendered


def test_candidate_activation_requires_evidence_bound_to_the_bundle(tmp_path):
    from linkverse_recommendation.core.model_bundle import sha256_file

    bundle = _write_bundle(tmp_path, "trade-gated")
    activate(tmp_path, bundle)
    original = (tmp_path / "active-model.json").read_bytes()
    with pytest.raises(ValueError, match="需要质量与运行门禁证据"):
        activate(tmp_path, bundle, "REGRESSION_CANDIDATE")
    evidence = tmp_path / "gate.json"
    gate = {"status": "PASS", "manifest_sha256": "other-model", "checks": {key: True for key in
            ("offline_quality", "data_isolation", "tests", "http_validity", "latency", "rollback")}}
    evidence.write_text(json.dumps(gate), encoding="utf-8")
    with pytest.raises(ValueError, match="证据不属于"):
        activate(tmp_path, bundle, "REGRESSION_CANDIDATE", evidence)
    assert (tmp_path / "active-model.json").read_bytes() == original
    gate["manifest_sha256"] = sha256_file(bundle / "manifest.json")
    evidence.write_text(json.dumps(gate), encoding="utf-8")
    activate(tmp_path, bundle, "REGRESSION_CANDIDATE", evidence)
    with pytest.raises(ValueError, match="自然流量候选"):
        activate(tmp_path, bundle, "NATURAL_CANDIDATE", evidence)


@pytest.mark.parametrize("feature_version", [2, 3])
def test_schema_two_enforces_final_quota_after_vector_and_popularity_fill(tmp_path, feature_version):
    from collections import Counter
    from linkverse_recommendation.pipeline.ranking.features import empty_state, HISTORY_FEATURES, RECALL_FEATURES
    from linkverse_recommendation.pipeline.ranking.lambda_rank import LambdaRankConfig, LambdaRanker
    from linkverse_recommendation.serving.registry import LoadedTradeModel

    bundle = _write_bundle(tmp_path, "trade-current")
    ids = ["a", "b", *(str(index) for index in range(2, 80))]
    (bundle / "object-ids.json").write_text(json.dumps(ids), encoding="utf-8")
    (bundle / "object-vocabulary.json").write_text(json.dumps({item: index + 1 for index, item in enumerate(ids)}), encoding="utf-8")
    vectors = np.random.default_rng(7).normal(size=(80, 2)).astype("float32")
    faiss.normalize_L2(vectors)
    index = faiss.IndexFlatIP(2)
    index.add(vectors)
    faiss.write_index(index, str(bundle / "index.faiss"))
    state = empty_state({item: {"category_code": str(index % 5), "seller_key": str(index % 11), "author": "作者", "unit_price": 20, "published_at": "2026-09-01T00:00:00+00:00"} for index, item in enumerate(ids)})
    state["fit_cutoff"] = "2026-09-05T00:00:00+00:00"
    names = RECALL_FEATURES if feature_version == 3 else HISTORY_FEATURES
    schema = {"version": feature_version, "ranker_features": names,
              "mmr_score_policy": "percentile" if feature_version == 3 else "raw",
              "mmr_score_scale": .25 if feature_version == 3 else 1}
    (bundle / "feature-schema.json").write_text(json.dumps(schema), encoding="utf-8")
    (bundle / "serving-state.json").write_text(json.dumps(state), encoding="utf-8")
    LambdaRanker(LambdaRankConfig(min_child_samples=2)).fit(np.random.default_rng(7).random((80, len(names))), np.arange(80) % 3, [5] * 16, rounds=5).save(bundle / "lambda-rank.txt")
    write_manifest(bundle, "trade-current", "data-hash", "lock-hash")
    model = LoadedTradeModel(bundle)
    for user in ("u", "unknown"):
        candidates = model.recommend(user, 50, context={"exclude_ids": "a,b"})
        assert len(candidates) == 50
        assert not {"a", "b"} & {item.object_id for item in candidates}
        assert max(Counter(state["items"][item.object_id]["category_code"] for item in candidates[:20]).values()) <= 6
        assert max(Counter(state["items"][item.object_id]["seller_key"] for item in candidates[:20]).values()) <= 3
        assert len({item.object_id for item in candidates}) == 50
        ids, raw, features, now = model.candidate_features(user, 50, context={"exclude_ids": "a,b"})
        replay = model.rank_candidates(ids, raw, model.ranker.predict(features, num_threads=1), now, 50, model.score_policy, model.score_scale)
        assert replay == candidates
        for invalid_scale in (0, float("nan"), 1.01):
            with pytest.raises(ValueError, match="缩放"):
                model.rank_candidates(ids, raw, np.zeros(len(ids)), now, 50, "raw", invalid_scale)
    if feature_version == 3:
        schema["ranker_features"] = list(reversed(names))
        (bundle / "feature-schema.json").write_text(json.dumps(schema), encoding="utf-8")
        write_manifest(bundle, "trade-current", "data-hash", "lock-hash")
        with pytest.raises(ValueError, match="特征顺序"):
            LoadedTradeModel(bundle)
