"""Real CB-090 Elasticsearch, ToolSpec, privacy, and failure-boundary probe."""

from __future__ import annotations

import argparse
import json
from collections.abc import Callable
from copy import deepcopy
from typing import Any, cast
from urllib.parse import quote

import httpx
from citybuddy_agent.agent_control import AgentEvent, AttemptBudget, ToolAdapter
from citybuddy_agent.knowledge import (
    ElasticsearchKnowledgeSearch,
    KnowledgeSearchFailure,
    KnowledgeSearchInput,
)

INDEX = "knowledge_docs_v1"
ALIAS = "knowledge_docs_read"


class ForbiddenObo:
    def __init__(self) -> None:
        self.calls = 0

    def exchange(self, direct_token: str, subject: str, session_id: str, scope: str) -> str:
        del direct_token, subject, session_id, scope
        self.calls += 1
        raise AssertionError("knowledge.search must not acquire OBO authority")


def api(
    base_url: str,
    method: str,
    path: str,
    body: dict[str, object] | None = None,
) -> httpx.Response:
    return httpx.request(method, f"{base_url}{path}", json=body, timeout=5.0)


def object_payload(response: httpx.Response) -> dict[str, Any]:
    decoded = response.json()
    if not isinstance(decoded, dict):
        raise AssertionError("Elasticsearch probe response was not an object")
    return cast(dict[str, Any], decoded)


def require_status(response: httpx.Response, *expected: int) -> None:
    if response.status_code not in expected:
        raise AssertionError(f"Unexpected Elasticsearch status: {response.status_code}")


def alias_action(base_url: str, *actions: dict[str, object]) -> None:
    response = api(base_url, "POST", "/_aliases", {"actions": list(actions)})
    require_status(response, 200)


def expect_failure(code: str, operation: Callable[[], object]) -> None:
    try:
        operation()
    except KnowledgeSearchFailure as error:
        if error.code != code:
            raise AssertionError(f"Expected {code}, got {error.code}") from error
    else:
        raise AssertionError(f"Expected knowledge failure: {code}")


