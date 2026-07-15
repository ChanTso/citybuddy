"""Direct-user authentication and support-session identity boundary."""

from __future__ import annotations

import secrets
import time
from collections.abc import Mapping
from typing import Any, Protocol

import httpx
import jwt
import pymysql
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field

SESSION_PERMISSION = "support:session:create"
DIRECT_TOKEN_TYPE = "direct_user"


class AgentSettings(BaseModel):
    """Runtime identity configuration; secret values have no defaults."""

    model_config = ConfigDict(frozen=True)

    service_name: str = "agent-service"
    environment: str = "development"
    identity_enabled: bool = False
    issuer: str = ""
    user_audience: str = ""
    jwks_url: str = ""
    mysql_host: str = ""
    mysql_port: int = 3306
    mysql_password: str = ""
    auth_exchange_url: str = ""
    service_client_id: str = ""
    service_client_secret: str = ""
    exchange_scopes: tuple[str, ...] = ()
    clock_skew_seconds: int = 30
    jwks_cache_seconds: int = 60


class DirectPrincipal(BaseModel):
    model_config = ConfigDict(frozen=True)

    subject: str
    permissions: tuple[str, ...]


class JwksSource(Protocol):
    def load(self) -> Mapping[str, Any]: ...


class HttpJwksSource:
    def __init__(self, url: str) -> None:
        self._url = url

    def load(self) -> Mapping[str, Any]:
        response = httpx.get(self._url, timeout=3.0)
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, dict):
            raise ValueError("JWKS payload must be an object")
        return payload


class DirectJwtValidator:
    """Validate direct JWTs with one bounded refresh for an unknown kid."""

    def __init__(self, settings: AgentSettings, source: JwksSource) -> None:
        self._settings = settings
        self._source = source
        self._keys: dict[str, jwt.PyJWK] = {}
        self._loaded_at: float | None = None

    def validate(self, token: str) -> DirectPrincipal:
        try:
            header = jwt.get_unverified_header(token)
            kid = header.get("kid")
            if not isinstance(kid, str) or header.get("alg") != "RS256":
                raise ValueError("Invalid JWT header")
            now = time.monotonic()
            refreshed = False
            if (
                self._loaded_at is None
                or now - self._loaded_at >= self._settings.jwks_cache_seconds
            ):
                self._refresh()
                refreshed = True
            key = self._keys.get(kid)
            if key is None and not refreshed:
                self._refresh()
                key = self._keys.get(kid)
            if key is None:
                raise ValueError("Unknown signing key")
            claims = jwt.decode(
                token,
                key=key,
                algorithms=["RS256"],
                audience=self._settings.user_audience,
                issuer=self._settings.issuer,
                leeway=self._settings.clock_skew_seconds,
                options={"require": ["aud", "exp", "iat", "iss", "nbf", "sub"]},
            )
            permissions = claims.get("permissions")
            audience = claims.get("aud")
            if (
                claims.get("token_type") != DIRECT_TOKEN_TYPE
                or claims.get("principal_state") != "ACTIVE"
                or audience not in (self._settings.user_audience, [self._settings.user_audience])
                or not isinstance(permissions, list)
                or not all(isinstance(item, str) for item in permissions)
                or SESSION_PERMISSION not in permissions
                or "act" in claims
                or "session" in claims
                or "sandbox" in claims
                or "eval_sandbox" in claims
            ):
                raise ValueError("Invalid direct token claims")
            subject = claims["sub"]
            if not isinstance(subject, str) or not subject:
                raise ValueError("Invalid token subject")
            return DirectPrincipal(subject=subject, permissions=tuple(permissions))
        except (jwt.PyJWTError, ValueError, TypeError, httpx.HTTPError) as exception:
            raise HTTPException(status_code=401, detail="Unauthorized") from exception

    def _refresh(self) -> None:
        payload = self._source.load()
        keys = payload.get("keys")
        if not isinstance(keys, list):
            raise ValueError("JWKS is missing keys")
        loaded: dict[str, jwt.PyJWK] = {}
        for value in keys:
            if not isinstance(value, dict):
                raise ValueError("JWKS key must be an object")
            key = jwt.PyJWK.from_dict(value)
            kid = value.get("kid")
            if isinstance(kid, str) and key.algorithm_name == "RS256":
                loaded[kid] = key
        self._keys = loaded
        self._loaded_at = time.monotonic()


