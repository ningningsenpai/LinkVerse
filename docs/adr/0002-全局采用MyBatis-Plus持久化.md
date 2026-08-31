# ADR-0002：全局采用 MyBatis-Plus 持久化

## 背景与目标

当前 Identity、Trade 与 Payment 已使用 Spring JDBC 完成持久化，但长期规划中的 Trade、Forum 已选择 MyBatis-Plus。为避免后续扩展用户资料、关注、交易和论坛表时再次迁移持久化技术栈，现统一采用 MyBatis-Plus。

## 当前约束与证据

- 当前运行基线为 JDK 21、Spring Boot `3.5.15` 和 MySQL `8.4.11`。
- MySQL 继续作为业务事实源，数据库 Schema、唯一约束、事务边界和跨服务数据所有权不变。
- 库存、订单、支付、秒杀与 Outbox 依赖条件更新、锁定读和受影响行数，不能改写为无条件通用 CRUD。
- MyBatis-Plus 官方安装文档为 Spring Boot 3 提供 `mybatis-plus-spring-boot3-starter`，当前锁定版本为 `3.5.17`；官方同时要求不要重复引入 MyBatis、MyBatis Spring Boot Starter 或 MyBatis-Spring。

官方依据：

- [MyBatis-Plus 安装与 Spring Boot 3 Starter](https://baomidou.com/en/getting-started/install/)
- [MyBatis-Plus 3.5.17 发布记录](https://github.com/baomidou/mybatis-plus/releases/tag/v3.5.17)
- [MyBatis-Plus Mapper 扫描配置](https://baomidou.com/en/getting-started/config/)

## 备选方案

1. 保持 Spring JDBC：改动最小，但未来新增标准单表能力时会继续形成两套持久化写法。
2. 全部改成 MyBatis-Plus 通用 CRUD：代码短，但会隐藏或削弱条件更新、锁和 Outbox 抢占语义。
3. MyBatis-Plus BaseMapper 与显式自定义 Mapper 并用：标准单表操作使用 BaseMapper，关键并发 SQL 保留为命名 Mapper 方法。

## 决策

选择方案 3。根 Reactor 通过 MyBatis-Plus BOM 锁定 `3.5.17`，Identity、Trade、Payment 使用 `mybatis-plus-spring-boot3-starter`。生产代码不再直接注入 `JdbcTemplate`；持久化适配器只依赖 Mapper。

实体仅存在于 `infrastructure.persistence`，不得跨越 API 边界。领域接口和领域对象不依赖 MyBatis-Plus。库存扣减、状态迁移、消费去重、锁定读及 Outbox 抢占继续使用显式 SQL，并以受影响行数裁决结果。

## 数据所有权与一致性

Identity、Trade 与 Payment 仍分别独占自身 Schema。迁移不新增表、不修改字段、不跨 Schema 查询，也不改变 MySQL 本地事务与同事务 Outbox 规则。

## 失败模式与恢复

- Mapper 参数或结果映射错误：由 H2 组件测试和 MySQL Testcontainers 测试覆盖。
- 条件更新语义退化：保留原 WHERE 前置状态和库存条件，并复用并发、幂等和竞态测试。
- Outbox 抢占退化：保留 `FOR UPDATE SKIP LOCKED`、锁租约和状态条件。
- 依赖不兼容：全量 Maven `clean verify` 与依赖收敛门禁必须通过。

## 可观测性与安全

不启用生产 SQL 明文打印，不记录密码哈希、客户端密钥、支付载荷或 Outbox 载荷。现有指标、日志、请求链路和中文安全错误保持不变。

## 验证标准

- 生产 Java 源码不再引用 `JdbcTemplate`。
- 三个服务均通过组件测试和 MySQL Testcontainers 测试。
- 并发库存、订单幂等、支付回调重放、支付关单竞态、秒杀预约和 Outbox 测试保持通过。
- `./mvnw.cmd clean verify` 通过，依赖收敛门禁无冲突。

## 迁移与回滚

本次是代码和依赖迁移，不执行数据库数据迁移。回滚时恢复 Spring JDBC 依赖和原仓储实现即可，数据库与 Flyway 版本无需回退。若发布后出现映射问题，应整体回滚应用版本，不修改现有业务事实来适配代码。
