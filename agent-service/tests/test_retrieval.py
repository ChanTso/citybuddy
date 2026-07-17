from __future__ import annotations

import json
from typing import Any

import httpx
import pytest
from citybuddy_agent.agent_control import (
    AgentEvent,
    AttemptBudget,
    LiteLlmClient,
    ModelPlan,
    ProviderCircuits,
    ProviderFailure,
    ProviderRoute,
    ToolAdapter,
)
from citybuddy_agent.knowledge import (
    KnowledgeSearchInput,
    KnowledgeSearchOutput,
    KnowledgeSearchResult,
    PublicKnowledgeMetadata,
)
from citybuddy_agent.retrieval import (
    RerankCandidate,
    RerankOutput,
    RerankRequest,
    RerankScore,
    RerankValidationError,
    calibration_classification,
    decide_retrieval,
    load_calibration,
)
from pydantic import ValidationError


def result(
    source_id: str,
    *,
    rank: int,
    chunk_id: str = "chunk",
    source_version: int = 1,
) -> KnowledgeSearchResult:
    return KnowledgeSearchResult(
        source_id=source_id,
        chunk_id=chunk_id,
        source_version=source_version,
        doc_type="faq",
        title=f"Public {source_id}",
        excerpt=f"Allowlisted public text for {source_id}.",
        public_metadata=PublicKnowledgeMetadata(category="public", language="en"),
        rank=rank,
        rrf_score=round(0.05 - rank / 1000, 8),
    )


def search_output() -> KnowledgeSearchOutput:
    return KnowledgeSearchOutput(
        index_version="knowledge_docs_v7",
        results=(result("source-a", rank=1), result("source-b", rank=2)),
    )


def plan() -> ModelPlan:
    return ModelPlan(
        tier="standard",
        routes=(ProviderRoute("support-standard-primary", "primary"),),
        reranker_route=ProviderRoute("support-reranker-standard", "reranker"),
        attempt_limit=8,
    )


def test_calibration_artifact_is_uniquely_derived_from_public_synthetic_fixture() -> None:
    calibration = load_calibration()
    passing = [
        (threshold, margin)
        for threshold in calibration.threshold_candidates
        for margin in calibration.margin_candidates
        if all(
            calibration_classification(case.scores, threshold, margin) == case.expected
            for case in calibration.cases
        )
    ]

    assert passing == [(calibration.score_threshold, calibration.top_result_margin)]
    assert calibration.derivation_command == "uv run python scripts/check_retrieval_calibration.py"
    assert all("synthetic public" in case.query for case in calibration.cases)


def test_reranker_request_contains_only_bounded_public_candidate_view() -> None:
    request = RerankRequest(
        query="public question",
        rewrite="public rewrite",
        candidates=tuple(
            RerankCandidate.from_search_result(item) for item in search_output().results
        ),
    )

    payload = request.model_dump(by_alias=True, mode="json")
    assert set(payload) == {"query", "rewrite", "candidates"}
    assert payload["query"] == "public question"
    assert payload["rewrite"] == "public rewrite"
    candidates = payload["candidates"]
    assert isinstance(candidates, list)
    assert len(candidates) == 2
    assert set(candidates[0]) == {
        "candidateId",
        "sourceId",
        "chunkId",
        "sourceVersion",
        "docType",
        "title",
        "excerpt",
        "fusedRank",
        "rrfScore",
    }
    serialized = json.dumps(payload)
    for forbidden in (
        "embedding",
        "vector",
        "userSubject",
        "sessionId",
        "orderId",
        "priceMinor",
        "credential",
        "provider",
    ):
        assert forbidden not in serialized


@pytest.mark.parametrize(
    "scores",
    [
        (RerankScore(candidate_id="source-a:chunk", score=0.9),),
        (
            RerankScore(candidate_id="source-a:chunk", score=0.9),
            RerankScore(candidate_id="source-a:chunk", score=0.8),
        ),
        (
            RerankScore(candidate_id="source-a:chunk", score=0.9),
            RerankScore(candidate_id="unknown:chunk", score=0.8),
        ),
    ],
)
def test_rerank_output_must_exactly_cover_candidate_allowlist(
    scores: tuple[RerankScore, ...],
) -> None:
    with pytest.raises(RerankValidationError):
        decide_retrieval(search_output(), RerankOutput(scores=scores), load_calibration())


@pytest.mark.parametrize("score", [float("nan"), float("inf"), -0.1, 1.1])
def test_rerank_scores_reject_non_finite_or_out_of_bounds(score: float) -> None:
    with pytest.raises(ValidationError):
        RerankScore(candidate_id="source-a:chunk", score=score)


