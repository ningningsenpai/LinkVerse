# MVP 接口与测试报告

## 1. 测试基线

- 测试日期：2026-08-25；工作区基于提交 `cfd03cb`；JDK：`D:\Java JDK\jdk-21.0.12+8`；
- 服务入口：Gateway `http://127.0.0.1:18080`；
- 中间件：MySQL 8.4、Redis 8.2、RabbitMQ 4.3、Nacos；
- 自动化：JUnit 5、Testcontainers、WireMock、Toxiproxy、k6 2.2.0；
- 测试机：Windows 11，AMD Ryzen 7 6800H（8 核 16 线程），13.7 GB 内存；Docker Desktop 29.5.2 分配 16 CPU、约 6.6 GB 内存；
- Java 服务未显式设置 `-Xms/-Xmx`，使用 JDK 21 默认人体工学参数；压测数据为 1000 个真实注册用户、两个库存 10000 的独立商品和一个库存 100 的独立秒杀活动；
- `mvnw.cmd clean verify`：11 个模块通过，126 个测试，0 failure、0 error、0 skipped；
- `acceptance.ps1` 与 `fault-test.ps1` 通过。令牌、密码、签名和密钥均以 `<脱敏>` 表示。

## 2. 认证接口

| 方法与路径 | 目的及前置条件 | Header、参数与请求体 | 预期结果 | 实际摘要与结论 |
|---|---|---|---|---|
| `POST /api/v1/auth/register` | 创建本地用户；用户名未注册 | JSON：`username=linkverse_fault_***`、`password=<脱敏>` | `201`；返回 `user_id`、`username`、`created_at` | 故障测试创建隔离用户返回 `201`，通过 |
| `POST /api/v1/auth/login` | 用户登录；账号有效 | JSON：用户名、`password=<脱敏>` | `200`；返回 `access_token`、`token_type=Bearer`、`expires_in` | seed、验收和故障测试均返回 `200`，令牌未落入报告，通过 |
| `POST /oauth2/token` | Trade 获取服务令牌；仅本地已注册客户端 | Basic `<client_id>:<secret>`；表单 `grant_type=client_credentials` | `200`；服务 JWT 含约定 audience/scope | Trade 创建支付 Intent 时实际获取并使用服务 JWT，Payment 鉴权通过 |

## 3. 交易与支付公开接口

所有用户接口均要求 `Authorization: Bearer <脱敏>`。创建操作还要求 8～64 字符的 `Idempotency-Key`。

| 方法与路径 | 测试目的及参数 | 预期状态码、字段与业务状态 | 实际摘要与结论 |
|---|---|---|---|
| `GET /api/v1/listings/{listingId}` | 查询商品；`listingId=10001` | `200`；`listing_id`、`unit_price`、`currency`、`status`、`available` | `200`，商品状态 `ON_SALE`，通过 |
| `POST /api/v1/orders` | 幂等立即购买；JSON `listing_id=10001, quantity=1` | 首次 `201`、同键同请求 `200`；返回 `order_no`、`PENDING_PAYMENT`、服务端金额快照 | 首次 `201`，同键重放 `200` 且订单号一致，通过 |
| `GET /api/v1/orders/{orderNo}` | 仅订单所有者查询 | `200`；订单、金额、过期时间及不可变 `item` 快照 | 支付前后均为 `200`；事件消费后状态为 `PAID`，通过 |
| `PUT /api/v1/orders/{orderNo}/payment-intent` | Trade 校验订单后创建支付 | `200`；`intent_no`、`order_no`、服务端金额、`PENDING` | `200`；Trade 通过服务 JWT 调用 Payment，跨 Schema 无直连，通过 |
| `GET /api/v1/payment-intents/{intentNo}` | 仅买家查询支付 Intent | `200`；支付金额、渠道、状态及时间字段 | Mock 确认后返回 `200/SUCCEEDED`，通过 |
| `POST /api/v1/mock-provider/payment-intents/{intentNo}/confirm` | local/test 模拟支付；路径 Intent、幂等键 | `200/SUCCEEDED`；同键重放不得重复迁移 | 连续调用 100 次均 `200`，数据库仅一次状态迁移，通过 |
| `POST /api/v1/payments/callbacks/mock` | Provider HMAC 回调 | Header：`X-Mock-Timestamp`、`X-Mock-Signature=<脱敏>`；JSON 含通知号、渠道流水、Intent、订单、商户、金额、币种、状态 | 合法回调 `200/SUCCEEDED`；伪造签名 `401`；错误商户/金额/币种 `400` | MySQL 集成测试验证全部分支；错误回调未写回调记录，通过 |

