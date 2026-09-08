FROM python:3.12.11-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    LINKVERSE_MODEL_ROOT=/models

ENV OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir ".[data]" "faiss-cpu==1.12.0" "lightgbm==4.6.0"

USER 65532:65532
EXPOSE 18084
ENTRYPOINT ["uvicorn", "linkverse_recommendation.api.app:app", "--host", "0.0.0.0", "--port", "18084"]
