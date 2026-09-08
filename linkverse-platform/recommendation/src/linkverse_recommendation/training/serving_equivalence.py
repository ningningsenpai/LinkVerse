"""在同一冻结模型上核对矩阵重排与原默认重排的完整响应一致性。"""

import argparse
import json
import hashlib
from pathlib import Path
from datetime import datetime, timezone

from linkverse_recommendation.pipeline.reranking.mmr import rerank
from linkverse_recommendation.serving import registry
from linkverse_recommendation.training.recording import RunRecorder, record, write_json


def compare_http(config):
    import httpx
    from linkverse_recommendation.training.local_validation import service_token

    bundle = Path(config["bundle"])
    vocabulary = json.loads((bundle / "user-vocabulary.json").read_text(encoding="utf-8"))
    objects = json.loads((bundle / "object-ids.json").read_text(encoding="utf-8"))
    expected_version = json.loads((bundle / "manifest.json").read_text(encoding="utf-8"))["model_version"]
    users = [(key, "known") for key in sorted(vocabulary)[:10]] + [(hashlib.sha256(f"equivalence-cold-{index}".encode()).hexdigest(), "cold") for index in range(5)]
    headers = {"Authorization": f"Bearer {service_token()}"}
    cases, failures = 0, 0
    with httpx.Client(timeout=5, trust_env=False) as client:
        for index, (key, cohort) in enumerate(users):
            for scene in ("HOME", "DETAIL", "CART"):
                payload = {"request_id": f"equivalence-{index}-{scene}", "user_key": key, "domain": "trade", "scene": scene,
                           "occurred_at": datetime.now(timezone.utc).isoformat(), "candidate_count": 300,
                           "context": {"object_id": objects[index % len(objects)], "recent_positive_ids": objects[(index + 1) % len(objects)]}}
                responses = [client.post(f"http://127.0.0.1:{port}/internal/v1/recommendations", headers=headers, json=payload) for port in config["http_ports"]]
                bodies = [response.json() for response in responses]
                same = all(response.status_code == 200 for response in responses) and all(body.get("model_version") == expected_version for body in bodies) and bodies[0] == bodies[1]
                failures += not same
                cases += 1
                record("equivalence-cases", cohort=cohort, scene=scene, matched=same, statuses=[response.status_code for response in responses])
    return {"passed": failures == 0, "cases": cases, "failures": failures, "model_version": expected_version,
            "scope": "对相同请求逐项比较基线与优化容器的完整 HTTP JSON 响应。"}


def main():
    parser = argparse.ArgumentParser(description="验证在线重排优化的响应等价性")
    parser.add_argument("--config", required=True, type=Path)
    config = json.loads(parser.parse_args().config.read_text(encoding="utf-8"))
    output = Path(config["output"])
    with RunRecorder(output, config, Path(__file__).resolve().parents[5]):
        if config.get("http_ports"):
            result = compare_http(config)
            write_json(output / "summary.json", result)
            print(json.dumps(result, ensure_ascii=False))
            if not result["passed"]:
                raise RuntimeError("优化前后 HTTP 响应不等价")
            return
        model = registry.LoadedTradeModel(Path(config["bundle"]))
        optimized = registry.rerank_with_matrix

        def reference(candidates, limit, similarities):
            positions = {item.vector[0]: index for index, item in enumerate(candidates)}
            return rerank(candidates, limit, lambda left, right: float(similarities[positions[left[0]], positions[right[0]]]))

        users = [(key, "known") for key in sorted(model.user_vocabulary)[:10]] + [(f"equivalence-cold-{index}", "cold") for index in range(5)]
        failures, cases = 0, 0
        try:
            for index, (key, cohort) in enumerate(users):
                for scene in ("HOME", "DETAIL", "CART"):
                    context = {"object_id": model.object_ids[index % len(model.object_ids)], "recent_positive_ids": model.object_ids[(index + 1) % len(model.object_ids)]}
                    registry.rerank_with_matrix = reference
                    before = model.recommend(key, 300, scene=scene, context=context)
                    registry.rerank_with_matrix = optimized
                    after = model.recommend(key, 300, scene=scene, context=context)
                    same = before == after
                    failures += not same
                    cases += 1
                    record("equivalence-cases", cohort=cohort, scene=scene, matched=same, returned=len(after))
        finally:
            registry.rerank_with_matrix = optimized
        result = {"passed": failures == 0, "cases": cases, "failures": failures, "model_version": model.model_version,
                  "scope": "相同权重、特征、候选与配额下逐项比较完整响应，包括 ID、分数、来源与原因。"}
        write_json(output / "summary.json", result)
        print(json.dumps(result, ensure_ascii=False))
        if failures:
            raise RuntimeError("在线重排优化的响应不等价")


if __name__ == "__main__":
    main()
