# LinkVerse 后端平台

`linkverse-platform/` 是 LinkVerse 新后端的唯一运行代码根目录，提供图书商品交易、支付和秒杀所需的轻量微服务闭环。

## 模块职责

- `backend/linkverse-gateway`：统一 HTTP 入口、路由、安全预校验和请求上下文；
- `backend/linkverse-identity`：注册、登录、OAuth2 用户/服务令牌；
- `backend/linkverse-trade`：商品、MySQL 库存、订单、秒杀及交易侧对账；
- `backend/linkverse-payment`：支付 Intent、Mock 回调、关单、退款补偿和支付侧对账；
- `backend/platform-*`：统一依赖、错误、安全、可观测和可靠消息能力；
- `infrastructure/`：MySQL、Redis、RabbitMQ、Nacos 的 Docker Compose 配置；
- `scripts/`：本地环境准备、启停、种子数据、验收、故障注入、对账和重放；
- `tests/k6/`：锁定 k6 2.2.0 的普通交易、支付与秒杀场景。

## 数据与一致性

Identity、Trade、Payment 分别拥有独立 MySQL Schema 和最小权限账号。普通订单使用 MySQL 条件更新扣减库存；秒杀先由 Redis Lua 原子准入，再由 RabbitMQ 异步建单，MySQL 仍是最终库存防线。支付事实通过本地 Outbox 发布，Trade 幂等消费；失败事件采用有限重试、停车队列、人工重放和定时对账。

## 本地启动

```powershell
Set-Location linkverse-platform
.\scripts\prepare-local.ps1
.\scripts\start.ps1
.\scripts\seed.ps1
```

`start.ps1` 只启动四个中间件和 Gateway、Identity、Trade、Payment 四个 Java 服务。停止环境且保留数据卷：

```powershell
.\scripts\clean.ps1 -Confirm:$false
```

删除命名卷必须显式执行 `-RemoveData -ConfirmProject linkverse-mvp`，并接受 PowerShell 高风险确认。

## 测试入口

```powershell
.\scripts\acceptance.ps1
.\scripts\fault-test.ps1
.\scripts\reconcile.ps1
Set-Location backend
.\mvnw.cmd clean verify
```

原始响应和运行时令牌保存在 Git 忽略目录。人工重放仅允许 `PARKED` 事件，并要求 `replay.ps1 -ConfirmReplay`。完整接口、参数、预期响应和实测结论见 [`../docs/13-MVP接口与测试报告.md`](../docs/13-MVP接口与测试报告.md)。
