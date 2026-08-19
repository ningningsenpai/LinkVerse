# LinkVerse 求职 MVP 新平台

`linkverse-platform/` 是唯一的新系统代码根目录。旧 Java/Python 目录只用于只读核对，不得被新代码导入、构建、部署或作为运行时依赖。

当前已完成阶段 1 基础环境与阶段 2 最小认证/安全实现，并通过全量构建、底层运行验证和 HTTP 黑盒 E2E；Trade/Payment 业务尚未实现，本地 MVP 不能视为可上线业务系统。

## MVP 边界

本期固定四个 Java 部署单元：

- `linkverse-gateway`：公共入口、路由、请求 ID、JWT 预校验和基础限流；
- `linkverse-identity`：注册、登录、用户 JWT 与服务 JWT；
- `linkverse-trade`：后续承载商品、库存、秒杀预约和订单；
- `linkverse-payment`：后续承载支付意图、回调、关闭、退款补偿和支付对账。

Forum、聊天、Python Recommendation、Elasticsearch、真实资金支付与完整前端均不在阶段 1～2。Payment 独立边界见 [`docs/adr/0001-MVP独立Payment服务边界.md`](../docs/adr/0001-MVP独立Payment服务边界.md)。

## 目录

```text
linkverse-platform/
├─ backend/            # 扁平 Maven Reactor、core、四个 Starter 与四个 Java 服务
├─ infrastructure/     # 隔离的 MySQL、Redis、RabbitMQ、Nacos
├─ scripts/            # 幂等初始化与验收入口
└─ README.md
```

`backend/` 下的模块均为 Reactor 根目录的扁平子模块：`platform-bom`、`platform-core`、`platform-starter-web`、`platform-starter-security`、`platform-starter-observability`、`platform-starter-messaging` 和四个 `linkverse-*` 部署单元；不存在 `starters/` 或 `services/` 中间层级。

## 本地基础设施

```powershell
Set-Location linkverse-platform
./scripts/prepare-local.ps1
./scripts/bootstrap.ps1
./scripts/verify.ps1
```

`prepare-local.ps1` 只创建不覆盖：它生成忽略的 `.env` 与 Identity RSA 2048 PEM，并且不回显秘密值。

镜像、端口、三个 Schema、六个数据库账号及 namespace/vhost/key 前缀见 [`infrastructure/README.md`](infrastructure/README.md)。

2026-08-19 已验证四个中间件 healthy、六账号所属 Schema 访问成功与跨 Schema 拒绝、Nacos 四服务各 `1` 个 healthy 实例、精确镜像 `tag@digest`，以及 bootstrap/verify 二次重放。

## 后端验证

从 `linkverse-platform/backend/` 执行：

```powershell
./mvnw.cmd clean verify
```

2026-08-19 在 JDK `21.0.12`、Maven Wrapper `3.9.10` 环境下，在线 `./mvnw.cmd clean verify` 与离线 `./mvnw.cmd -o clean verify` 均 BUILD SUCCESS：`84` 个测试，`0` failure、`0` error、`0` skipped；Identity 的 `2` 个 MySQL `8.4.11` Testcontainers 用例在两次构建中均实际运行。

阶段 1～2 的真实验收状态只记录在 [`docs/05-MVP阶段1与阶段2验收记录.md`](../docs/05-MVP阶段1与阶段2验收记录.md)；其中 HTTP 黑盒 E2E 已通过，Provider 回调仍只是阶段 2 的 fail-closed 边界，不代表支付业务已实现。
