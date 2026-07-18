"""Real CB-090 Elasticsearch, ToolSpec, privacy, and failure-boundary probe."""

from __future__ import annotations

import argparse
import json
from collections.abc import Callable
from copy import deepcopy
from typing import Any, cast
from urllib.parse import quote

import httpx
from citybuddy_agent.agent_control import (
    AgentEvent,
    AttemptBudget,
    ModelPlan,
    ProviderRoute,
    ToolAdapter,
)
from citybuddy_agent.knowledge import (
    EMBEDDING_DIMS,
    FINAL_RESULT_LIMIT,
    ElasticsearchKnowledgeSearch,
    KnowledgeSearchFailure,
    KnowledgeSearchInput,
)
from citybuddy_agent.retrieval import RerankOutput, RerankScore, load_calibration

INDEX = "knowledge_docs_v1"
ALIAS = "knowledge_docs_read"


class ForbiddenObo:
    def __init__(self) -> None:
        self.calls = 0

    def exchange(
        self,
        direct_token: str,
        subject: str,
        session_id: str,
        scope: str,
        sandbox_id: str | None = None,
    ) -> str:
        del direct_token, subject, session_id, scope, sandbox_id
        self.calls += 1
        raise AssertionError("knowledge.search must not acquire OBO authority")


class DeterministicReranker:
    def rerank(
        self,
        plan: ModelPlan,
        request: Any,
        budget: AttemptBudget,
        events: list[AgentEvent],
    ) -> RerankOutput:
        del events
        budget.charge("reranker_http", plan.reranker_route.provider_key)
        return RerankOutput(
            scores=tuple(
                RerankScore(
                    candidate_id=candidate.candidate_id,
                    score=round(0.95 - candidate.fused_rank * 0.2, 2),
                )
                for candidate in request.candidates
            )
        )


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


