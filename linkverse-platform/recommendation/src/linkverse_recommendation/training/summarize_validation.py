"""从独立运行目录汇总分层性能和长稳趋势，不把未执行项填成零。"""

from __future__ import annotations

import argparse
import json
import re
from datetime import datetime
from pathlib import Path

import numpy as np

from linkverse_recommendation.training.recording import write_json
from linkverse_recommendation.training.summarize_run import read_lines


def memory_gib(value):
    match = re.match(r"([\d.]+)\s*([A-Za-z]+)", value.split("/")[0].strip())
    units = {"B": 1, "kB": 1000, "KiB": 1024, "MB": 1000**2, "MiB": 1024**2, "GB": 1000**3, "GiB": 1024**3}
    return float(match[1]) * units[match[2]] / 1024**3 if match and match[2] in units else None


def resource_summary(rows):
    result = {"samples": len(rows), "series": {}}
    series = {}
    if not rows:
        return result
    result["sampling_error_count"] = sum(bool(row.get("sampling_error_type")) for row in rows)
    result["container_samples"] = sum(bool(row.get("containers")) for row in rows)
    result["utilization"] = {}
    for metric in ("process_cpu_percent", "system_cpu_percent", "gpu_utilization_percent", "system_available_bytes"):
        values = [row[metric] for row in rows if row.get(metric) is not None]
        result["utilization"][metric] = {"samples": len(values), "median": float(np.median(values)), "p95": float(np.quantile(values, .95)), "min": min(values), "max": max(values)} if values else None
    beginning = datetime.fromisoformat(rows[0]["utc"])
    for row in rows:
        minute = (datetime.fromisoformat(row["utc"]) - beginning).total_seconds() / 60
        for process in row.get("java_processes", []):
            if process.get("process_rss_bytes") is not None:
                series.setdefault(process["name"], []).append((minute, process["process_rss_bytes"] / 1024**3))
        for container in row.get("containers", []):
            memory = memory_gib(container["MemUsage"])
            if memory is not None:
                series.setdefault(container["Name"], []).append((minute, memory))
    for name, observations in series.items():
        times, values = np.asarray(observations).T
        first = values[times <= times.min() + 5]
        last = values[times >= times.max() - 5]
        stable = times >= times.min() + (times.max() - times.min()) / 3
        result["series"][name] = {"samples": len(values), "first_five_min_median_gib": float(np.median(first)),
                                   "last_five_min_median_gib": float(np.median(last)), "peak_gib": float(values.max()),
                                   "late_slope_mib_per_minute": float(np.polyfit(times[stable], values[stable], 1)[0] * 1024) if stable.sum() >= 3 else None}
    return result


