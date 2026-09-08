"""LightGBM LambdaRank 精排训练与推断。"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from pathlib import Path
from linkverse_recommendation.training.recording import record
from linkverse_recommendation.core.metrics import grouped_tie_aware_ndcg


@dataclass(frozen=True, slots=True)
class LambdaRankConfig:
    """CPU 精排器的受控超参数。"""

    num_leaves: int = 31
    learning_rate: float = 0.05
    min_child_samples: int = 20
    feature_fraction: float = 0.9
    bagging_fraction: float = 0.9
    lambda_l1: float = 0.0
    lambda_l2: float = 0.1


class LambdaRanker:
    """封装按请求分组的 LambdaRank 训练和模型持久化。"""

    def __init__(self, config: LambdaRankConfig) -> None:
        self.config = config
        self.booster = None

    def fit(self, features, labels, groups, validation=None, rounds=1000, seed=20260903) -> "LambdaRanker":
        import lightgbm as lgb

        parameters = {
            "objective": "lambdarank",
            "metric": "None",
            "verbosity": -1,
            "num_threads": 8,
            "deterministic": True,
            "force_col_wise": True,
            "seed": seed,
            **asdict(self.config),
        }
        train = lgb.Dataset(features, label=labels, group=groups)
        valid_sets = [train]
        def capture(environment):
            record("ranker_epochs", epoch=environment.iteration + 1,
                   metrics={f"{entry[0]}/{entry[1]}": entry[2] for entry in environment.evaluation_result_list})
        callbacks = [capture]
        def evaluate(predictions, dataset):
            return "tie_aware_ndcg@20", grouped_tie_aware_ndcg(predictions, dataset.get_label(), dataset.get_group(), 20), True
        if validation is not None:
            valid_features, valid_labels, valid_groups = validation
            valid_sets.append(lgb.Dataset(valid_features, label=valid_labels, group=valid_groups, reference=train))
            callbacks.append(lgb.early_stopping(50, verbose=False))
        self.booster = lgb.train(parameters, train, num_boost_round=rounds, valid_sets=valid_sets, callbacks=callbacks, feval=evaluate)
        return self

    def predict(self, features):
        if self.booster is None:
            raise RuntimeError("LambdaRank 模型尚未训练")
        return self.booster.predict(features, num_threads=1)

    def save(self, path: Path) -> None:
        if self.booster is None:
            raise RuntimeError("LambdaRank 模型尚未训练")
        self.booster.save_model(str(path))
