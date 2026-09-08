# Trade 推荐实验记录

本目录只提交可审查的配置、汇总统计、指标、五类用户案例、图表与模型卡。原始行为、Parquet、完整 Optuna trial、模型权重和 Faiss 索引由仓库根目录 `.gitignore` 排除。

后续训练和测试按[迭代计划](../../docs/14-推荐系统训练测试与迭代计划.md)执行，逐轮填写[迭代记录](iteration-template.md)。[2026-09-06 准备记录](iterations/20260906-准备记录.md)保存本机资源、应用就绪检查和旧模型身份；它不代表新训练或 HTTP 测试已经完成。正式运行前须先完成计划中的评估与数据修复。

2026-09-08 的候选对齐升级见[本轮总结](iterations/20260908-精排候选对齐总结.md)和[实验协议](../../docs/15-推荐系统精排候选对齐迭代.md)，使用 `training.ranker_iteration` 与 `training.ranker_calibration` 固定配置入口。2026-09-06 的完整训练、反馈和运行验证见[上一轮总结](iterations/20260906-执行总结.md)及[复现步骤](iterations/20260906-复现与升级步骤.md)。下面保留早期脚本入口供旧运行复现；当前质量比较须使用冻结边界、修正的同分指标、相同请求时刻及完整 HTTP 验证。

每次运行必须通过实验入口和 `RunRecorder` 生成独立的 `runs/<run_id>/`，禁止手工修改指标。若某项测试未执行，报告必须明确写成“未执行”，不能用零值冒充实测结果。

```powershell
..\scripts\recommendation\run-experiment.ps1 `
  -BeforeDataset ..\recommendation\datasets\trade-synthetic `
  -AfterDataset ..\recommendation\datasets\trade-feedback `
  -ModelRoot ..\recommendation\artifacts\candidates
```

当前所有旧数据结果均标注为 `SYNTHETIC`，只用于验证工程链路，不能用于线上质量宣传。

正式实验完成后，可对候选模型包执行 20 并发 CPU 进程内推断基准：

```powershell
..\scripts\recommendation\benchmark-model.ps1 `
  -Bundle ..\recommendation\artifacts\candidates\after\<model_version> `
  -Output .\runs\<run_id>\serving-benchmark.json
```

`serving-benchmark.json` 只证明 Python 模型加载、Faiss 查询和候选补位的并发性能，不能替代
Gateway → Trade → Recommendation 的真实 HTTP 压测；未执行 HTTP 链路时模型不得晋级。
