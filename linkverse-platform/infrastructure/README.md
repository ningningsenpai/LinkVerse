# LinkVerse MVP 本地基础设施

本目录只提供阶段 1～2 的隔离本地环境，不代表生产高可用部署。默认启动 MySQL、Redis、RabbitMQ 和 Nacos；论坛、推荐、Elasticsearch、Kafka、Seata 与 Sentinel 不在本期范围。

## 版本与供应链状态

| 组件 | 固定镜像标签 | 摘要状态 |
|---|---|---|
| MySQL | `mysql:8.4.11` | 待网络恢复后核验 |
| Redis | `redis:8.2.8` | 待网络恢复后核验 |
| RabbitMQ | `rabbitmq:4.3.4-management` | 待网络恢复后核验 |
| Nacos | `nacos/nacos-server:v3.2.3` | 待网络恢复后核验 |

镜像标签、端口和隔离名称以 [`versions.env`](versions.env) 为唯一真值。Compose 在摘要存在时组装 `tag@sha256:...` 不可变引用。2026-08-19 执行 `docker manifest inspect` 时无法连接 Docker Hub，因此摘要字段保持为空；这是一项明确的阶段 1 供应链门禁，禁止填入猜测值。`verify.ps1 -StaticOnly` 会在 Compose 语法校验后以非零退出报告该外部阻断，不会将供应链门禁误报为通过。

## 隔离边界

| 资源 | 固定值 |
|---|---|
| Compose 项目 | `linkverse-mvp` |
| MySQL Schema | `linkverse_mvp_identity`、`linkverse_mvp_trade`、`linkverse_mvp_payment` |
| Nacos namespace ID | `linkverse-mvp` |
| Nacos group | `LINKVERSE_MVP` |
| Nacos 服务角色 | `linkverse_mvp_runtime` |
| RabbitMQ vhost | `linkverse-mvp` |
| Redis Key 前缀 | `lv:mvp:{domain}:` |

每个 Schema 有两个账号：`*_app` 只拥有运行期读写权限，`*_migrator` 额外拥有 Flyway 所需的 DDL 权限。账号不能访问其他业务 Schema；服务不得跨 Schema Join。

所有宿主端口只绑定到 `127.0.0.1`：MySQL `13307`、Redis `16380`、RabbitMQ AMQP `15674`、RabbitMQ 管理端 `15675`、Nacos 控制台 `18091`、Nacos 服务端 `18849`、Nacos gRPC `19849`。

## 启动

从 `linkverse-platform/` 执行：

```powershell
./scripts/prepare-local.ps1
./scripts/bootstrap.ps1
./scripts/verify.ps1
```

`prepare-local.ps1` 使用系统密码学随机数生成忽略的 `infrastructure/.env`，并在同样忽略的 `infrastructure/secrets/` 下生成 PKCS#8 `identity-private.pem` 和 X.509 `identity-public.pem`（RSA 2048）。该目录在首次运行前故意不存在，不应添加占位密钥。任一文件已存在时，脚本都不会覆盖它；密钥对不匹配或只留下公钥时会明确失败。脚本不会回显秘密。

`prepare-local.ps1` 会自动满足以下规则。若必须手工维护，MySQL 业务密码仅允许 16～128 位字母、数字及 `_@%+=:,.-`。`NACOS_AUTH_TOKEN` 必须是至少 32 个随机字节的 Base64 编码，可在 PowerShell 中生成：

```powershell
$tokenBytes = New-Object byte[] 48
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($tokenBytes)
[Convert]::ToBase64String($tokenBytes)
```

`bootstrap.ps1` 可重复执行：它不会删除数据，会重新收敛六个数据库账号的授权，并只在目标 namespace、runtime 账号、角色或权限不存在时创建。本期仅使用服务发现，runtime 角色只对四个固定资源 `linkverse-mvp:LINKVERSE_MVP:naming/linkverse-{gateway|identity|trade|payment}` 拥有读写权限，不授予通配服务权限或 `config/*` 权限。若已有 Nacos 数据卷使用了不同管理员密码，脚本会失败并要求显式处理，不会猜测或重置凭据。

## 验证与停止

执行静态配置与供应链门禁检查：

```powershell
./scripts/verify.ps1 -StaticOnly
```

当四个摘要未锁定时，该命令会在 Compose 语法校验成功后报告“外部镜像仓库核验受阻”并以非零退出；这是预期的未完成门禁。

执行运行态健康、Schema、账号、vhost 和 namespace 检查：

```powershell
./scripts/verify.ps1
```

停止容器但保留数据：

```powershell
docker compose --env-file infrastructure/.env --env-file infrastructure/versions.env `
  -f infrastructure/compose/compose.yml down
```

删除命名卷会永久清除本地数据，不属于默认回滚动作，必须单独确认目标后执行。

## 安全说明

- `.env`、数据库密码、Nacos token、管理员密码和 runtime 密码不得提交或写入 Nacos 明文配置。
- `identity-private.pem` 只供 Identity 本地签发使用，不得被 Gateway、Trade 或 Payment 读取；其他服务只通过 JWK 端点获取公钥。
- Gateway、Identity、Trade 和 Payment 必须使用 `.env` 中的 `NACOS_RUNTIME_USERNAME/PASSWORD`；`nacos` 管理员只供 bootstrap 和本地运维使用。
- Nacos 内置鉴权只适用于受信任的本地网络；本 Compose 不可直接暴露到公网。
- Redis 仅用于限流、缓存和后续秒杀准入；RabbitMQ 仅用于至少一次消息投递；两者都不是业务事实源。
- MySQL 是唯一业务事实源。Nacos 当前使用自身的本地持久化，不占用业务 Schema。

Nacos 鉴权与 namespace 自动化依据：[Nacos 3.2 鉴权](https://nacos.io/docs/latest/manual/admin/auth/)、[Nacos 3.x 运维 API](https://nacos.io/docs/latest/manual/admin/admin-api/)。
