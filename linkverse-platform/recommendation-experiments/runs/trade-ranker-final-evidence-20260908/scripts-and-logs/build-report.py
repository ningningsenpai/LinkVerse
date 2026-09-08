"""汇总本轮训练、回归和运行证据，保留失败与适用边界。"""
import ast
import hashlib
import json
import shutil
import statistics
import re
import subprocess
from datetime import datetime
from pathlib import Path

import httpx
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.font_manager import FontProperties
import numpy as np

from linkverse_recommendation.core.model_bundle import sha256_file
from linkverse_recommendation.training.recording import write_json
from linkverse_recommendation.training.trainer import _dataset_hash

root = Path.cwd()
runs = root / "linkverse-platform/recommendation-experiments/runs"
cache = root / ".cache/recommendation-ranker-20260908"
evidence = runs / "trade-ranker-final-evidence-20260908"
evidence.mkdir(exist_ok=True)
read = lambda path: json.loads(path.read_text(encoding="utf-8-sig"))
lines = lambda path: [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()] if path.exists() else []
formal = runs / "trade-ranker-formal-20260908"
calibration = runs / "trade-ranker-calibration-20260908"
evaluation = runs / "trade-ranker-request-time-20260908"
selection = read(calibration / "selection.json")
metrics = read(evaluation / "evaluation.json")["models"]
comparisons = read(evaluation / "paired-comparisons.json")
runtime = read(runs / "trade-ranker-runtime-v2-20260908/summary.json")
http_before = read(runs / "trade-ranker-http-before-20260908/summary.json")
http_after = read(runs / "trade-ranker-http-after-20260908/summary.json")
inprocess = read(runs / "trade-ranker-inprocess-20260908/summary.json")
config = read(formal / "config.json")
model = Path(selection["bundle"])
assert sha256_file(model / "manifest.json") == selection["manifest_sha256"]
assert _dataset_hash(Path(config["dataset"])) == read(model / "manifest.json")["data_hash"]
protected = {
    "linkverse-platform/backend/platform-starter-security/src/main/java/ning/linkverse/security/jwt/LinkVerseJwtProfileValidator.java": "0A46DB0AB444303E9D20A279A6F984C48965359BA3535B4272C7200DD7A8E983",
    "linkverse-platform/scripts/_infrastructure.ps1": "DBFDBBD6F6F77CCA6E978182BC4D9938FB4D735B98C6FC87F4CF662281605A13",
}
protected_result = {name: {"sha256": sha256_file(root / name), "unchanged": sha256_file(root / name).upper() == expected}
                    for name, expected in protected.items()}
assert all(item["unchanged"] for item in protected_result.values())
syntax_files = list((root / "linkverse-platform/recommendation/src").rglob("*.py"))
for path in syntax_files:
    ast.parse(path.read_text(encoding="utf-8-sig"))
runtime_names = ["pipeline/ranking/features.py", "serving/registry.py", "pipeline/reranking/mmr.py", "api/app.py", "core/model_bundle.py"]
source_root = root / "linkverse-platform/recommendation/src/linkverse_recommendation"
runtime_code = "import hashlib,json,pathlib; p=pathlib.Path('/usr/local/lib/python3.12/site-packages/linkverse_recommendation'); print(json.dumps({x:hashlib.sha256((p/x).read_bytes()).hexdigest() for x in " + repr(runtime_names) + "}))"
actual_hashes = json.loads(subprocess.check_output(["docker", "exec", "linkverse-mvp-recommendation-ranker-20260908", "python", "-c", runtime_code], text=True))
source_identity = {name: {"workspace": sha256_file(source_root / name), "container": actual_hashes[name],
                          "matched": sha256_file(source_root / name) == actual_hashes[name]} for name in runtime_names}
assert all(item["matched"] for item in source_identity.values())
container = json.loads(subprocess.check_output(["docker", "inspect", "linkverse-mvp-recommendation-ranker-20260908"], text=True))[0]
identity = {"image_id": container["Image"], "image_tag": container["Config"]["Image"],
            "cpu_limit": container["HostConfig"]["NanoCpus"] / 1e9, "memory_bytes": container["HostConfig"]["Memory"],
            "container_id": container["Id"], "source_files": source_identity}
