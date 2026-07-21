from __future__ import annotations

import json
from copy import deepcopy
from typing import Any

import httpx
import pytest
from citybuddy_agent.agent_control import (
    KNOWLEDGE_SEARCH_SPEC,
    AgentEvent,
    AttemptBudget,
    BoundedAgent,
    LiteLlmClient,
    ModelPlan,
    ModelReply,
    ModelRouter,
    ProviderCircuits,
    ProviderRoute,
    RuleRouter,
    ToolAdapter,
)
from citybuddy_agent.knowledge import (
    EMBEDDING_DIMS,
    FINAL_RESULT_LIMIT,
    RECALL_LIMIT,
    RRF_CONSTANT,
    ElasticsearchKnowledgeSearch,
    KnowledgeSearchFailure,
    KnowledgeSearchInput,
    KnowledgeSearchOutput,
    KnowledgeSearchResult,
    PublicKnowledgeMetadata,
    deterministic_query_embedding,
)
from citybuddy_agent.retrieval import RerankOutput, RerankScore, load_calibration
from pydantic import ValidationError


def mapping() -> dict[str, object]:
    return {
        "knowledge_docs_v1": {
            "mappings": {
                "dynamic": "strict",
                "properties": {
                    "schema_version": {"type": "keyword"},
                    "source_id": {"type": "keyword"},
                    "source_version": {"type": "long"},
                    "chunk_id": {"type": "keyword"},
                    "doc_type": {"type": "keyword"},
                    "published": {"type": "boolean"},
                    "deleted": {"type": "boolean"},
                    "title": {
                        "type": "text",
                        "analyzer": "ik_max_word",
                        "search_analyzer": "ik_smart",
                    },
                    "content": {
                        "type": "text",
                        "analyzer": "ik_max_word",
                        "search_analyzer": "ik_smart",
                    },
                    "embedding": {
                        "type": "dense_vector",
                        "dims": EMBEDDING_DIMS,
                        "index": True,
                        "similarity": "cosine",
                    },
                    "public_metadata": {
                        "type": "object",
                        "dynamic": "strict",
                        "properties": {
                            "product_id": {"type": "keyword"},
                            "category": {"type": "keyword"},
                            "language": {"type": "keyword"},
                        },
                    },
                    "sync_record_type": {"type": "keyword"},
                    "sync_event_id": {"type": "keyword"},
                    "sync_event_commitment": {"type": "keyword"},
                    "sync_occurred_at": {
                        "type": "date",
                        "format": "strict_date_optional_time_nanos",
                    },
                },
            }
        }
    }


def alias() -> dict[str, object]:
    return {"knowledge_docs_v1": {"aliases": {"knowledge_docs_read": {}}}}


def source(source_id: str, chunk_id: str, *, title: str | None = None) -> dict[str, object]:
    return {
        "schema_version": "cb090-v1",
        "source_id": source_id,
        "source_version": 1,
        "chunk_id": chunk_id,
        "doc_type": "faq",
        "published": True,
        "deleted": False,
        "title": title or source_id,
        "content": f"Public content for {source_id}",
        "public_metadata": {"category": "policy", "language": "en"},
    }


def hits(*values: tuple[str, dict[str, object]]) -> dict[str, object]:
    return {
        "timed_out": False,
        "_shards": {"total": 1, "successful": 1, "skipped": 0, "failed": 0},
        "hits": {
            "hits": [
                {
                    "_id": document_id,
                    "_index": "knowledge_docs_v1",
                    "_source": document_source,
                }
                for document_id, document_source in values
            ]
        },
    }


def response(status: int, payload: dict[str, object]) -> httpx.Response:
    return httpx.Response(status, json=payload)


