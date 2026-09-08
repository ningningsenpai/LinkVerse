"""仅对本项目推荐容器执行可恢复的故障注入，并隔离正常性能指标。"""

from __future__ import annotations

import argparse
import asyncio
import json
import shutil
import subprocess
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

import httpx
import jwt
from cryptography.hazmat.primitives.asymmetric import rsa

from linkverse_recommendation.serving.activate import activate
from linkverse_recommendation.training.http_benchmark import Tokens
from linkverse_recommendation.training.local_validation import service_token
from linkverse_recommendation.training.recording import RunRecorder, record, write_json


CONTAINER = "linkverse-mvp-recommendation"


async def docker(*arguments):
    result = await asyncio.to_thread(subprocess.run, ["docker", *arguments], capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise RuntimeError("推荐测试容器命令执行失败")
    return result.stdout


async def execute(config):
    root = Path(config["model_root"]).resolve()
    before, after = (Path(config[name]).resolve() for name in ("before", "after"))
    if root not in before.parents or root not in after.parents:
        raise ValueError("故障模型必须在指定根目录内")
    info = json.loads(await docker("inspect", CONTAINER))[0]
    if info["Config"]["Labels"].get("com.docker.compose.project") != "linkverse-mvp":
        raise ValueError("容器不属于当前隔离项目，拒绝注入故障")
    mounted = [mount["Source"].replace("\\", "/").lower() for mount in info["Mounts"] if mount["Destination"] == "/models"]
    expected_suffix = root.as_posix().lower()[2:] if root.drive else root.as_posix().lower()
    if not mounted or not mounted[0].endswith(expected_suffix):
        raise ValueError("推荐容器挂载目录与故障测试目录不一致")
    fixture = json.loads(Path(config["fixture"]).read_text(encoding="utf-8-sig"))
    tokens = Tokens(fixture)
    pointer = root / "active-model.json"
    original = json.loads(pointer.read_text(encoding="utf-8"))
    versions = {name: json.loads(path.joinpath("manifest.json").read_text(encoding="utf-8"))["model_version"] for name, path in (("before", before), ("after", after))}
    checks = []
    with RunRecorder(Path(config["output"]), config, Path(__file__).resolve().parents[5]):
        async with httpx.AsyncClient(timeout=5, trust_env=False) as client:
            async def probe(stage, target="python", supplied_token=None):
                internal = target == "python"
                token = supplied_token or await tokens.get(0, internal, client)
                start = time.perf_counter()
                try:
                    if internal:
                        response = await client.post("http://127.0.0.1:18084/internal/v1/recommendations", headers={"Authorization": f"Bearer {token}"}, json={"request_id": uuid.uuid4().hex, "user_key": tokens.users[0]["user_key"], "domain": "trade", "scene": "HOME", "occurred_at": datetime.now(timezone.utc).isoformat(), "candidate_count": 100, "context": {}})
                    else:
                        response = await client.get("http://127.0.0.1:18080/api/v1/recommendations/listings", headers={"Authorization": f"Bearer {token}"}, params={"scene": "HOME", "limit": 20})
                    payload = response.json()
                    items = payload.get("candidates" if internal else "items", [])
                    ids = [str(item.get("object_id" if internal else "listing_id")) for item in items]
                    result = {"stage": stage, "target": target, "status": response.status_code, "model_version": payload.get("model_version"),
                              "fallback": bool(payload.get("fallback")), "complete": len(ids) >= 20 and len(ids) == len(set(ids))}
                except (httpx.HTTPError, ValueError):
                    result = {"stage": stage, "target": target, "status": 0, "fallback": False, "complete": False, "model_version": None}
                result["latency_ms"] = (time.perf_counter() - start) * 1000
                record("fault-requests", **result)
                return result

            async def observe_version(version, target="python", timeout=60):
                start = time.monotonic()
                while time.monotonic() - start < timeout:
                    row = await probe("await_recovery", target)
                    if row["status"] == 200 and row["complete"] and not row["fallback"] and row["model_version"] == version:
                        return time.monotonic() - start
                    await asyncio.sleep(.5)
                return None

            def check(name, passed, **details):
                row = {"name": name, "passed": bool(passed), **details}
                checks.append(row)
                record("fault-checks", **row)
                print(json.dumps(row, ensure_ascii=False), flush=True)

            try:
                await asyncio.to_thread(activate, root, after)
                latency = await observe_version(versions["after"])
                check("initial_model", latency is not None, recovery_seconds=latency)
                signed = await tokens.get(0, True, client)
                forged = jwt.encode(jwt.decode(signed, options={"verify_signature": False}), rsa.generate_private_key(public_exponent=65537, key_size=2048), algorithm="RS256", headers={"kid": jwt.get_unverified_header(signed).get("kid")})
                for name, token in (("user_token", await tokens.get(0, False, client)),
                                    ("wrong_service_audience", await asyncio.to_thread(service_token, "payment.internal")),
                                    ("malformed_token", "invalid.signature.token"), ("invalid_signature", forged)):
                    row = await probe(name, supplied_token=token)
                    check(name, row["status"] in (401, 403), status=row["status"])

                for index, (path, version) in enumerate(((before, versions["before"]), (after, versions["after"]))):
                    rows = []
                    stop = asyncio.Event()
                    async def traffic():
                        while not stop.is_set():
                            rows.append(await probe("atomic_switch"))
                            await asyncio.sleep(.02)
                    task = asyncio.create_task(traffic())
                    await asyncio.to_thread(activate, root, path)
                    latency = await observe_version(version, timeout=30)
                    stop.set()
                    await task
                    check(f"atomic_switch_{index}", latency is not None and all(row["status"] == 200 and row["complete"] and row["model_version"] in versions.values() for row in rows), requests=len(rows), recovery_seconds=latency)

                broken = root / "fault-fixtures" / uuid.uuid4().hex
                await asyncio.to_thread(shutil.copytree, after, broken)
                (broken / "index.faiss").write_bytes(b"invalid-index")
                write_json(pointer, {"model_version": versions["after"], "path": broken.relative_to(root).as_posix()})
                await asyncio.sleep(2)
                rows = [await probe("bad_bundle") for _ in range(100)]
                ready = await client.get("http://127.0.0.1:18084/health/ready")
                check("bad_bundle_keeps_old", ready.status_code == 200 and all(row["status"] == 200 and row["complete"] and row["model_version"] == versions["after"] for row in rows), requests=len(rows))
                write_json(pointer, {"model_version": "invalid", "path": "../outside"})
                await asyncio.sleep(2)
                rows = [await probe("outside_pointer") for _ in range(20)]
                check("outside_pointer_keeps_old", all(row["status"] == 200 and row["model_version"] == versions["after"] for row in rows), requests=len(rows))
                await asyncio.to_thread(activate, root, after)

                for mode in ("pause", "stop"):
                    await docker(mode, CONTAINER)
                    try:
                        if mode == "pause":
                            burst = await asyncio.gather(*(probe("paused_concurrency_30", "gateway") for _ in range(30)))
                            check("paused_concurrency_30_fallback", all(row["status"] == 200 and row["fallback"] and row["complete"] for row in burst), requests=len(burst))
                        rows = [await probe(mode, "gateway") for _ in range(10)]
                        check(f"{mode}_business_fallback", all(row["status"] == 200 and row["fallback"] and row["complete"] for row in rows), requests=len(rows), max_latency_ms=max(row["latency_ms"] for row in rows))
                    finally:
                        await docker("unpause" if mode == "pause" else "start", CONTAINER)
                    latency = await observe_version(versions["after"], "gateway")
                    check(f"{mode}_recovery", latency is not None, recovery_seconds=latency)

                pointer.unlink()
                await docker("restart", CONTAINER)
                deadline = time.monotonic() + 30
                live = None
                while time.monotonic() < deadline:
                    try:
                        live = await client.get("http://127.0.0.1:18084/health/live")
                        if live.status_code == 200:
                            break
                    except httpx.HTTPError:
                        pass
                    await asyncio.sleep(.5)
                ready = await client.get("http://127.0.0.1:18084/health/ready")
                python_row = await probe("cold_start_without_model")
                gateway_row = await probe("cold_start_without_model", "gateway")
                check("no_active_model", live is not None and live.status_code == 200 and ready.status_code == 503 and python_row["status"] == 503 and gateway_row["status"] == 200 and gateway_row["fallback"] and gateway_row["complete"])
                await asyncio.to_thread(activate, root, after)
                latency = await observe_version(versions["after"], "gateway")
                check("no_model_recovery", latency is not None, recovery_seconds=latency)
            finally:
                write_json(pointer, original)
                current = json.loads(await docker("inspect", CONTAINER))[0]["State"]
                if current.get("Paused"):
                    await docker("unpause", CONTAINER)
                if not current.get("Running"):
                    await docker("start", CONTAINER)
                recovered = await observe_version(original["model_version"], "gateway")
                check("finally_restored", recovered is not None, recovery_seconds=recovered)
                write_json(Path(config["output"]) / "summary.json", {"passed": bool(checks) and all(row["passed"] for row in checks), "checks": checks})


def main():
    parser = argparse.ArgumentParser(description="执行本地推荐模型故障与恢复测试")
    parser.add_argument("--config", required=True, type=Path)
    asyncio.run(execute(json.loads(parser.parse_args().config.read_text(encoding="utf-8"))))


if __name__ == "__main__":
    main()
