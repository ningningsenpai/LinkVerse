---
name: linkverse-architecture-governance
description: Govern architecture, dependency, and middleware decisions for LinkVerse. Use whenever work adds, removes, or upgrades Spring Boot, Spring Cloud, Nacos, Gateway, authentication, MySQL, Redis, RabbitMQ, Elasticsearch, observability, Docker Compose, recommendation integration, service boundaries, or cross-service data flows.
compatibility: LinkVerse repository; web access is useful for verifying current official compatibility matrices.
---

# LinkVerse Architecture Governance

## Purpose

Keep LinkVerse small, observable, recoverable, and consistent while it is rebuilt as a trade-and-forum microservice platform. Prefer evidence and explicit failure handling over adding infrastructure for appearance.

## Required context

1. Read `../../../docs/01-重构规划.md`.
2. Read `../../../docs/02-技术选型.md`.
3. Read the relevant data and business rules in `../../../docs/03-业务规范.md`.
4. Inspect the current BOM, configuration, Compose files, and affected service contracts before proposing a change.

## Decision workflow

1. Identify the current roadmap phase and the concrete problem.
2. State the latency, consistency, scale, security, and recovery requirements.
3. Check whether an existing component already solves the problem.
4. For version-sensitive choices, verify official compatibility documentation and cite the primary-source URLs in the decision record. Do not use “latest” without an exact tested version.
5. Describe the source of truth, cache or projection ownership, sync and async paths, failure modes, observability, tests, migration, and rollback.
6. Choose the smallest solution that meets measured requirements.
7. Keep dependency upgrades, infrastructure additions, schema changes, and business behavior changes in separate reviewable changes.

## Architectural constraints

- Keep five deployables unless an approved decision changes the boundary: Gateway, Identity, Trade, Forum, and Python Recommendation.
- Do not restore the chat service.
- Treat MySQL as the business source of truth.
- Treat Redis as cache, rate limiter, idempotency window, hot-data store, and seckill admission layer; never as final order, stock, or payment truth.
- Introduce Elasticsearch only for demonstrated full-text or relevance requirements. It must remain a rebuildable projection.
- Use RabbitMQ as the single message broker. Do not add Kafka while RabbitMQ meets the measured requirement.
- Use local transactions, transactional Outbox, publisher confirms, idempotent consumers, bounded retries, and compensation. Do not claim exactly-once delivery.
- Do not introduce Seata/XA or Sentinel initially without evidence that existing patterns are insufficient.
- Use `spring.config.import` for Nacos configuration; do not restore `bootstrap.yml`.
- Let BOMs manage core dependency versions. Document and test any necessary override.
- Do not create a network `logger-service` or `error-service`; use shared starters/packages and OpenTelemetry.
- Do not add Swagger or Knife4j.
- Keep Python database access read-only. Python must not write MySQL, Redis, or Elasticsearch unless the user explicitly approves a separate design.
- Use Docker Compose profiles for optional local infrastructure and pin tested image patches.

## Required decision record

For a material architecture change, produce concise Chinese Markdown with:

```markdown
## 背景与目标
## 当前约束与证据
## 备选方案
## 决策
## 数据所有权与一致性
## 失败模式与恢复
## 可观测性与安全
## 验证标准
## 迁移与回滚
```

State assumptions and unresolved user decisions explicitly. Do not implement a material expansion while approval is missing.

## Completion checklist

- Version compatibility is backed by cited official primary sources.
- Every datastore has one named owner and purpose.
- Cross-service writes have an idempotency and recovery story.
- Optional middleware has an activation criterion.
- Secrets remain outside Git and plaintext Nacos configuration.
- The change has health checks, metrics, traces, tests, migration, and rollback.
- Documentation remains Chinese Markdown; skill files remain English.