金额、用户、订单状态和支付结果均由服务端订单快照及支付记录校验，不信任客户端字段。

## 4. Payment 内部接口

内部接口仅接受 Trade 服务 JWT，Header 使用 `Authorization: Bearer <服务令牌>`。

| 方法与路径 | 参数 | 预期结果 | 实际摘要与结论 |
|---|---|---|---|
| `POST /internal/v1/payment-intents` | `Idempotency-Key=order_no`；JSON：`order_no`、`buyer_id`、`merchant_id`、`amount`、`currency`、`expire_at` | `200/PENDING`；同订单并发只创建一个 Intent | 黑盒支付创建通过；100 个并发请求仅一个 Intent，通过 |
| `GET /internal/v1/payment-intents/by-order/{orderNo}` | 订单号 | `200`；返回对应 Intent | WireMock 契约与 Toxiproxy 断网恢复查询 2/2 通过 |
| `PUT /internal/v1/payment-intents/{orderNo}/close` | 路径订单号必须与请求快照一致 | `200/CLOSED`，或在迟到支付时进入退款补偿 | 到期关单、迟到支付退款及 1000 轮竞态测试通过 |

## 5. 秒杀接口

| 方法与路径 | 目的及参数 | 预期结果 | 实际摘要与结论 |
|---|---|---|---|
| `GET /api/v1/seckill/campaigns/{campaignId}` | 查询活动；`campaignId=20001` | `200`；`campaign_id`、`listing_id`、价格、币种、`OPEN/CLOSED`、时间窗 | 返回 `200/OPEN`，通过 |
| `POST /api/v1/seckill/campaigns/{campaignId}/reservations` | Redis Lua 原子准入；用户 JWT 与幂等键 | 接受为 `202`；售罄/重复为受控 `409`；Redis 或 RabbitMQ 不可用为 `503` | 正常返回 `202`；两类中间件故障均 `503`，普通下单仍 `201`，通过 |
| `GET /api/v1/seckill/reservations/{reservationNo}` | 仅预约所有者查询异步结果 | `200`；`reservation_no`、状态、订单号或失败码 | 返回 `200`，预约号一致，通过 |

## 6. 运维接口与脚本

- `GET /internal/v1/outbox-events/{eventId}`：查询事件状态，不返回业务载荷；
- `POST /internal/v1/outbox-events/{eventId}/replay`：仅 `PARKED` 可重放，事件 ID 不变；
- `POST /internal/v1/reconciliation`：Trade 与 Payment 分别执行订单、库存、预约、支付、退款差异扫描；
- `prepare-load-test.ps1`：每次创建独立商品、库存和活动，不修改演示数据；
- `load-test.ps1`：刷新本地令牌、执行三组 k6 场景并核验 MySQL/Redis 最终状态；
- `replay.ps1` 必须提供 `-ConfirmReplay`；`clean.ps1` 删除卷必须提供 `-RemoveData -ConfirmProject linkverse-mvp` 并接受高风险确认。

本次黑盒对账结果：`closingOrder=0`、`intermediateReservation=0`、`reservationOrderMismatch=0`、`expiredPending=0`、`uncertainRefund=0`、`openException=0`。压测活动最终 MySQL 库存为 0，Redis 投影库存为 0，Redis `pending` 与 `pending-order` 均为 0。测试数据卷累计 Trade Outbox 1004 条、Payment Outbox 1717 条，状态全部为 `PUBLISHED`；四个 RabbitMQ 业务/停车队列的就绪和未确认消息均为 0。

