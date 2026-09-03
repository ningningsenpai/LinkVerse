"""双塔和 LambdaRank 的固定搜索空间。"""

from __future__ import annotations

from collections.abc import Callable


def tune_two_tower(objective: Callable[[dict[str, object]], float], trials: int = 30, seed: int = 20260903):
    """以 NDCG@20 最大化双塔结构、优化器和负采样参数。"""

    import optuna

    def wrapped(trial):
        parameters = {
            "embedding_dim": trial.suggest_categorical("embedding_dim", [32, 64, 96, 128]),
            "hidden_dims": trial.suggest_categorical("hidden_dims", ["128", "256-128", "256-128-64"]),
            "learning_rate": trial.suggest_float("learning_rate", 1e-4, 3e-3, log=True),
            "weight_decay": trial.suggest_float("weight_decay", 1e-7, 1e-3, log=True),
            "batch_size": trial.suggest_categorical("batch_size", [512, 1024, 2048]),
            "dropout": trial.suggest_float("dropout", 0.0, 0.4),
            "negative_count": trial.suggest_categorical("negative_count", [4, 8, 16, 32]),
            "temperature": trial.suggest_float("temperature", 0.03, 0.3, log=True),
            "max_epochs": 40,
            "patience": 5,
        }
        return objective(parameters)

    sampler = optuna.samplers.TPESampler(seed=seed)
    study = optuna.create_study(direction="maximize", sampler=sampler)
    study.optimize(wrapped, n_trials=trials)
    return study


def tune_lambda_rank(objective: Callable[[dict[str, object]], float], trials: int = 50, seed: int = 20260903):
    """在 CPU 上以 NDCG@20 最大化树模型超参数。"""

    import optuna

    def wrapped(trial):
        parameters = {
            "num_leaves": trial.suggest_int("num_leaves", 15, 127),
            "learning_rate": trial.suggest_float("learning_rate", 0.01, 0.2, log=True),
            "min_child_samples": trial.suggest_int("min_child_samples", 10, 100),
            "feature_fraction": trial.suggest_float("feature_fraction", 0.6, 1.0),
            "bagging_fraction": trial.suggest_float("bagging_fraction", 0.6, 1.0),
            "lambda_l1": trial.suggest_float("lambda_l1", 1e-8, 10.0, log=True),
            "lambda_l2": trial.suggest_float("lambda_l2", 1e-8, 10.0, log=True),
        }
        return objective(parameters)

    sampler = optuna.samplers.TPESampler(seed=seed)
    study = optuna.create_study(direction="maximize", sampler=sampler)
    study.optimize(wrapped, n_trials=trials)
    return study
