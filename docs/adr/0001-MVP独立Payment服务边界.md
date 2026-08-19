# ADR-0001：MVP 独立 Payment 服务边界

- 状态：已接受（仅适用于求职 MVP）
- 日期：2026-08-19
- 决策者：LinkVerse 项目所有者
- 关联文档：`docs/04-MVP开发步骤与注意事项.md`

## 背景与目标

求职 MVP 需要在有限周期内完整证明支付创建、回调验签、关单竞态、迟到成功补偿和对账。长期规划原本把支付放在 Trade 内，但该边界不能清楚展示资金事实与交易订单之间的独立所有权、服务身份和故障恢复。

本决策把 Payment 从 Trade 拆为独立 Java 部署单元，使支付事实、渠道交互和退款补偿具有单一所有者，同时禁止通过跨 Schema 事务伪造强一致。

## 当前约束与证据

- MVP 固定四个 Java 部署单元：Gateway、Identity、Trade、Payment；Forum、Python Recommendation 与 Elasticsearch 延期。
- MySQL 是唯一业务事实源。Trade 只能写 `linkverse_mvp_trade`，Payment 只能写 `linkverse_mvp_payment`。
- Redis、RabbitMQ 和 Nacos 不是支付或订单事实源。
- Trade 与 Payment 之间只能使用受认证的内部 API 和版本化事件，不得跨 Schema Join、共享 Entity 或直接更新对方表。
- 本 ADR 只冻结阶段 1～2 的模块、安全和数据边界；Payment Intent、回调、退款与 Outbox 业务表在后续支付阶段通过 Flyway 新增。

## 备选方案

1. **支付继续位于 Trade。** 本地事务简单，但无法独立证明支付服务身份、渠道隔离和跨边界恢复，不符合本期展示重点。
2. **Payment 独立部署并独占 Schema。** 边界清晰，但必须接受两个本地事务、至少一次事件和对账带来的复杂度。
3. **使用 Seata/XA 连接 Trade 与 Payment。** 表面上减少最终一致性处理，实际扩大故障域并把外部支付渠道包装成不可成立的全局事务，因此拒绝。

## 决策

采用方案 2：

- `linkverse-trade` 拥有商品、库存、秒杀预约、订单和超时关单。
- `linkverse-payment` 拥有 Payment Intent、渠道回调、关闭、退款补偿、支付异常和支付对账。
- 用户从 Gateway 访问外部接口；Trade 以 `client_credentials` 获得 `aud=linkverse-payment` 的短期服务 JWT 后调用 Payment 内部接口。
- 用户 JWT 不得调用 Payment 内部接口；Provider 回调不要求用户 JWT，而是校验渠道签名、时间戳、商户、订单、金额和币种。
- 支付成功采用两个本地事务：Payment 条件迁移支付状态并写本地 Outbox；Trade 幂等消费后复核事实并条件迁移订单状态。
- 同步请求超时或结果未知时，只能使用相同订单号和幂等键有限重试，或查询 Payment 当前事实。

## 数据所有权与一致性

| 数据 | 唯一写入者 | 事实位置 |
|---|---|---|
| 订单、订单金额快照、库存 | Trade | `linkverse_mvp_trade` |
| Payment Intent、渠道流水、回调摘要 | Payment | `linkverse_mvp_payment` |
| 退款尝试、迟到支付异常 | Payment | `linkverse_mvp_payment` |
| 消息投递状态 | 各生产服务 | 各自 Schema 的 Outbox |
| 消费去重 | 各消费服务 | 各自 Schema 的消费记录 |

Payment 不读取 Trade 表。创建或关闭支付时，Trade 传递订单号、用户、金额、币种、商户和过期时间的服务端快照；Payment 将其保存为不可变校验依据。

消息语义固定为至少一次。发布确认不确定时允许以同一 `event_id` 重发；Trade 以唯一约束去重，并在同一事务内写消费记录和订单状态。系统不宣称 exactly-once。

## 失败模式与恢复

- **创建响应丢失：** Trade 使用相同 `order_no` 和幂等键重试或查询，Payment 返回已存在 Intent。
- **支付事件重复：** Trade 消费去重，重复消息不重复迁移订单。
- **支付成功与关单并发：** Trade 先进入 `CLOSING`，再以 Payment 当前事实决定 `PAID` 或 `CLOSED`；未知结果不释放库存。
- **迟到支付成功：** 已关闭订单不重开。Payment 记录异常并进入退款补偿，明确退款成功后才允许 Trade 完成释放。
- **RabbitMQ 不可用：** Payment 事实与 Outbox 已在本地事务提交，Relay 有界重试；恢复后继续投递。
- **消费者提交后、ACK 前宕机：** RabbitMQ 重投，Trade 的消费唯一键阻止重复副作用。

## 可观测性与安全

- 日志和 Trace 关联 `request_id`、`trace_id`、`order_no`、`intent_no` 与 `event_id`。
- 不记录 JWT、客户端密钥、渠道密钥、完整 Authorization Header、支付回调原文或完整支付参数。
- 内部 Payment API 同时校验签名、`iss`、`aud`、`exp`、客户端主体和授权 scope。
- Payment 内部接口不经公共 Gateway 暴露；本地 Mock Provider 仅在 `local/test` profile 启用。
- 数据库应用账号不得拥有 DDL 权限，迁移账号不得访问其他 Schema。

## 验证标准

- 四个 Java 模块可独立构建和启动，Payment 只连接自身 Schema。
- Trade 用户令牌无法调用 Payment 内部接口；合法 Trade 服务令牌可以调用。
- 伪造、过期、错误签发者和错误受众令牌全部被拒绝。
- 后续支付阶段必须覆盖重复创建、重复回调、伪造回调、关单竞态、迟到支付、Outbox 重投和 ACK 前宕机。
- 通过授权查询证明 Trade 与 Payment 数据库账号不能跨 Schema 读取或写入。

## 迁移与回滚

当前新平台尚无业务数据，因此阶段 1～2 只创建独立空 Schema、账号、服务骨架和安全契约，不迁移旧支付记录。

回滚时停止新 Payment 与新 Compose，回退新代码和配置；旧快照与旧数据不受影响。已执行的安全隔离和凭据轮换不得回滚。不得通过把 Payment 表直接并回 Trade Schema 作为应急回滚。

恢复 Forum 和 Recommendation 的长期规划前，必须新增 ADR 决定 Payment 是否继续独立。若继续独立，目标将成为六个部署单元；若合回 Trade，必须先设计数据迁移、兼容窗口、审计与回滚，不能直接改写本 ADR。
