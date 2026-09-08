"""记录固定的进程内压力与三次重复测试，和 HTTP 压测独立归档。"""

import argparse
import json
from pathlib import Path

from linkverse_recommendation.training.recording import RunRecorder, metric_scope, write_json
from linkverse_recommendation.training.serving_benchmark import benchmark_loaded_model


def main():
    parser = argparse.ArgumentParser(description="执行有过程记录的进程内推断基准")
    parser.add_argument("--config", required=True, type=Path)
    config = json.loads(parser.parse_args().config.read_text(encoding="utf-8"))
    output = Path(config["output"])
    results = []
    with RunRecorder(output, config, Path(__file__).resolve().parents[5]):
        for index, calls in enumerate((400, 2000, 2000, 2000)):
            with metric_scope(repeat=index):
                result = benchmark_loaded_model(Path(config["bundle"]), concurrency=20, calls=calls, candidate_count=300)
            results.append(result)
            write_json(output / f"repeat-{index}.json", result)
            print(json.dumps(result, ensure_ascii=False), flush=True)
        write_json(output / "summary.json", results)


if __name__ == "__main__":
    main()
