# 训练过程摘要

运行：`trade-local-corrected-20260906T084509Z`

已记录双塔 5 次搜索、精排 5 次搜索、46 条 epoch 记录及 2 次资源采样。

图中的 epoch 损失与验证分数来自同一个由验证集选中的 trial；不将不同超参数 trial 的损失连接成单次训练曲线。

![训练和资源曲线](D:/Code/ning/LinkVerse/linkverse-platform/recommendation-experiments/runs/trade-local-corrected-20260906T084509Z/training-curves.png)

设备显存包含桌面等其他进程；PyTorch 已分配峰值单独记录，不能把二者差值直接解释为模型内存泄漏。

排序收益须结合冻结测试窗口的配对比较判断；搜索中的最佳验证分数不等于线上收益。
