import math

import numpy as np
import pytest

from linkverse_recommendation.core.metrics import grouped_tie_aware_ndcg
from linkverse_recommendation.pipeline.ranking.lambda_rank import LambdaRankConfig, LambdaRanker


def test_equal_scores_do_not_reward_positive_first_position():
    expected = sum(1 / math.log2(index + 2) for index in range(5)) / 5
    for position in range(5):
        labels = np.zeros(5)
        labels[position] = 7
        assert grouped_tie_aware_ndcg(np.zeros(5), labels, [5], 20) == pytest.approx(expected)
        assert grouped_tie_aware_ndcg(np.zeros(5), labels, [5], 1) == pytest.approx(.2)


def test_ties_across_cutoff_and_variable_groups_use_expected_gain():
    assert grouped_tie_aware_ndcg([2, 1, 1], [0, 7, 0], [3], 2) == pytest.approx(.5 / math.log2(3))
    assert grouped_tie_aware_ndcg([3, 1, 2, 1, 0], [7, 0, 7, 0, 0], [2, 3], 20) == 1
    with pytest.raises(ValueError, match="分组"):
        grouped_tie_aware_ndcg([1], [7], [2])


def test_lightgbm_early_stopping_uses_tie_aware_validation():
    features = np.zeros((100, 5), dtype="float32")
    labels = np.tile([7, 0, 0, 0, 0], 20)
    groups = [5] * 20
    model = LambdaRanker(LambdaRankConfig()).fit(features, labels, groups, validation=(features, labels, groups), rounds=3)
    score = model.booster.best_score["valid_1"]["tie_aware_ndcg@20"]
    assert score == pytest.approx(sum(1 / math.log2(index + 2) for index in range(5)) / 5)
    assert score < 1
