"""候选模型训练命令入口。"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from linkverse_recommendation.training.trainer import train_trade_model


def main() -> None:
    parser = argparse.ArgumentParser(description="训练 Trade 推荐候选模型")
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--two-tower-trials", type=int, default=30)
    parser.add_argument("--ranker-trials", type=int, default=50)
    parser.add_argument("--include-feedback", action="store_true")
    parser.add_argument("--require-cuda", action="store_true", default=True)
    arguments = parser.parse_args()
    result = train_trade_model(
        arguments.dataset,
        arguments.output,
        arguments.two_tower_trials,
        arguments.ranker_trials,
        arguments.include_feedback,
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
