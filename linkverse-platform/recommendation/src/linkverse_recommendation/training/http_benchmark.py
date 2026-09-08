"""分层 HTTP 压测与长稳测试，分别核算成功、有效模型和降级响应。"""

from __future__ import annotations

import argparse
import asyncio
import json
import time
import uuid
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

import httpx
import jwt
import numpy as np

from linkverse_recommendation.training.local_validation import load_users, service_token
from linkverse_recommendation.training.recording import RunRecorder, record, write_json


def summarize(rows: list[dict], budget: float) -> dict:
    latencies = [row["latency_ms"] for row in rows]
    model_latencies = [row["latency_ms"] for row in rows if row["valid_model"]]
    count = len(rows)
    successful = sum(200 <= row["status"] < 300 for row in rows)
    valid = sum(row["valid_model"] for row in rows)
    fallback = sum(row["fallback"] for row in rows)
    return {"requests": count, "successful": successful, "valid_model": valid, "fallback": fallback,
            "success_rate": successful / count if count else None,
            "valid_model_rate": valid / count if count else None,
            "fallback_rate": fallback / count if count else None,
            "latency_ms": {name: float(np.quantile(latencies, value)) if count else None for name, value in (("p50", .5), ("p95", .95), ("p99", .99))},
            "model_latency_p95_ms": float(np.quantile(model_latencies, .95)) if model_latencies else None,
            "statuses": dict(Counter(str(row["status"]) for row in rows)),
            "validation_errors": dict(Counter(reason for row in rows for reason in row["validation_errors"])),
            "passed": bool(count and successful / count >= .99 and valid / count >= .99 and fallback / count <= .01 and np.quantile(latencies, .95) <= budget)}


class Tokens:
    def __init__(self, fixture):
        self.users = load_users(fixture)
        seed = json.loads(Path(fixture["seed_state_path"]).read_text(encoding="utf-8-sig"))
        self.credentials = [(user["username"], seed["password"]) for user in seed["users"][:20]]
        self.locks = [asyncio.Lock() for _ in self.users]
        self.internal = None
        self.internal_lock = asyncio.Lock()

    @staticmethod
    def valid(token):
        return token and jwt.decode(token, options={"verify_signature": False})["exp"] > time.time() + 60

    async def get(self, index, internal, client):
        if internal:
            async with self.internal_lock:
                if not self.valid(self.internal):
                    self.internal = await asyncio.to_thread(service_token)
                    record("authentication", type="service", status="REFRESHED")
            return self.internal
        async with self.locks[index]:
            if not self.valid(self.users[index]["token"]):
                username, password = self.credentials[index]
                response = await client.post("http://127.0.0.1:18080/api/v1/auth/login", json={"username": username, "password": password})
                response.raise_for_status()
                self.users[index]["token"] = response.json()["access_token"]
                record("authentication", type="user", status="REFRESHED")
        return self.users[index]["token"]


