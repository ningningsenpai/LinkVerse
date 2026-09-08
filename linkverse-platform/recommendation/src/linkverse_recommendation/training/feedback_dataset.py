"""按预先规定的三个会话阶段冻结本地反馈窗口并核验退款与曝光成熟度。"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import pandas as pd

from linkverse_recommendation.data.split import fixed_temporal_split
from linkverse_recommendation.training.recording import write_json
from linkverse_recommendation.training.trainer import _normalize_interactions


def audit(snapshot: Path, feedback_run: Path, split_path: Path) -> dict:
    manifest = json.loads((snapshot / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("traffic_origin") != "SCRIPTED":
        raise ValueError("本地会话验证只接受明确标记的 SCRIPTED 快照")
    events = pd.read_parquet(snapshot / "interactions.parquet")
    samples = _normalize_interactions(events, as_of=manifest["exported_at"])
    requests = [json.loads(line) for line in (feedback_run / "raw/http-requests.jsonl").read_text(encoding="utf-8").splitlines()]
    boundaries = [min(row["utc"] for row in requests if row["stage"] == "recommend" and row["session"] == phase) for phase in range(3)]
    if split_path.exists():
        raise ValueError("会话切分文件已存在，请使用新的运行路径")
    write_json(split_path, {"schema_version": 1, "boundaries": boundaries, "end_inclusive": manifest["exported_at"],
                            "purpose": "LOCAL_SCRIPTED_PROTOCOL_REGRESSION", "rule": "旧反馈训练、HOME 验证、DETAIL 反馈、CART 测试；边界来自请求阶段，不由得分决定"})
    split = fixed_temporal_split(samples.to_dict("records"), split_path)
    outcomes = json.loads((feedback_run / "feedback-result.json").read_text(encoding="utf-8"))["outcomes"]
    for outcome in outcomes:
        selected = samples[samples["recommendation_delivery_id"].eq(outcome["delivery_id"])]
        if len(selected) != 1:
            raise ValueError("推荐反馈链没有唯一归因样本")
        row = selected.iloc[0]
        expected_negative = outcome["refund_reason"] == "USER_RETURN"
        expected_gain = 0 if expected_negative else (7 if outcome["refund_reason"] == "LATE_SUCCESS" else 15)
        if bool(row["preference_negative"]) != expected_negative or int(row["gain"]) != expected_gain:
            raise ValueError("支付或退款链标签与真实 HTTP 终态不一致")
    exposures = samples[samples["negative_source"].eq("REAL_EXPOSURE")]
    if exposures.empty or (exposures["label_available_at"] < exposures["event_time"] + pd.Timedelta(minutes=30)).any():
        raise ValueError("缺少成熟曝光证据或曝光标签可用时间不正确")
    result = {"status": "PASS", "traffic_origin": "SCRIPTED", "events": len(events), "samples": len(samples),
              "mature_exposure_negatives": len(exposures), "user_refund_negatives": int(samples["preference_negative"].sum()),
              "verified_conversion_chains": len(outcomes), "missing_delivery_ids": int(events["recommendation_delivery_id"].isna().sum()),
              "split_rows": [len(split.train), len(split.validation), len(split.feedback), len(split.test)],
              "quality_claim_allowed": False, "snapshot": str(snapshot), "split_manifest": str(split_path)}
    write_json(feedback_run / "dataset-audit.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(description="审计真实反馈并冻结会话边界")
    parser.add_argument("--snapshot", required=True, type=Path)
    parser.add_argument("--feedback-run", required=True, type=Path)
    parser.add_argument("--split", required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(audit(args.snapshot, args.feedback_run, args.split), ensure_ascii=False))


if __name__ == "__main__":
    main()
