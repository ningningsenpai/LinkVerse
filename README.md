# LinkVerse / 知链空间

LinkVerse 是面向图书与知识类商品交易的微服务平台。当前后端聚焦账号认证、商品与库存、普通订单、独立支付链路、商品秒杀及可靠消息处理；论坛、推荐系统、Elasticsearch、真实支付渠道和前端不在当前实现范围。

## 技术栈

- Java 21、Spring Boot 3.5、Spring Cloud 2025、Spring Cloud Alibaba；
- Spring Authorization Server、OAuth2 Resource Server、Gateway、Nacos；
- MySQL 8.4、Redis 8.2、RabbitMQ 4.3、Flyway；
- Micrometer、结构化日志、Outbox、幂等消费与定时对账；
- JUnit 5、Testcontainers、WireMock、Toxiproxy、k6 2.2.0。

## 服务职责

- `linkverse-gateway`：统一入口、路由、JWT 预校验、请求 ID 与基础限流；
- `linkverse-identity`：用户注册、登录、用户令牌和 Trade 服务令牌；
- `linkverse-trade`：商品、库存、订单、秒杀预约、支付事件消费与交易对账；
- `linkverse-payment`：支付 Intent、Mock HMAC 回调、关单、退款补偿、Outbox 与支付对账。

MySQL 是订单、库存和支付的事实源；Redis 仅承担秒杀原子准入与派生状态；RabbitMQ 负责跨服务事件传递。Payment 不访问 Trade Schema，Trade 通过服务 JWT 调用 Payment，并以 Outbox、发布确认、幂等消费和对账保证最终收敛。

## 代码结构

```text
LinkVerse/
├─ linkverse-platform/
│  ├─ backend/          # Maven Reactor、共享 Starter 与四个 Java 服务
│  ├─ infrastructure/   # MySQL、Redis、RabbitMQ、Nacos Compose
│  ├─ scripts/          # 启停、数据准备、验收、故障与对账脚本
│  ├─ tests/k6/         # 普通交易、支付链路与秒杀测试
│  └─ README.md
├─ docs/                # 设计、阶段验收和接口测试报告
└─ .agents/             # 项目开发规范
```

旧 Java/Python 快照由 `.gitignore` 隔离，仅用于只读核对，不参与新平台构建和运行。

## 启动与测试

在 `linkverse-platform/` 下执行：

```powershell
.\scripts\prepare-local.ps1
.\scripts\start.ps1
.\scripts\seed.ps1
.\scripts\acceptance.ps1
.\scripts\load-test.ps1
```

运行故障恢复与全量 Java 测试：

```powershell
.\scripts\fault-test.ps1
Set-Location backend
.\mvnw.cmd clean verify
```

本地固定使用 `D:\Java JDK\jdk-21.0.12+8`。接口与实测结论见 [`docs/13-MVP接口与测试报告.md`](docs/13-MVP接口与测试报告.md)。
