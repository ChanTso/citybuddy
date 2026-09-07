from __future__ import annotations

import json

import pytest
from citybuddy_agent.knowledge import (
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


@pytest.mark.parametrize("score", [True, False, "0.9"])
def test_reranker_json_score_type_confusion_is_rejected(score: object) -> None:
    raw_output = json.dumps(
        {
            "scores": [
                {"candidate_id": "source-a:chunk", "score": score},
                {"candidate_id": "source-b:chunk", "score": 0.2},
            ]
        }
    )

    with pytest.raises(ValidationError):
        RerankOutput.model_validate_json(raw_output)


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
