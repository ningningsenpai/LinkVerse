"""使用独立夹具执行真实 HTTP 反馈闭环，凭据仅在内存和忽略目录中读取。"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import time
import uuid
from pathlib import Path

import httpx
import jwt

from linkverse_recommendation.training.recording import RunRecorder, record, write_json, metric_scope


def load_users(fixture: dict) -> list[dict]:
    seed = json.loads(Path(fixture["seed_state_path"]).read_text(encoding="utf-8-sig"))
    secret = os.environ["RECOMMENDATION_USER_HMAC_SECRET"].encode()
    users = []
    for user in seed["users"][:20]:
        # JWT 由本地登录流程签发，此处解码只用于匹配测试身份，服务端仍执行完整验签。
        subject = jwt.decode(user["access_token"], options={"verify_signature": False})["sub"]
        users.append({"token": user["access_token"], "id": int(subject),
                      "user_key": hmac.new(secret, str(subject).encode(), hashlib.sha256).hexdigest()})
    return users


def service_token(scope="recommendation.internal") -> str:
    recommendation = scope == "recommendation.internal"
    client_id = os.environ.get("RECOMMENDATION_OAUTH_CLIENT_ID" if recommendation else "TRADE_OAUTH_CLIENT_ID", "linkverse-trade-recommendation" if recommendation else "linkverse-trade")
    secret = os.environ["RECOMMENDATION_OAUTH_CLIENT_SECRET" if recommendation else "TRADE_OAUTH_CLIENT_SECRET"]
    response = httpx.post("http://127.0.0.1:18081/oauth2/token", auth=(client_id, secret), data={"grant_type": "client_credentials", "scope": scope}, timeout=10, trust_env=False)
    response.raise_for_status()
    return response.json()["access_token"]


def send(client, method, path, stage, **kwargs):
    started = time.perf_counter()
    response = client.request(method, path, **kwargs)
    elapsed = (time.perf_counter() - started) * 1000
    try:
        payload = response.json()
    except ValueError:
        payload = {}
    summary = payload if isinstance(payload, dict) else {}
    record("http-requests", stage=stage, status=response.status_code, latency_ms=elapsed,
           model_version=summary.get("model_version"), fallback=summary.get("fallback"),
           business_status=summary.get("status"),
           listing_ids=[item["listing_id"] for item in summary.get("items", [])])
    response.raise_for_status()
    return payload


def replay_feedback(fixture: dict, output: Path, expected_model_version: str | None = None) -> dict:
    users = load_users(fixture)
    payment_token = service_token("payment.internal")
    outcomes = []
    with httpx.Client(base_url="http://127.0.0.1:18080", timeout=10, trust_env=False) as client:
        for session_index, scene in enumerate(("HOME", "DETAIL", "CART")):
            for index, user in enumerate(users[:5]):
                with metric_scope(user_key=user["user_key"], session=session_index, scene=scene):
                    headers = {"Authorization": f"Bearer {user['token']}"}
                    params = {"scene": scene, "limit": 20}
                    if scene != "HOME":
                        params["context_listing_id"] = fixture["first_listing_id"] + index
                    payload = send(client, "GET", "/api/v1/recommendations/listings", "recommend", params=params, headers=headers)
                    if expected_model_version and (payload.get("model_version") != expected_model_version or payload.get("fallback")):
                        raise RuntimeError("反馈采集没有使用预期活动模型，已停止本轮闭环")
                    if len(payload["items"]) != 20:
                        raise RuntimeError("夹具推荐未返回完整 Top20")
                    session = f"rec-{uuid.uuid4().hex}"
                    for item in payload["items"]:
                        send(client, "POST", "/api/v1/recommendation-events", "impression", headers=headers, json={"recommendation_delivery_id": item["recommendation_delivery_id"], "listing_id": item["listing_id"], "action": "IMPRESSION", "session_id": session})
                    eligible = [item for item in payload["items"] if fixture["first_listing_id"] <= item["listing_id"] <= fixture["last_listing_id"]]
                    if not eligible:
                        raise RuntimeError("推荐中没有本轮夹具商品，不能完成确定性反馈用例")
                    item = eligible[index % len(eligible)]
                    attribution = {"recommendation_delivery_id": item["recommendation_delivery_id"]}
                    send(client, "POST", "/api/v1/recommendation-events", "detail", headers=headers, json={**attribution, "listing_id": item["listing_id"], "action": "DETAIL_OPEN", "session_id": session})
                    send(client, "PUT", f"/api/v1/cart/items/{item['listing_id']}", "cart", headers=headers, json=attribution)
                    key = uuid.uuid4().hex
                    order = send(client, "POST", "/api/v1/orders", "order", headers={**headers, "Idempotency-Key": key}, json={**attribution, "listing_id": item["listing_id"], "quantity": 1})
                    duplicate = send(client, "POST", "/api/v1/orders", "order_repeated", headers={**headers, "Idempotency-Key": key}, json={**attribution, "listing_id": item["listing_id"], "quantity": 1})
                    if duplicate["order_no"] != order["order_no"]:
                        raise RuntimeError("重复下单未返回原订单")
                    intent = send(client, "PUT", f"/api/v1/orders/{order['order_no']}/payment-intent", "payment_intent", headers={**headers, "Idempotency-Key": uuid.uuid4().hex})
                    operational = session_index == 0 and index == 1
                    if operational:
                        current_order = send(client, "GET", f"/api/v1/orders/{order['order_no']}", "order_snapshot", headers=headers)
                        closed = send(client, "PUT", f"http://127.0.0.1:18083/internal/v1/payment-intents/{order['order_no']}/close", "close_before_late_success", headers={"Authorization": f"Bearer {payment_token}"}, json={"order_no": order["order_no"], "buyer_id": user["id"], "merchant_id": current_order["item"]["seller_id"], "amount": intent["amount"], "currency": intent["currency"], "expire_at": intent["expire_at"]})
                        if closed["status"] != "CLOSED":
                            raise RuntimeError("迟到支付用例未成功关闭支付意图")
                    confirm_headers = {**headers, "Idempotency-Key": uuid.uuid4().hex}
                    paid = send(client, "POST", f"/api/v1/mock-provider/payment-intents/{intent['intent_no']}/confirm", "late_success" if operational else "payment_confirm", headers=confirm_headers)
                    repeated = send(client, "POST", f"/api/v1/mock-provider/payment-intents/{intent['intent_no']}/confirm", "payment_repeated", headers=confirm_headers)
                    if repeated["status"] != paid["status"]:
                        raise RuntimeError("支付重放改变了终态")
                    if operational:
                        if paid["status"] != "REFUNDED":
                            raise RuntimeError("迟到支付未完成运营退款")
                        reason = "LATE_SUCCESS"
                    else:
                        deadline = time.monotonic() + 30
                        while True:
                            current_order = send(client, "GET", f"/api/v1/orders/{order['order_no']}", "await_order_paid", headers=headers)
                            if current_order["status"] == "PAID":
                                break
                            if time.monotonic() >= deadline:
                                raise RuntimeError("支付后订单未在 30 秒内收敛为 PAID")
                            time.sleep(.3)
                        reason = "USER_RETURN" if session_index == 0 and index == 0 else None
                        if reason:
                            paid = send(client, "POST", f"/api/v1/mock-provider/payment-intents/{intent['intent_no']}/refund", "user_refund", headers=headers, json={"reason_code": reason})
                            if paid["status"] != "REFUNDED":
                                raise RuntimeError("主动退款未完成")
                    outcomes.append({"user_key": user["user_key"], "session": session_index, "scene": scene,
                                     "listing_id": item["listing_id"], "delivery_id": item["recommendation_delivery_id"],
                                     "order_no": order["order_no"], "payment_status": paid["status"], "refund_reason": reason})
    result = {"traffic_origin": "SCRIPTED", "users": 5, "sessions": len(outcomes), "outcomes": outcomes,
              "user_refunds": sum(row["refund_reason"] == "USER_RETURN" for row in outcomes),
              "operational_refunds": sum(row["refund_reason"] == "LATE_SUCCESS" for row in outcomes)}
    if result["sessions"] != 15 or result["user_refunds"] != 1 or result["operational_refunds"] != 1:
        raise RuntimeError("反馈闭环缺少必要分支")
    write_json(output / "feedback-result.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(description="验证本地推荐 HTTP 反馈闭环")
    parser.add_argument("--fixture", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expected-model-version")
    args = parser.parse_args()
    fixture = json.loads(args.fixture.read_text(encoding="utf-8-sig"))
    with RunRecorder(args.output, {"operation": "http_feedback", "fixture": fixture, "expected_model_version": args.expected_model_version}, Path(__file__).resolve().parents[5]):
        result = replay_feedback(fixture, args.output, args.expected_model_version)
    print(json.dumps({key: value for key, value in result.items() if key != "outcomes"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