with httpx.Client(trust_env=False, timeout=5) as client:
    main = client.get("http://127.0.0.1:18084/health/ready").json()
    candidate_ready = client.get("http://127.0.0.1:18085/health/ready").json()
assert main["model_version"] == "trade-20260906084521028172-6f240414-670bd78a"
assert candidate_ready["model_version"] == model.name
write_json(evidence / "identity.json", {"runtime": identity, "protected_files": protected_result,
           "main_service": main, "candidate_service": candidate_ready, "syntax_files": len(syntax_files),
           "dataset_hash_verified": True, "selected_manifest_sha256": selection["manifest_sha256"]})

resource_summary = {}
def container_memory(sample):
    values = []
    for item in sample.get("containers", []):
        match = re.match(r"([\d.]+)(GiB|MiB|KiB|B)", item["MemUsage"])
        if match:
            values.append(float(match[1]) * {"GiB": 1, "MiB": 1 / 1024, "KiB": 1 / 1024**2, "B": 1 / 1024**3}[match[2]])
    return max(values) if values else None

run_names = ["trade-ranker-smoke-20260908", "trade-ranker-formal-20260908", "trade-ranker-calibration-20260908",
             "trade-ranker-request-time-20260908", "trade-ranker-runtime-20260908", "trade-ranker-runtime-v2-20260908",
             "trade-ranker-http-before-20260908", "trade-ranker-http-after-20260908", "trade-ranker-inprocess-20260908"]
for name in run_names:
    run = runs / name
    samples = lines(run / "raw/resources.jsonl")
    memory = [value for row in samples if (value := container_memory(row)) is not None]
    resource_summary[name] = {"status": read(run / "status.json"), "resource_samples": len(samples),
                              "peak_process_rss_gib": max((row.get("process_rss_bytes", 0) for row in samples), default=0) / 2**30,
                              "container_available": sum(row.get("container_sampling_status") == "AVAILABLE" for row in samples),
                              "container_unavailable": sum(row.get("container_sampling_status") == "UNAVAILABLE" for row in samples),
                              "container_status_missing": sum("container_sampling_status" not in row for row in samples)
                                  if read(run / "config.json").get("container_metrics") else None,
                              "container_peak_gib": max(memory) if memory else None}
write_json(evidence / "resource-summary.json", resource_summary)
after, before, previous = metrics["after"], metrics["before"], metrics["previous_after"]
offline_pass = all(comparisons[name]["bootstrap_95"][0] > 0 for name in ("before", "previous_after", "popular")) and after["coverage_at_20"] >= before["coverage_at_20"] * .9
http_pass = all(stage["passed"] for stage in http_after)
decision = {"status": "NOT_PROMOTED" if not http_pass else "HISTORICAL_CHECKS_ONLY", "purpose": "ENGINEERING",
            "manifest_sha256": selection["manifest_sha256"], "natural_traffic_evidence": False,
            "checks": {"offline_quality": offline_pass, "data_isolation": True, "tests": True,
                       "http_validity": all(stage["all"]["valid_model_rate"] >= .99 for stage in http_after),
                       "latency": http_pass and all(row["threshold_passed"] for row in inprocess[1:]), "rollback": runtime["passed"]},
            "business_active_model_unchanged": main["model_version"],
            "limitations": ["数据为合成历史，测试窗口已查看，不构成自然用户收益。", "新模型只在隔离 18085 端口测试。"]}
write_json(evidence / "promotion-decision.json", decision)

font = FontProperties(fname="C:/Windows/Fonts/msyh.ttc")
plt.rcParams.update({"axes.unicode_minus": False, "figure.dpi": 160})
def chinese(ax, title, xlabel=None, ylabel=None):
    ax.set_title(title, fontproperties=font, fontsize=12)
    if xlabel:
        ax.set_xlabel(xlabel, fontproperties=font)
    if ylabel:
        ax.set_ylabel(ylabel, fontproperties=font)
    ax.grid(alpha=.18)

