# 数据审计

- 原始/清洗商品：6262 / 6262
- 原始事件/训练样本：94934 / 94934
- `rating` 与行为不一致行：55249
- 删除的训练字段：['item_buy_last3m', 'item_cart_last3m', 'item_click_last3m', 'item_forward_last3m', 'rating', 'user_buy_last3m', 'user_cart_last3m', 'user_click_last3m', 'user_forward_last3m']
- 旧购买仅保留为 `legacy_buy_signal`。
- 曝光负样本无法从旧数据恢复；负采样均标记为 `SAMPLED`。
- 本轮仅是合成工程验证。
