"""Support identity, historical feedback, and evaluation evidence service."""

from __future__ import annotations

import logging
import secrets
import time
import uuid
from base64 import b64decode
from collections.abc import AsyncIterator, Awaitable, Callable, Mapping
from contextlib import asynccontextmanager
from typing import Any, Literal, Protocol

import httpx
import jwt
import pymysql
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, ConfigDict, Field

from . import http_client
from .conversation import (
    ConversationStore,
    MysqlConversationStore,
)
from .evaluation import (
    ActionEvaluationEvidenceInvalid,
    EvaluationEvidenceInvalid,
    EvaluationEvidenceNotFound,
    EvaluationEvidenceResponse,
    EvaluationEvidenceStore,
    MysqlEvaluationEvidenceStore,
)
from .feedback import (
    FeedbackConflictError,
    FeedbackOwnershipError,
    FeedbackStore,
    MysqlFeedbackStore,
)
from .history_types import TOOL_BOUNDARY_FAILURE_REASONS
from .metrics import (
    PROMETHEUS_CONTENT_TYPE,
    MetricsRuntime,
    SafeCityBuddyMetrics,
    create_metrics_runtime,
)
from .tracing import (
    TraceSink,
    create_trace_sink,
)

SESSION_PERMISSION = "support:session:create"


CHAT_PERMISSION = "support:chat"


DIRECT_TOKEN_TYPE = "direct_user"


EVALUATION_DIRECT_TOKEN_TYPE = "eval_direct_user"


MAX_EVALUATION_AUTHORIZATION_LENGTH = 1024


LOGGER = logging.getLogger(__name__)


ACTION_REQUEST_FAILURE_REASONS = TOOL_BOUNDARY_FAILURE_REASONS | frozenset(
    {
        "AGENT_REQUEST_INVALID",
        "AGENT_AUTHENTICATION_REJECTED",
        "AGENT_AUTHORIZATION_REJECTED",
        "ACTION_SESSION_OWNERSHIP_REJECTED",
        "ACTION_IDEMPOTENCY_CONFLICT",
        "ACTION_TURN_IN_PROGRESS",
        "ACTION_TURN_PREVIOUSLY_FAILED",
        "ACTION_DURABLE_TRUTH_INCONSISTENT",
        "ACTION_EVALUATION_DURABLE_TRUTH_INCONSISTENT",
        "ACTION_LOCAL_ARBITRATION_CONFLICT",
        "ACTION_STREAM_PROJECTION_INVALID",
        "ACTION_STREAM_UNEXPECTED_FAILURE",
    }
)


def record_action_request_failure(reason: str) -> None:
    if reason not in ACTION_REQUEST_FAILURE_REASONS:
        raise ValueError("Unregistered action-request failure producer")
    LOGGER.warning("agent_request_rejected reason_code=%s", reason)


class AgentSettings(BaseModel):
    """Runtime identity configuration; secret values have no defaults."""

    model_config = ConfigDict(frozen=True)

    service_name: str = "agent-service"
    environment: str = "development"
    identity_enabled: bool = False
    evaluation_enabled: bool = False
    evaluation_client_id: str = ""
    evaluation_client_secret: str = ""
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
    commerce_liveness_url: str = ""
    # Preserves the deadline of historical PROCESSING turns in MysqlConversationStore.
    attempt_budget: int = 16
    clock_skew_seconds: int = 30
    jwks_cache_seconds: int = 60
    metrics_enabled: bool = False
    trace_export_url: str = ""
    http_client_layout: http_client.HttpClientLayout = "shared"


class DirectPrincipal(BaseModel):
    model_config = ConfigDict(frozen=True)

    subject: str
    permissions: tuple[str, ...]
    sandbox_id: str | None = None


class JwksSource(Protocol):
    def load(self) -> Mapping[str, Any]: ...