def test_toolspec_is_server_owned_and_forbids_model_control_fields() -> None:
    schema = KnowledgeSearchInput.model_json_schema(by_alias=True)

    assert KNOWLEDGE_SEARCH_SPEC.name == "knowledge.search"
    assert KNOWLEDGE_SEARCH_SPEC.authority == "elasticsearch"
    assert KNOWLEDGE_SEARCH_SPEC.scope is None
    assert KNOWLEDGE_SEARCH_SPEC.risk == "read"
    assert KNOWLEDGE_SEARCH_SPEC.idempotency == "read-only"
    assert schema["additionalProperties"] is False
    assert set(schema["properties"]) == {"query", "rewrite"}
    assert not {"index", "alias", "filter", "limit", "candidateCount", "rrfK"}.intersection(
        schema["properties"]
    )
    with pytest.raises(ValidationError):
        KnowledgeSearchInput.model_validate({"query": "refund", "limit": 100})
    with pytest.raises(ValidationError):
        KnowledgeSearchInput.model_validate({"query": "  "})


@pytest.mark.parametrize(
    "serialized_arguments",
    [
        "{}",
        '{"query":"refund","filter":{"user":"private"}}',
        '{"query":"refund","candidateCount":1000}',
        '{"query":"refund","rewrite":["return"]}',
        '{"query":"refund\\nprivate"}',
        json.dumps({"query": "x" * 513}),
    ],
)
def test_invalid_tool_arguments_are_rejected_before_any_backend_io(
    serialized_arguments: str,
) -> None:
    class ForbiddenObo:
        def exchange(self, *args: object) -> str:
            raise AssertionError(f"invalid knowledge input attempted OBO: {args!r}")

    class ForbiddenKnowledge:
        def search(self, request: KnowledgeSearchInput, charge: Any) -> KnowledgeSearchOutput:
            raise AssertionError(f"invalid knowledge input reached Elasticsearch: {request!r}")

    events: list[AgentEvent] = []
    budget = AttemptBudget(2, events)
    result = ToolAdapter("https://commerce.test", ForbiddenObo(), ForbiddenKnowledge()).execute(
        name="knowledge.search",
        serialized_arguments=serialized_arguments,
        direct_token="direct",
        subject="user",
        session_id="session",
        budget=budget,
        events=events,
    )

    assert result.model_view == {
        "outcome": "deny_with_feedback",
        "reason": "invalid_arguments",
    }
    assert budget.used == 0


def test_tool_adapter_uses_elasticsearch_without_obo_or_caller_authority() -> None:
    class ForbiddenObo:
        def exchange(self, *args: object) -> str:
            raise AssertionError(f"knowledge.search attempted OBO: {args!r}")

    class StubKnowledge:
        def search(self, request: KnowledgeSearchInput, charge: Any) -> KnowledgeSearchOutput:
            assert request == KnowledgeSearchInput(query="refund", rewrite="return policy")
            charge("knowledge_http", "alias_resolution")
            charge("knowledge_http", "bm25_recall")
            return KnowledgeSearchOutput(
                index_version="knowledge_docs_v1",
                results=(
                    KnowledgeSearchResult(
                        source_id="faq-refund",
                        chunk_id="answer",
                        source_version=1,
                        doc_type="faq",
                        title="Refund policy",
                        excerpt="Public refund guidance.",
                        public_metadata=PublicKnowledgeMetadata(category="policy", language="en"),
                        rank=1,
                        rrf_score=0.03,
                    ),
                ),
            )

    class StubReranker:
        def rerank(
            self,
            plan: ModelPlan,
            request: Any,
            budget: AttemptBudget,
            events: list[AgentEvent],
        ) -> RerankOutput:
            del events
            assert plan.reranker_route.role_alias == "support-reranker-standard"
            assert request.model_dump(by_alias=True)["query"] == "refund"
            budget.charge("reranker_http", plan.reranker_route.provider_key)
            return RerankOutput(scores=(RerankScore(candidate_id="faq-refund:answer", score=0.9),))

    events: list[AgentEvent] = []
    budget = AttemptBudget(4, events)
    adapter = ToolAdapter(
        "https://commerce.test",
        ForbiddenObo(),
        StubKnowledge(),
        StubReranker(),
        load_calibration(),
    )
    plan = ModelPlan(
        tier="standard",
        routes=(ProviderRoute("support-standard-primary", "primary"),),
        reranker_route=ProviderRoute("support-reranker-standard", "reranker"),
        attempt_limit=4,
    )

    result = adapter.execute(
        name="knowledge.search",
        serialized_arguments='{"query":"refund","rewrite":"return policy"}',
        direct_token="direct-token-must-not-be-forwarded",
        subject="user-must-not-be-forwarded",
        session_id="session-must-not-be-forwarded",
        budget=budget,
        events=events,
        plan=plan,
    )

    assert result.outcome == "ok"
    assert set(result.model_view) == {
        "indexVersion",
        "calibrationVersion",
        "outcome",
        "reason",
        "candidateCount",
        "topScore",
        "topMargin",
        "evidence",
    }
    assert budget.used == 3
    tool_names: set[str] = set()
    for schema in adapter.schemas():
        function = schema.get("function")
        assert isinstance(function, dict)
        name = function.get("name")
        assert isinstance(name, str)
        tool_names.add(name)
    assert tool_names == {
        "catalog.product.get",
        "knowledge.search",
    }

    denied = adapter.execute(
        name="knowledge.search",
        serialized_arguments='{"query":"refund","index":"private_orders"}',
        direct_token="direct",
        subject="user",
        session_id="session",
        budget=AttemptBudget(2, []),
        events=[],
    )
    assert denied.model_view == {
        "outcome": "deny_with_feedback",
        "reason": "invalid_arguments",
    }