def test_normalization_is_deterministic_and_gate_is_fail_closed() -> None:
    calibration = load_calibration()
    sufficient = decide_retrieval(
        search_output(),
        RerankOutput(
            scores=(
                RerankScore(candidate_id="source-b:chunk", score=0.95 - 0.5),
                RerankScore(candidate_id="source-a:chunk", score=0.9),
            )
        ),
        calibration,
    )
    tied = decide_retrieval(
        search_output(),
        RerankOutput(
            scores=(
                RerankScore(candidate_id="source-b:chunk", score=0.9),
                RerankScore(candidate_id="source-a:chunk", score=0.9),
            )
        ),
        calibration,
    )
    below = decide_retrieval(
        search_output(),
        RerankOutput(
            scores=(
                RerankScore(candidate_id="source-a:chunk", score=0.74),
                RerankScore(candidate_id="source-b:chunk", score=0.2),
            )
        ),
        calibration,
    )

    assert sufficient.outcome == "SUFFICIENT"
    assert [item.source_id for item in sufficient.evidence] == ["source-a", "source-b"]
    assert sufficient.evidence[1].score == 0.45
    assert tied.outcome == "INSUFFICIENT"
    assert tied.reason == "ambiguous_margin"
    assert tied.evidence == ()
    assert below.outcome == "INSUFFICIENT"
    assert below.reason == "below_threshold"
    assert below.evidence == ()


def test_litellm_reranker_uses_fixed_alias_shared_budget_and_one_retry(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[dict[str, Any]] = []

    class Response:
        def __init__(self, status_code: int, payload: dict[str, object]) -> None:
            self.status_code = status_code
            self._payload = payload

        def json(self) -> dict[str, object]:
            return self._payload

    def post(url: str, **kwargs: Any) -> Response:
        assert url == "https://proxy.test/v1/chat/completions"
        payload = kwargs["json"]
        assert isinstance(payload, dict)
        calls.append(payload)
        if len(calls) == 1:
            return Response(503, {"error": "transient"})
        content = json.dumps(
            {
                "scores": [
                    {"candidate_id": "source-a:chunk", "score": 0.9},
                    {"candidate_id": "source-b:chunk", "score": 0.6},
                ]
            }
        )
        return Response(200, {"choices": [{"message": {"content": content}}]})

    monkeypatch.setattr(httpx, "post", post)
    events: list[AgentEvent] = []
    budget = AttemptBudget(3, events)
    client = LiteLlmClient(
        "https://proxy.test",
        ProviderCircuits(minimum_requests=2, open_seconds=1, half_open_probes=1),
    )
    request = RerankRequest(
        query="public question",
        candidates=tuple(
            RerankCandidate.from_search_result(item) for item in search_output().results
        ),
    )

    output = client.rerank(plan(), request, budget, events)

    assert len(output.scores) == 2
    assert budget.used == 2
    assert [call["model"] for call in calls] == [
        "support-reranker-standard",
        "support-reranker-standard",
    ]
    assert all(set(call) == {"model", "messages"} for call in calls)
    assert any(event.payload.get("result") == "rerank-transient" for event in events)


def test_reranker_failure_becomes_structured_insufficient_decision() -> None:
    class ForbiddenObo:
        def exchange(self, *args: object) -> str:
            raise AssertionError(args)

    class Knowledge:
        def search(self, request: KnowledgeSearchInput, charge: Any) -> KnowledgeSearchOutput:
            del request
            charge("knowledge_http", "bounded-fixture")
            return search_output()

    class FailedReranker:
        def rerank(self, *args: object) -> RerankOutput:
            raise ProviderFailure(transient=True)

    events: list[AgentEvent] = []
    adapter = ToolAdapter(
        "https://commerce.test",
        ForbiddenObo(),
        Knowledge(),
        FailedReranker(),
        load_calibration(),
    )

    denied = adapter.execute(
        name="knowledge.search",
        serialized_arguments='{"query":"public question"}',
        direct_token="not-forwarded",
        subject="not-forwarded",
        session_id="not-forwarded",
        budget=AttemptBudget(4, events),
        events=events,
        plan=plan(),
    )

    assert denied.outcome == "deny_with_feedback"
    assert denied.model_view == {
        "outcome": "deny_with_feedback",
        "reason": "reranker_denied",
    }
    assert denied.retrieval_decision is not None
    assert denied.retrieval_decision.outcome == "INSUFFICIENT"
    assert denied.retrieval_decision.evidence == ()
