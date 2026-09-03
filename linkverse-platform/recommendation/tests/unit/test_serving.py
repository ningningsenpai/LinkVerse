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


def test_loaded_model_benchmark_records_scope_and_thresholds(tmp_path):
    bundle = _write_bundle(tmp_path, "trade-benchmark")

    result = benchmark_loaded_model(bundle, concurrency=1, calls=2, candidate_count=2)

    assert result["scope"] == "PYTHON_LOADED_MODEL_CPU_IN_PROCESS"
    assert result["http_executed"] is False
    assert result["success_rate"] == 1.0
    assert result["failure_count"] == 0
    assert result["latency_ms"]["p95"] >= 0


def test_service_token_requires_audience_scope_type_and_client():
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
        "scope": "recommendation.internal",
        "token_use": "service",
        "iat": 1_700_000_000,
        "exp": 2_000_000_000,
    }
    token = jwt.encode(base_claims, private_key, algorithm="RS256")
    assert verifier.verify(token)["scope"] == "recommendation.internal"

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
