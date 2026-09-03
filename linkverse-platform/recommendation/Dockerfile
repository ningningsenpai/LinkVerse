FROM python:3.12.11-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    LINKVERSE_MODEL_ROOT=/models

WORKDIR /app

COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir ".[data]" "faiss-cpu==1.12.0"

USER 65532:65532
EXPOSE 18084
ENTRYPOINT ["uvicorn", "linkverse_recommendation.api.app:app", "--host", "0.0.0.0", "--port", "18084"]
