from collections import Counter

from linkverse_recommendation.pipeline.fusion import fuse_channels
from linkverse_recommendation.pipeline.recall.base import RecallHit
from linkverse_recommendation.pipeline.recall.content import TfidfContentRecall
from linkverse_recommendation.pipeline.recall.item_cf import WeightedItemCF
from linkverse_recommendation.pipeline.reranking.mmr import RankedObject, rerank


def test_fusion_preserves_channel_scores_ranks_and_sources():
    result = fuse_channels(
        {
            "POPULARITY": [RecallHit("a", "POPULARITY", 10.0, 1), RecallHit("b", "POPULARITY", 5.0, 2)],
            "ITEM_CF": [RecallHit("b", "ITEM_CF", 0.9, 1)],
        }
    )

    candidate = next(item for item in result if item.object_id == "b")
    assert candidate.sources == ("ITEM_CF", "POPULARITY")
    assert candidate.channel_scores == {"POPULARITY": 5.0, "ITEM_CF": 0.9}
    assert candidate.channel_ranks == {"POPULARITY": 2, "ITEM_CF": 1}


def test_item_cf_excludes_seen_positive_objects():
    model = WeightedItemCF().fit(
        [
            {"user_key": "u1", "object_id": "a", "weight": 1},
            {"user_key": "u1", "object_id": "b", "weight": 1},
            {"user_key": "u2", "object_id": "a", "weight": 1},
            {"user_key": "u2", "object_id": "c", "weight": 1},
        ]
    )

    result = model.recall([("a", 1.0)], 10)

    assert {item.object_id for item in result} == {"b", "c"}
    assert all(item.object_id != "a" for item in result)


def test_tfidf_batch_recall_matches_single_user_and_excludes_history():
    objects = [
        {
            "object_id": "a",
            "title": "Python 入门",
            "author": "甲",
            "description": "编程",
            "category_code": "IT",
        },
        {
            "object_id": "b",
            "title": "Python 实战",
            "author": "乙",
            "description": "编程项目",
            "category_code": "IT",
        },
        {
            "object_id": "c",
            "title": "古典文学",
            "author": "丙",
            "description": "诗词",
            "category_code": "LIT",
        },
    ]
    model = TfidfContentRecall().fit(objects)

    single = model.recall([("a", 1.0)], 2)
    batch = model.recall_many({"u1": [("a", 1.0)]}, 2)["u1"]

    assert [item.object_id for item in batch] == [item.object_id for item in single]
    assert all(item.object_id != "a" for item in batch)


def test_reranking_applies_seller_quota_and_new_exploration():
    candidates = [
        RankedObject("a", 1.0, "c1", "s1", False, [1.0, 0.0]),
        RankedObject("b", 0.9, "c2", "s1", False, [0.9, 0.1]),
        RankedObject("c", 0.8, "c3", "s2", True, [0.0, 1.0]),
    ]

    result = rerank(
        candidates,
        limit=2,
        similarity=lambda left, right: sum(a * b for a, b in zip(left, right, strict=True)),
        seller_quota=1,
        exploration_ratio=0.1,
    )

    assert result[0].object_id == "c"
    assert len({item.seller for item in result}) == 2


def test_reranking_calculates_each_selected_pair_only_once():
    candidates = [
        RankedObject(str(index), 1.0 / index, f"c{index}", f"s{index}", False, [index, 1.0])
        for index in range(1, 8)
    ]
    calls = Counter()

    def similarity(left, right):
        key = tuple(sorted((int(left[0]), int(right[0]))))
        calls[key] += 1
        return 0.0

    rerank(candidates, 5, similarity, exploration_ratio=0.0)

    assert calls
    assert max(calls.values()) == 1


def test_matrix_reranking_preserves_quota_exploration_and_tie_order():
    import numpy as np
    from linkverse_recommendation.pipeline.reranking.mmr import rerank_with_matrix

    generator = np.random.default_rng(20260906)
    for size in (1, 15, 100, 500):
        for tied in (False, True):
            vectors = generator.normal(size=(size, 8)).astype("float32")
            vectors /= np.maximum(np.linalg.norm(vectors, axis=1, keepdims=True), 1e-8)
            similarities = vectors @ vectors.T
            candidates = [RankedObject(str(index), 1.0 if tied else float(generator.normal()),
                                      f"c{index % 6}", f"s{index % 9}", index % 4 == 0, (index,)) for index in range(size)]
            expected = rerank(candidates, 20, lambda left, right: float(similarities[left[0], right[0]]))
            actual = rerank_with_matrix(candidates, 20, similarities)
            assert [item.object_id for item in actual] == [item.object_id for item in expected]