def search(
    client: ElasticsearchKnowledgeSearch,
    query: str,
    rewrite: str | None = None,
) -> tuple[dict[str, object], list[tuple[str, str]]]:
    charges: list[tuple[str, str]] = []

    def charge(kind: str, target: str) -> None:
        charges.append((kind, target))

    output = client.search(KnowledgeSearchInput(query=query, rewrite=rewrite), charge)
    return output.model_dump(by_alias=True, mode="json"), charges


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--elasticsearch-url", required=True)
    args = parser.parse_args()
    base_url = str(args.elasticsearch_url).rstrip("/")
    client = ElasticsearchKnowledgeSearch(base_url)

    alias_payload = object_payload(api(base_url, "GET", f"/_alias/{ALIAS}"))
    if set(alias_payload) != {INDEX}:
        raise AssertionError("Stable alias did not resolve to exactly the approved index")
    alias_index = alias_payload.get(INDEX)
    aliases = alias_index.get("aliases") if isinstance(alias_index, dict) else None
    if not isinstance(aliases, dict) or set(aliases) != {ALIAS}:
        raise AssertionError("Stable alias response did not contain the exact approved alias")
    mapping_payload = object_payload(api(base_url, "GET", f"/{INDEX}/_mapping"))
    index_payload = mapping_payload.get(INDEX)
    if not isinstance(index_payload, dict):
        raise AssertionError("Approved index mapping was missing")
    mappings = index_payload.get("mappings")
    if not isinstance(mappings, dict):
        raise AssertionError("Approved index mapping body was missing")

    mixed, mixed_charges = search(client, "CityBuddy 退款 refund policy")
    mixed_results = mixed.get("results")
    if (
        not isinstance(mixed_results, list)
        or not mixed_results
        or not isinstance(mixed_results[0], dict)
        or mixed_results[0].get("sourceId") != "faq-refund-policy"
    ):
        raise AssertionError("Mixed-language BM25/dense recall missed the public refund FAQ")
    tea, _ = search(client, "茉莉 tea product")
    tea_results = tea.get("results")
    if (
        not isinstance(tea_results, list)
        or not tea_results
        or not isinstance(tea_results[0], dict)
        or tea_results[0].get("sourceId") != "product-jasmine-tea"
    ):
        raise AssertionError("Real dense recall missed the expected public product")
    combined, combined_charges = search(client, "delivery guide", "refund policy")
    repeated, _ = search(client, "delivery guide", "refund policy")
    if combined != repeated:
        raise AssertionError("Repeated hybrid retrieval did not preserve deterministic order")
    combined_results = combined.get("results")
    if not isinstance(combined_results, list):
        raise AssertionError("Combined retrieval omitted results")
    combined_ids = {item.get("sourceId") for item in combined_results if isinstance(item, dict)}
    if not {"faq-delivery", "faq-refund-policy"}.issubset(combined_ids):
        raise AssertionError("Original and rewrite inputs did not both contribute candidates")
    if len(combined_ids) != len(combined_results):
        raise AssertionError("RRF emitted a duplicate stable source identity")
    if len(mixed_charges) != 4 or len(combined_charges) != 6:
        raise AssertionError("Knowledge HTTP work was not bounded per recall input")
    for result in combined_results:
        if not isinstance(result, dict) or set(result) != {
            "sourceId",
            "chunkId",
            "sourceVersion",
            "docType",
            "title",
            "excerpt",
            "publicMetadata",
            "rank",
            "rrfScore",
        }:
            raise AssertionError("Knowledge result escaped the public field allowlist")
        metadata = result.get("publicMetadata")
        if not isinstance(metadata, dict) or not set(metadata).issubset(
            {"productId", "category", "language"}
        ):
            raise AssertionError("Knowledge metadata escaped its public allowlist")
        forbidden = {
            "embedding",
            "priceMinor",
            "stockQuantity",
            "userSubject",
            "orderId",
            "refundId",
            "sandboxId",
        }
        if forbidden.intersection(result) or forbidden.intersection(metadata):
            raise AssertionError("Private or live-commerce fields escaped retrieval")

    obo = ForbiddenObo()
    tool_events: list[AgentEvent] = []
    tool_budget = AttemptBudget(8, tool_events)
    tool_result = ToolAdapter("http://commerce-must-not-be-used", obo, client).execute(
        name="knowledge.search",
        serialized_arguments='{"query":"退款 policy"}',
        direct_token="direct-token",
        subject="user-subject",
        session_id="support-session",
        budget=tool_budget,
        events=tool_events,
    )
    if tool_result.outcome != "ok" or obo.calls != 0 or tool_budget.used != 4:
        raise AssertionError("knowledge.search crossed the OBO or bounded-I/O boundary")
    denied_budget = AttemptBudget(2, [])
    denied = ToolAdapter("http://commerce-must-not-be-used", obo, client).execute(
        name="knowledge.search",
        serialized_arguments='{"query":"refund","index":"private_orders"}',
        direct_token="direct-token",
        subject="user-subject",
        session_id="support-session",
        budget=denied_budget,
        events=[],
    )
    if denied.model_view.get("reason") != "invalid_arguments" or denied_budget.used != 0:
        raise AssertionError("Caller-selected index authority reached Elasticsearch")

    existing = object_payload(
        api(base_url, "GET", f"/{INDEX}/_doc/{quote('faq-refund-policy:overview', safe='')}")
    )
    existing_source = existing.get("_source")
    if not isinstance(existing_source, dict):
        raise AssertionError("Expected public fixture source was missing")
    private_source = deepcopy(existing_source)
    private_source["user_subject"] = "private-user"
    private_response = api(
        base_url,
        "PUT",
        f"/{INDEX}/_doc/{quote('private-record:chunk', safe='')}",
        cast(dict[str, object], private_source),
    )
    require_status(private_response, 400)

    for document_id, published, deleted in (
        ("unpublished-record:chunk", False, False),
        ("deleted-record:chunk", True, True),
    ):
        rejected_source = deepcopy(existing_source)
        source_id, chunk_id = document_id.split(":", 1)
        rejected_source.update(
            {
                "source_id": source_id,
                "chunk_id": chunk_id,
                "published": published,
                "deleted": deleted,
                "title": source_id,
                "content": f"{source_id} uniqueboundarytoken",
            }
        )
        require_status(
            api(
                base_url,
                "PUT",
                f"/{INDEX}/_doc/{quote(document_id, safe='')}",
                cast(dict[str, object], rejected_source),
            ),
            200,
            201,
        )
    require_status(api(base_url, "POST", f"/{INDEX}/_refresh"), 200)
    filtered, _ = search(client, "uniqueboundarytoken")
    filtered_results = filtered.get("results")
    if not isinstance(filtered_results, list):
        raise AssertionError("Filtered search omitted its bounded result list")
    filtered_ids = {item.get("sourceId") for item in filtered_results if isinstance(item, dict)}
    if filtered_ids.intersection({"unpublished-record", "deleted-record"}):
        raise AssertionError("Deleted or unpublished knowledge escaped recall filters")

    malformed = deepcopy(existing_source)
    malformed.pop("source_version", None)
    malformed.update(
        {
            "source_id": "malformed-record",
            "chunk_id": "chunk",
            "title": "malformed-boundary-token",
            "content": "malformed-boundary-token",
        }
    )
    require_status(
        api(
            base_url,
            "PUT",
            f"/{INDEX}/_doc/{quote('malformed-record:chunk', safe='')}",
            cast(dict[str, object], malformed),
        ),
        200,
        201,
    )
    require_status(api(base_url, "POST", f"/{INDEX}/_refresh"), 200)
    expect_failure(
        "partial_recall_failed",
        lambda: search(client, "malformed-boundary-token"),
    )

    alias_action(base_url, {"remove": {"index": INDEX, "alias": ALIAS}})
    expect_failure("alias_missing", lambda: search(client, "refund"))
    alias_action(base_url, {"add": {"index": INDEX, "alias": ALIAS}})

    require_status(api(base_url, "PUT", "/knowledge_docs_v2", {"mappings": mappings}), 200)
    alias_action(base_url, {"add": {"index": "knowledge_docs_v2", "alias": ALIAS}})
    expect_failure("alias_ambiguous", lambda: search(client, "refund"))
    alias_action(base_url, {"remove": {"index": "knowledge_docs_v2", "alias": ALIAS}})
    require_status(api(base_url, "DELETE", "/knowledge_docs_v2"), 200)

    incompatible = deepcopy(mappings)
    incompatible_properties = incompatible.get("properties")
    if not isinstance(incompatible_properties, dict):
        raise AssertionError("Fixture mapping properties were missing")
    incompatible_embedding = incompatible_properties.get("embedding")
    if not isinstance(incompatible_embedding, dict):
        raise AssertionError("Fixture embedding mapping was missing")
    incompatible_embedding["dims"] = 3
    require_status(api(base_url, "PUT", "/knowledge_docs_v3", {"mappings": incompatible}), 200)
    alias_action(
        base_url,
        {"remove": {"index": INDEX, "alias": ALIAS}},
        {"add": {"index": "knowledge_docs_v3", "alias": ALIAS}},
    )
    expect_failure("mapping_incompatible", lambda: search(client, "refund"))
    alias_action(
        base_url,
        {"remove": {"index": "knowledge_docs_v3", "alias": ALIAS}},
        {"add": {"index": INDEX, "alias": ALIAS}},
    )
    require_status(api(base_url, "DELETE", "/knowledge_docs_v3"), 200)

    expect_failure(
        "knowledge_unavailable",
        lambda: search(
            ElasticsearchKnowledgeSearch("http://127.0.0.1:9", timeout_seconds=0.1),
            "refund",
        ),
    )

    require_status(
        api(
            base_url,
            "DELETE",
            f"/{INDEX}/_doc/{quote('unpublished-record:chunk', safe='')}",
        ),
        200,
    )
    require_status(
        api(
            base_url,
            "DELETE",
            f"/{INDEX}/_doc/{quote('deleted-record:chunk', safe='')}",
        ),
        200,
    )
    require_status(
        api(
            base_url,
            "DELETE",
            f"/{INDEX}/_doc/{quote('malformed-record:chunk', safe='')}",
        ),
        200,
    )
    print(
        json.dumps(
            {
                "alias": ALIAS,
                "denseRecall": "passed",
                "indexVersion": INDEX,
                "mixedLanguageBm25": "passed",
                "oboCalls": obo.calls,
                "rrfRepeatable": True,
            },
            separators=(",", ":"),
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