def recall_ids(
    client: ElasticsearchKnowledgeSearch,
    query: str,
    *,
    dense: bool,
) -> list[str]:
    charges: list[tuple[str, str]] = []
    recall = client._dense if dense else client._bm25
    candidates = recall(INDEX, query, lambda kind, target: charges.append((kind, target)))
    expected_target = "dense_recall" if dense else "bm25_recall"
    if charges != [("knowledge_http", expected_target)]:
        raise AssertionError("A recall leg exceeded its single bounded Elasticsearch request")
    return [candidate.identity for candidate in candidates]


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

    mixed_bm25 = recall_ids(client, "CityBuddy 退款 refund policy", dense=False)
    if not mixed_bm25 or mixed_bm25[0] != "faq-refund-policy:overview":
        raise AssertionError("Real mixed-language BM25 missed the public refund FAQ")
    dense_only_lexical = recall_ids(client, "drink", dense=False)
    dense_only = recall_ids(client, "drink", dense=True)
    if dense_only_lexical or not dense_only or dense_only[0] != "product-jasmine-tea:description":
        raise AssertionError("Real kNN was not independently required for the public tea result")

    mixed, mixed_charges = search(client, "CityBuddy 退款 refund policy")
    mixed_results = mixed.get("results")
    if (
        not isinstance(mixed_results, list)
        or not mixed_results
        or not isinstance(mixed_results[0], dict)
        or mixed_results[0].get("sourceId") != "faq-refund-policy"
    ):
        raise AssertionError("Hybrid recall missed the public refund FAQ")
    original_bm25 = recall_ids(client, "delivery guide", dense=False)
    original_dense = recall_ids(client, "delivery guide", dense=True)
    rewrite_bm25 = recall_ids(client, "refund policy", dense=False)
    rewrite_dense = recall_ids(client, "refund policy", dense=True)
    if "faq-delivery:coverage" not in original_bm25:
        raise AssertionError("Original query did not independently contribute BM25 evidence")
    if "faq-refund-policy:overview" not in rewrite_bm25:
        raise AssertionError("Rewrite did not independently contribute BM25 evidence")
    if not original_dense or not rewrite_dense:
        raise AssertionError("Original and rewrite dense recall legs were not independently real")
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
    if [target for _, target in mixed_charges] != [
        "alias_resolution",
        "mapping_validation",
        "bm25_recall",
        "dense_recall",
    ] or [target for _, target in combined_charges] != [
        "alias_resolution",
        "mapping_validation",
        "bm25_recall",
        "dense_recall",
        "bm25_recall",
        "dense_recall",
    ]:
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
    tool_adapter = ToolAdapter(
        "http://commerce-must-not-be-used",
        obo,
        client,
        DeterministicReranker(),
        load_calibration(),
    )
    model_plan = ModelPlan(
        tier="standard",
        routes=(ProviderRoute("support-standard-primary", "primary"),),
        reranker_route=ProviderRoute("support-reranker-standard", "reranker"),
        attempt_limit=8,
    )
    tool_result = tool_adapter.execute(
        name="knowledge.search",
        serialized_arguments='{"query":"退款 policy"}',
        direct_token="direct-token",
        subject="user-subject",
        session_id="support-session",
        budget=tool_budget,
        events=tool_events,
        plan=model_plan,
    )
    if tool_result.outcome != "ok" or obo.calls != 0 or tool_budget.used != 5:
        raise AssertionError("knowledge.search crossed the OBO or bounded-I/O boundary")
    denied_budget = AttemptBudget(2, [])
    denied = tool_adapter.execute(
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

    boundary_ids: list[str] = []
    for group, dimension in (("boundalpha", 4), ("boundbeta", 5)):
        for sequence in range(3):
            source_id = f"fixture-{group}-{sequence}"
            document_id = f"{source_id}:chunk"
            boundary_source = deepcopy(existing_source)
            embedding = [0.0] * EMBEDDING_DIMS
            embedding[dimension] = 1.0
            boundary_source.update(
                {
                    "source_id": source_id,
                    "source_version": 1,
                    "chunk_id": "chunk",
                    "title": f"{group} {sequence}",
                    "content": group,
                    "embedding": embedding,
                    "public_metadata": {"category": "probe", "language": "en"},
                }
            )
            require_status(
                api(
                    base_url,
                    "PUT",
                    f"/{INDEX}/_doc/{quote(document_id, safe='')}",
                    cast(dict[str, object], boundary_source),
                ),
                200,
                201,
            )
            boundary_ids.append(document_id)
    require_status(api(base_url, "POST", f"/{INDEX}/_refresh"), 200)
    alpha_candidates = client._bm25(INDEX, "boundalpha", lambda *args: None)
    beta_candidates = client._bm25(INDEX, "boundbeta", lambda *args: None)
    alpha_ids = [candidate.identity for candidate in alpha_candidates]
    beta_ids = [candidate.identity for candidate in beta_candidates]
    if len(alpha_ids) != 3 or len(beta_ids) != 3 or set(alpha_ids).intersection(beta_ids):
        raise AssertionError("Real BM25 fixture lists did not create six distinct candidates")
    tied = client._fuse([alpha_candidates, beta_candidates])
    if [result.source_id for result in tied] != [
        "fixture-boundalpha-0",
        "fixture-boundbeta-0",
        "fixture-boundalpha-1",
        "fixture-boundbeta-1",
        "fixture-boundalpha-2",
    ]:
        raise AssertionError("Real equal-rank RRF did not use stable identity tie-breaking")
    deduplicated = client._fuse([alpha_candidates, alpha_candidates])
    if len(deduplicated) != 3 or len({result.source_id for result in deduplicated}) != 3:
        raise AssertionError("Real duplicate candidates were not fused by stable identity")
    bounded, _ = search(client, "boundalpha", "boundbeta")
    bounded_results = bounded.get("results")
    if not isinstance(bounded_results, list) or len(bounded_results) != FINAL_RESULT_LIMIT:
        raise AssertionError(
            "Real hybrid output did not truncate six candidates to its fixed bound"
        )

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
    for document_id in boundary_ids:
        require_status(
            api(
                base_url,
                "DELETE",
                f"/{INDEX}/_doc/{quote(document_id, safe='')}",
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
                "realBoundedResultCount": FINAL_RESULT_LIMIT,
                "realRrfTieOrder": "passed",
                "rrfRepeatable": True,
            },
            separators=(",", ":"),
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
