# Repository Guidelines

## Project Structure & Module Organization

`linkverse-platform/backend/` is the current Java 21 Maven reactor. Its modules are flat children of the reactor root: `platform-bom`, `platform-core`, `platform-starter-web`, `platform-starter-security`, `platform-starter-observability`, `platform-starter-messaging`, `linkverse-gateway`, `linkverse-identity`, `linkverse-trade`, and `linkverse-payment`. Modules use standard `src/main/java`, `src/main/resources`, and `src/test/java` layouts.

The stage 1–2 MVP has four Java deployables: Gateway, Identity, Trade, and Payment. Forum and Python Recommendation belong to the long-term architecture and are deferred; do not invent their directories or build commands. `linkverse-platform/infrastructure/` contains the isolated local Compose environment, and `linkverse-platform/scripts/` contains its preparation, bootstrap, and verification entry points.

`KnowledgeLink-backend/` and `KnowledgeLink-RecommenderSystem/` are ignored, read-only reference snapshots. Never build, format, edit, deploy, import, or depend on them from new runtime code.

## Build, Test, and Development Commands

Use JDK 21 and the checked-in Maven Wrapper 3.9.10. Run commands from the stated directory:

- From `linkverse-platform/backend/`, run `./mvnw clean verify` or `.\mvnw.cmd clean verify` to build the full reactor and run JUnit tests.
- From `linkverse-platform/backend/`, run `.\mvnw.cmd -pl linkverse-identity -am test` to test Identity and required shared modules.
- From `linkverse-platform/backend/`, run `.\mvnw.cmd -pl linkverse-identity -am spring-boot:run` to start Identity after loading the local environment.
- From `linkverse-platform/`, run `.\scripts\prepare-local.ps1` to create ignored local secrets and the RSA key pair without overwriting existing files.
- From `linkverse-platform/`, run `.\scripts\bootstrap.ps1` to converge and start the four local middleware containers.
- From `linkverse-platform/`, run `.\scripts\verify.ps1` for runtime infrastructure and isolation checks; use `-StaticOnly` for Compose and image-digest gates.

Backend services require the isolated middleware and Nacos credentials documented in `linkverse-platform/infrastructure/README.md`.

## Coding Style & Naming Conventions

Use four-space indentation. Java packages are lowercase, classes PascalCase, and members camelCase; preserve suffixes such as `DTO`, `VO`, `ServiceImpl`, and `Test`. Python follows PEP 8 with snake_case functions/modules and PascalCase classes. No formatter is configured; avoid unrelated reformatting. Code comments and user-facing errors must be Chinese. Documentation must be Chinese Markdown, except this guide.

## Testing Guidelines

Backend tests use JUnit 5, Spring Boot Test, and disposable Testcontainers where a real database boundary matters. Mirror production packages under `src/test/java`; name classes `*Test.java`. No coverage threshold exists, so cover changed behavior and failures. Tests must not connect to persistent or production middleware, payment providers, search clusters, or external AI services unless the user explicitly approves a gated integration test.

## Commit & Pull Request Guidelines

This checkout has no Git metadata from which to infer a house style. Use Conventional Commits, for example `feat(identity): add client credentials` or `fix(payment): reject user tokens on internal APIs`. Keep each commit to one concern. Pull requests must identify affected modules, explain behavior or configuration changes, link issues, and report exact tests. Include API examples for visible changes; call out SQL migrations and weight updates.

## Security & Configuration

Never commit credentials, private endpoints, or production data. Use environment variables or ignored local overrides. Replace large `model_weights/` or `data/` files only when intentional and documented.
