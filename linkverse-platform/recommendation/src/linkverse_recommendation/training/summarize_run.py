"""从原始 epoch、trial 和资源日志生成可复核的中文训练摘要与曲线。"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from linkverse_recommendation.training.recording import write_json


def read_lines(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()] if path.is_file() else []


def summarize(run: Path):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib import font_manager

    font = Path("C:/Windows/Fonts/msyh.ttc")
    if font.is_file():
        font_manager.fontManager.addfont(str(font))
        plt.rcParams["font.family"] = font_manager.FontProperties(fname=str(font)).get_name()
    plt.rcParams["axes.unicode_minus"] = False
    epochs = read_lines(run / "raw/epoch-metrics.jsonl")
    trials = read_lines(run / "raw/trials.jsonl")
    resources = read_lines(run / "raw/resources.jsonl")
    tower_trials = [row for row in trials if "embedding_dim" in row["parameters"] and row["value"] is not None]
    ranker_trials = [row for row in trials if "num_leaves" in row["parameters"] and row["value"] is not None]
    report = {"run_id": run.name, "tower_trials": len(tower_trials), "ranker_trials": len(ranker_trials),
              "epoch_records": len(epochs), "resource_samples": len(resources),
              "peak_process_rss_gib": max((row.get("process_rss_bytes", 0) for row in resources), default=0) / 1024**3 if resources else None,
              "peak_device_memory_mib": max((row.get("gpu_used_mib", 0) for row in resources), default=0) if resources else None,
              "peak_pytorch_allocated_mib": max((row.get("cuda_peak_allocated_bytes", 0) for row in epochs), default=0) / 1024**2 if epochs else None,
              "resource_scope": "设备显存包含桌面等其他进程；PyTorch 已分配峰值单独记录，不能把二者差值直接解释为模型内存泄漏。"}
    write_json(run / "training-summary.json", report)
    if not tower_trials:
        return report
    best = max(tower_trials, key=lambda row: row["value"])
    selected = [row for row in epochs if row.get("phase") == best.get("phase") and row.get("stage") == "tuning" and row.get("trial") == best["trial"]]
    figure, axes = plt.subplots(2, 2, figsize=(12, 7), constrained_layout=True)
    axes[0, 0].plot([row["epoch"] for row in selected], [row["train_loss"] for row in selected], color="#236A8A")
    axes[0, 0].set(title="验证集选中 trial 的训练损失", xlabel="Epoch", ylabel="损失")
    axes[0, 1].plot([row["epoch"] for row in selected], [row["validation_ndcg_at_20"] for row in selected], color="#C27732")
    axes[0, 1].set(title="同一 trial 的验证集 NDCG@20", xlabel="Epoch", ylabel="NDCG@20")
    values = [row["value"] for row in tower_trials]
    axes[1, 0].plot([row["trial"] for row in tower_trials], values, marker=".", color="#85979C", label="本次 trial")
    axes[1, 0].plot([row["trial"] for row in tower_trials], [max(values[:index + 1]) for index in range(len(values))], color="#236A8A", label="累计最佳")
    axes[1, 0].set(title="双塔搜索过程", xlabel="Trial", ylabel="验证集 NDCG@20")
    axes[1, 0].legend()
    if resources:
        from datetime import datetime
        first = datetime.fromisoformat(resources[0]["utc"])
        times = [(datetime.fromisoformat(row["utc"]) - first).total_seconds() / 60 for row in resources]
        axes[1, 1].plot(times, [row.get("gpu_used_mib", float("nan")) / 1024 for row in resources], color="#967249", label="整卡显存")
        axes[1, 1].plot(times, [row.get("process_rss_bytes", float("nan")) / 1024**3 for row in resources], color="#3C7D5A", label="训练进程 RSS")
        axes[1, 1].set(title="运行资源变化（含其他图形进程）", xlabel="经过时间 / 分钟", ylabel="GiB")
        axes[1, 1].legend()
    for axis in axes.flat:
        axis.grid(alpha=.2)
    figure.suptitle(run.name, fontsize=13)
    figure.savefig(run / "training-curves.png", dpi=150)
    plt.close(figure)
    lines = ["# 训练过程摘要", "", f"运行：`{run.name}`", "", f"已记录双塔 {len(tower_trials)} 次搜索、精排 {len(ranker_trials)} 次搜索、{len(epochs)} 条 epoch 记录及 {len(resources)} 次资源采样。", "", "图中的 epoch 损失与验证分数来自同一个由验证集选中的 trial；不将不同超参数 trial 的损失连接成单次训练曲线。", "", f"![训练和资源曲线]({(run / 'training-curves.png').resolve().as_posix()})", "", report["resource_scope"], "", "排序收益须结合冻结测试窗口的配对比较判断；搜索中的最佳验证分数不等于线上收益。"]
    (run / "training-summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return report


def main():
    parser = argparse.ArgumentParser(description="汇总已留档的推荐训练过程")
    parser.add_argument("--run", required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(summarize(args.run), ensure_ascii=False))


if __name__ == "__main__":
    main()