class HttpJwksSource:
    def __init__(self, url: str) -> None:
        self._url = url

    def load(self) -> Mapping[str, Any]:
        response = http_client.get(self._url, timeout=3.0)
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

    def validate(self, token: str, eval_sandbox_header: str | None = None) -> DirectPrincipal:
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
            token_type = claims.get("token_type")
            sandbox_claim = claims.get("sandbox")
            if (
                claims.get("principal_state") != "ACTIVE"
                or audience not in (self._settings.user_audience, [self._settings.user_audience])
                or not isinstance(permissions, list)
                or not all(isinstance(item, str) for item in permissions)
                or "act" in claims
                or "session" in claims
                or "eval_sandbox" in claims
            ):
                raise ValueError("Invalid direct token claims")
            if token_type == DIRECT_TOKEN_TYPE:
                if sandbox_claim is not None or eval_sandbox_header is not None:
                    raise ValueError("Production token cannot use evaluation context")
                sandbox_id = None
            elif token_type == EVALUATION_DIRECT_TOKEN_TYPE:
                if (
                    not self._settings.evaluation_enabled
                    or not isinstance(sandbox_claim, str)
                    or not sandbox_claim
                    or sandbox_claim != eval_sandbox_header
                ):
                    raise ValueError("Invalid evaluation token claims")
                sandbox_id = sandbox_claim
            else:
                raise ValueError("Invalid direct token type")
            subject = claims["sub"]
            if not isinstance(subject, str) or not subject:
                raise ValueError("Invalid token subject")
            return DirectPrincipal(
                subject=subject, permissions=tuple(permissions), sandbox_id=sandbox_id
            )
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
    def create(self, subject: str, sandbox_id: str | None = None) -> str: ...

    def verify_owner(
        self, session_id: str, subject: str, sandbox_id: str | None = None
    ) -> None: ...


class MysqlSessionStore:
    def __init__(self, settings: AgentSettings) -> None:
        self._settings = settings

    def create(self, subject: str, sandbox_id: str | None = None) -> str:
        session_id = secrets.token_urlsafe(32)
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                "INSERT INTO support_session (session_id, user_subject, sandbox_id) "
                "VALUES (%s, %s, %s)",
                (session_id, subject, sandbox_id),
            )
            cursor.execute(
                "INSERT INTO support_conversation "
                "(conversation_id, session_id, user_subject, state, next_turn_sequence) "
                "VALUES (%s, %s, %s, 'ACTIVE', 0)",
                (str(uuid.uuid4()), session_id, subject),
            )
            connection.commit()
        return session_id

    def verify_owner(self, session_id: str, subject: str, sandbox_id: str | None = None) -> None:
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                "SELECT user_subject, sandbox_id FROM support_session WHERE session_id = %s",
                (session_id,),
            )
            row = cursor.fetchone()
        if row is None or row[0] != subject or row[1] != sandbox_id:
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


class FeedbackRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    trace_id: uuid.UUID = Field(alias="traceId")
    rating: Literal["POSITIVE", "NEGATIVE"]
    comment: str | None = Field(default=None, min_length=1, max_length=1000)


class FeedbackResponse(BaseModel):
    model_config = ConfigDict(frozen=True)

    feedback_id: str = Field(serialization_alias="feedbackId")
    trace_id: str = Field(serialization_alias="traceId")
    rating: Literal["POSITIVE", "NEGATIVE"]


class SandboxLiveness(Protocol):
    def require_active(self, direct_token: str, sandbox_id: str) -> None: ...


class SandboxLivenessRejected(Exception):
    """The authoritative sandbox boundary confirmed a fixed rejection."""


class SandboxLivenessUnavailable(Exception):
    """The authoritative sandbox boundary could not complete its observation."""


class HttpSandboxLiveness:
    def __init__(self, base_url: str) -> None:
        self._base_url = base_url.rstrip("/")

    def require_active(self, direct_token: str, sandbox_id: str) -> None:
        try:
            response = http_client.post(
                f"{self._base_url}/internal/eval/sandboxes/{sandbox_id}/liveness",
                headers={
                    "Authorization": f"Bearer {direct_token}",
                    "X-Eval-Sandbox-Id": sandbox_id,
                },
                timeout=3.0,
            )
        except http_client.TRANSPORT_FAILURES as exception:
            raise SandboxLivenessUnavailable from exception
        if response.status_code == 204:
            return
        if response.status_code in {400, 401, 403, 404, 409, 422}:
            raise SandboxLivenessRejected
        raise SandboxLivenessUnavailable


