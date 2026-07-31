---
name: linkverse-code-governance
description: Enforce LinkVerse code, comments, errors, layering, naming, testing, and safe-change conventions. Use whenever writing, refactoring, reviewing, or generating Java, Python, frontend, SQL migration, configuration, tests, API contracts, or comments in this repository.
compatibility: LinkVerse repository with Java 21 services, Python 3.12 recommendation code, and future frontend code.
---

# LinkVerse Code Governance

## Required context

1. Read the repository `AGENTS.md`.
2. Read `../../../docs/03-业务规范.md`.
3. Read `../../../docs/01-重构规划.md` when a change affects migration order or service boundaries.
4. Inspect nearby code and tests before editing.

## Change discipline

- Make one small, reviewable behavior change at a time.
- State the invariant being changed and how it will be verified.
- Avoid unrelated renames, formatting, dependency upgrades, or abstractions.
- Preserve compatibility through expand-migrate-contract when data or contracts change.
- Never silently weaken authorization, validation, idempotency, transactions, or observability.
- Do not generate business code unless the user has requested that implementation phase.

## Language and documentation

- Write conversations and project documentation in Chinese Markdown.
- Write code comments and user-facing error messages in Chinese.
- Use comments to explain business invariants, concurrency choices, non-obvious constraints, and recovery behavior.
- Remove comments that merely repeat code or no longer match behavior.
- Keep identifiers conventional English; do not use pinyin.

## Java conventions

- Use four spaces, lowercase packages, PascalCase types, and camelCase members.
- Organize each service into `api`, `application`, `domain`, and `infrastructure`.
- Keep Controllers limited to protocol mapping, validation, and authorization context.
- Put use-case orchestration and transaction boundaries in `application`.
- Keep domain rules independent of Spring, MyBatis, Redis, RabbitMQ, and Elasticsearch.
- Keep persistence models, Mappers, remote clients, and adapters in `infrastructure`.
- Do not expose database entities as API DTOs.
- Use `BigDecimal` and database `DECIMAL` for money.
- Use enums or value objects for states, plus conditional transitions or optimistic versions.
- Use constructor injection. Avoid field injection and static mutable request state.
- Convert exceptions centrally to stable Chinese `application/problem+json` responses.
- Log through structured fields; never log credentials, tokens, payment payloads, or personal data.

## Python conventions

- Follow PEP 8 with four spaces, snake_case functions/modules, and PascalCase classes.
- Separate `api`, `pipeline`, `training`, `serving`, `domain`, and `adapters`.
- Use explicit typing for public boundaries and Pydantic models for API schemas.
- Keep training outside request handlers; do not block the event loop with unbounded Pandas or PyTorch work.
- Publish immutable, versioned model bundles and switch versions atomically.
- Preserve deterministic preprocessing, OOV handling, feature schema hashes, and time-based evaluation.
- Database adapters are read-only. Do not add insert, update, delete, commit, Redis writes, or Elasticsearch writes without explicit user approval.

## Frontend conventions

- Implement only from an approved design artifact and versioned API contract.
- Separate pages, domain features, reusable components, API clients, and design tokens.
- Represent loading, empty, validation, degraded, conflict, and retry states explicitly.
- Never infer payment success from redirect parameters; query authoritative server state.
- Keep accessibility, keyboard use, responsive layout, and error recovery testable.

## Data and integration conventions

- A business transaction writes MySQL and its Outbox in one local transaction.
- Consumers deduplicate by event ID and acknowledge only after commit.
- Redis and Elasticsearch are projections; do not synchronize-write them inside the business transaction.
- Every write API defines authorization, validation, idempotency, transaction scope, failure handling, and audit fields.
- Python returns recommendations with object ID, score, source/reason, and model version; Java owns business filtering and writes.
- Do not add Swagger or Knife4j.

## Testing

- Java: JUnit 5; use `*Test` for unit/component tests and `*IT` for cross-component integration tests.
- Python: pytest with `test_*.py`; fix random seeds and verify reproducibility where applicable.
- Use Testcontainers or fakes for infrastructure. Tests must not use production data, credentials, indexes, payment providers, or AI services.
- Cover the changed invariant, its failure path, authorization, idempotency, and concurrency when relevant.
- Report the exact commands run and any test not run.

## Review checklist

- The change is in the correct layer and service.
- Controller inputs and outputs use explicit API DTOs; persistence entities never cross the API boundary.
- Naming and comments communicate domain intent.
- User-facing errors are Chinese and stable.
- No secret or sensitive value is exposed.
- Transactions contain all required writes and no detached async mutation.
- Cache, search, and message failure cannot corrupt the source of truth.
- Tests prove the behavior and failure path.
- Documentation and migration/rollback notes are updated when required.