def test_cache_hit_uses_server_message_and_still_runs_existing_sufficiency_path() -> None:
    class ForbiddenObo:
        def exchange(self, *args: object) -> str:
            raise AssertionError(args)

    class ForbiddenKnowledge:
        def search(self, request: KnowledgeSearchInput, charge: Any) -> KnowledgeSearchOutput:
            raise AssertionError((request, charge))

    class StubCache:
        def lookup(self, public_query: str) -> KnowledgeSearchOutput | None:
            assert public_query == "SERVER original question"
            return KnowledgeSearchOutput(
                index_version="knowledge_docs_v2",
                results=(
                    KnowledgeSearchResult(
                        source_id="faq-refund",
                        chunk_id="answer",
                        source_version=9,
                        doc_type="faq",
                        title="Refund policy",
                        excerpt="Public refund guidance.",
                        public_metadata=PublicKnowledgeMetadata(category="faq", language="und"),
                        rank=1,
                        rrf_score=1 / 61,
                    ),
                ),
            )

        def populate_mapping(self, public_query: str, source_id: str, source_version: int) -> bool:
            raise AssertionError((public_query, source_id, source_version))

    class StubReranker:
        def rerank(
            self,
            plan: ModelPlan,
            request: Any,
            budget: AttemptBudget,
            events: list[AgentEvent],
        ) -> RerankOutput:
            del plan, events
            assert request.query == "model-selected rewrite target"
            budget.charge("reranker_http", "reranker")
            return RerankOutput(scores=(RerankScore(candidate_id="faq-refund:answer", score=0.9),))

    events: list[AgentEvent] = []
    result = ToolAdapter(
        "https://commerce.test",
        ForbiddenObo(),
        ForbiddenKnowledge(),
        StubReranker(),
        load_calibration(),
        StubCache(),
    ).execute(
        name="knowledge.search",
        serialized_arguments='{"query":"model-selected rewrite target"}',
        direct_token="direct",
        subject="user",
        session_id="session",
        budget=AttemptBudget(2, events),
        events=events,
        plan=ModelPlan(
            tier="standard",
            routes=(ProviderRoute("support-standard-primary", "primary"),),
            reranker_route=ProviderRoute("support-reranker-standard", "reranker"),
            attempt_limit=2,
        ),
        public_query="SERVER original question",
    )

    assert result.outcome == "ok"
    assert result.retrieval_decision is not None
    assert result.retrieval_decision.evidence[0].source_version == 9
    assert result.retrieval_decision.evidence[0].source_id == "faq-refund"


