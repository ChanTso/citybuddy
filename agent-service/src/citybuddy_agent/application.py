"""Direct-user authentication and support-session identity boundary."""

from __future__ import annotations

import logging
import secrets
import time
import uuid
from base64 import b64decode
from collections.abc import Callable, Mapping
from datetime import UTC, datetime
from enum import Enum
from typing import Any, Literal, Protocol, TypeVar

import httpx
import jwt
import pymysql
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

from .actions import (
    ACTION_SCOPE,
    ActionJsonError,
    ActionReceiptPayload,
    ConfirmationDecision,
    PendingActionReference,
    bounded_http_post,
    confirmation_decision,
    strict_json_object,
)
from .agent_control import (
    AgentEvent,
    AgentRunner,
    AttemptBudget,
    AttemptBudgetExhausted,
    BoundedAgent,
    LiteLlmClient,
    ModelRouter,
    ProviderCircuits,
    ProviderRoute,
    RuleRouter,
    ToolAdapter,
    ToolBoundaryFailure,
)
from .conversation import (
    ActionArbitrationConflictError,
    ConversationIntegrityError,
    ConversationOwnershipError,
    ConversationResult,
    ConversationStore,
    CorrelationConflictError,
    MysqlConversationStore,
    TurnFailedError,
    TurnInProgressError,
)
from .evaluation import (
    ActionEvidenceIntegrityError,
    EvaluationEvidenceInvalid,
    EvaluationEvidenceNotFound,
    EvaluationEvidenceResponse,
    EvaluationEvidenceStore,
    MysqlEvaluationEvidenceStore,
)
from .faq_cache import RedisFaqCache
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
MAX_EVALUATION_AUTHORIZATION_LENGTH = 1024
LOGGER = logging.getLogger(__name__)
T = TypeVar("T")


class AgentRequestPhase(str, Enum):
    SESSION_VERIFICATION = "SESSION_VERIFICATION"
    TURN_REPLAY = "TURN_REPLAY"
    ACTION_REFERENCE_LOOKUP = "ACTION_REFERENCE_LOOKUP"
    SANDBOX_LIVENESS = "SANDBOX_LIVENESS"
    TURN_RESERVATION = "TURN_RESERVATION"
    ACTION_DECLINE_COMMIT = "ACTION_DECLINE_COMMIT"
    ACTION_EXPIRY_COMMIT = "ACTION_EXPIRY_COMMIT"
    ACTION_RECEIPT_COMMIT = "ACTION_RECEIPT_COMMIT"
    TURN_COMPLETION = "TURN_COMPLETION"


class AgentUnavailableReason(str, Enum):
    ACTION_SANDBOX_LIVENESS_UNAVAILABLE = "ACTION_SANDBOX_LIVENESS_UNAVAILABLE"
    ACTION_TURN_PREVIOUSLY_FAILED = "ACTION_TURN_PREVIOUSLY_FAILED"
    ACTION_SESSION_PERSISTENCE_UNAVAILABLE = "ACTION_SESSION_PERSISTENCE_UNAVAILABLE"
    ACTION_REPLAY_PERSISTENCE_UNAVAILABLE = "ACTION_REPLAY_PERSISTENCE_UNAVAILABLE"
    ACTION_REFERENCE_PERSISTENCE_UNAVAILABLE = "ACTION_REFERENCE_PERSISTENCE_UNAVAILABLE"
    ACTION_TURN_RESERVATION_PERSISTENCE_UNAVAILABLE = (
        "ACTION_TURN_RESERVATION_PERSISTENCE_UNAVAILABLE"
    )
    ACTION_DECLINE_PERSISTENCE_UNAVAILABLE = "ACTION_DECLINE_PERSISTENCE_UNAVAILABLE"
    ACTION_EXPIRY_PERSISTENCE_UNAVAILABLE = "ACTION_EXPIRY_PERSISTENCE_UNAVAILABLE"
    ACTION_RECEIPT_PERSISTENCE_UNAVAILABLE = "ACTION_RECEIPT_PERSISTENCE_UNAVAILABLE"
    TURN_COMPLETION_PERSISTENCE_UNAVAILABLE = "TURN_COMPLETION_PERSISTENCE_UNAVAILABLE"
    ACTION_CONFIRMATION_IDENTITY_UNAVAILABLE = "ACTION_CONFIRMATION_IDENTITY_UNAVAILABLE"
    ACTION_CONFIRMATION_INDETERMINATE = "ACTION_CONFIRMATION_INDETERMINATE"
    ACTION_PREPARATION_IDENTITY_UNAVAILABLE = "identity_unavailable"
    ACTION_PREPARATION_COMMERCE_TIMEOUT = "commerce_timeout"
    ACTION_PREPARATION_COMMERCE_INDETERMINATE = "commerce_indeterminate"
    ACTION_PREPARATION_COMMERCE_UNAVAILABLE = "commerce_unavailable"