class OboClient:
    """JIT exchange that rechecks the persisted support-session owner."""

    def __init__(self, settings: AgentSettings, sessions: SessionStore) -> None:
        self._settings = settings
        self._sessions = sessions

    def exchange(
        self,
        direct_token: str,
        subject: str,
        session_id: str,
        scope: str,
        sandbox_id: str | None = None,
    ) -> str:
        if sandbox_id is None:
            self._sessions.verify_owner(session_id, subject)
        else:
            self._sessions.verify_owner(session_id, subject, sandbox_id)
        if scope not in self._settings.exchange_scopes:
            raise HTTPException(status_code=403, detail="Forbidden")
        headers = {"X-User-Authorization": f"Bearer {direct_token}"}
        if sandbox_id is not None:
            headers["X-Eval-Sandbox-Id"] = sandbox_id
        response = http_client.post(
            self._settings.auth_exchange_url,
            auth=(self._settings.service_client_id, self._settings.service_client_secret),
            headers=headers,
            json={
                "sessionId": session_id,
                "userSubject": subject,
                "scope": scope,
            },
            timeout=3.0,
        )
        if response.status_code in {401, 403}:
            raise HTTPException(
                status_code=response.status_code,
                detail="Identity exchange rejected",
            )
        if response.status_code != 200:
            raise HTTPException(status_code=502, detail="Identity exchange rejected")
        try:
            payload = response.json()
        except ValueError as exception:
            raise HTTPException(status_code=502, detail="Identity exchange rejected") from exception
        token = payload.get("accessToken") if isinstance(payload, dict) else None
        if not isinstance(token, str) or not token:
            raise HTTPException(status_code=502, detail="Identity exchange rejected")
        return token


class ToolBoundaryFailure(Exception):
    """An authoritative support-boundary failure with a closed historical reason."""

    def __init__(self, *, status_code: int, reason: str, detail: str) -> None:
        if reason not in TOOL_BOUNDARY_FAILURE_REASONS:
            raise ValueError("Unregistered sensitive-tool boundary producer")
        super().__init__(detail)
        self.status_code = status_code
        self.reason = reason
        self.detail = detail


def create_app(
    settings: AgentSettings | None = None,
    *,
    validator: DirectJwtValidator | None = None,
    sessions: SessionStore | None = None,
    conversations: ConversationStore | None = None,
    feedback: FeedbackStore | None = None,
    evidence: EvaluationEvidenceStore | None = None,
    liveness: SandboxLiveness | None = None,
    metrics_runtime: MetricsRuntime | None = None,
    trace_sink: TraceSink | None = None,
) -> FastAPI:
    """Construct one worker's app and prebuild all of its outbound HTTP clients."""
    resolved = settings or AgentSettings()
    http_clients = http_client.HttpClients(
        resolved.http_client_layout,
        (
            resolved.jwks_url,
            resolved.auth_exchange_url,
            resolved.commerce_liveness_url,
            resolved.trace_export_url,
        ),
    )
    resolved_trace_sink: TraceSink | None = None
    try:
        resolved_metrics_runtime = metrics_runtime or create_metrics_runtime(
            resolved.metrics_enabled
        )
        resolved_metrics = SafeCityBuddyMetrics(resolved_metrics_runtime.recorder)
        resolved_trace_sink = trace_sink or create_trace_sink(
            resolved.trace_export_url,
            resolved_metrics,
            http_clients=http_clients,
        )
        return _create_app(
            resolved,
            validator=validator,
            sessions=sessions,
            conversations=conversations,
            feedback=feedback,
            evidence=evidence,
            liveness=liveness,
            metrics_runtime=resolved_metrics_runtime,
            trace_sink=resolved_trace_sink,
            http_clients=http_clients,
        )
    except BaseException:
        try:
            if resolved_trace_sink is not None:
                resolved_trace_sink.close()
        finally:
            http_clients.close()
        raise