## 7. 关键一致性与故障场景

| 场景 | 实测结果 |
|---|---|
| 库存 100、1000 个独立用户 HTTP 秒杀 | 100 VU 完成 1000 次请求；恰好 100 个接受、900 个受控拒绝、100 个订单，MySQL/Redis 库存均为 0，无超卖 |
| 同一用户并发 100 次 | 仅一个有效预约；同一普通订单幂等键并发 100 次仅一个订单 |
| 支付回调重放 100 次 | 一条回调记录、一次状态迁移、一个 Outbox 事实 |
| 创建/关单竞态 1000 轮 | 每轮收敛为合法单一状态，通过 |
| 支付成功/关单竞态 1000 轮 | 每轮收敛；迟到成功不重开订单并进入退款补偿，通过 |
| MySQL 已提交后 RabbitMQ 中断 | 支付仍 `SUCCEEDED`；Broker 恢复后 Outbox 将订单收敛为 `PAID` |
| 消费提交后 ACK 前等价重投 | 消费去重测试连续重放 100 次；失败处理不提前记录消费事实，重投成功后再记录 |
| Redis 预扣后进程中断 | pending 请求保持稳定事件号，可重投或按预约状态条件补偿 |
| 毒消息与停车队列 | 第五次失败标记 `PARKED` 并进入停车路由；后续正常消息继续处理 |
| Redis/RabbitMQ 故障降级 | 新秒杀请求 `503`；普通订单 `201`，恢复后链路收敛 |

## 8. k6 实测

k6 固定为 `2.2.0`，用户令牌仅在本地启动时延长为 2 小时；应用默认值仍为 15 分钟。正式结果来自 `load-20260825T022835Z`：

| 场景 | 负载模型 | 迭代 | 每次迭代 |
|---|---|---:|---|
| 普通交易 | 50 VU，共享迭代 | 1000 | 创建订单并查询，共 2 个 HTTP 请求 |
| 支付链路 | 25 VU，共享迭代 | 500 | 创建订单、Intent、Mock 确认，共 3 个 HTTP 请求 |
| 商品秒杀 | 100 VU，共享迭代 | 1000 | 1000 个独立用户各请求一次，共 1000 个 HTTP 请求 |

| 场景 | 请求数 | checks rate | HTTP failure rate | 吞吐（迭代/秒） | p95 | p99 |
|---|---:|---:|---:|---:|---:|---:|
| 普通交易 | 2000 | 100% | 0% | 95.88 | 415.08 ms | 507.36 ms |
| 支付链路 | 1500 | 100% | 0% | 51.92 | 302.65 ms | 703.92 ms |
| 商品秒杀 | 1000 | 100% | 0% | 1083.10 | 258.55 ms | 349.21 ms |

秒杀的 `202` 和 `409` 都是预期状态，k6 将二者标记为受控响应；最终为 100 个 `202`、900 个 `409`、0 个非预期响应。原始 JSON 与汇总位于 Git 忽略目录 `linkverse-platform/test-results/{raw,generated}/load-20260825T022835Z/`。

预检曾以 1000 VU 同时建连，100 个请求被接受后出现 472 个本机连接拒绝；四个 Java 进程仍存活且健康，推断瓶颈是本机 TCP 建连队列而非业务状态机。该失败结果不计入正式性能数字，也说明当前单机结果不能外推为 1000 瞬时并发能力。

## 9. 验收结论

阶段 7 后端 MVP 门禁通过：公开交易、支付和秒杀契约可用，服务间边界、幂等、无超卖、支付竞态、故障恢复、停车重放和对账均有自动化或黑盒证据。未包含真实支付渠道、生产高可用、前端、论坛、推荐系统和性能容量承诺。
