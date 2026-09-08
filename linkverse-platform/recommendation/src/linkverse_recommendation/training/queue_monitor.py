"""只读采集本地推荐长稳期间的队列计数，避免反复启动 Erlang 命令行虚拟机。"""

import argparse
import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path

import httpx

from linkverse_recommendation.training.recording import write_json


def main():
    parser = argparse.ArgumentParser(description="采集隔离项目的队列积压")
    parser.add_argument("--run", required=True, type=Path)
    arguments = parser.parse_args()
    if not (arguments.run / "manifest.json").is_file():
        raise ValueError("队列采样必须绑定已开始的测试运行")
    write_json(arguments.run / "queue-monitor.json", {"pid": os.getpid(), "interval_seconds": 30, "scope": "linkverse-mvp", "transport": "DIRECT_LOOPBACK", "message_bodies_collected": False})
    with httpx.Client(trust_env=False, auth=(os.environ["RABBITMQ_USER"], os.environ["RABBITMQ_PASSWORD"]), timeout=3) as client:
        while True:
            sample = {"utc": datetime.now(timezone.utc).isoformat()}
            try:
                response = client.get("http://127.0.0.1:15675/api/queues/linkverse-mvp")
                response.raise_for_status()
                sample["queues"] = [{name: queue.get(name) for name in ("name", "messages", "messages_ready", "messages_unacknowledged", "consumers")} for queue in response.json()]
                sample["status"] = "AVAILABLE"
            except (httpx.HTTPError, ValueError) as exception:
                sample.update(status="UNAVAILABLE", error_type=type(exception).__name__)
            with (arguments.run / "raw/queue-samples.jsonl").open("a", encoding="utf-8") as stream:
                stream.write(json.dumps(sample, ensure_ascii=False) + "\n")
            if (arguments.run / "status.json").is_file() or (arguments.run / "cancellation.json").is_file():
                break
            time.sleep(30)


if __name__ == "__main__":
    main()