class SessionStore(Protocol):
    def create(self, subject: str) -> str: ...

    def verify_owner(self, session_id: str, subject: str) -> None: ...


class MysqlSessionStore:
    def __init__(self, settings: AgentSettings) -> None:
        self._settings = settings

    def create(self, subject: str) -> str:
        session_id = secrets.token_urlsafe(32)
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                "INSERT INTO support_session (session_id, user_subject) VALUES (%s, %s)",
                (session_id, subject),
            )
            connection.commit()
        return session_id

    def verify_owner(self, session_id: str, subject: str) -> None:
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                "SELECT user_subject, sandbox_id FROM support_session WHERE session_id = %s",
                (session_id,),
            )
            row = cursor.fetchone()
        if row is None or row[0] != subject or row[1] is not None:
            raise HTTPException(status_code=403, detail="Forbidden")

    def _connect(self) -> pymysql.Connection[pymysql.cursors.Cursor]:
        return pymysql.connect(
            host=self._settings.mysql_host,
            port=self._settings.mysql_port,
            user="agent_app",
            password=self._settings.mysql_password,
            database="cs_db",
            autocommit=False,
        )


class SessionCreateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")


class SessionResponse(BaseModel):
    model_config = ConfigDict(frozen=True)

    session_id: str = Field(serialization_alias="sessionId")


class OboClient:
    """JIT exchange boundary used by future server-owned ToolSpecs."""

    def __init__(self, settings: AgentSettings, sessions: SessionStore) -> None:
        self._settings = settings
        self._sessions = sessions

    def exchange(
        self, direct_token: str, principal: DirectPrincipal, session_id: str, scope: str
    ) -> str:
        self._sessions.verify_owner(session_id, principal.subject)
        if scope not in self._settings.exchange_scopes:
            raise HTTPException(status_code=403, detail="Forbidden")
        response = httpx.post(
            self._settings.auth_exchange_url,
            auth=(self._settings.service_client_id, self._settings.service_client_secret),
            headers={"X-User-Authorization": f"Bearer {direct_token}"},
            json={
                "sessionId": session_id,
                "userSubject": principal.subject,
                "scope": scope,
            },
            timeout=3.0,
        )
        if response.status_code != 200:
            raise HTTPException(status_code=502, detail="Identity exchange rejected")
        payload = response.json()
        token = payload.get("accessToken") if isinstance(payload, dict) else None
        if not isinstance(token, str) or not token:
            raise HTTPException(status_code=502, detail="Identity exchange rejected")
        return token


def create_app(
    settings: AgentSettings | None = None,
    *,
    validator: DirectJwtValidator | None = None,
    sessions: SessionStore | None = None,
) -> FastAPI:
    """Construct the app, enabling identity routes only with complete runtime configuration."""
    resolved = settings or AgentSettings()
    app = FastAPI(title=resolved.service_name, docs_url=None, redoc_url=None)
    app.state.settings = resolved
    if not resolved.identity_enabled:
        return app

    resolved_validator = validator or DirectJwtValidator(
        resolved, HttpJwksSource(resolved.jwks_url)
    )
    resolved_sessions = sessions or MysqlSessionStore(resolved)
    app.state.validator = resolved_validator
    app.state.sessions = resolved_sessions
    app.state.obo_client = OboClient(resolved, resolved_sessions)

    @app.post("/api/sessions", response_model=SessionResponse, status_code=201)
    def create_session(
        request: SessionCreateRequest,
        authorization: str = Header(),
        x_eval_sandbox_id: str | None = Header(default=None),
    ) -> SessionResponse:
        del request
        if x_eval_sandbox_id is not None or not authorization.startswith("Bearer "):
            raise HTTPException(status_code=401, detail="Unauthorized")
        principal = resolved_validator.validate(authorization[7:])
        return SessionResponse(session_id=resolved_sessions.create(principal.subject))

    return app
