"""LightGBM LambdaRank 精排训练与推断。"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from pathlib import Path


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

    def fit(self, features, labels, groups, validation=None) -> "LambdaRanker":
        import lightgbm as lgb

        parameters = {
            "objective": "lambdarank",
            "metric": "ndcg",
            "ndcg_eval_at": [20],
            "verbosity": -1,
            "num_threads": -1,
            **asdict(self.config),
        }
        train = lgb.Dataset(features, label=labels, group=groups)
        valid_sets = [train]
        callbacks = []
        if validation is not None:
            valid_features, valid_labels, valid_groups = validation
            valid_sets.append(lgb.Dataset(valid_features, label=valid_labels, group=valid_groups, reference=train))
            callbacks.append(lgb.early_stopping(50, verbose=False))
        self.booster = lgb.train(parameters, train, num_boost_round=1000, valid_sets=valid_sets, callbacks=callbacks)
        return self

    def predict(self, features):
        if self.booster is None:
            raise RuntimeError("LambdaRank 模型尚未训练")
        return self.booster.predict(features)

    def save(self, path: Path) -> None:
        if self.booster is None:
            raise RuntimeError("LambdaRank 模型尚未训练")
        self.booster.save_model(str(path))