def test_cache_miss_populates_only_one_sufficient_faq_from_server_message() -> None:
    class ForbiddenObo:
        def exchange(self, *args: object) -> str:
            raise AssertionError(args)

    class StubCache:
        populated: tuple[str, str, int] | None = None

        def lookup(self, public_query: str) -> KnowledgeSearchOutput | None:
            assert public_query == "server message"
            return None

        def populate_mapping(self, public_query: str, source_id: str, source_version: int) -> bool:
            self.populated = (public_query, source_id, source_version)
            return True

    class StubKnowledge:
        def search(self, request: KnowledgeSearchInput, charge: Any) -> KnowledgeSearchOutput:
            del request, charge
            return KnowledgeSearchOutput(
                index_version="knowledge_docs_v1",
                results=(
                    KnowledgeSearchResult(
                        source_id="faq-refund",
                        chunk_id="answer",
                        source_version=4,
                        doc_type="faq",
                        title="Refund policy",
                        excerpt="Public refund guidance.",
                        public_metadata=PublicKnowledgeMetadata(category="faq", language="und"),
                        rank=1,
                        rrf_score=0.03,
                    ),
                ),
            )

    class StubReranker:
        def rerank(self, *args: object) -> RerankOutput:
            return RerankOutput(scores=(RerankScore(candidate_id="faq-refund:answer", score=0.9),))

    cache = StubCache()
    result = ToolAdapter(
        "https://commerce.test",
        ForbiddenObo(),
        StubKnowledge(),
        StubReranker(),
        load_calibration(),
        cache,
    ).execute(
        name="knowledge.search",
        serialized_arguments='{"query":"model query"}',
        direct_token="direct",
        subject="user",
        session_id="session",
        budget=AttemptBudget(3, []),
        events=[],
        plan=ModelPlan(
            tier="standard",
            routes=(ProviderRoute("support-standard-primary", "primary"),),
            reranker_route=ProviderRoute("support-reranker-standard", "reranker"),
            attempt_limit=3,
        ),
        public_query="server message",
    )

    assert result.outcome == "ok"
    assert cache.populated == ("server message", "faq-refund", 4)


@pytest.mark.parametrize(
    ("doc_types", "scores"),
    [
        (("faq",), (0.4,)),
        (("product",), (0.9,)),
        (("faq", "faq"), (0.9, 0.5)),
    ],
)
def test_insufficient_product_or_multi_source_result_never_populates_mapping(
    doc_types: tuple[str, ...], scores: tuple[float, ...]
) -> None:
    class ForbiddenObo:
        def exchange(self, *args: object) -> str:
            raise AssertionError(args)

    class SpyCache:
        populated: tuple[str, str, int] | None = None

        def lookup(self, public_query: str) -> KnowledgeSearchOutput | None:
            assert public_query == "server message"
            return None

        def populate_mapping(self, public_query: str, source_id: str, source_version: int) -> bool:
            self.populated = (public_query, source_id, source_version)
            return True

    class StubKnowledge:
        def search(self, request: KnowledgeSearchInput, charge: Any) -> KnowledgeSearchOutput:
            del request, charge
            return KnowledgeSearchOutput(
                index_version="knowledge_docs_v1",
                results=tuple(
                    KnowledgeSearchResult(
                        source_id=f"source-{index}",
                        chunk_id="answer" if doc_type == "faq" else "description",
                        source_version=1,
                        doc_type=doc_type,  # type: ignore[arg-type]
                        title=f"Public source {index}",
                        excerpt=f"Public content {index}",
                        public_metadata=PublicKnowledgeMetadata(
                            category="faq" if doc_type == "faq" else "product",
                            language="und",
                            product_id=None if doc_type == "faq" else f"source-{index}",
                        ),
                        rank=index,
                        rrf_score=1 / (60 + index),
                    )
                    for index, doc_type in enumerate(doc_types, start=1)
                ),
            )

    class StubReranker:
        def rerank(self, *args: object) -> RerankOutput:
            del args
            return RerankOutput(
                scores=tuple(
                    RerankScore(
                        candidate_id=(
                            f"source-{index}:{'answer' if doc_type == 'faq' else 'description'}"
                        ),
                        score=score,
                    )
                    for index, (doc_type, score) in enumerate(
                        zip(doc_types, scores, strict=True), start=1
                    )
                )
            )

    cache = SpyCache()
    ToolAdapter(
        "https://commerce.test",
        ForbiddenObo(),
        StubKnowledge(),
        StubReranker(),
        load_calibration(),
        cache,
    ).execute(
        name="knowledge.search",
        serialized_arguments='{"query":"model query"}',
        direct_token="direct",
        subject="user",
        session_id="session",
        budget=AttemptBudget(3, []),
        events=[],
        plan=ModelPlan(
            tier="standard",
            routes=(ProviderRoute("support-standard-primary", "primary"),),
            reranker_route=ProviderRoute("support-reranker-standard", "reranker"),
            attempt_limit=3,
        ),
        public_query="server message",
    )

    assert cache.populated is None


