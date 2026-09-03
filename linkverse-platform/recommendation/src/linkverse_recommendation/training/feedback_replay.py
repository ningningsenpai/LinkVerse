"""通过 Gateway 执行推荐反馈、下单、Mock 支付和退款的真实 HTTP 回放。"""

from __future__ import annotations

import argparse
import json
import time
import uuid
from pathlib import Path

import httpx


def replay(base_url: str, cases: list[dict[str, object]], output: Path) -> dict[str, object]:
    """对每名画像至少三个会话执行 Top20 请求，并保存逐请求响应与耗时。"""

    records = []
    refund_executed = False
    with httpx.Client(base_url=base_url, timeout=10.0) as client:
        for case in cases:
            token = str(case["access_token"])
            headers = {"Authorization": f"Bearer {token}"}
            targets = {int(value) for value in case.get("target_listing_ids", [])}
            for session_index in range(3):
                session_id = f"{case['user_key'][:16]}-{session_index}-{uuid.uuid4().hex[:8]}"
                started = time.perf_counter()
                response = client.get(
                    "/api/v1/recommendations/listings",
                    params={"scene": "HOME", "limit": 20},
                    headers=headers,
                )
                latency_ms = (time.perf_counter() - started) * 1000
                response.raise_for_status()
                payload = response.json()
                records.append(
                    {
                        "step": "recommend",
                        "user_key": case["user_key"],
                        "session_id": session_id,
                        "status": response.status_code,
                        "latency_ms": latency_ms,
                        "response": payload,
                    }
                )
                for item in payload["items"]:
                    _event(client, headers, item, "IMPRESSION", session_id, records, case["user_key"])
                matched = next((item for item in payload["items"] if int(item["listing_id"]) in targets), None)
                if matched is None:
                    continue
                _event(client, headers, matched, "DETAIL_OPEN", session_id, records, case["user_key"])
                cart = client.put(
                    f"/api/v1/cart/items/{matched['listing_id']}",
                    headers=headers,
                    json={"recommendation_delivery_id": matched["recommendation_delivery_id"]},
                )
                cart.raise_for_status()
                order = client.post(
                    "/api/v1/orders",
                    headers={**headers, "Idempotency-Key": f"rec-{uuid.uuid4().hex}"},
                    json={
                        "listing_id": matched["listing_id"],
                        "quantity": 1,
                        "recommendation_delivery_id": matched["recommendation_delivery_id"],
                    },
                )
                order.raise_for_status()
                order_payload = order.json()
                payment = client.put(
                    f"/api/v1/orders/{order_payload['order_no']}/payment-intent",
                    headers={**headers, "Idempotency-Key": f"pay-{uuid.uuid4().hex}"},
                )
                payment.raise_for_status()
                intent = payment.json()
                confirmed = client.post(
                    f"/api/v1/mock-provider/payment-intents/{intent['intent_no']}/confirm",
                    headers={**headers, "Idempotency-Key": f"confirm-{uuid.uuid4().hex}"},
                )
                confirmed.raise_for_status()
                records.append(
                    {
                        "step": "conversion",
                        "user_key": case["user_key"],
                        "session_id": session_id,
                        "listing_id": matched["listing_id"],
                        "order": order_payload,
                        "payment": confirmed.json(),
                    }
                )
                if not refund_executed and case.get("refund_reason_code") in {"USER_RETURN", "QUALITY_ISSUE"}:
                    refunded = client.post(
                        f"/api/v1/mock-provider/payment-intents/{intent['intent_no']}/refund",
                        headers=headers,
                        json={"reason_code": case["refund_reason_code"]},
                    )
                    refunded.raise_for_status()
                    records.append(
                        {
                            "step": "refund",
                            "user_key": case["user_key"],
                            "session_id": session_id,
                            "reason_code": case["refund_reason_code"],
                            "response": refunded.json(),
                        }
                    )
                    refund_executed = True
    if len([record for record in records if record["step"] == "recommend"]) < 15:
        raise RuntimeError("回放未达到 5 名用户、每人 3 个会话的最低要求")
    output.parent.mkdir(parents=True, exist_ok=True)
    result = {"refund_executed": refund_executed, "records": records}
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return result


def _event(client, headers, item, action, session_id, records, user_key):
    started = time.perf_counter()
    response = client.post(
        "/api/v1/recommendation-events",
        headers=headers,
        json={
            "recommendation_delivery_id": item["recommendation_delivery_id"],
            "listing_id": item["listing_id"],
            "action": action,
            "session_id": session_id,
        },
    )
    latency_ms = (time.perf_counter() - started) * 1000
    response.raise_for_status()
    records.append(
        {
            "step": action.lower(),
            "user_key": user_key,
            "session_id": session_id,
            "listing_id": item["listing_id"],
            "status": response.status_code,
            "latency_ms": latency_ms,
            "event_id": response.json()["event_id"],
        }
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="回放 Trade 推荐 HTTP 反馈闭环")
    parser.add_argument("--base-url", default="http://127.0.0.1:18080")
    parser.add_argument("--cases", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    cases = json.loads(arguments.cases.read_text(encoding="utf-8"))
    print(json.dumps(replay(arguments.base_url, cases, arguments.output), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