trials = lines(formal / "raw/trials.jsonl")
epochs = lines(formal / "raw/ranker_epochs.jsonl")
fig, axes = plt.subplots(1, 2, figsize=(11, 4.2), constrained_layout=True)
for variant, label, color in (("history", "五项历史特征", "#52687d"), ("recall", "十五项扩展特征", "#00897b")):
    rows = [row for row in trials if row["variant"] == variant]
    axes[0].plot([row["trial"] + 1 for row in rows], np.maximum.accumulate([row["value"] for row in rows]), marker="o", label=label, color=color)
    rows = [row for row in epochs if row.get("variant") == variant and row.get("stage") == "selected_refit"]
    axes[1].plot([row["epoch"] for row in rows], [row["metrics"]["valid_1/tie_aware_ndcg@20"] for row in rows], label=label, color=color)
    axes[1].plot([row["epoch"] for row in rows], [row["metrics"]["training/tie_aware_ndcg@20"] for row in rows], color=color, alpha=.4, linestyle="--")
chinese(axes[0], "搜索过程：已观察到的最佳分组指标", "试验次数", "验证组 NDCG@20")
chinese(axes[1], "选定模型逐轮变化（虚线为训练）", "训练轮次", "分组 NDCG@20")
for ax in axes:
    ax.legend(prop=font, fontsize=9)
fig.savefig(evidence / "training-trends.png")
plt.close(fig)
fig, axes = plt.subplots(1, 2, figsize=(11, 4.2), constrained_layout=True)
labels = ["热门基线", "冻结召回基线", "上一轮反馈后", "本轮候选"]
selected_metrics = [metrics[name] for name in ("popular", "before", "previous_after", "after")]
axes[0].bar(range(4), [row["ndcg_at_20"] for row in selected_metrics], color=["#bcc6cd", "#788e9f", "#52687d", "#00897b"])
axes[0].set_xticks(range(4), labels, fontproperties=font)
chinese(axes[0], "统一请求时刻的历史回归", ylabel="NDCG@20")
for name, stages, color in (("before", http_before, "#52687d"), ("after", http_after[:2], "#00897b")):
    positions = np.arange(2) + (-.18 if name == "before" else .18)
    axes[1].bar(positions, [stage["all"]["latency_ms"]["p95"] for stage in stages], width=.36, color=color,
                label="冻结召回基线" if name == "before" else "本轮候选")
axes[1].axhline(300, color="#bb4430", linestyle="--", label="本轮 HTTP 门槛")
axes[1].set_xticks([0, 1], ["100 候选", "300 候选"], fontproperties=font)
chinese(axes[1], "同容器、20 并发的 HTTP p95", ylabel="毫秒")
axes[1].legend(prop=font)
fig.savefig(evidence / "quality-and-latency.png")
plt.close(fig)

seed_values = [metrics[name]["ndcg_at_20"] for name in ("after", "seed_20260917", "seed_20261001")]
quality = []
for name, label in (("popular", "热门与配额"), ("before", "冻结召回基线"), ("previous_after", "上一轮反馈后"), ("after", "本轮候选")):
    row = metrics[name]
    quality.append(f"| {label} | {row['ndcg_at_20']:.6f} | {row['recall_at_50']:.6f} | {row['hit_rate_at_20']:.6f} | {row['coverage_at_20']:.2%} | {row['short_lists']} / {row['category_violations']} |")
performance = []
for stage in [*http_before, *http_after]:
    value = stage["all"]
    performance.append(f"| {stage['configuration']['name']} | {value['requests']:,} | {value['valid_model_rate']:.2%} | {value['latency_ms']['p95']:.1f} | {value['latency_ms']['p99']:.1f} | {stage['throughput_rps']:.1f} | {'通过' if stage['passed'] else '未通过'} |")
ci = comparisons["previous_after"]["bootstrap_95"]
sample_rows = lines(formal / "raw/candidate-audit.jsonl")
normal_count = sum(stage["all"]["requests"] for stage in [*http_before, *http_after])
resources_table = []
for name, title in (("trade-ranker-formal-20260908", "正式训练及初始回归"), ("trade-ranker-calibration-20260908", "尺度校准及种子复现"),
                    ("trade-ranker-runtime-v2-20260908", "6GiB 容器换版验证"), ("trade-ranker-http-after-20260908", "候选 HTTP 测试")):
    value = resource_summary[name]
    peak = f"{value['container_peak_gib']:.3f}" if value["container_peak_gib"] is not None else "未采集"
    resources_table.append(f"| {title} | {value['resource_samples']} | {value['peak_process_rss_gib']:.3f} | {peak} | {value['container_available']} / {value['container_unavailable']} / {value['container_status_missing']} |")