ACTION_503_PRODUCER_INVENTORY: Mapping[AgentUnavailableReason, AgentRequestPhase] = {
    AgentUnavailableReason.ACTION_SANDBOX_LIVENESS_UNAVAILABLE: (
        AgentRequestPhase.SANDBOX_LIVENESS
    ),
    AgentUnavailableReason.ACTION_TURN_PREVIOUSLY_FAILED: AgentRequestPhase.TURN_REPLAY,
    AgentUnavailableReason.ACTION_SESSION_PERSISTENCE_UNAVAILABLE: (
        AgentRequestPhase.SESSION_VERIFICATION
    ),
    AgentUnavailableReason.ACTION_REPLAY_PERSISTENCE_UNAVAILABLE: (AgentRequestPhase.TURN_REPLAY),
    AgentUnavailableReason.ACTION_REFERENCE_PERSISTENCE_UNAVAILABLE: (
        AgentRequestPhase.ACTION_REFERENCE_LOOKUP
    ),
    AgentUnavailableReason.ACTION_TURN_RESERVATION_PERSISTENCE_UNAVAILABLE: (
        AgentRequestPhase.TURN_RESERVATION
    ),
    AgentUnavailableReason.ACTION_DECLINE_PERSISTENCE_UNAVAILABLE: (
        AgentRequestPhase.ACTION_DECLINE_COMMIT
    ),
    AgentUnavailableReason.ACTION_EXPIRY_PERSISTENCE_UNAVAILABLE: (
        AgentRequestPhase.ACTION_EXPIRY_COMMIT
    ),
    AgentUnavailableReason.ACTION_RECEIPT_PERSISTENCE_UNAVAILABLE: (
        AgentRequestPhase.ACTION_RECEIPT_COMMIT
    ),
    AgentUnavailableReason.TURN_COMPLETION_PERSISTENCE_UNAVAILABLE: (
        AgentRequestPhase.TURN_COMPLETION
    ),
    AgentUnavailableReason.ACTION_CONFIRMATION_IDENTITY_UNAVAILABLE: (
        AgentRequestPhase.ACTION_RECEIPT_COMMIT
    ),
    AgentUnavailableReason.ACTION_CONFIRMATION_INDETERMINATE: (
        AgentRequestPhase.ACTION_RECEIPT_COMMIT
    ),
    AgentUnavailableReason.ACTION_PREPARATION_IDENTITY_UNAVAILABLE: (
        AgentRequestPhase.TURN_COMPLETION
    ),
    AgentUnavailableReason.ACTION_PREPARATION_COMMERCE_TIMEOUT: (AgentRequestPhase.TURN_COMPLETION),
    AgentUnavailableReason.ACTION_PREPARATION_COMMERCE_INDETERMINATE: (
        AgentRequestPhase.TURN_COMPLETION
    ),
    AgentUnavailableReason.ACTION_PREPARATION_COMMERCE_UNAVAILABLE: (
        AgentRequestPhase.TURN_COMPLETION
    ),
}