async def run_stage(stage, fixture, tokens, expected_version, model_users):
    observations = []
    started = time.monotonic()
    warmup = float(stage.get("warmup_seconds", 30))
    duration = float(stage["duration_seconds"])
    deadline = started + warmup + duration
    internal = stage["target"] == "python"
    port = stage.get("port", 18084 if internal else (18082 if stage["target"] == "trade" else 18080))
    budget = 300 if internal else 800
    concurrency = int(stage["concurrency"])
    pending = asyncio.Event()
    timeout = httpx.Timeout(5, connect=2)
    async with httpx.AsyncClient(trust_env=False, timeout=timeout, limits=httpx.Limits(max_connections=concurrency + 2, max_keepalive_connections=concurrency + 2)) as client:
        async def worker(worker_index):
            index = worker_index % len(tokens.users)
            user = tokens.users[index]
            sequence = 0
            await pending.wait()
            while time.monotonic() < deadline:
                token = await tokens.get(index, internal, client)
                scene = ("HOME", "DETAIL", "CART")[sequence % 3]
                context_id = str(fixture["first_listing_id"] + sequence % fixture["item_count"])
                if internal and stage.get("context_ids"):
                    context_id = str(stage["context_ids"][sequence % len(stage["context_ids"])])
                headers = {"Authorization": f"Bearer {token}"}
                request_offset = time.monotonic() - started
                before = time.perf_counter()
                status, payload, errors = 0, {}, []
                try:
                    if internal:
                        response = await client.post(f"http://127.0.0.1:{port}/internal/v1/recommendations", headers=headers, json={"request_id": uuid.uuid4().hex, "user_key": user["user_key"], "domain": "trade", "scene": scene, "occurred_at": datetime.now(timezone.utc).isoformat(), "candidate_count": stage.get("candidate_count", 100), "context": {"object_id": context_id} if scene != "HOME" else {}})
                    else:
                        params = {"scene": scene, "limit": 20}
                        if scene != "HOME":
                            params["context_listing_id"] = context_id
                        response = await client.get(f"http://127.0.0.1:{port}/api/v1/recommendations/listings", headers=headers, params=params)
                    status = response.status_code
                    payload = response.json()
                except (httpx.HTTPError, ValueError) as exception:
                    errors.append(type(exception).__name__)
                elapsed = (time.perf_counter() - before) * 1000
                if not isinstance(payload, dict):
                    payload = {}
                    errors.append("invalid_response")
                items = payload.get("candidates" if internal else "items", [])
                ids = [str(item.get("object_id" if internal else "listing_id", "")) for item in items]
                fallback = bool(payload.get("fallback")) or any("FALLBACK" in item.get("sources", []) for item in items)
                if payload.get("model_version") != expected_version:
                    errors.append("unexpected_model_version")
                if len(ids) < 20 or len(ids) != len(set(ids)):
                    errors.append("incomplete_or_duplicate_items")
                if scene != "HOME" and context_id in ids:
                    errors.append("context_item_returned")
                if not internal:
                    if any(not str(fixture["first_listing_id"]) <= item <= str(fixture["last_listing_id"]) for item in ids):
                        errors.append("outside_fixture")
                    if max(Counter(item.get("category_code") for item in items[:20]).values(), default=0) > 6:
                        errors.append("category_quota")
                    if max(Counter((int(item) - fixture["first_listing_id"]) % 20 for item in ids[:20]).values(), default=0) > 3:
                        errors.append("seller_quota")
                observation = {"stage": stage["name"], "target": stage["target"], "vu": worker_index, "scene": scene,
                               "cohort": "known" if user["user_key"] in model_users else "cold", "status": status,
                               "latency_ms": elapsed, "fallback": fallback, "returned": len(ids),
                               "model_version": payload.get("model_version"), "validation_errors": errors,
                               "valid_model": 200 <= status < 300 and not fallback and not errors,
                               "elapsed_seconds": time.monotonic() - started}
                observation["request_offset_seconds"] = request_offset
                if warmup <= request_offset < warmup + duration:
                    observations.append(observation)
                    record("http-requests", **observation)
                sequence += 1
                await asyncio.sleep(float(stage.get("think_seconds", 0)))
        tasks = [asyncio.create_task(worker(index)) for index in range(concurrency)]
        pending.set()
        await asyncio.gather(*tasks)
    result = {"configuration": stage, "elapsed_seconds": time.monotonic() - started, "throughput_rps": len(observations) / duration, "all": summarize(observations, budget),
              "cohorts": {cohort: summarize([row for row in observations if row["cohort"] == cohort], budget) for cohort in ("known", "cold")}}
    result["windows"] = [{"window": window, **summarize([row for row in observations if int((row["elapsed_seconds"] - warmup) / 30) == window], budget)} for window in sorted({int((row["elapsed_seconds"] - warmup) / 30) for row in observations})]
    result["passed"] = result["all"]["passed"] and all(value["passed"] for value in result["cohorts"].values() if value["requests"])
    return result


async def execute(config):
    config = {**config, "transport": {"route": "DIRECT_LOOPBACK", "trust_env": False}}
    fixture = json.loads(Path(config["fixture"]).read_text(encoding="utf-8-sig"))
    bundle = Path(config["bundle"])
    model_users = json.loads((bundle / "user-vocabulary.json").read_text(encoding="utf-8"))
    expected = json.loads((bundle / "manifest.json").read_text(encoding="utf-8"))["model_version"]
    output = Path(config["output"])
    tokens = Tokens(fixture)
    if config.get("python_known_user_count"):
        if any(stage["target"] != "python" for stage in config["stages"]):
            raise ValueError("合成已知用户只能用于独立 Python 压测")
        known_count = min(int(config["python_known_user_count"]), len(tokens.users), len(model_users))
        for index, key in enumerate(sorted(model_users)[:known_count]):
            tokens.users[index]["user_key"] = key
    results = []
    with RunRecorder(output, config, Path(__file__).resolve().parents[5]):
        for stage in config["stages"]:
            record("stages", stage=stage["name"], status="STARTED")
            result = await run_stage(stage, fixture, tokens, expected, model_users)
            results.append(result)
            write_json(output / f"{stage['name']}.json", result)
            record("stages", stage=stage["name"], status="COMPLETED", metrics=result["all"])
            print(json.dumps({"stage": stage["name"], **result["all"]}, ensure_ascii=False), flush=True)
            if stage.get("stop_on_failure", True) and not result["passed"]:
                break
        write_json(output / "summary.json", results)
    return results


def main():
    parser = argparse.ArgumentParser(description="执行分层推荐 HTTP 性能与长稳测试")
    parser.add_argument("--config", required=True, type=Path)
    args = parser.parse_args()
    asyncio.run(execute(json.loads(args.config.read_text(encoding="utf-8"))))


if __name__ == "__main__":
    main()
