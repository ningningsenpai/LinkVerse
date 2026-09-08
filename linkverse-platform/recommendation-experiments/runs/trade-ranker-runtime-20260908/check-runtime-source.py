"""通过真实 Identity 服务令牌核对 Linux HTTP、原子换版与回滚。"""
import json
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

import httpx
import numpy as np

from linkverse_recommendation.serving.activate import activate
from linkverse_recommendation.serving.registry import LoadedTradeModel
from linkverse_recommendation.training.local_validation import service_token
from linkverse_recommendation.training.recording import RunRecorder, record, write_json

root = Path.cwd()
config = json.loads((root / ".cache/recommendation-ranker-20260908/formal-config.json").read_text(encoding="utf-8"))
selection = json.loads((root / "linkverse-platform/recommendation-experiments/runs/trade-ranker-calibration-20260908/selection.json").read_text(encoding="utf-8"))
model_root = Path(config["model_root"])
base = model_root / Path(config["base_bundle"]).name
candidate = Path(selection["bundle"])
output = root / "linkverse-platform/recommendation-experiments/runs/trade-ranker-runtime-20260908"
settings = {"before": str(base), "after": str(candidate), "port": 18085, "container_metrics": True,
            "metric_containers": ["linkverse-mvp-recommendation-ranker-20260908"], "purpose": "ISOLATED_LINUX_HTTP_VALIDATION"}


def wait_version(client, version):
    started = time.monotonic()
    while time.monotonic() - started < 30:
        ready = client.get("/health/ready")
        if ready.status_code == 200 and ready.json().get("model_version") == version:
            return time.monotonic() - started
        time.sleep(.2)
    raise RuntimeError("隔离测试容器未在期限内加载预期版本")


with RunRecorder(output, settings, root):
    models = {"before": LoadedTradeModel(base), "after": LoadedTradeModel(candidate)}
    token = service_token()
    headers = {"Authorization": "Bearer " + token}
    counts, failures, swaps = 0, 0, []
    with httpx.Client(base_url="http://127.0.0.1:18085", trust_env=False, timeout=10) as client:
        for name, bundle in (("before", base), ("after", candidate)):
            started = time.monotonic()
            activate(model_root, bundle, "ENGINEERING")
            wait_version(client, models[name].model_version)
            swaps.append({"stage": name, "elapsed_seconds_including_validation": time.monotonic() - started})
            model = models[name]
            users = sorted(model.user_vocabulary)[:10] + [f"runtime-cold-{i}" for i in range(5)]
            for index, user in enumerate(users):
                for scene in ("HOME", "DETAIL", "CART"):
                    for count in (100, 300):
                        now = datetime.now(timezone.utc)
                        context = {"object_id": model.object_ids[index], "recent_positive_ids": model.object_ids[index + 1],
                                   "exclude_ids": ",".join(model.object_ids[index + 2:index + 5])}
                        payload = {"request_id": f"{name}-{index}-{scene}-{count}", "user_key": user, "domain": "trade",
                                   "scene": scene, "candidate_count": count, "context": context, "occurred_at": now.isoformat()}
                        expected = model.recommend(user, count, scene=scene, context=context, occurred_at=now)
                        response = client.post("/internal/v1/recommendations", headers=headers, json=payload)
                        body = response.json()
                        actual = body.get("candidates", [])
                        matched = response.status_code == 200 and body.get("model_version") == model.model_version
                        matched = matched and [(x["object_id"], tuple(x["sources"]), x["reason_code"]) for x in actual] == [(x.object_id, x.sources, x.reason_code) for x in expected]
                        matched = matched and np.allclose([x["score"] for x in actual], [x.score for x in expected], atol=1e-8, rtol=1e-6)
                        counts += 1
                        failures += not matched
                        record("parity", phase=name, cohort="known" if index < 10 else "cold", scene=scene,
                               count=count, matched=bool(matched), status=response.status_code)
        def request(index):
            body = {**payload, "user_key": users[-1], "request_id": f"swap-{index}"}
            response = httpx.post("http://127.0.0.1:18085/internal/v1/recommendations", headers=headers,
                                  json=body, trust_env=False, timeout=10)
            data = response.json()
            ids = [x["object_id"] for x in data.get("candidates", [])]
            return {"status": response.status_code, "model_version": data.get("model_version"),
                    "valid": response.status_code == 200 and len(ids) >= 20 and len(set(ids)) == len(ids)
                    and data.get("model_version") in {m.model_version for m in models.values()}}
        with ThreadPoolExecutor(max_workers=20) as executor:
            pending = [executor.submit(request, i) for i in range(100)]
            began = time.monotonic()
            activate(model_root, base, "ENGINEERING")
            wait_version(client, models["before"].model_version)
            swaps.append({"stage": "rollback_with_requests", "elapsed_seconds_including_validation": time.monotonic() - began})
            outcomes = [future.result() for future in pending]
        for row in outcomes:
            record("concurrent-swap", **row)
        failures += sum(not row["valid"] for row in outcomes)
        activate(model_root, candidate, "ENGINEERING")
        wait_version(client, models["after"].model_version)
        pointer = model_root / "active-model.json"
        good_pointer = json.loads(pointer.read_text(encoding="utf-8"))
        write_json(pointer, {"model_version": "invalid", "path": "../outside-model-root"})
        time.sleep(2)
        invalid_pointer_results = [request(i) for i in range(20)]
        write_json(pointer, good_pointer)
        wait_version(client, models["after"].model_version)
        kept = all(row["valid"] and row["model_version"] == models["after"].model_version for row in invalid_pointer_results)
        failures += not kept
        denied = client.post("/internal/v1/recommendations", headers={"Authorization": "Bearer invalid"}, json=payload)
        failures += denied.status_code != 401
        summary = {"http_parity_cases": counts, "concurrent_swap_requests": len(outcomes),
                   "invalid_pointer_requests": len(invalid_pointer_results), "invalid_pointer_kept_active": kept,
                   "invalid_token_status": denied.status_code, "swaps": swaps, "failures": failures, "passed": failures == 0,
                   "candidate_manifest_sha256": selection["manifest_sha256"]}
        write_json(output / "summary.json", summary)
        print(json.dumps(summary, ensure_ascii=False))
        if failures:
            raise RuntimeError("隔离容器接口或回滚验证失败")
