# LinkVerse MVP 本地基础设施

本目录提供隔离本地环境，不代表生产高可用部署。默认启动 MySQL、Redis、RabbitMQ 和 Nacos；Recommendation 使用可选 `recommendation` profile，论坛、Elasticsearch、Kafka、Seata 与 Sentinel 仍不在本期范围。

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
| 本地 MySQL 账号 | `root`（仅三个业务 Schema） |
| 本地 Nacos 账号 | `nacos` |
| 本地 RabbitMQ 账号 | `rabbitmq` |
| RabbitMQ vhost | `linkverse-mvp` |
| Redis Key 前缀 | `lv:mvp:{domain}:` |

三个 Schema 仍保持物理隔离，服务不得跨 Schema Join。为降低本地 MVP 的使用成本，应用和 Flyway 统一使用 `root`，且该远程账号只允许当前 Docker 网段访问三个业务 Schema；生产环境必须恢复按服务拆分的运行账号和迁移账号。

所有宿主端口只绑定到 `127.0.0.1`：MySQL `13307`、Redis `16380`、RabbitMQ AMQP `15674`、RabbitMQ 管理端 `15675`、Nacos 控制台 `18091`、Nacos 服务端 `18849`、Nacos gRPC `19849`。

## 启动

从 `linkverse-platform/` 执行：

```powershell
./scripts/prepare-local.ps1
./scripts/bootstrap.ps1
./scripts/verify.ps1
```

`prepare-local.ps1` 生成忽略的 `infrastructure/.env`：MySQL、Redis、RabbitMQ 和 Nacos 的本地密码统一为 `123456abc`，JWT 密钥、Nacos token、内部鉴权值、支付签名密钥和 OAuth2 客户端密钥仍使用系统密码学随机数。脚本还会在同样忽略的 `infrastructure/secrets/` 下生成 PKCS#8 `identity-private.pem` 和 X.509 `identity-public.pem`（RSA 2048）。任一文件已存在时都不会覆盖；密钥对不匹配或只留下公钥时会明确失败。脚本不会回显随机秘密。

`prepare-local.ps1` 会自动满足以下规则。若必须手工维护，中间件密码仅允许 8～128 位字母、数字及 `_@%+=:,.-`。`NACOS_AUTH_TOKEN` 必须是至少 32 个随机字节的 Base64 编码，可在 PowerShell 中生成：

```powershell
$tokenBytes = New-Object byte[] 48
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($tokenBytes)
[Convert]::ToBase64String($tokenBytes)
```

`bootstrap.ps1` 可重复执行：它不会删除业务数据，会收敛三个 Schema、受限来源的 MySQL `root`、RabbitMQ `rabbitmq` 账号以及 Nacos `nacos` 账号。若已有数据卷使用不同密码，脚本会失败并要求显式处理，不会猜测或重置凭据。

## 验证与停止

启动 CPU Recommendation 在线服务前，必须在 `.env` 中把 `RECOMMENDATION_MODEL_ROOT` 设为包含 `active-model.json` 的绝对目录，然后执行：

```powershell
docker compose --env-file infrastructure/.env --env-file infrastructure/versions.env `
  -f infrastructure/compose/compose.yml --profile recommendation up -d recommendation
```

该服务不加入 Gateway 路由，模型卷以只读方式挂载。Windows 宿主机负责 CUDA 训练，容器仅执行 CPU Faiss 推断。

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

- `.env`、Nacos token、内部鉴权值和业务签名密钥不得提交或写入 Nacos 明文配置。
- `identity-private.pem` 只供 Identity 本地签发使用，不得被 Gateway、Trade 或 Payment 读取；其他服务只通过 JWK 端点获取公钥。
- Gateway、Identity、Trade 和 Payment 本地共用 `nacos` 账号；该简化仅适用于绑定到 `127.0.0.1` 的开发环境。
- Nacos 内置鉴权只适用于受信任的本地网络；本 Compose 不可直接暴露到公网。
- Redis 仅用于限流、缓存和后续秒杀准入；RabbitMQ 仅用于至少一次消息投递；两者都不是业务事实源。
- MySQL 是唯一业务事实源。Nacos 当前使用自身的本地持久化，不占用业务 Schema。
- `123456abc` 是便于记忆的本地开发密码，禁止用于公网、共享测试环境或生产环境。

Nacos 鉴权与 namespace 自动化依据：[Nacos 3.2 鉴权](https://nacos.io/docs/latest/manual/admin/auth/)、[Nacos 3.x 运维 API](https://nacos.io/docs/latest/manual/admin/admin-api/)。
