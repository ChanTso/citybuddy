from __future__ import annotations

from copy import deepcopy
from typing import Any

import httpx
import pytest
from citybuddy_agent import http_client
from citybuddy_agent.knowledge import (
    EMBEDDING_DIMS,
    FINAL_RESULT_LIMIT,
    RECALL_LIMIT,
    RRF_CONSTANT,
    ElasticsearchKnowledgeSearch,
    KnowledgeSearchFailure,
    KnowledgeSearchInput,
    deterministic_query_embedding,
)


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

    monkeypatch.setattr(http_client, "request", request)
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

    monkeypatch.setattr(http_client, "request", request)
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
        http_client,
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

    monkeypatch.setattr(http_client, "request", partial)
    with pytest.raises(KnowledgeSearchFailure, match="partial_recall_failed"):
        ElasticsearchKnowledgeSearch("http://elasticsearch.test").search(
            KnowledgeSearchInput(query="refund"), lambda *args: None
        )

    monkeypatch.setattr(
        http_client,
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
    monkeypatch.setattr(http_client, "request", lambda *args, **kwargs: next(sequence))
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
    monkeypatch.setattr(http_client, "request", lambda *args, **kwargs: next(sequence))

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
    monkeypatch.setattr(http_client, "request", lambda *args, **kwargs: next(sequence))

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
