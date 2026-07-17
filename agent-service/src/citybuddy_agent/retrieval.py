"""Validated rerank output, calibrated sufficiency, and durable evidence values."""

from __future__ import annotations

import math
from importlib.resources import files
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator

from .knowledge import FINAL_RESULT_LIMIT, KnowledgeSearchOutput, KnowledgeSearchResult

CALIBRATION_RESOURCE = "calibration/cb091-v1.json"
RERANK_RESULT_LIMIT = 3


class CalibrationCase(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    case_id: str = Field(serialization_alias="caseId", min_length=1, max_length=64)
    query: str = Field(min_length=1, max_length=200)
    scores: tuple[float, ...] = Field(max_length=FINAL_RESULT_LIMIT)
    expected: Literal["SUFFICIENT", "INSUFFICIENT"]

    @model_validator(mode="after")
    def validate_scores(self) -> CalibrationCase:
        if any(not math.isfinite(score) or score < 0 or score > 1 for score in self.scores):
            raise ValueError("Calibration scores must be finite values in [0, 1]")
        return self


class SufficiencyCalibration(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    calibration_version: str = Field(
        serialization_alias="calibrationVersion",
        pattern=r"^cb091-calibration-v[1-9][0-9]*$",
    )
    fixture_version: str = Field(
        serialization_alias="fixtureVersion", pattern=r"^cb091-dev-v[1-9][0-9]*$"
    )
    score_threshold: float = Field(serialization_alias="scoreThreshold", ge=0, le=1)
    top_result_margin: float = Field(serialization_alias="topResultMargin", ge=0, le=1)
    max_evidence: int = Field(serialization_alias="maxEvidence", ge=1, le=RERANK_RESULT_LIMIT)
    threshold_candidates: tuple[float, ...] = Field(
        serialization_alias="thresholdCandidates", min_length=1
    )
    margin_candidates: tuple[float, ...] = Field(
        serialization_alias="marginCandidates", min_length=1
    )
    derivation_command: str = Field(
        serialization_alias="derivationCommand", min_length=1, max_length=200
    )
    cases: tuple[CalibrationCase, ...] = Field(min_length=1, max_length=32)

    @model_validator(mode="after")
    def validate_grid(self) -> SufficiencyCalibration:
        grids = (self.threshold_candidates, self.margin_candidates)
        if any(
            not math.isfinite(value) or value < 0 or value > 1 for grid in grids for value in grid
        ):
            raise ValueError("Calibration grid must contain finite values in [0, 1]")
        if self.score_threshold not in self.threshold_candidates:
            raise ValueError("Selected score threshold is outside the derivation grid")
        if self.top_result_margin not in self.margin_candidates:
            raise ValueError("Selected margin is outside the derivation grid")
        return self


class RerankCandidate(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    candidate_id: str = Field(serialization_alias="candidateId", min_length=1, max_length=300)
    source_id: str = Field(serialization_alias="sourceId", min_length=1, max_length=128)
    chunk_id: str = Field(serialization_alias="chunkId", min_length=1, max_length=128)
    source_version: int = Field(serialization_alias="sourceVersion", ge=1)
    doc_type: Literal["faq", "product"] = Field(serialization_alias="docType")
    title: str = Field(min_length=1, max_length=200)
    excerpt: str = Field(min_length=1, max_length=600)
    fused_rank: int = Field(serialization_alias="fusedRank", ge=1, le=FINAL_RESULT_LIMIT)
    rrf_score: float = Field(serialization_alias="rrfScore", gt=0, le=1)

    @classmethod
    def from_search_result(cls, result: KnowledgeSearchResult) -> RerankCandidate:
        return cls(
            candidate_id=f"{result.source_id}:{result.chunk_id}",
            source_id=result.source_id,
            chunk_id=result.chunk_id,
            source_version=result.source_version,
            doc_type=result.doc_type,
            title=result.title,
            excerpt=result.excerpt,
            fused_rank=result.rank,
            rrf_score=result.rrf_score,
        )


class RerankRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    query: str = Field(min_length=1, max_length=512)
    rewrite: str | None = Field(default=None, min_length=1, max_length=512)
    candidates: tuple[RerankCandidate, ...] = Field(min_length=1, max_length=FINAL_RESULT_LIMIT)

    @model_validator(mode="after")
    def validate_candidate_set(self) -> RerankRequest:
        identities = [candidate.candidate_id for candidate in self.candidates]
        ranks = [candidate.fused_rank for candidate in self.candidates]
        if len(set(identities)) != len(identities) or ranks != list(range(1, len(ranks) + 1)):
            raise ValueError("Rerank candidates must be unique and retain fused order")
        return self


class RerankScore(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    candidate_id: str = Field(serialization_alias="candidateId", min_length=1, max_length=300)
    score: float = Field(ge=0, le=1, allow_inf_nan=False)


class RerankOutput(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    scores: tuple[RerankScore, ...] = Field(min_length=1, max_length=FINAL_RESULT_LIMIT)


class RetrievalEvidence(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    source_id: str = Field(serialization_alias="sourceId", min_length=1, max_length=128)
    chunk_id: str = Field(serialization_alias="chunkId", min_length=1, max_length=128)
    source_version: int = Field(serialization_alias="sourceVersion", ge=1)
    doc_type: Literal["faq", "product"] = Field(serialization_alias="docType")
    title: str = Field(min_length=1, max_length=200)
    excerpt: str = Field(min_length=1, max_length=600)
    rank: int = Field(ge=1, le=RERANK_RESULT_LIMIT)
    score: float = Field(ge=0, le=1, allow_inf_nan=False)


class RetrievalDecision(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)

    index_version: str = Field(
        serialization_alias="indexVersion", pattern=r"^knowledge_docs_v[1-9][0-9]*$"
    )
    calibration_version: str = Field(
        serialization_alias="calibrationVersion",
        pattern=r"^cb091-calibration-v[1-9][0-9]*$",
    )
    outcome: Literal["SUFFICIENT", "INSUFFICIENT"]
    reason: Literal[
        "sufficient",
        "empty_candidates",
        "below_threshold",
        "ambiguous_margin",
        "reranker_denied",
    ]
    candidate_count: int = Field(serialization_alias="candidateCount", ge=0, le=FINAL_RESULT_LIMIT)
    top_score: float | None = Field(serialization_alias="topScore", default=None, ge=0, le=1)
    top_margin: float | None = Field(serialization_alias="topMargin", default=None, ge=0, le=1)
    evidence: tuple[RetrievalEvidence, ...] = Field(max_length=RERANK_RESULT_LIMIT)

    @model_validator(mode="after")
    def validate_outcome(self) -> RetrievalDecision:
        if self.outcome == "SUFFICIENT":
            if self.reason != "sufficient" or not self.evidence or self.top_score is None:
                raise ValueError("Sufficient decisions require bounded evidence")
        elif self.evidence:
            raise ValueError("Insufficient decisions cannot carry answer evidence")
        if [item.rank for item in self.evidence] != list(range(1, len(self.evidence) + 1)):
            raise ValueError("Retrieval evidence ranks must be contiguous")
        identities = [
            (item.source_id, item.source_version, item.chunk_id) for item in self.evidence
        ]
        if len(set(identities)) != len(identities):
            raise ValueError("Retrieval evidence identities must be unique")
        return self


class RerankValidationError(Exception):
    """Reranker output did not exactly cover the server-owned candidate allowlist."""


def load_calibration() -> SufficiencyCalibration:
    try:
        payload = files("citybuddy_agent").joinpath(CALIBRATION_RESOURCE).read_text("utf-8")
        return SufficiencyCalibration.model_validate_json(payload)
    except (OSError, ValidationError) as error:
        raise RuntimeError("Retrieval calibration artifact is invalid") from error


def calibration_classification(
    scores: tuple[float, ...], threshold: float, margin: float
) -> Literal["SUFFICIENT", "INSUFFICIENT"]:
    if not scores or scores[0] < threshold:
        return "INSUFFICIENT"
    if len(scores) > 1 and scores[0] - scores[1] < margin:
        return "INSUFFICIENT"
    return "SUFFICIENT"


def insufficient_decision(
    *,
    index_version: str,
    calibration: SufficiencyCalibration,
    reason: Literal["empty_candidates", "reranker_denied"],
    candidate_count: int,
) -> RetrievalDecision:
    return RetrievalDecision(
        index_version=index_version,
        calibration_version=calibration.calibration_version,
        outcome="INSUFFICIENT",
        reason=reason,
        candidate_count=candidate_count,
        evidence=(),
    )


def decide_retrieval(
    search: KnowledgeSearchOutput,
    output: RerankOutput,
    calibration: SufficiencyCalibration,
) -> RetrievalDecision:
    candidates = tuple(RerankCandidate.from_search_result(item) for item in search.results)
    by_identity = {candidate.candidate_id: candidate for candidate in candidates}
    # Canonicalize once at the durable boundary so the first response and a
    # DECIMAL(9, 8)-backed replay expose exactly the same retrieval truth.
    scores = {item.candidate_id: round(item.score, 8) for item in output.scores}
    if (
        len(scores) != len(output.scores)
        or set(scores) != set(by_identity)
        or len(output.scores) != len(candidates)
    ):
        raise RerankValidationError
    ordered = sorted(
        candidates,
        key=lambda candidate: (
            -scores[candidate.candidate_id],
            candidate.fused_rank,
            candidate.candidate_id,
        ),
    )
    top_score = scores[ordered[0].candidate_id]
    top_margin = round(top_score - scores[ordered[1].candidate_id], 8) if len(ordered) > 1 else None
    if top_score < calibration.score_threshold:
        return RetrievalDecision(
            index_version=search.index_version,
            calibration_version=calibration.calibration_version,
            outcome="INSUFFICIENT",
            reason="below_threshold",
            candidate_count=len(candidates),
            top_score=top_score,
            top_margin=top_margin,
            evidence=(),
        )
    if top_margin is not None and top_margin < calibration.top_result_margin:
        return RetrievalDecision(
            index_version=search.index_version,
            calibration_version=calibration.calibration_version,
            outcome="INSUFFICIENT",
            reason="ambiguous_margin",
            candidate_count=len(candidates),
            top_score=top_score,
            top_margin=top_margin,
            evidence=(),
        )
    evidence = tuple(
        RetrievalEvidence(
            source_id=candidate.source_id,
            chunk_id=candidate.chunk_id,
            source_version=candidate.source_version,
            doc_type=candidate.doc_type,
            title=candidate.title,
            excerpt=candidate.excerpt,
            rank=rank,
            score=scores[candidate.candidate_id],
        )
        for rank, candidate in enumerate(ordered[: calibration.max_evidence], start=1)
    )
    return RetrievalDecision(
        index_version=search.index_version,
        calibration_version=calibration.calibration_version,
        outcome="SUFFICIENT",
        reason="sufficient",
        candidate_count=len(candidates),
        top_score=top_score,
        top_margin=top_margin,
        evidence=evidence,
    )
