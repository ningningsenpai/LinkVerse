"""对比上一轮归档实现，核对旧包响应不受新增特征能力影响。"""
import importlib.util
import json
import sys
import zipfile
from pathlib import Path

from linkverse_recommendation.serving.registry import LoadedTradeModel
from linkverse_recommendation.training.recording import RunRecorder, record, write_json

root = Path.cwd()
cache = root / ".cache/recommendation-ranker-20260908"
config = json.loads((cache / "formal-config.json").read_text(encoding="utf-8"))
archive = root / "linkverse-platform/recommendation-experiments/runs/trade-formal-20260906T060941Z/raw/source.zip"
module_file = cache / "archived-registry.py"
with zipfile.ZipFile(archive) as source:
    module_file.write_bytes(source.read("linkverse-platform/recommendation/src/linkverse_recommendation/serving/registry.py"))
spec = importlib.util.spec_from_file_location("archived_registry", module_file)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
run = root / "linkverse-platform/recommendation-experiments/runs/trade-ranker-compatibility-20260908"
with RunRecorder(run, {"bundle": config["base_bundle"], "archived_source": str(archive), "resource_monitor": False}, root):
    old, new = module.LoadedTradeModel(Path(config["base_bundle"])), LoadedTradeModel(Path(config["base_bundle"]))
    users = sorted(new.user_vocabulary)[:10] + [f"compatibility-cold-{i}" for i in range(5)]
    count, failed = 0, 0
    for i, user in enumerate(users):
        for scene in ("HOME", "DETAIL", "CART"):
            for limit in (20, 100, 300):
                context = {"object_id": new.object_ids[i], "recent_positive_ids": new.object_ids[i + 1],
                           "exclude_ids": ",".join(new.object_ids[i + 2:i + 5])}
                left = old.recommend(user, limit, scene=scene, context=context)
                right = new.recommend(user, limit, scene=scene, context=context)
                serialize = lambda items: [(x.object_id, x.score, x.sources, x.reason_code) for x in items]
                matched = serialize(left) == serialize(right)
                count += 1
                failed += not matched
                record("cases", scene=scene, count=limit, matched=matched)
    result = {"cases": count, "failed": failed, "passed": failed == 0}
    write_json(run / "summary.json", result)
    print(json.dumps(result))
    if failed:
        raise RuntimeError("旧模型响应兼容性验证失败")
