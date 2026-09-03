# Trade 推荐实验记录

本目录只提交可审查的配置、汇总统计、指标、五类用户案例、图表与模型卡。原始行为、Parquet、完整 Optuna trial、模型权重和 Faiss 索引由仓库根目录 `.gitignore` 排除。

每次运行必须通过同一个实验命令生成 `runs/<run_id>/`，禁止手工修改指标。若某项测试未执行，报告必须明确写成“未执行”，不能用零值冒充实测结果。

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