baseline_users = lines(evaluation / "raw/users-before.jsonl")
candidate_users = {row["user_key"]: row for row in lines(evaluation / "raw/users-after.jsonl")}
cohort_metrics, cohort_table = {}, []
for cohort, title in (("cold", "冷启动"), ("sparse", "稀疏"), ("warm", "活跃")):
    members = [row for row in baseline_users if row["cohort"] == cohort and row["ndcg_at_20"] is not None]
    left = statistics.fmean(row["ndcg_at_20"] for row in members) if members else None
    right = statistics.fmean(candidate_users[row["user_key"]]["ndcg_at_20"] for row in members) if members else None
    cohort_metrics[cohort] = {"eligible_users": len(members), "before_ndcg": left, "after_ndcg": right}
    cohort_table.append(f"| {title} | {len(members):,} | {left:.6f} | {right:.6f} |")
write_json(evidence / "fixed-cohort-metrics.json", cohort_metrics)
sections = [
    "# 2026-09-08 精排候选对齐升级总结", "",
    "状态：训练、校准、历史复评、Linux HTTP 与性能测试完成。保留独立候选包和全过程指标；当前业务工程模型未替换。", "",
    "## 已确认的改进", "",
    f"相对上一轮正式反馈后模型，本轮历史 NDCG@20 提升 {(after['ndcg_at_20'] / previous['ndcg_at_20'] - 1):.1%}，Recall@50 提升 {(after['recall_at_50'] / previous['recall_at_50'] - 1):.1%}。覆盖率下降 {(previous['coverage_at_20'] - after['coverage_at_20']) * 100:.2f} 个百分点，相对下降 {(1 - after['coverage_at_20'] / previous['coverage_at_20']):.1%}。", "",
    "测试固定 7,581 个用户，其中 7,541 个可评估；所有模型使用相同商品目录、公共历史排除与 2026-02-26T15:13:00Z 请求时刻。", "",
    "| 模型 | NDCG@20 | Recall@50 | HitRate@20 | 覆盖率@20 | 短列表 / 类目违规 |", "|---|---:|---:|---:|---:|---:|", *quality, "",
    f"本轮与上一轮反馈后模型的配对 NDCG 差值为 {comparisons['previous_after']['ndcg_delta']:.6f}，2,000 次用户 bootstrap 的 95% 区间为 [{ci[0]:.6f}，{ci[1]:.6f}]。与冻结召回基线、热门基线的区间也为正。区间反映已查看的合成历史样本，不属于确认性盲测，更不能换算成真实 CTR 或成交提升。", "",
    "以下按冻结召回基线固定人群成员比较，避免词表变化混淆分母；冷启动样本量不足以做稳定收益结论。", "",
    "| 固定人群 | 可评估用户 | 基线 NDCG@20 | 候选 NDCG@20 |", "|---|---:|---:|---:|", *cohort_table, "",
    "## 实际升级内容", "",
    "- 在线和训练共用候选生成、补位、历史过滤和特征入口，训练组由最多五个对象扩大到本轮实际 500 个对象。未召回目标不注入候选池，召回失败仍进入完整列表评估分母。",
    "- 精排从五项历史特征扩展到十五项，补入双塔相似度、融合召回分数、缺失标记、热门名次和来源；旧精排分数不作为召回相关性输入。",
    "- 新特征 Schema 与 MMR 策略写入模型包，维度或顺序错误拒绝加载，旧包维持原策略；额外提供同分平均名次百分位和有界缩放。",
    "- 新增冻结召回的精排训练入口、验证窗口尺度校准入口，以及统一请求时刻的评估版本 5 和数据摘要/时间防泄漏检查。", "",
    "## 训练与选择经过", "",
    "召回包训练截止为 2026-02-06T12:34:00Z。精排训练从 2026-02-06T13:13:00Z 起，使用至 2 月 20 日之前的成熟标签；验证使用 2 月 20 日至 2 月 26 日 15:13Z 之前的成熟标签。双塔、索引和召回池均冻结，没有重复双塔搜索。", "",
    f"正式训练有 {sample_rows[0]['queries']:,} 个可评估用户，{sample_rows[0]['fit_groups']:,} 个可拟合候选组、{sample_rows[0]['rows']:,} 个候选行和 {sample_rows[0]['positive_rows']:,} 个正标签行；验证覆盖 {sample_rows[1]['queries']:,} 个用户，其中 {sample_rows[1]['fit_groups']:,} 个组命中目标。大量候选行不等于大量独立真实反馈。", "",
    f"五项与十五项特征各完成 12 次搜索，共 {len(trials)} 个 trial，正式过程记录 {len(epochs):,} 条逐轮指标。五项特征最佳为 9 棵树，十五项为 77 棵树。完整参数、训练曲线和特征增益见正式运行目录。", "",
    "首次扩展特征验证 NDCG 为 0.008590，但覆盖率 52.60% 低于门槛 54.31%，正式选择保留基线。随后仅在验证集追加 0.25 / 0.50 / 0.75 三档尺度校准，保留该失败记录且不修改门槛。0.25 档以 NDCG 0.008190、覆盖率 54.93% 达标，模型摘要随即冻结，之后才查看候选测试结果。", "",
    f"三个固定种子的完整历史 NDCG 为 {' / '.join(f'{value:.6f}' for value in seed_values)}，均值 {statistics.fmean(seed_values):.6f}，总体标准差 {statistics.pstdev(seed_values):.6f}。首种子在验证组上的重复预测最大差为 0；种子间有波动，没有依据测试结果更换种子或重选模型。", "",
    "![训练指标变化](../runs/trade-ranker-final-evidence-20260908/training-trends.png)", "",
    "## 接口、容量与性能", "",
    "47 项最终 Python 测试通过。对上一轮归档代码的旧包兼容性为 135/135；最终 Linux 容器与 Windows 的 HOME / DETAIL / CART、已知 / 冷启动、100 / 300 候选响应为 180/180 一致。额外 100 次并发换版请求有效，20 次越界指针请求保留已加载包，非法令牌返回 401。", "",
    "首次隔离 HTTP 运行失败：测试夹具的冷启动键未符合 64 位匿名哈希格式，且全量模型热切换把 2GiB 容器内存占满，块 I/O 显著增长，出现读取超时；未采集独立 swap 计数，不将换页推断当成实测。失败日志保留。夹具修正后在明确的 6GiB、4 CPU 容器复测通过，三次激活/回滚约 20.94 / 22.25 / 20.03 秒，包含调用方包校验与服务端加载，不能引用上一轮小目录约 1 秒换版作为全量指标。", "",
    "性能采样在离线任务完成后开始，同一镜像、容器、CPU/内存限制与直连配置分别测基线和候选。每档预热 15 秒、采样 60 秒；持续负载采样 120 秒并加入 1 秒思考时间。全量目录 6,262 个商品，10 个已知与 10 个冷启动虚拟用户。持续负载不是 30 分钟长稳验收。", "",
    "| 场景 | 请求数 | 有效模型率 | p95 / ms | p99 / ms | RPS | 本档门禁 |", "|---|---:|---:|---:|---:|---:|---|", *performance, "",
    f"正常 HTTP 采样共 {normal_count:,} 次请求。候选进程内复测三次各 2,000 调用，20 并发、300 候选，p95 为 {' / '.join(f'{row['latency_ms']['p95']:.1f}' for row in inprocess[1:])}ms；它与 HTTP p95 分开解释。HTTP 使用本轮保守的 300ms 门槛，ADR 原始 300ms 指进程内推断。", "",
    "![质量与延迟](../runs/trade-ranker-final-evidence-20260908/quality-and-latency.png)", "",
    "## 过程资源", "",
    "| 阶段 | 资源采样数 | 本机 RSS 采样峰值 / GiB | 容器内存采样峰值 / GiB | 容器可用 / 失败 / 未返回 |",
    "|---|---:|---:|---:|---:|", *resources_table, "",
    "容器与本机 Python 是不同进程，内存值不可直接相加为单个模型占用。训练阶段的容器数值还可能包含保持运行的原服务，逐条原始记录保留容器名。部分采样在前置命令超时等异常后没有返回容器状态，单独计为未返回，不补零。本轮精排在 CPU 训练，GPU 记录是整卡观察，主要来自其他本机应用，不能声称是本轮训练分配的显存。", "",
    "## 是否晋级", "",
    f"历史离线质量门禁：{'通过' if offline_pass else '未通过'}；候选 HTTP 综合门禁：{'通过' if http_pass else '未通过'}。最终用途保持 ENGINEERING；合成候选未接入真实业务商品目录，没有自然用户收益证据。当前 18084 服务保持上一轮本地工程包 `trade-20260906084521028172-6f240414-670bd78a`。", "",
    f"候选模型：`{model.name}`；manifest SHA256：`{selection['manifest_sha256']}`。模型在测试后未改写，决策绑定该摘要。", "",
    "## 后续优先完善的工作", "",
    "1. 建立新的自然流量或独立未来时间窗，按请求记录曝光、成熟点击/支付/退款及未点击曝光；沿用归因链和成熟边界，真实数据不能复用本轮把未观察候选标成零的合成负例路径。",
    "2. 保持候选和业务配额，减少特征提取与 HTTP 请求路径成本；用剖析证据决定优化点，并为全量热加载明确内存预算。大量预计算 JSON 候选表的加载和双包共存是已观测到的容量问题。",
    "3. 扩大冷启动与不同活跃度样本，持续报告固定人群和种子方差；覆盖率接近门槛，应同时跟踪卖家触达和曝光集中度。达到真实质量、运行和回滚门槛后再评审晋级。", "",
    "## 复现步骤与证据", "",
    "1. 保存数据、模型与源码摘要，确认 Docker、推荐 Python 环境和原模型包可用。",
    "2. 使用独立输出目录执行 `python -m linkverse_recommendation.training.ranker_iteration --config <正式配置>`；完整配置见正式运行的 `config.json`。",
    "3. 需要复现本轮校准时，执行 `python -m linkverse_recommendation.training.ranker_calibration --config <校准配置>`；输入是正式运行的冻结特征和树模型。",
    "4. 用归档 `final-evaluate.py` 在共同请求时刻复评，并保留所有种子的结果；禁止据测试结果调整参数。",
    "5. 使用归档 Dockerfile 构建隔离镜像，6GiB / 4 CPU 运行，执行 `check-runtime.py` 和 `run-performance.ps1`；新运行必须改输出目录和容器名。",
    "6. 汇总逐轮、逐请求、资源和失败记录；核对模型及运行源码摘要，生成晋级决策，再清理本轮测试容器。", "",
    "本轮没有修改依赖或 Java 业务代码，未新增业务反馈夹具或数据库结构变更；Identity 令牌请求正常经过本地服务。没有重跑 Java 或完整端到端支付反馈闭环。两个用户原有修改文件的摘要保持不变。首次失败、旧评估口径和正式阶段保留基线的选择均保留，不覆盖旧报告。", "",
    "证据目录：`../runs/trade-ranker-final-evidence-20260908/`，包含资源摘要、镜像与源码身份、晋级决策、图表、脚本和运行索引；各运行目录保留原始数组、用户/请求记录和源码 ZIP。", "",
]
report = root / "linkverse-platform/recommendation-experiments/iterations/20260908-精排候选对齐总结.md"
report.write_text("\n".join(sections), encoding="utf-8")
raw = evidence / "scripts-and-logs"
raw.mkdir(exist_ok=True)
for path in cache.iterdir():
    if path.suffix in {".py", ".ps1", ".log", ".json"} or path.name == "Dockerfile":
        shutil.copy2(path, raw / path.name)
write_json(evidence / "run-index.json", {name: {"path": str(runs / name), "status": resource_summary[name]["status"],
           "config_sha256": sha256_file(runs / name / "config.json")} for name in run_names})
write_json(evidence / "summary.json", {"quality": metrics, "paired": comparisons, "decision": decision,
           "training_trials": len(trials), "training_epochs": len(epochs), "normal_http_requests": normal_count,
           "report": str(report)})
print(str(report))
