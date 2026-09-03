# LinkVerse Recommendation

本目录实现一个独立 Recommendation 部署单元。Trade 是首个领域；未来 Forum 必须实现新的 `DomainAdapter` 并使用独立数据集、标签、对象塔、索引和模型包，不能直接混合 Trade 标签。

## 本地环境

```powershell
conda env create -f environment.yml
conda activate linkverse-recommendation
python -m pip install --no-deps -e .
python -m linkverse_recommendation.training.cuda_check
pytest
```

CUDA 检查失败时训练命令会立即终止，不会静默回退到 CPU。在线容器只使用 CPU Faiss；模型目录以只读方式挂载到 `/models`。

## 数据和训练

旧数据只允许经显式迁移命令读入，不构成运行时依赖：

```powershell
python -m linkverse_recommendation.data.legacy `
  --items <items_new.csv> --users <users_new.csv> --interactions <interactions_new.csv> `
  --output datasets/trade-synthetic

python -m linkverse_recommendation.training.cli `
  --dataset datasets/trade-synthetic --output artifacts/candidates --require-cuda
```

清洗器删除 `rating`、`last3m` 和全历史聚合特征，保留原始时间与行为标记，将旧 `buy` 改名为 `legacy_buy_signal`，并把全部样本标记为 `SYNTHETIC`。原始数据、Parquet、完整 trial、权重和索引均由根目录 `.gitignore` 排除。

真实快照先由 Trade 的 `snapshot-export` profile 只读导出匿名 JSONL。该 profile 使用 `RECOMMENDATION_EXPORT_DB_USER` / `RECOMMENDATION_EXPORT_DB_PASSWORD` 独立配置并强制连接池只读；本地未设置时才回退到隔离环境的 Trade 账号。随后执行：

```powershell
python -m linkverse_recommendation.data.snapshot --snapshot <快照目录>
```

训练时，同一归因链的行为取最高等级；30 分钟未转化曝光成为 `REAL_EXPOSURE`，主动退款成为 `REAL_REFUND`，两者优先用作硬负样本。运营退款保持中性。所有随机负样本只从该行为发生时已经发布、且不属于用户正样本的商品中产生。`REAL` 与 `SYNTHETIC` 混合时训练会直接失败。

## 模型发布

训练只生成候选模型包。先执行模型包校验和影子比较，再原子激活：

```powershell
python -m linkverse_recommendation.serving.activate `
  --model-root <模型根目录> --bundle <候选模型包目录>
```

在线进程检测到指针变化后先完整校验新包，成功后才切换；失败时继续使用旧模型。
