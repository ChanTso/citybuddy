"""Direct-user authentication and support-session identity boundary."""

from __future__ import annotations

import secrets
import time
import uuid
from collections.abc import Mapping
from typing import Any, Literal, Protocol

import httpx
import jwt
import pymysql
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

from .agent_control import (
    AgentRunner,
    BoundedAgent,
    LiteLlmClient,
    ModelRouter,
    ProviderCircuits,
    ProviderRoute,
    RuleRouter,
    ToolAdapter,
)
from .conversation import (
    ConversationOwnershipError,
    ConversationResult,
    ConversationStore,
    CorrelationConflictError,
    MysqlConversationStore,
    TurnFailedError,
    TurnInProgressError,
)
from .feedback import (
    FeedbackConflictError,
    FeedbackOwnershipError,
    FeedbackStore,
    MysqlFeedbackStore,
)
from .knowledge import ElasticsearchKnowledgeSearch
from .retrieval import load_calibration
from .sse import SseEgressFilter, SseProjectionError, stream_events

SESSION_PERMISSION = "support:session:create"
CHAT_PERMISSION = "support:chat"
DIRECT_TOKEN_TYPE = "direct_user"
EVALUATION_DIRECT_TOKEN_TYPE = "eval_direct_user"


class AgentSettings(BaseModel):
    """Runtime identity configuration; secret values have no defaults."""

    model_config = ConfigDict(frozen=True)

    service_name: str = "agent-service"
    environment: str = "development"
    identity_enabled: bool = False
    evaluation_enabled: bool = False
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
    model_proxy_url: str = ""
    commerce_tools_url: str = ""
    commerce_liveness_url: str = ""
    primary_role_alias: str = "support-standard-primary"
    fallback_role_alias: str = "support-standard-fallback"
    primary_provider_key: str = "primary"
    fallback_provider_key: str = "fallback"
    reranker_role_alias: str = "support-reranker-standard"
    reranker_provider_key: str = "reranker"
    elasticsearch_url: str = ""
    knowledge_alias: str = "knowledge_docs_read"
    attempt_budget: int = 8
    circuit_minimum_requests: int = 2
    circuit_open_seconds: float = 1.0
    circuit_half_open_probes: int = 1
    clock_skew_seconds: int = 30
    jwks_cache_seconds: int = 60


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


class ChatRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    message: str = Field(min_length=1, max_length=4000)


class CitationResponse(BaseModel):
    model_config = ConfigDict(frozen=True)

    source_id: str = Field(serialization_alias="sourceId")
    chunk_id: str = Field(serialization_alias="chunkId")
    source_version: int = Field(serialization_alias="sourceVersion")
    doc_type: Literal["faq", "product"] = Field(serialization_alias="docType")
    title: str


class ChatResponse(BaseModel):
    model_config = ConfigDict(frozen=True)

    conversation_id: str = Field(serialization_alias="conversationId")
    trace_id: str = Field(serialization_alias="traceId")
    turn_id: str = Field(serialization_alias="turnId")
    reply: str
    outcome: str
    citations: tuple[CitationResponse, ...] = ()


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


class HttpSandboxLiveness:
    def __init__(self, base_url: str) -> None:
        self._base_url = base_url.rstrip("/")

    def require_active(self, direct_token: str, sandbox_id: str) -> None:
        try:
            response = httpx.post(
                f"{self._base_url}/internal/eval/sandboxes/{sandbox_id}/liveness",
                headers={
                    "Authorization": f"Bearer {direct_token}",
                    "X-Eval-Sandbox-Id": sandbox_id,
                },
                timeout=3.0,
            )
        except (httpx.TimeoutException, httpx.NetworkError) as exception:
            raise HTTPException(status_code=503, detail="Service unavailable") from exception
        if response.status_code == 204:
            return
        if response.status_code in {400, 401, 403, 404, 409, 422}:
            raise HTTPException(status_code=403, detail="Forbidden")
        raise HTTPException(status_code=503, detail="Service unavailable")


