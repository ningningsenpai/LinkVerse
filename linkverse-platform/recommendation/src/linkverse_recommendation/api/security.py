"""Recommendation 服务 JWT 最小权限校验。"""

from __future__ import annotations

import os

import jwt
from fastapi import HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer


bearer = HTTPBearer(auto_error=True)


class ServiceTokenVerifier:
    """校验签名、受众、令牌用途、范围和固定 Trade 客户端主体。"""

    def __init__(self, jwk_set_uri: str, issuer: str) -> None:
        self.jwk_client = jwt.PyJWKClient(jwk_set_uri)
        self.issuer = issuer

    def verify(self, token: str) -> dict[str, object]:
        try:
            signing_key = self.jwk_client.get_signing_key_from_jwt(token)
            claims = jwt.decode(
                token,
                signing_key.key,
                algorithms=["RS256"],
                audience="linkverse-recommendation",
                issuer=self.issuer,
                options={"require": ["exp", "iat", "sub", "aud"]},
            )
        except Exception as exception:
            raise HTTPException(status_code=401, detail="推荐服务令牌无效") from exception
        raw_scope = claims.get("scope", "")
        if isinstance(raw_scope, str):
            scope = set(raw_scope.split())
        elif isinstance(raw_scope, list) and all(isinstance(value, str) for value in raw_scope):
            scope = set(raw_scope)
        else:
            scope = set()
        subject = claims.get("sub")
        client_id = claims.get("client_id", subject)
        if (
            claims.get("token_use") != "service"
            or "recommendation.internal" not in scope
            or subject != "linkverse-trade-recommendation"
            or client_id != "linkverse-trade-recommendation"
        ):
            raise HTTPException(status_code=403, detail="推荐服务令牌权限不足")
        return claims


verifier = ServiceTokenVerifier(
    os.environ.get("LINKVERSE_JWK_SET_URI", "http://linkverse-identity/oauth2/jwks"),
    os.environ.get("LINKVERSE_ISSUER_URI", "http://localhost:18080"),
)


def require_service_token(credentials: HTTPAuthorizationCredentials) -> dict[str, object]:
    return verifier.verify(credentials.credentials)