def test_unavailable_initial_retrieval_is_terminal_without_model_regeneration(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class ForbiddenObo:
        def exchange(self, *args: object) -> str:
            raise AssertionError(args)

    class UnavailableKnowledge:
        def search(self, request: KnowledgeSearchInput, charge: Any) -> KnowledgeSearchOutput:
            del request, charge
            raise KnowledgeSearchFailure("knowledge_unavailable")

    client = LiteLlmClient(
        "https://proxy.test",
        ProviderCircuits(minimum_requests=2, open_seconds=1, half_open_probes=1),
    )
    calls = 0

    def complete(*args: object, **kwargs: object) -> ModelReply:
        nonlocal calls
        del args, kwargs
        calls += 1
        if calls > 1:
            raise AssertionError("unavailable retrieval reached model regeneration")
        return ModelReply(None, "knowledge.search", '{"query":"public question"}')

    monkeypatch.setattr(client, "complete", complete)
    agent = BoundedAgent(
        RuleRouter(),
        ModelRouter((ProviderRoute("support-standard-primary", "primary"),), 8),
        client,
        ToolAdapter(
            "https://commerce.test",
            ForbiddenObo(),
            UnavailableKnowledge(),
            client,
            load_calibration(),
        ),
    )

    result = agent.run(
        message="public question",
        direct_token="not-forwarded",
        subject="not-forwarded",
        session_id="not-forwarded",
        trace_id="trace",
        turn_id="turn",
    )

    assert calls == 1
    assert result.outcome == "retrieval_denied"
    assert result.retrieval_decision is None
    assert any(
        event.event_type == "TOOL_DENIED" and event.payload.get("reason") == "knowledge_unavailable"
        for event in result.events
    )


def test_separate_recall_and_rrf_are_bounded_deduplicated_and_repeatable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    refund = source("faq-refund", "answer")
    product = source("product-tea", "description")
    delivery = source("faq-delivery", "answer")
    search_payloads = [
        hits(("faq-refund:answer", refund)),
        hits(("product-tea:description", product), ("faq-refund:answer", refund)),
        hits(("faq-delivery:answer", delivery)),
        hits(("faq-refund:answer", refund), ("faq-delivery:answer", delivery)),
    ]
    requests: list[dict[str, Any] | None] = []
    search_urls: list[str] = []

    def request(method: str, url: str, **kwargs: Any) -> httpx.Response:
        del method
        if "/_alias/" in url:
            return response(200, alias())
        if url.endswith("/_mapping"):
            return response(200, mapping())
        search_urls.append(url)
        requests.append(kwargs.get("json"))
        return response(200, search_payloads[len(requests) - 1])

    monkeypatch.setattr(httpx, "request", request)
    charged: list[tuple[str, str]] = []

    def charge(kind: str, target: str) -> None:
        charged.append((kind, target))

    client = ElasticsearchKnowledgeSearch("http://elasticsearch.test")

    first = client.search(
        KnowledgeSearchInput(query="退款 policy", rewrite="delivery guide"), charge
    )
    first_payloads = deepcopy(requests)
    requests.clear()
    search_payloads[:] = [
        hits(("faq-refund:answer", refund)),
        hits(("product-tea:description", product), ("faq-refund:answer", refund)),
        hits(("faq-delivery:answer", delivery)),
        hits(("faq-refund:answer", refund), ("faq-delivery:answer", delivery)),
    ]
    second = client.search(
        KnowledgeSearchInput(query="退款 policy", rewrite="delivery guide"), charge
    )

    assert first == second
    assert first.index_version == "knowledge_docs_v1"
    assert len(first.results) == 3
    assert first.results[0].source_id == "faq-refund"
    assert len({(item.source_id, item.chunk_id) for item in first.results}) == 3
    assert [item.rank for item in first.results] == [1, 2, 3]
    assert first.results[0].rrf_score == round(
        (2 / (RRF_CONSTANT + 1)) + (1 / (RRF_CONSTANT + 2)), 8
    )
    assert len(first_payloads) == 4
    assert all(url.endswith("/knowledge_docs_read/_search") for url in search_urls)
    assert all(
        payload is not None and payload["size"] == RECALL_LIMIT for payload in first_payloads
    )
    assert "query" in first_payloads[0]  # type: ignore[operator]
    assert "knn" in first_payloads[1]  # type: ignore[operator]
    assert "query" in first_payloads[2]  # type: ignore[operator]
    assert "knn" in first_payloads[3]  # type: ignore[operator]
    assert all(
        payload is not None and payload["_source"] and "embedding" not in payload["_source"]
        for payload in first_payloads
    )
    assert len(first.results) <= FINAL_RESULT_LIMIT
    assert len(charged) == 12


def test_rrf_equal_scores_use_stable_source_identity_tie_break(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    alpha = source("faq-alpha", "answer")
    beta = source("faq-beta", "answer")
    search_payloads = iter(
        [
            hits(("faq-beta:answer", beta)),
            hits(("faq-alpha:answer", alpha)),
        ]
    )

    def request(method: str, url: str, **kwargs: Any) -> httpx.Response:
        del method, kwargs
        if "/_alias/" in url:
            return response(200, alias())
        if url.endswith("/_mapping"):
            return response(200, mapping())
        return response(200, next(search_payloads))

    monkeypatch.setattr(httpx, "request", request)
    result = ElasticsearchKnowledgeSearch("http://elasticsearch.test").search(
        KnowledgeSearchInput(query="equal score"), lambda *args: None
    )

    assert [item.source_id for item in result.results] == ["faq-alpha", "faq-beta"]


@pytest.mark.parametrize(
    ("alias_payload", "expected"),
    [
        ({}, "alias_ambiguous"),
        (
            {
                "knowledge_docs_v1": {"aliases": {"knowledge_docs_read": {}}},
                "knowledge_docs_v2": {"aliases": {"knowledge_docs_read": {}}},
            },
            "alias_ambiguous",
        ),
        ({"private_orders_v1": {"aliases": {"knowledge_docs_read": {}}}}, "alias_ambiguous"),
    ],
)
def test_alias_resolution_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
    alias_payload: dict[str, object],
    expected: str,
) -> None:
    monkeypatch.setattr(
        httpx,
        "request",
        lambda *args, **kwargs: response(200, alias_payload),
    )

    with pytest.raises(KnowledgeSearchFailure, match=expected):
        ElasticsearchKnowledgeSearch("http://elasticsearch.test").search(
            KnowledgeSearchInput(query="refund"), lambda *args: None
        )


def test_mapping_timeout_partial_failure_and_malformed_candidate_are_bounded(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls = 0

    def partial(method: str, url: str, **kwargs: Any) -> httpx.Response:
        nonlocal calls
        del method, kwargs
        calls += 1
        if "/_alias/" in url:
            return response(200, alias())
        if url.endswith("/_mapping"):
            return response(200, mapping())
        if calls == 3:
            return response(200, hits(("faq-refund:answer", source("faq-refund", "answer"))))
        return response(503, {"error": "private backend detail"})

    monkeypatch.setattr(httpx, "request", partial)
    with pytest.raises(KnowledgeSearchFailure, match="partial_recall_failed"):
        ElasticsearchKnowledgeSearch("http://elasticsearch.test").search(
            KnowledgeSearchInput(query="refund"), lambda *args: None
        )

    monkeypatch.setattr(
        httpx,
        "request",
        lambda *args, **kwargs: (_ for _ in ()).throw(httpx.ReadTimeout("private timeout")),
    )
    with pytest.raises(KnowledgeSearchFailure, match="knowledge_unavailable"):
        ElasticsearchKnowledgeSearch("http://elasticsearch.test").search(
            KnowledgeSearchInput(query="refund"), lambda *args: None
        )

    malformed = source("faq-refund", "answer")
    malformed["user_subject"] = "private-user"
    sequence = iter(
        [
            response(200, alias()),
            response(200, mapping()),
            response(200, hits(("faq-refund:answer", malformed))),
        ]
    )
    monkeypatch.setattr(httpx, "request", lambda *args, **kwargs: next(sequence))
    with pytest.raises(KnowledgeSearchFailure, match="partial_recall_failed"):
        ElasticsearchKnowledgeSearch("http://elasticsearch.test").search(
            KnowledgeSearchInput(query="refund"), lambda *args: None
        )


@pytest.mark.parametrize(
    "mutation",
    [
        lambda payload: payload.update({"timed_out": True}),
        lambda payload: payload.update(
            {"_shards": {"total": 1, "successful": 0, "skipped": 0, "failed": 1}}
        ),
        lambda payload: payload.pop("_shards"),
        lambda payload: payload.update(
            {
                "_shards": {
                    "total": 1,
                    "successful": 1,
                    "skipped": 0,
                    "failed": "0",
                }
            }
        ),
    ],
)
def test_http_200_incomplete_or_anomalous_search_response_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
    mutation: Any,
) -> None:
    incomplete = hits(("faq-refund:answer", source("faq-refund", "answer")))
    mutation(incomplete)
    sequence = iter(
        [
            response(200, alias()),
            response(200, mapping()),
            response(200, incomplete),
        ]
    )
    monkeypatch.setattr(httpx, "request", lambda *args, **kwargs: next(sequence))

    with pytest.raises(KnowledgeSearchFailure, match="partial_recall_failed"):
        ElasticsearchKnowledgeSearch("http://elasticsearch.test").search(
            KnowledgeSearchInput(query="refund"), lambda *args: None
        )


@pytest.mark.parametrize(
    "mutation",
    [
        lambda properties: properties.update({"user_subject": {"type": "keyword"}}),
        lambda properties: properties["embedding"].update({"dims": 3}),
        lambda properties: properties["content"].update({"analyzer": "standard"}),
        lambda properties: properties["public_metadata"]["properties"]["language"].update(
            {"type": "text"}
        ),
    ],
)
def test_search_rejects_incompatible_or_private_mapping(
    monkeypatch: pytest.MonkeyPatch,
    mutation: Any,
) -> None:
    incompatible = mapping()
    index_payload = incompatible["knowledge_docs_v1"]
    assert isinstance(index_payload, dict)
    mappings = index_payload["mappings"]
    assert isinstance(mappings, dict)
    properties = mappings["properties"]
    assert isinstance(properties, dict)
    mutation(properties)
    sequence = iter([response(200, alias()), response(200, incompatible)])
    monkeypatch.setattr(httpx, "request", lambda *args, **kwargs: next(sequence))

    with pytest.raises(KnowledgeSearchFailure, match="mapping_incompatible"):
        ElasticsearchKnowledgeSearch("http://elasticsearch.test").search(
            KnowledgeSearchInput(query="refund"), lambda *args: None
        )


def test_deterministic_embedding_has_fixed_shape_and_semantic_axes() -> None:
    refund = deterministic_query_embedding("退款 refund")
    tea = deterministic_query_embedding("茉莉 tea")

    assert len(refund) == EMBEDDING_DIMS
    assert len(tea) == EMBEDDING_DIMS
    assert refund == deterministic_query_embedding("退款 refund")
    assert refund[0] > refund[1]
    assert tea[1] > tea[0]