PERSISTENCE_REASON_BY_PHASE: Mapping[AgentRequestPhase, AgentUnavailableReason] = {
    AgentRequestPhase.SESSION_VERIFICATION: (
        AgentUnavailableReason.ACTION_SESSION_PERSISTENCE_UNAVAILABLE
    ),
    AgentRequestPhase.TURN_REPLAY: (AgentUnavailableReason.ACTION_REPLAY_PERSISTENCE_UNAVAILABLE),
    AgentRequestPhase.ACTION_REFERENCE_LOOKUP: (
        AgentUnavailableReason.ACTION_REFERENCE_PERSISTENCE_UNAVAILABLE
    ),
    AgentRequestPhase.TURN_RESERVATION: (
        AgentUnavailableReason.ACTION_TURN_RESERVATION_PERSISTENCE_UNAVAILABLE
    ),
    AgentRequestPhase.ACTION_DECLINE_COMMIT: (
        AgentUnavailableReason.ACTION_DECLINE_PERSISTENCE_UNAVAILABLE
    ),
    AgentRequestPhase.ACTION_EXPIRY_COMMIT: (
        AgentUnavailableReason.ACTION_EXPIRY_PERSISTENCE_UNAVAILABLE
    ),
    AgentRequestPhase.ACTION_RECEIPT_COMMIT: (
        AgentUnavailableReason.ACTION_RECEIPT_PERSISTENCE_UNAVAILABLE
    ),
    AgentRequestPhase.TURN_COMPLETION: (
        AgentUnavailableReason.TURN_COMPLETION_PERSISTENCE_UNAVAILABLE
    ),
}


class SandboxLivenessRejected(Exception):
    """The authoritative liveness boundary returned a definite rejection."""


class SandboxLivenessUnavailable(Exception):
    """The authoritative liveness boundary could not produce a decision."""


class AgentRequestUnavailable(Exception):
    def __init__(
        self,
        reason: AgentUnavailableReason,
        phase: AgentRequestPhase,
        *,
        vendor_code: int | None = None,
        sql_state: str | None = None,
    ) -> None:
        super().__init__(reason.value)
        self.reason = reason
        self.phase = phase
        self.vendor_code = vendor_code
        self.sql_state = sql_state


def _mysql_diagnostics(exception: pymysql.MySQLError) -> tuple[int | None, str | None]:
    vendor_code = exception.args[0] if exception.args and type(exception.args[0]) is int else None
    raw_sql_state = getattr(exception, "sqlstate", None)
    sql_state = raw_sql_state if isinstance(raw_sql_state, str) else None
    return vendor_code, sql_state


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
    support_redis_url: str = ""
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


class ActionReceiptResponse(BaseModel):
    model_config = ConfigDict(frozen=True)

    receipt_id: str = Field(serialization_alias="receiptId")
    status: Literal["REQUESTED"]


