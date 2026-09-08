"""以冻结配置执行可留档的基线、训练与反馈重训。"""

from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

from linkverse_recommendation.training.evaluation import evaluate_models
from linkverse_recommendation.training.recording import RunRecorder, record, write_json, metric_scope


def execute(config_path: Path) -> Path:
    config = json.loads(config_path.read_text(encoding="utf-8"))
    root = Path(__file__).resolve().parents[5]
    run = Path(config["output"])
    with RunRecorder(run, config, root):
        dataset = Path(config["dataset"])
        if config["operation"] == "baseline":
            result = evaluate_models(dataset, {key: Path(value) for key, value in config["bundles"].items()}, run,
                                     Path(config["split_manifest"]) if config.get("split_manifest") else None)
        elif config["operation"] == "train":
            from linkverse_recommendation.training.trainer import train_trade_model
            split_path = run / "split-manifest.json"
            if config.get("split_manifest"):
                shutil.copyfile(config["split_manifest"], split_path)
            with metric_scope(phase="before"):
                before = train_trade_model(dataset, Path(config["model_root"]) / "before", config["two_tower_trials"], config["ranker_trials"], False, split_manifest_path=split_path)
            write_json(run / "before-training.json", before)
            with metric_scope(phase="after"):
                after = train_trade_model(dataset, Path(config["model_root"]) / "after", config["two_tower_trials"], config["ranker_trials"], True, tower_parameters=before["two_tower_best_params"], ranker_parameters=before["lambda_rank_best_params"], split_manifest_path=split_path)
            write_json(run / "after-training.json", after)
            result = evaluate_models(dataset, {"before": Path(before["bundle"]), "after": Path(after["bundle"])}, run, split_path=split_path)
        else:
            raise ValueError("未知的迭代操作")
        write_json(run / "result.json", result)
    return run


def main():
    parser = argparse.ArgumentParser(description="执行并记录推荐迭代")
    parser.add_argument("--config", type=Path, required=True)
    arguments = parser.parse_args()
    print(execute(arguments.config))


if __name__ == "__main__":
    main()