def _create_app(
    settings: AgentSettings,
    *,
    validator: DirectJwtValidator | None,
    sessions: SessionStore | None,
    conversations: ConversationStore | None,
    feedback: FeedbackStore | None,
    evidence: EvaluationEvidenceStore | None,
    liveness: SandboxLiveness | None,
    metrics_runtime: MetricsRuntime,
    trace_sink: TraceSink,
    http_clients: http_client.HttpClients,
) -> FastAPI:
    """Construct the app, enabling identity routes only with complete runtime configuration."""
    resolved = settings
    resolved_metrics_runtime = metrics_runtime
    resolved_trace_sink = trace_sink

    @asynccontextmanager
    async def lifespan(application: FastAPI) -> AsyncIterator[None]:
        del application
        try:
            yield
        finally:
            try:
                resolved_trace_sink.close()
            finally:
                http_clients.close()

    app = FastAPI(
        title=resolved.service_name,
        version="0.0.1",
        docs_url=None,
        redoc_url=None,
        lifespan=lifespan,
    )
    app.state.settings = resolved

    @app.middleware("http")
    async def bind_http_clients(
        request: Request,
        call_next: Callable[[Request], Awaitable[Response]],
    ) -> Response:
        with http_client.use(http_clients):
            return await call_next(request)

    if resolved.metrics_enabled:

        @app.get("/internal/metrics/prometheus", include_in_schema=False)
        def prometheus_metrics() -> Response:
            headers = {"Cache-Control": "no-store"}
            try:
                payload = resolved_metrics_runtime.render()
            except Exception:
                return Response(status_code=503, headers=headers)
            headers["Content-Type"] = PROMETHEUS_CONTENT_TYPE
            return Response(content=payload, headers=headers)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request: Request, exception: RequestValidationError) -> JSONResponse:
        del request, exception
        return JSONResponse(status_code=422, content={"detail": "Invalid request"})

    @app.exception_handler(ToolBoundaryFailure)
    async def tool_boundary_failure(
        request: Request, exception: ToolBoundaryFailure
    ) -> JSONResponse:
        del request
        record_action_request_failure(exception.reason)
        return JSONResponse(
            status_code=exception.status_code,
            content={"detail": exception.detail},
        )

    if not resolved.identity_enabled:
        return app

    resolved_validator = validator or DirectJwtValidator(
        resolved, HttpJwksSource(resolved.jwks_url)
    )
    resolved_sessions = sessions or MysqlSessionStore(resolved)
    resolved_conversations = conversations or MysqlConversationStore(resolved)
    resolved_feedback = feedback or MysqlFeedbackStore(resolved)
    resolved_evidence = evidence or MysqlEvaluationEvidenceStore(resolved)
    resolved_liveness = liveness
    if resolved.evaluation_enabled and (
        not resolved.evaluation_client_id or not resolved.evaluation_client_secret
    ):
        raise ValueError("Evaluation API credential is required")
    if resolved.evaluation_enabled and resolved_liveness is None:
        if not resolved.commerce_liveness_url:
            raise ValueError("Evaluation liveness URL is required")
        resolved_liveness = HttpSandboxLiveness(resolved.commerce_liveness_url)
    app.state.validator = resolved_validator
    app.state.sessions = resolved_sessions
    app.state.conversations = resolved_conversations
    app.state.feedback = resolved_feedback
    app.state.evidence = resolved_evidence
    app.state.liveness = resolved_liveness
    app.state.obo_client = OboClient(resolved, resolved_sessions)

    def authorize(
        authorization: str | None,
        x_eval_sandbox_id: str | None,
        permission: str,
    ) -> tuple[DirectPrincipal, str]:
        if (
            authorization is None
            or not authorization.startswith("Bearer ")
            or (x_eval_sandbox_id is not None and not resolved.evaluation_enabled)
        ):
            record_action_request_failure("AGENT_AUTHENTICATION_REJECTED")
            raise HTTPException(status_code=401, detail="Unauthorized")
        token = authorization[7:]
        try:
            if x_eval_sandbox_id is None:
                principal = resolved_validator.validate(token)
            else:
                principal = resolved_validator.validate(token, x_eval_sandbox_id)
        except HTTPException:
            record_action_request_failure("AGENT_AUTHENTICATION_REJECTED")
            raise
        if permission not in principal.permissions:
            record_action_request_failure("AGENT_AUTHORIZATION_REJECTED")
            raise HTTPException(status_code=403, detail="Forbidden")
        return principal, token

    def authorize_evaluator(authorization: str | None) -> None:
        if (
            authorization is None
            or len(authorization) > MAX_EVALUATION_AUTHORIZATION_LENGTH
            or not authorization.startswith("Basic ")
        ):
            raise HTTPException(status_code=401, detail="Unauthorized")
        try:
            encoded = authorization[6:].encode("ascii")
            decoded = b64decode(encoded, validate=True)
        except ValueError:
            raise HTTPException(status_code=401, detail="Unauthorized") from None
        client_id, separator, client_secret = decoded.partition(b":")
        if (
            separator != b":"
            or not secrets.compare_digest(client_id, resolved.evaluation_client_id.encode("utf-8"))
            or not secrets.compare_digest(
                client_secret, resolved.evaluation_client_secret.encode("utf-8")
            )
        ):
            raise HTTPException(status_code=401, detail="Unauthorized")

    def require_liveness(principal: DirectPrincipal, token: str) -> None:
        if principal.sandbox_id is None:
            return
        if resolved_liveness is None:
            raise ToolBoundaryFailure(
                status_code=503,
                reason="ACTION_SANDBOX_LIVENESS_UNAVAILABLE",
                detail="Service unavailable",
            )
        try:
            resolved_liveness.require_active(token, principal.sandbox_id)
        except SandboxLivenessRejected as exception:
            raise ToolBoundaryFailure(
                status_code=403,
                reason="ACTION_SANDBOX_LIVENESS_REJECTED",
                detail="Forbidden",
            ) from exception
        except SandboxLivenessUnavailable as exception:
            raise ToolBoundaryFailure(
                status_code=503,
                reason="ACTION_SANDBOX_LIVENESS_UNAVAILABLE",
                detail="Service unavailable",
            ) from exception

    def verify_session(session_id: str, principal: DirectPrincipal) -> None:
        if principal.sandbox_id is None:
            resolved_sessions.verify_owner(session_id, principal.subject)
        else:
            resolved_sessions.verify_owner(session_id, principal.subject, principal.sandbox_id)

    @app.post("/api/sessions", response_model=SessionResponse, status_code=201)
    def create_session(
        request: SessionCreateRequest,
        authorization: str | None = Header(default=None),
        x_eval_sandbox_id: str | None = Header(default=None),
    ) -> SessionResponse:
        del request
        principal, token = authorize(authorization, x_eval_sandbox_id, SESSION_PERMISSION)
        require_liveness(principal, token)
        if principal.sandbox_id is None:
            session_id = resolved_sessions.create(principal.subject)
        else:
            session_id = resolved_sessions.create(principal.subject, principal.sandbox_id)
        return SessionResponse(session_id=session_id)

    @app.post("/api/feedback", response_model=FeedbackResponse, status_code=201)
    def append_feedback(
        request: FeedbackRequest,
        authorization: str | None = Header(default=None),
        x_session_id: str = Header(min_length=1, max_length=64),
        idempotency_key: str = Header(min_length=1, max_length=128),
        x_eval_sandbox_id: str | None = Header(default=None),
    ) -> FeedbackResponse:
        principal, token = authorize(authorization, x_eval_sandbox_id, CHAT_PERMISSION)
        try:
            require_liveness(principal, token)
            verify_session(x_session_id, principal)
            record = resolved_feedback.append(
                session_id=x_session_id,
                subject=principal.subject,
                trace_id=str(request.trace_id),
                idempotency_key=idempotency_key,
                rating=request.rating,
                comment=request.comment,
            )
        except FeedbackOwnershipError as exception:
            raise HTTPException(status_code=403, detail="Forbidden") from exception
        except FeedbackConflictError as exception:
            raise HTTPException(status_code=409, detail="Idempotency conflict") from exception
        except pymysql.MySQLError as exception:
            raise HTTPException(status_code=503, detail="Service unavailable") from exception
        return FeedbackResponse(
            feedback_id=record.feedback_id,
            trace_id=record.trace_id,
            rating=record.rating,
        )

    if resolved.evaluation_enabled:

        @app.get(
            "/api/eval/evidence/{trace_id}",
            response_model=EvaluationEvidenceResponse,
            response_model_exclude_none=True,
        )
        def evaluation_evidence(
            trace_id: uuid.UUID,
            request: Request,
            authorization: str | None = Header(default=None),
            x_eval_sandbox_id: str = Header(
                min_length=1,
                max_length=64,
                pattern=r"^[A-Za-z0-9][A-Za-z0-9._-]*$",
            ),
        ) -> EvaluationEvidenceResponse:
            if (
                request.query_params
                or request.headers.get("content-length") not in {None, "0"}
                or request.headers.get("transfer-encoding") is not None
            ):
                raise HTTPException(status_code=422, detail="Invalid request")
            authorize_evaluator(authorization)
            try:
                return resolved_evidence.load(str(trace_id), x_eval_sandbox_id)
            except EvaluationEvidenceNotFound as exception:
                raise HTTPException(status_code=404, detail="Evidence not found") from exception
            except ActionEvaluationEvidenceInvalid as exception:
                record_action_request_failure("ACTION_EVALUATION_DURABLE_TRUTH_INCONSISTENT")
                raise HTTPException(status_code=409, detail="Evidence unavailable") from exception
            except EvaluationEvidenceInvalid as exception:
                raise HTTPException(status_code=409, detail="Evidence unavailable") from exception
            except pymysql.MySQLError as exception:
                raise HTTPException(status_code=503, detail="Service unavailable") from exception

    return app