def execute(config):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib import font_manager

    font = Path("C:/Windows/Fonts/msyh.ttc")
    if font.is_file():
        font_manager.fontManager.addfont(str(font))
        plt.rcParams["font.family"] = font_manager.FontProperties(fname=str(font)).get_name()
    plt.rcParams["axes.unicode_minus"] = False
    output = Path(config["output"])
    output.mkdir(parents=True, exist_ok=True)
    report = {"runs": {}, "http": []}
    lines = ["# 推荐运行验证指标", "", "各运行使用自己的配置、源码归档和原始请求记录。全量合成模型与 120 商品业务夹具分别统计；正常模型响应和故障降级分别验收。", "",
             "| 运行 | 阶段 | 请求 | 有效模型率 | 降级率 | p95 / ms | 吞吐 / 秒 | 分群门禁 |", "|---|---|---:|---:|---:|---:|---:|---|"]
    for name, directory in config["runs"].items():
        run = Path(directory)
        status = json.loads((run / "status.json").read_text(encoding="utf-8")) if (run / "status.json").is_file() else {"status": "NOT_COMPLETED"}
        report["runs"][name] = {"path": str(run), "status": status, "resources": resource_summary(read_lines(run / "raw/resources.jsonl"))}
        queues = read_lines(run / "raw/queue-samples.jsonl")
        queue_peak = {}
        for sample in queues:
            for queue in sample.get("queues", []):
                peak = queue_peak.setdefault(queue["name"], {})
                for field in ("messages", "messages_ready", "messages_unacknowledged"):
                    if queue.get(field) is not None:
                        peak[field] = max(peak.get(field, 0), queue[field])
        report["runs"][name]["queues"] = {"samples": len(queues), "unavailable_samples": sum(row.get("status") != "AVAILABLE" for row in queues), "peak_counts": queue_peak}
        report["runs"][name]["authentication_refreshes"] = len(read_lines(run / "raw/authentication.jsonl"))
        if not (run / "summary.json").is_file():
            lines.append(f"| {name} | 未完成或未执行 | — | — | — | — | — | 未验收 |")
            continue
        summary = json.loads((run / "summary.json").read_text(encoding="utf-8"))
        if not isinstance(summary, list):
            report["runs"][name]["summary"] = summary
            continue
        for stage in summary:
            if "configuration" not in stage:
                continue
            entry = {"run": name, "stage": stage["configuration"]["name"], "configuration": stage["configuration"], "metrics": stage["all"],
                     "cohorts": stage["cohorts"], "passed": stage["passed"], "throughput_rps": stage["throughput_rps"]}
            report["http"].append(entry)
            metrics = entry["metrics"]
            rate = lambda value: "—" if value is None else f"{value:.2%}"
            latency = "—" if metrics["latency_ms"]["p95"] is None else f"{metrics['latency_ms']['p95']:.1f}"
            lines.append(f"| {name} | {entry['stage']} | {metrics['requests']} | {rate(metrics['valid_model_rate'])} | {rate(metrics['fallback_rate'])} | {latency} | {entry['throughput_rps']:.1f} | {'通过' if stage['passed'] else '未通过'} |")
    write_json(output / "validation-summary.json", report)
    if report["http"]:
        figure, axis = plt.subplots(figsize=(12, max(5, len(report["http"]) * .3)), constrained_layout=True)
        entries = report["http"]
        axis.barh(range(len(entries)), [entry["metrics"]["latency_ms"]["p95"] or 0 for entry in entries],
                  color=["#397F83" if entry["passed"] else "#B36444" for entry in entries])
        axis.set_yticks(range(len(entries)), [f"{entry['run']} · {entry['stage']}" for entry in entries], fontsize=8)
        axis.invert_yaxis()
        axis.axvline(300, color="#8F714D", linestyle="--", label="Python HTTP 本轮 300ms")
        axis.axvline(800, color="#756F7D", linestyle=":", label="Trade / Gateway 本轮 800ms")
        axis.set(xlabel="p95 延迟 / ms", title="分层性能：绿色通过该阶段门禁，橙色未通过")
        axis.grid(axis="x", alpha=.2)
        axis.legend()
        figure.savefig(output / "http-latency.png", dpi=150)
        plt.close(figure)
        lines.extend(["", f"![分层性能]({(output / 'http-latency.png').resolve().as_posix()})"])
    for name, directory in config.get("soak_runs", {}).items():
        run = Path(directory)
        if not (run / "summary.json").is_file():
            continue
        stages = json.loads((run / "summary.json").read_text(encoding="utf-8"))
        rows = read_lines(run / "raw/resources.jsonl")
        if not stages or not rows:
            continue
        # 请求可能在采样截止后才完成；保留所有原始统计，图中不把尾部短桶画成完整 30 秒窗口。
        windows = [window for window in stages[0]["windows"] if window["window"] * 30 < stages[0]["configuration"]["duration_seconds"]]
        figure, axes = plt.subplots(2, 2, figsize=(12, 7), constrained_layout=True)
        minutes = [window["window"] / 2 for window in windows]
        axes[0, 0].plot(minutes, [window["valid_model_rate"] * 100 for window in windows], color="#397F83")
        axes[0, 0].set(title="每 30 秒有效模型率", xlabel="采样分钟", ylabel="%", ylim=(95, 100.5))
        axes[0, 1].plot(minutes, [window["latency_ms"]["p95"] for window in windows], color="#A66B3F")
        axes[0, 1].set(title="每 30 秒 p95", xlabel="采样分钟", ylabel="ms")
        beginning = datetime.fromisoformat(rows[0]["utc"])
        resource_times = [(datetime.fromisoformat(row["utc"]) - beginning).total_seconds() / 60 for row in rows]
        for service in ("linkverse-trade", "linkverse-gateway"):
            values = [next((process["process_rss_bytes"] / 1024**3 for process in row.get("java_processes", []) if process["name"] == service and process.get("process_rss_bytes") is not None), float("nan")) for row in rows]
            axes[1, 0].plot(resource_times, values, label=service)
        axes[1, 0].set(title="Java 进程工作集", xlabel="运行分钟", ylabel="GiB")
        axes[1, 0].legend(fontsize=8)
        for service in ("linkverse-mvp-recommendation", "linkverse-mvp-mysql"):
            values = [next((memory_gib(container["MemUsage"]) for container in row.get("containers", []) if container["Name"] == service), float("nan")) for row in rows]
            axes[1, 1].plot(resource_times, values, label=service)
        axes[1, 1].set(title="推荐与 MySQL 容器内存", xlabel="运行分钟", ylabel="GiB")
        axes[1, 1].legend(fontsize=8)
        for axis in axes.flat:
            axis.grid(alpha=.2)
        path = output / f"{name}-trend.png"
        figure.savefig(path, dpi=150)
        plt.close(figure)
        lines.extend(["", f"![{name}趋势]({path.resolve().as_posix()})"])
    lines.extend(["", "长稳采用冻结的虚拟用户数与思考时间，实际吞吐单独记录。趋势图不绘制采样截止后由延迟完成请求形成的尾部短桶，原始统计与总请求数保留。工作集增长可能包含缓存和 JVM 内存管理，不能仅凭短时斜率认定或排除泄漏；短于十分钟的运行，其前后五分钟统计窗口会重叠。完整分群、前后五分钟内存中位数和后段斜率见同目录 JSON。"])
    (output / "运行指标.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description="汇总推荐 HTTP、故障和长稳运行指标")
    parser.add_argument("--config", type=Path, required=True)
    execute(json.loads(parser.parse_args().config.read_text(encoding="utf-8")))


if __name__ == "__main__":
    main()
