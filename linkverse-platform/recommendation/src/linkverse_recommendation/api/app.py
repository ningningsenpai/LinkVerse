"""Recommendation FastAPI 内部服务。"""

from __future__ import annotations

import os
import time
from pathlib import Path
from contextlib import asynccontextmanager

import uvicorn
from fastapi import Depends, FastAPI, HTTPException, Response
from fastapi.security import HTTPAuthorizationCredentials

from linkverse_recommendation.api.schemas import CandidateResponse, RecommendationRequest, RecommendationResponse
from linkverse_recommendation.api.security import bearer, require_service_token
from linkverse_recommendation.domains import get_adapter
from linkverse_recommendation.observability.metrics import RecommendationMetrics
from linkverse_recommendation.serving.registry import ModelRegistry


registry = ModelRegistry(Path(os.environ.get("LINKVERSE_MODEL_ROOT", "models")))
metrics = RecommendationMetrics()


@asynccontextmanager
async def lifespan(application):
    registry.start(metrics.record_model_load_failure)
    yield
    registry.close()


app = FastAPI(title="LinkVerse Recommendation", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)


@app.get("/health/live")
def live() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/health/ready")
def ready() -> dict[str, str | None]:
    if not registry.ready:
        raise HTTPException(status_code=503, detail="尚未加载活动模型")
    return {"status": "UP", "model_version": registry.model_version}


@app.get("/metrics", response_class=Response)
def prometheus_metrics() -> Response:
    return Response(metrics.render(), media_type="text/plain; version=0.0.4; charset=utf-8")


@app.post("/internal/v1/recommendations", response_model=RecommendationResponse)
def recommend(
    request: RecommendationRequest,
    credentials: HTTPAuthorizationCredentials = Depends(bearer),
) -> RecommendationResponse:
    started = time.perf_counter()
    outcome = "success"
    try:
        require_service_token(credentials)
        get_adapter(request.domain)
        model_version, candidates = registry.recommend(request.user_key, request.candidate_count, scene=request.scene, context=request.context, occurred_at=request.occurred_at)
    except HTTPException as exception:
        outcome = "invalid" if exception.status_code < 500 else "unavailable"
        raise
    except ValueError as exception:
        outcome = "invalid"
        raise HTTPException(status_code=400, detail=str(exception)) from exception
    except RuntimeError as exception:
        outcome = "unavailable"
        raise HTTPException(status_code=503, detail=str(exception)) from exception
    finally:
        metrics.observe_request(request.domain, outcome, time.perf_counter() - started)
    return RecommendationResponse(
        request_id=request.request_id,
        domain=request.domain,
        model_version=model_version,
        candidates=[
            CandidateResponse(
                object_id=item.object_id,
                score=item.score,
                sources=list(item.sources),
                reason_code=item.reason_code,
            )
            for item in candidates
        ],
    )


def run() -> None:
    uvicorn.run(app, host="0.0.0.0", port=18084)
