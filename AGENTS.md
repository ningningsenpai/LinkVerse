# Repository Guidelines

## Project Structure & Module Organization

`KnowledgeLink-backend (1)/KnowledgeLink-backend/` is a Java 21 Maven reactor with `common`, `gateway`, `trade`, `forum`, `chat`, and `user` modules. Modules use standard `src/main/java`, `src/main/resources`, and `src/test/java` layouts. SQL and fixtures live in `db/` and `mock-data/`.

`KnowledgeLink-RecommenderSystem/KnowledgeLink-RecommenderSystem/` contains the Python pipeline: `recall.py`, `rough_ranking.py`, `fine_ranking.py`, and `rearrangement.py`. FastAPI code is in `interface/`; data and environment files are in `data/` and `dependency/`.

## Build, Test, and Development Commands

Use JDK 21 for Maven; run commands from the relevant project root:

- `mvn clean verify`: build every backend module and run JUnit tests.
- `mvn -pl knowledgelink-forum -am test`: test one module and its dependencies.
- `mvn install -DskipTests`: install reactor artifacts before running one module.
- `mvn -pl knowledgelink-user spring-boot:run`: start the user service.
- `conda env create -f dependency/environment.yml`: create the `rs_project` Python 3.12 environment.
- `python main.py`: run the fixture-based recommendation pipeline.
- `python -m uvicorn interface.main:app --host 127.0.0.1 --port 8000`: start the API.

Activate Python with `conda activate rs_project`. Backend services require their configured middleware.

## Coding Style & Naming Conventions

Use four-space indentation. Java packages are lowercase, classes PascalCase, and members camelCase; preserve suffixes such as `DTO`, `VO`, `ServiceImpl`, and `Test`. Python follows PEP 8 with snake_case functions/modules and PascalCase classes. No formatter is configured; avoid unrelated reformatting. Code comments and user-facing errors must be Chinese. Documentation must be Chinese Markdown, except this guide.

## Testing Guidelines

Backend tests use JUnit 5 and Spring Boot Test. Mirror production packages under `src/test/java`; name classes `*Test.java`. Python includes pytest but no suite; add `tests/test_*.py` and run `pytest`. No coverage threshold exists, so cover changed behavior and failures. Gate DashScope tests with `test.dashscope.integration.enabled` and `DASHSCOPE_API_KEY`.

## Commit & Pull Request Guidelines

This checkout has no Git metadata from which to infer a house style. Use Conventional Commits, for example `feat(forum): add recommendation weights` or `fix(recommender): handle empty recall results`. Keep each commit to one concern. Pull requests must identify affected modules, explain behavior or configuration changes, link issues, and report exact tests. Include API examples for visible changes; call out SQL migrations and weight updates.

## Security & Configuration

Never commit credentials, private endpoints, or production data. Use environment variables or ignored local overrides. Replace large `model_weights/` or `data/` files only when intentional and documented.
