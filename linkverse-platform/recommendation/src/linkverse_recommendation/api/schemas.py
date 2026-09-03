"""Recommendation 内部接口 DTO。"""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class RecommendationRequest(BaseModel):
    """Trade 发起的匿名内部推荐请求。"""

    model_config = ConfigDict(extra="forbid")

    request_id: str = Field(min_length=16, max_length=64)
    user_key: str = Field(pattern=r"^[a-f0-9]{64}$")
    domain: str
    scene: str
    occurred_at: datetime
    candidate_count: int = Field(ge=1, le=500)
    context: dict[str, str] = Field(default_factory=dict)


class CandidateResponse(BaseModel):
    """统一候选及其召回证据。"""

    object_id: str = Field(min_length=1, max_length=64)
    score: float = Field(allow_inf_nan=False)
    sources: list[str] = Field(min_length=1, max_length=10)
    reason_code: str = Field(min_length=1, max_length=64)


class RecommendationResponse(BaseModel):
    """与 contracts/recommendation/v1 对齐的内部响应。"""

    request_id: str
    domain: str
    model_version: str
    candidates: list[CandidateResponse]
