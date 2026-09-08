"""读取本地运行阶段计数与模型指标，输出不包含身份凭据的时间点快照。"""

from __future__ import annotations

import argparse
import asyncio
import json
from datetime import datetime, timezone
from pathlib import Path

import httpx

from linkverse_recommendation.training.http_benchmark import Tokens
from linkverse_recommendation.training.recording import write_json


async def capture(fixture: Path, output: Path) -> None:
    tokens = Tokens(json.loads(fixture.read_text(encoding="utf-8-sig")))
    result = {"utc": datetime.now(timezone.utc).isoformat(), "transport": "DIRECT_LOOPBACK", "metrics": {}}
    async with httpx.AsyncClient(timeout=5, trust_env=False) as client:
        token = await tokens.get(0, False, client)
        headers = {"Authorization": f"Bearer {token}"}
        names = ["linkverse.recommendation.stage.duration", "linkverse.recommendation.fallback",
                 "hikaricp.connections.active", "hikaricp.connections.pending", "jvm.memory.used"]
        for name in names:
            response = await client.get(f"http://127.0.0.1:18082/actuator/metrics/{name}", headers=headers)
            entry = {"status": response.status_code}
            if response.status_code == 200:
                entry["aggregate"] = response.json()
                if name.startswith("linkverse.recommendation"):
                    entry["groups"] = {}
                    for tag in entry["aggregate"].get("availableTags", []):
                        for value in tag["values"]:
                            grouped = await client.get(f"http://127.0.0.1:18082/actuator/metrics/{name}",
                                                       headers=headers, params={"tag": f"{tag['tag']}:{value}"})
                            entry["groups"][value] = grouped.json() if grouped.status_code == 200 else {"status": grouped.status_code}
            result["metrics"][name] = entry
        response = await client.get("http://127.0.0.1:18084/metrics")
        result["python_status"] = response.status_code
        if response.status_code == 200:
            output.parent.mkdir(parents=True, exist_ok=True)
            output.with_suffix(".prom").write_text(response.text, encoding="utf-8")
    write_json(output, result)


def main() -> None:
    parser = argparse.ArgumentParser(description="采集本地推荐服务的脱敏指标快照")
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    asyncio.run(capture(arguments.fixture, arguments.output))


if __name__ == "__main__":
    main()