class ChatResponse(BaseModel):
    model_config = ConfigDict(frozen=True)

    conversation_id: str = Field(serialization_alias="conversationId")
    trace_id: str = Field(serialization_alias="traceId")
    turn_id: str = Field(serialization_alias="turnId")
    reply: str
    outcome: str
    citations: tuple[CitationResponse, ...] = ()
    action_receipt: ActionReceiptResponse | None = Field(
        default=None, serialization_alias="actionReceipt"
    )


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
            raise SandboxLivenessUnavailable from exception
        if response.status_code == 204:
            return
        if response.status_code in {400, 401, 403, 404, 409, 422}:
            raise SandboxLivenessRejected
        raise SandboxLivenessUnavailable


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
        try:
            response = bounded_http_post(
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
        except ValueError as exception:
            raise HTTPException(
                status_code=503, detail="Identity exchange unavailable"
            ) from exception
        if response.status_code in {401, 403}:
            raise HTTPException(status_code=403, detail="Forbidden")
        if response.status_code != 200:
            raise HTTPException(status_code=503, detail="Identity exchange unavailable")
        try:
            payload = strict_json_object(response.content)
        except ActionJsonError as exception:
            raise HTTPException(
                status_code=503, detail="Identity exchange unavailable"
            ) from exception
        token = payload.get("accessToken") if isinstance(payload, dict) else None
        if not isinstance(token, str) or not token:
            raise HTTPException(status_code=502, detail="Identity exchange rejected")
        return token


class ActionConfirmationBoundary(Protocol):
    def confirm(
        self,
        *,
        direct_token: str,
        pending: PendingActionReference,
        budget: AttemptBudget,
    ) -> ActionReceiptPayload: ...


class HttpActionConfirmationBoundary:
    def __init__(self, base_url: str, obo: OboClient) -> None:
        self._base_url = base_url.rstrip("/")
        self._obo = obo

    def confirm(
        self,
        *,
        direct_token: str,
        pending: PendingActionReference,
        budget: AttemptBudget,
    ) -> ActionReceiptPayload:
        indeterminate = False
        while True:
            try:
                budget.charge("identity_http", ACTION_SCOPE)
            except AttemptBudgetExhausted as exception:
                raise ToolBoundaryFailure(
                    status_code=503,
                    reason=AgentUnavailableReason.ACTION_CONFIRMATION_INDETERMINATE.value,
                    detail=(
                        "Action confirmation indeterminate"
                        if indeterminate
                        else "Action confirmation attempt budget exhausted"
                    ),
                ) from exception
            try:
                obo = self._obo.exchange(
                    direct_token,
                    pending.user_subject,
                    pending.session_id,
                    ACTION_SCOPE,
                    pending.sandbox_id,
                )
            except HTTPException as exception:
                if exception.status_code in {401, 403}:
                    raise ToolBoundaryFailure(
                        status_code=403,
                        reason="ACTION_CONFIRMATION_IDENTITY_DENIED",
                        detail="Forbidden",
                    ) from exception
                raise ToolBoundaryFailure(
                    status_code=503,
                    reason=(AgentUnavailableReason.ACTION_CONFIRMATION_IDENTITY_UNAVAILABLE.value),
                    detail="Action confirmation indeterminate",
                ) from exception
            except (httpx.TimeoutException, httpx.NetworkError) as exception:
                raise ToolBoundaryFailure(
                    status_code=503,
                    reason=(AgentUnavailableReason.ACTION_CONFIRMATION_IDENTITY_UNAVAILABLE.value),
                    detail="Action confirmation indeterminate",
                ) from exception
            try:
                budget.charge("tool_http", "actions.refund.confirm")
            except AttemptBudgetExhausted as exception:
                raise ToolBoundaryFailure(
                    status_code=503,
                    reason=AgentUnavailableReason.ACTION_CONFIRMATION_INDETERMINATE.value,
                    detail="Action confirmation attempt budget exhausted",
                ) from exception
            headers = {
                "Authorization": f"Bearer {obo}",
                "X-Support-Session-Id": pending.session_id,
                "X-Agent-Trace-Id": pending.source_trace_id,
                "X-Agent-Turn-Id": pending.source_turn_id,
            }
            if pending.sandbox_id is not None:
                headers["X-Eval-Sandbox-Id"] = pending.sandbox_id
            try:
                response = bounded_http_post(
                    f"{self._base_url}/internal/tools/actions/{pending.pending_action_id}/confirm",
                    headers=headers,
                    timeout=3.0,
                )
            except (httpx.TimeoutException, httpx.NetworkError):
                indeterminate = True
                continue
            except ValueError as exception:
                raise ToolBoundaryFailure(
                    status_code=502,
                    reason="ACTION_CONFIRMATION_RESPONSE_INVALID",
                    detail="Invalid action confirmation response",
                ) from exception
            if response.status_code == 200:
                try:
                    receipt = ActionReceiptPayload.model_validate(
                        strict_json_object(response.content)
                    )
                except (ActionJsonError, ValueError, TypeError) as exception:
                    raise ToolBoundaryFailure(
                        status_code=502,
                        reason="ACTION_CONFIRMATION_RESPONSE_INVALID",
                        detail="Invalid action confirmation response",
                    ) from exception
                if (
                    receipt.pending_action_id != pending.pending_action_id
                    or receipt.action_type != pending.action_type
                    or receipt.order_id != pending.order_id
                    or receipt.amount_minor != pending.amount_minor
                    or receipt.currency != pending.currency
                    or receipt.argument_commitment != pending.argument_commitment
                ):
                    raise ToolBoundaryFailure(
                        status_code=502,
                        reason="ACTION_CONFIRMATION_RESPONSE_INVALID",
                        detail="Invalid action confirmation response",
                    )
                return receipt
            if response.status_code in {401, 403, 404}:
                raise ToolBoundaryFailure(
                    status_code=403,
                    reason="ACTION_CONFIRMATION_DENIED",
                    detail="Forbidden",
                )
            if response.status_code in {400, 409, 422}:
                raise ToolBoundaryFailure(
                    status_code=409,
                    reason="ACTION_CONFIRMATION_REJECTED",
                    detail="Action confirmation rejected",
                )
            if response.status_code in {408, 429, 502, 503, 504}:
                indeterminate = True
                continue
            raise RuntimeError("Unexpected action confirmation response")


def create_app(
    settings: AgentSettings | None = None,
    *,
    validator: DirectJwtValidator | None = None,
    sessions: SessionStore | None = None,
    conversations: ConversationStore | None = None,
    agent: AgentRunner | None = None,
    feedback: FeedbackStore | None = None,
    evidence: EvaluationEvidenceStore | None = None,
    liveness: SandboxLiveness | None = None,
    actions: ActionConfirmationBoundary | None = None,
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
    sse_filter = SseEgressFilter()
    app.state.validator = resolved_validator
    app.state.sessions = resolved_sessions
    app.state.conversations = resolved_conversations
    app.state.feedback = resolved_feedback
    app.state.evidence = resolved_evidence
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
                RedisFaqCache(resolved.support_redis_url) if resolved.support_redis_url else None,
            ),
        )
    else:
        resolved_agent = agent
    app.state.obo_client = resolved_obo
    resolved_actions = actions or HttpActionConfirmationBoundary(
        resolved.commerce_tools_url, resolved_obo
    )
    app.state.actions = resolved_actions
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
            raise SandboxLivenessUnavailable
        resolved_liveness.require_active(token, principal.sandbox_id)

    def verify_session(session_id: str, principal: DirectPrincipal) -> None:
        if principal.sandbox_id is None:
            resolved_sessions.verify_owner(session_id, principal.subject)
        else:
            resolved_sessions.verify_owner(session_id, principal.subject, principal.sandbox_id)

    def in_request_phase(
        phase: AgentRequestPhase,
        work: Callable[[], T],
    ) -> T:
        try:
            return work()
        except SandboxLivenessUnavailable as exception:
            raise AgentRequestUnavailable(
                AgentUnavailableReason.ACTION_SANDBOX_LIVENESS_UNAVAILABLE,
                phase,
            ) from exception
        except TurnFailedError as exception:
            raise AgentRequestUnavailable(
                AgentUnavailableReason.ACTION_TURN_PREVIOUSLY_FAILED,
                phase,
            ) from exception
        except pymysql.MySQLError as exception:
            vendor_code, sql_state = _mysql_diagnostics(exception)
            raise AgentRequestUnavailable(
                PERSISTENCE_REASON_BY_PHASE[phase],
                phase,
                vendor_code=vendor_code,
                sql_state=sql_state,
            ) from exception

    def log_request_unavailable(exception: AgentRequestUnavailable) -> None:
        fields: list[object] = [exception.reason.value, exception.phase.value]
        message = "agent_request_rejected reason_code=%s phase=%s"
        if exception.vendor_code is not None:
            message += " mysql_vendor_code=%s"
            fields.append(exception.vendor_code)
        if exception.sql_state is not None:
            message += " sql_state=%s"
            fields.append(exception.sql_state)
        LOGGER.warning(message, *fields)

    def unavailable_public_detail(exception: AgentRequestUnavailable) -> str:
        if exception.phase is AgentRequestPhase.ACTION_RECEIPT_COMMIT:
            return "Action confirmation indeterminate"
        return "Service unavailable"

    def fail_turn_without_masking(
        *,
        start: Any,
        failure_code: str,
        events: tuple[AgentEvent, ...],
        original_reason: str,
        original_phase: AgentRequestPhase,
    ) -> None:
        try:
            resolved_conversations.fail_turn(
                start=start,
                failure_code=failure_code,
                events=events,
            )
        except pymysql.MySQLError as cleanup_exception:
            vendor_code, sql_state = _mysql_diagnostics(cleanup_exception)
            fields: list[object] = [
                original_reason,
                original_phase.value,
                AgentUnavailableReason.TURN_COMPLETION_PERSISTENCE_UNAVAILABLE.value,
            ]
            message = (
                "agent_request_cleanup_failed original_reason_code=%s "
                "original_phase=%s cleanup_reason_code=%s"
            )
            if vendor_code is not None:
                message += " mysql_vendor_code=%s"
                fields.append(vendor_code)
            if sql_state is not None:
                message += " sql_state=%s"
                fields.append(sql_state)
            LOGGER.warning(message, *fields)

    def execute_turn(
        request: ChatRequest,
        *,
        token: str,
        principal: DirectPrincipal,
        session_id: str,
        correlation_key: str,
    ) -> ConversationResult:
        in_request_phase(
            AgentRequestPhase.SESSION_VERIFICATION,
            lambda: verify_session(session_id, principal),
        )
        replay = in_request_phase(
            AgentRequestPhase.TURN_REPLAY,
            lambda: resolved_conversations.replay_turn(
                session_id=session_id,
                subject=principal.subject,
                sandbox_id=principal.sandbox_id,
                correlation_key=correlation_key,
                message=request.message,
            ),
        )
        if replay is not None:
            return replay
        current_action = in_request_phase(
            AgentRequestPhase.ACTION_REFERENCE_LOOKUP,
            lambda: resolved_conversations.current_action_reference(
                session_id=session_id,
                subject=principal.subject,
                sandbox_id=principal.sandbox_id,
            ),
        )
        pending_decision = confirmation_decision(request.message)
        pending = (
            current_action.reference
            if current_action is not None and current_action.state in {"PENDING", "CONFIRMING"}
            else None
        )
        if (
            current_action is not None
            and current_action.state == "CONFIRMING"
            and pending_decision is not ConfirmationDecision.CONFIRM
        ):
            raise ActionArbitrationConflictError
        if (
            current_action is not None
            and current_action.state in {"DECLINED", "EXPIRED", "CONFIRMED"}
            and pending_decision in {ConfirmationDecision.CONFIRM, ConfirmationDecision.DECLINE}
        ):
            raise ActionArbitrationConflictError
        # A confirmation may be recovering a commerce commit whose response or local
        # projection was lost. The fixed CB-118 result boundary owns that decision and
        # resolves committed truth before mutable sandbox liveness or copied expiry.
        if pending_decision is not ConfirmationDecision.CONFIRM:
            in_request_phase(
                AgentRequestPhase.SANDBOX_LIVENESS,
                lambda: require_liveness(principal, token),
            )
        if pending is not None and pending_decision is ConfirmationDecision.CONFIRM:
            start = in_request_phase(
                AgentRequestPhase.TURN_RESERVATION,
                lambda: resolved_conversations.begin_or_resume_confirmation_turn(
                    session_id=session_id,
                    subject=principal.subject,
                    sandbox_id=principal.sandbox_id,
                    correlation_key=correlation_key,
                    message=request.message,
                    pending=pending,
                ),
            )
        else:
            start = in_request_phase(
                AgentRequestPhase.TURN_RESERVATION,
                lambda: resolved_conversations.begin_turn(
                    session_id=session_id,
                    subject=principal.subject,
                    sandbox_id=principal.sandbox_id,
                    correlation_key=correlation_key,
                    message=request.message,
                ),
            )
        if start.replay is not None:
            return start.replay
        action_events: list[AgentEvent] = []
        try:
            if pending is not None:
                if pending_decision is ConfirmationDecision.CONFIRM:
                    budget = AttemptBudget(resolved.attempt_budget, action_events)
                    receipt = resolved_actions.confirm(
                        direct_token=token,
                        pending=pending,
                        budget=budget,
                    )
                    return in_request_phase(
                        AgentRequestPhase.ACTION_RECEIPT_COMMIT,
                        lambda: resolved_conversations.complete_action_receipt(
                            start=start,
                            pending=pending,
                            receipt=receipt,
                            response_text="The refund request was accepted.",
                            events=tuple(action_events),
                        ),
                    )
                if pending.expires_at <= datetime.now(UTC):
                    return in_request_phase(
                        AgentRequestPhase.ACTION_EXPIRY_COMMIT,
                        lambda: resolved_conversations.complete_action_expired(
                            start=start,
                            pending=pending,
                            response_text="The prepared action expired and was not executed.",
                        ),
                    )
                if pending_decision is ConfirmationDecision.DECLINE:
                    return in_request_phase(
                        AgentRequestPhase.ACTION_DECLINE_COMMIT,
                        lambda: resolved_conversations.complete_action_decline(
                            start=start,
                            pending=pending,
                            response_text="The prepared action was declined and was not executed.",
                        ),
                    )
                if pending_decision is ConfirmationDecision.CLARIFY:
                    return in_request_phase(
                        AgentRequestPhase.TURN_COMPLETION,
                        lambda: resolved_conversations.complete_turn(
                            start=start,
                            response_text=(
                                "Please reply with an exact confirmation or decline for the "
                                "prepared refund request."
                            ),
                            outcome="action_clarification",
                            events=(
                                AgentEvent(
                                    "AGENT_OUTCOME",
                                    {"outcome": "action_clarification"},
                                ),
                            ),
                        ),
                    )
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
            return in_request_phase(
                AgentRequestPhase.TURN_COMPLETION,
                lambda: resolved_conversations.complete_turn(
                    start=start,
                    response_text=agent_result.response_text,
                    outcome=agent_result.outcome,
                    events=agent_result.events,
                    retrieval_decision=agent_result.retrieval_decision,
                    pending_action=agent_result.pending_action,
                ),
            )
        except ToolBoundaryFailure as exception:
            tool_phase = (
                AgentRequestPhase.ACTION_RECEIPT_COMMIT
                if start.confirmation_pending_id is not None
                else AgentRequestPhase.TURN_COMPLETION
            )
            if exception.status_code == 503:
                try:
                    unavailable_reason = AgentUnavailableReason(exception.reason)
                except ValueError as unregistered:
                    raise RuntimeError(
                        "Action-reachable 503 producer is not registered"
                    ) from unregistered
                if ACTION_503_PRODUCER_INVENTORY[unavailable_reason] is not tool_phase:
                    raise RuntimeError(
                        "Action-reachable 503 producer is registered for another phase"
                    ) from exception
            LOGGER.warning(
                "agent_request_rejected reason_code=%s phase=%s",
                exception.reason,
                tool_phase.value,
            )
            if start.confirmation_pending_id is None:
                fail_turn_without_masking(
                    start=start,
                    failure_code=exception.reason,
                    events=tuple(action_events),
                    original_reason=exception.reason,
                    original_phase=tool_phase,
                )
            raise HTTPException(
                status_code=exception.status_code, detail=exception.detail
            ) from exception
        except ActionArbitrationConflictError:
            if start.confirmation_pending_id is None:
                fail_turn_without_masking(
                    start=start,
                    failure_code="ACTION_CONFIRMATION_ARBITRATION_CONFLICT",
                    events=tuple(action_events),
                    original_reason="ACTION_CONFIRMATION_ARBITRATION_CONFLICT",
                    original_phase=AgentRequestPhase.TURN_COMPLETION,
                )
            raise
        except AgentRequestUnavailable as exception:
            if start.confirmation_pending_id is None:
                fail_turn_without_masking(
                    start=start,
                    failure_code=exception.reason.value,
                    events=tuple(action_events),
                    original_reason=exception.reason.value,
                    original_phase=exception.phase,
                )
            raise
        except Exception:
            if start.confirmation_pending_id is None:
                resolved_conversations.fail_turn(
                    start=start,
                    failure_code="agent_execution_failed",
                    events=tuple(action_events),
                )
            raise

    @app.post("/api/sessions", response_model=SessionResponse, status_code=201)
    def create_session(
        request: SessionCreateRequest,
        authorization: str | None = Header(default=None),
        x_eval_sandbox_id: str | None = Header(default=None),
    ) -> SessionResponse:
        del request
        principal, token = authorize(authorization, x_eval_sandbox_id, SESSION_PERMISSION)
        try:
            in_request_phase(
                AgentRequestPhase.SANDBOX_LIVENESS,
                lambda: require_liveness(principal, token),
            )
            if principal.sandbox_id is None:
                session_id = resolved_sessions.create(principal.subject)
            else:
                session_id = resolved_sessions.create(principal.subject, principal.sandbox_id)
        except SandboxLivenessRejected as exception:
            raise HTTPException(status_code=403, detail="Forbidden") from exception
        except AgentRequestUnavailable as exception:
            log_request_unavailable(exception)
            raise HTTPException(
                status_code=503,
                detail=unavailable_public_detail(exception),
            ) from exception
        return SessionResponse(session_id=session_id)

    @app.post("/api/chat", response_model=ChatResponse, response_model_exclude_none=True)
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
        except ActionArbitrationConflictError as exception:
            LOGGER.warning(
                "agent_request_rejected reason_code=ACTION_CONFIRMATION_ARBITRATION_CONFLICT"
            )
            raise HTTPException(
                status_code=409, detail="Action confirmation conflict"
            ) from exception
        except ConversationIntegrityError as exception:
            LOGGER.warning(
                "agent_request_rejected reason_code=CONVERSATION_ACTION_DURABLE_TRUTH_INCONSISTENT"
            )
            raise HTTPException(
                status_code=409, detail="Conversation evidence conflict"
            ) from exception
        except TurnInProgressError as exception:
            raise HTTPException(status_code=409, detail="Turn in progress") from exception
        except SandboxLivenessRejected as exception:
            raise HTTPException(status_code=403, detail="Forbidden") from exception
        except AgentRequestUnavailable as exception:
            log_request_unavailable(exception)
            raise HTTPException(
                status_code=503,
                detail=unavailable_public_detail(exception),
            ) from exception
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
            action_receipt=(
                ActionReceiptResponse(
                    receipt_id=result.action_receipt.receipt.receipt_id,
                    status=result.action_receipt.receipt.status,
                )
                if result.action_receipt is not None
                else None
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
        except ActionArbitrationConflictError as exception:
            LOGGER.warning(
                "agent_request_rejected reason_code=ACTION_CONFIRMATION_ARBITRATION_CONFLICT"
            )
            raise HTTPException(
                status_code=409, detail="Action confirmation conflict"
            ) from exception
        except ConversationIntegrityError as exception:
            LOGGER.warning(
                "agent_request_rejected reason_code=CONVERSATION_ACTION_DURABLE_TRUTH_INCONSISTENT"
            )
            raise HTTPException(
                status_code=409, detail="Conversation evidence conflict"
            ) from exception
        except TurnInProgressError as exception:
            raise HTTPException(status_code=409, detail="Turn in progress") from exception
        except SandboxLivenessRejected as exception:
            raise HTTPException(status_code=403, detail="Forbidden") from exception
        except AgentRequestUnavailable as exception:
            log_request_unavailable(exception)
            raise HTTPException(
                status_code=503,
                detail=unavailable_public_detail(exception),
            ) from exception
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
            except ActionEvidenceIntegrityError as exception:
                LOGGER.warning(
                    "evaluation_request_rejected "
                    "reason_code=EVALUATION_ACTION_DURABLE_TRUTH_INCONSISTENT"
                )
                raise HTTPException(status_code=409, detail="Evidence unavailable") from exception
            except EvaluationEvidenceInvalid as exception:
                raise HTTPException(status_code=409, detail="Evidence unavailable") from exception
            except pymysql.MySQLError as exception:
                raise HTTPException(status_code=503, detail="Service unavailable") from exception

    return app