class OboClient:
    """JIT exchange boundary used by future server-owned ToolSpecs."""

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
        response = httpx.post(
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


def create_app(
    settings: AgentSettings | None = None,
    *,
    validator: DirectJwtValidator | None = None,
    sessions: SessionStore | None = None,
    conversations: ConversationStore | None = None,
    agent: AgentRunner | None = None,
    feedback: FeedbackStore | None = None,
    liveness: SandboxLiveness | None = None,
) -> FastAPI:
    """Construct the app, enabling identity routes only with complete runtime configuration."""
    resolved = settings or AgentSettings()
    app = FastAPI(title=resolved.service_name, docs_url=None, redoc_url=None)
    app.state.settings = resolved

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request: Request, exception: RequestValidationError) -> JSONResponse:
        del request, exception
        return JSONResponse(status_code=422, content={"detail": "Invalid request"})

    if not resolved.identity_enabled:
        return app

    resolved_validator = validator or DirectJwtValidator(
        resolved, HttpJwksSource(resolved.jwks_url)
    )
    resolved_sessions = sessions or MysqlSessionStore(resolved)
    resolved_conversations = conversations or MysqlConversationStore(resolved)
    resolved_feedback = feedback or MysqlFeedbackStore(resolved)
    resolved_liveness = liveness
    if resolved.evaluation_enabled and resolved_liveness is None:
        if not resolved.commerce_liveness_url:
            raise ValueError("Evaluation liveness URL is required")
        resolved_liveness = HttpSandboxLiveness(resolved.commerce_liveness_url)
    sse_filter = SseEgressFilter()
    app.state.validator = resolved_validator
    app.state.sessions = resolved_sessions
    app.state.conversations = resolved_conversations
    app.state.feedback = resolved_feedback
    app.state.liveness = resolved_liveness
    app.state.sse_filter = sse_filter
    resolved_obo = OboClient(resolved, resolved_sessions)
    resolved_agent: AgentRunner
    if agent is None:
        model_client = LiteLlmClient(
            resolved.model_proxy_url,
            ProviderCircuits(
                minimum_requests=resolved.circuit_minimum_requests,
                open_seconds=resolved.circuit_open_seconds,
                half_open_probes=resolved.circuit_half_open_probes,
            ),
        )
        resolved_agent = BoundedAgent(
            RuleRouter(),
            ModelRouter(
                (
                    ProviderRoute(resolved.primary_role_alias, resolved.primary_provider_key),
                    ProviderRoute(resolved.fallback_role_alias, resolved.fallback_provider_key),
                ),
                resolved.attempt_budget,
                ProviderRoute(resolved.reranker_role_alias, resolved.reranker_provider_key),
            ),
            model_client,
            ToolAdapter(
                resolved.commerce_tools_url,
                resolved_obo,
                ElasticsearchKnowledgeSearch(
                    resolved.elasticsearch_url,
                    alias=resolved.knowledge_alias,
                )
                if resolved.elasticsearch_url
                else None,
                model_client,
                load_calibration(),
            ),
        )
    else:
        resolved_agent = agent
    app.state.obo_client = resolved_obo
    app.state.agent = resolved_agent

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
            raise HTTPException(status_code=401, detail="Unauthorized")
        token = authorization[7:]
        if x_eval_sandbox_id is None:
            principal = resolved_validator.validate(token)
        else:
            principal = resolved_validator.validate(token, x_eval_sandbox_id)
        if permission not in principal.permissions:
            raise HTTPException(status_code=403, detail="Forbidden")
        return principal, token

    def require_liveness(principal: DirectPrincipal, token: str) -> None:
        if principal.sandbox_id is None:
            return
        if resolved_liveness is None:
            raise HTTPException(status_code=503, detail="Service unavailable")
        resolved_liveness.require_active(token, principal.sandbox_id)

    def verify_session(session_id: str, principal: DirectPrincipal) -> None:
        if principal.sandbox_id is None:
            resolved_sessions.verify_owner(session_id, principal.subject)
        else:
            resolved_sessions.verify_owner(session_id, principal.subject, principal.sandbox_id)

    def execute_turn(
        request: ChatRequest,
        *,
        token: str,
        principal: DirectPrincipal,
        session_id: str,
        correlation_key: str,
    ) -> ConversationResult:
        require_liveness(principal, token)
        verify_session(session_id, principal)
        start = resolved_conversations.begin_turn(
            session_id=session_id,
            subject=principal.subject,
            sandbox_id=principal.sandbox_id,
            correlation_key=correlation_key,
            message=request.message,
        )
        if start.replay is not None:
            return start.replay
        try:
            if principal.sandbox_id is None:
                agent_result = resolved_agent.run(
                    message=request.message,
                    direct_token=token,
                    subject=principal.subject,
                    session_id=session_id,
                    trace_id=start.trace_id,
                    turn_id=start.turn_id,
                )
            else:
                agent_result = resolved_agent.run(
                    message=request.message,
                    direct_token=token,
                    subject=principal.subject,
                    session_id=session_id,
                    trace_id=start.trace_id,
                    turn_id=start.turn_id,
                    sandbox_id=principal.sandbox_id,
                )
            return resolved_conversations.complete_turn(
                start=start,
                response_text=agent_result.response_text,
                outcome=agent_result.outcome,
                events=agent_result.events,
                retrieval_decision=agent_result.retrieval_decision,
            )
        except Exception:
            resolved_conversations.fail_turn(start=start, failure_code="agent_execution_failed")
            raise

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

    @app.post("/api/chat", response_model=ChatResponse)
    def chat(
        request: ChatRequest,
        authorization: str | None = Header(default=None),
        x_session_id: str = Header(min_length=1, max_length=64),
        idempotency_key: str = Header(min_length=1, max_length=128),
        x_eval_sandbox_id: str | None = Header(default=None),
    ) -> ChatResponse:
        principal, token = authorize(authorization, x_eval_sandbox_id, CHAT_PERMISSION)
        try:
            result = execute_turn(
                request,
                token=token,
                principal=principal,
                session_id=x_session_id,
                correlation_key=idempotency_key,
            )
        except ConversationOwnershipError as exception:
            raise HTTPException(status_code=403, detail="Forbidden") from exception
        except CorrelationConflictError as exception:
            raise HTTPException(status_code=409, detail="Idempotency conflict") from exception
        except TurnInProgressError as exception:
            raise HTTPException(status_code=409, detail="Turn in progress") from exception
        except TurnFailedError as exception:
            raise HTTPException(status_code=503, detail="Service unavailable") from exception
        except pymysql.MySQLError as exception:
            raise HTTPException(status_code=503, detail="Service unavailable") from exception
        return ChatResponse(
            conversation_id=result.conversation_id,
            trace_id=result.trace_id,
            turn_id=result.turn_id,
            reply=result.response_text,
            outcome=result.outcome,
            citations=tuple(
                CitationResponse(
                    source_id=evidence.source_id,
                    chunk_id=evidence.chunk_id,
                    source_version=evidence.source_version,
                    doc_type=evidence.doc_type,
                    title=evidence.title,
                )
                for evidence in result.retrieval_evidence
            ),
        )

    @app.post("/api/chat/stream")
    def chat_stream(
        request: ChatRequest,
        http_request: Request,
        authorization: str | None = Header(default=None),
        x_session_id: str = Header(min_length=1, max_length=64),
        idempotency_key: str = Header(min_length=1, max_length=128),
        x_eval_sandbox_id: str | None = Header(default=None),
    ) -> StreamingResponse:
        principal, token = authorize(authorization, x_eval_sandbox_id, CHAT_PERMISSION)
        try:
            result = execute_turn(
                request,
                token=token,
                principal=principal,
                session_id=x_session_id,
                correlation_key=idempotency_key,
            )
        except ConversationOwnershipError as exception:
            raise HTTPException(status_code=403, detail="Forbidden") from exception
        except CorrelationConflictError as exception:
            raise HTTPException(status_code=409, detail="Idempotency conflict") from exception
        except TurnInProgressError as exception:
            raise HTTPException(status_code=409, detail="Turn in progress") from exception
        except TurnFailedError as exception:
            raise HTTPException(status_code=503, detail="Service unavailable") from exception
        except pymysql.MySQLError as exception:
            raise HTTPException(status_code=503, detail="Service unavailable") from exception
        except HTTPException:
            raise
        except Exception:
            events = sse_filter.terminal_error("stream_unavailable")
        else:
            try:
                events = sse_filter.project_result(result)
            except SseProjectionError:
                events = sse_filter.terminal_error("unsafe_output")
        return StreamingResponse(
            stream_events(events, http_request.is_disconnected),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache, no-store",
                "X-Accel-Buffering": "no",
            },
        )

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

    return app
