"""Bounded Elasticsearch recall and deterministic application-side RRF."""

from __future__ import annotations

import hashlib
import math
import re
import unicodedata
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Literal, Protocol, cast
from urllib.parse import quote

import httpx
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator

KNOWLEDGE_ALIAS = "knowledge_docs_read"
KNOWLEDGE_SCHEMA_VERSION = "cb090-v1"
EMBEDDING_DIMS = 8
RECALL_LIMIT = 4
FINAL_RESULT_LIMIT = 5
RRF_CONSTANT = 60
_INDEX_NAME = re.compile(r"^knowledge_docs_v[1-9][0-9]*$")
_SOURCE_FIELDS = (
    "schema_version",
    "source_id",
    "source_version",
    "chunk_id",
    "doc_type",
    "published",
    "deleted",
    "title",
    "content",
    "public_metadata",
)
_SEMANTIC_GROUPS: tuple[tuple[str, ...], ...] = (
    ("refund", "return", "退款", "退货"),
    ("tea", "drink", "product", "茉莉", "茶", "饮品", "商品"),
    ("delivery", "shipping", "配送", "快递"),
    ("hours", "opening", "open", "营业", "时间"),
    ("ingredient", "allergy", "成分", "过敏"),
    ("cancel", "order", "取消", "订单"),
)


class KnowledgeSearchInput(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    query: str = Field(min_length=1, max_length=512)
    rewrite: str | None = Field(default=None, min_length=1, max_length=512)

    @field_validator("query", "rewrite")
    @classmethod
    def reject_blank_or_control_text(cls, value: str | None) -> str | None:
        if value is None:
            return None
        stripped = value.strip()
        if not stripped or any(unicodedata.category(character) == "Cc" for character in stripped):
            raise ValueError("Knowledge query contains unsupported text")
        return stripped


class PublicKnowledgeMetadata(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True, frozen=True)

    category: str = Field(min_length=1, max_length=64)
    language: str = Field(min_length=1, max_length=16)
    product_id: str | None = Field(
        default=None, serialization_alias="productId", min_length=1, max_length=64
    )


class KnowledgeSearchResult(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True, frozen=True)

    source_id: str = Field(serialization_alias="sourceId", min_length=1, max_length=128)
    chunk_id: str = Field(serialization_alias="chunkId", min_length=1, max_length=128)
    source_version: int = Field(serialization_alias="sourceVersion", ge=1)
    doc_type: Literal["faq", "product"] = Field(serialization_alias="docType")
    title: str = Field(min_length=1, max_length=200)
    excerpt: str = Field(min_length=1, max_length=600)
    public_metadata: PublicKnowledgeMetadata = Field(serialization_alias="publicMetadata")
    rank: int = Field(ge=1, le=FINAL_RESULT_LIMIT)
    rrf_score: float = Field(serialization_alias="rrfScore", gt=0, le=1)


class KnowledgeSearchOutput(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True, frozen=True)

    index_version: str = Field(
        serialization_alias="indexVersion", pattern=r"^knowledge_docs_v[1-9][0-9]*$"
    )
    results: tuple[KnowledgeSearchResult, ...] = Field(max_length=FINAL_RESULT_LIMIT)


class _IndexedKnowledgeSource(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    schema_version: Literal["cb090-v1"]
    source_id: str = Field(min_length=1, max_length=128)
    source_version: int = Field(ge=1)
    chunk_id: str = Field(min_length=1, max_length=128)
    doc_type: Literal["faq", "product"]
    published: Literal[True]
    deleted: Literal[False]
    title: str = Field(min_length=1, max_length=200)
    content: str = Field(min_length=1, max_length=2000)
    public_metadata: PublicKnowledgeMetadata


class KnowledgeSearchFailure(Exception):
    """A public-safe structured retrieval failure code."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class KnowledgeSearch(Protocol):
    def search(
        self,
        request: KnowledgeSearchInput,
        charge: Callable[[str, str], None],
    ) -> KnowledgeSearchOutput: ...


@dataclass(frozen=True)
class _RankedCandidate:
    identity: str
    source: _IndexedKnowledgeSource
    rank: int


def deterministic_query_embedding(text: str) -> list[float]:
    """Local deterministic query fixture; it is not a measured production embedding model."""
    normalized = unicodedata.normalize("NFKC", text).casefold()
    vector = [0.0] * EMBEDDING_DIMS
    for dimension, terms in enumerate(_SEMANTIC_GROUPS):
        if any(term in normalized for term in terms):
            vector[dimension] = 1.0
    if not any(vector):
        digest = hashlib.sha256(normalized.encode()).digest()
        for dimension in range(EMBEDDING_DIMS):
            vector[dimension] = (digest[dimension] + 1) / 256
    magnitude = math.sqrt(sum(value * value for value in vector))
    return [value / magnitude for value in vector]


class ElasticsearchKnowledgeSearch:
    def __init__(
        self,
        base_url: str,
        *,
        alias: str = KNOWLEDGE_ALIAS,
        timeout_seconds: float = 1.5,
    ) -> None:
        if (
            not base_url.startswith(("http://", "https://"))
            or alias != KNOWLEDGE_ALIAS
            or timeout_seconds <= 0
        ):
            raise ValueError("Knowledge search configuration is incomplete")
        self._base_url = base_url.rstrip("/")
        self._alias = alias
        self._timeout_seconds = timeout_seconds

    def search(
        self,
        request: KnowledgeSearchInput,
        charge: Callable[[str, str], None],
    ) -> KnowledgeSearchOutput:
        index = self._resolve_index(charge)
        queries = [request.query]
        if request.rewrite is not None:
            queries.append(request.rewrite)
        ranked_lists: list[list[_RankedCandidate]] = []
        try:
            for query in queries:
                ranked_lists.append(self._bm25(index, query, charge))
                ranked_lists.append(self._dense(index, query, charge))
        except KnowledgeSearchFailure as error:
            raise KnowledgeSearchFailure("partial_recall_failed") from error
        return KnowledgeSearchOutput(
            index_version=index,
            results=tuple(self._fuse(ranked_lists)),
        )

    def _resolve_index(self, charge: Callable[[str, str], None]) -> str:
        status, payload = self._request(
            "GET",
            f"/_alias/{quote(self._alias)}",
            None,
            charge,
            "alias_resolution",
            expected=(200, 404),
        )
        if status == 404:
            raise KnowledgeSearchFailure("alias_missing")
        if len(payload) != 1:
            raise KnowledgeSearchFailure("alias_ambiguous")
        index = next(iter(payload))
        if not _INDEX_NAME.fullmatch(index):
            raise KnowledgeSearchFailure("alias_ambiguous")
        index_payload = payload[index]
        if not isinstance(index_payload, dict):
            raise KnowledgeSearchFailure("alias_ambiguous")
        aliases = index_payload.get("aliases")
        if not isinstance(aliases, dict) or set(aliases) != {self._alias}:
            raise KnowledgeSearchFailure("alias_ambiguous")
        _, mapping = self._request(
            "GET",
            f"/{quote(index)}/_mapping",
            None,
            charge,
            "mapping_validation",
        )
        self._validate_mapping(mapping, index)
        return index

    @staticmethod
    def _validate_mapping(payload: dict[str, Any], index: str) -> None:
        index_payload = payload.get(index)
        if not isinstance(index_payload, dict):
            raise KnowledgeSearchFailure("mapping_incompatible")
        mappings = index_payload.get("mappings")
        if not isinstance(mappings, dict) or mappings.get("dynamic") != "strict":
            raise KnowledgeSearchFailure("mapping_incompatible")
        properties = mappings.get("properties")
        if not isinstance(properties, dict):
            raise KnowledgeSearchFailure("mapping_incompatible")
        expected = {
            "schema_version": "keyword",
            "source_id": "keyword",
            "source_version": "long",
            "chunk_id": "keyword",
            "doc_type": "keyword",
            "published": "boolean",
            "deleted": "boolean",
            "title": "text",
            "content": "text",
            "embedding": "dense_vector",
            "public_metadata": "object",
        }
        if set(properties) != set(expected):
            raise KnowledgeSearchFailure("mapping_incompatible")
        for field, expected_type in expected.items():
            definition = properties.get(field)
            if not isinstance(definition, dict):
                raise KnowledgeSearchFailure("mapping_incompatible")
            actual_type = definition.get("type")
            if field == "public_metadata":
                if actual_type not in {None, expected_type}:
                    raise KnowledgeSearchFailure("mapping_incompatible")
            elif actual_type != expected_type:
                raise KnowledgeSearchFailure("mapping_incompatible")
        embedding = cast(dict[str, Any], properties["embedding"])
        if (
            embedding.get("dims") != EMBEDDING_DIMS
            or embedding.get("index") is not True
            or embedding.get("similarity") != "cosine"
        ):
            raise KnowledgeSearchFailure("mapping_incompatible")
        for field in ("title", "content"):
            definition = cast(dict[str, Any], properties[field])
            if (
                definition.get("analyzer") != "ik_max_word"
                or definition.get("search_analyzer") != "ik_smart"
            ):
                raise KnowledgeSearchFailure("mapping_incompatible")
        metadata = cast(dict[str, Any], properties["public_metadata"])
        metadata_properties = metadata.get("properties")
        if (
            not isinstance(metadata_properties, dict)
            or metadata.get("dynamic") != "strict"
            or set(metadata_properties)
            != {
                "product_id",
                "category",
                "language",
            }
        ):
            raise KnowledgeSearchFailure("mapping_incompatible")
        if any(
            not isinstance(metadata_properties[field], dict)
            or metadata_properties[field].get("type") != "keyword"
            for field in metadata_properties
        ):
            raise KnowledgeSearchFailure("mapping_incompatible")

    def _bm25(
        self,
        index: str,
        query: str,
        charge: Callable[[str, str], None],
    ) -> list[_RankedCandidate]:
        body: dict[str, object] = {
            "size": RECALL_LIMIT,
            "_source": list(_SOURCE_FIELDS),
            "sort": [{"_score": "desc"}, {"source_id": "asc"}, {"chunk_id": "asc"}],
            "query": {
                "bool": {
                    "filter": [
                        {"term": {"published": True}},
                        {"term": {"deleted": False}},
                    ],
                    "must": [
                        {
                            "multi_match": {
                                "query": query,
                                "fields": ["title^2", "content"],
                                "type": "best_fields",
                            }
                        }
                    ],
                }
            },
        }
        _, payload = self._request(
            "POST", f"/{quote(self._alias)}/_search", body, charge, "bm25_recall"
        )
        return self._parse_candidates(payload, index)

    def _dense(
        self,
        index: str,
        query: str,
        charge: Callable[[str, str], None],
    ) -> list[_RankedCandidate]:
        body: dict[str, object] = {
            "size": RECALL_LIMIT,
            "_source": list(_SOURCE_FIELDS),
            "sort": [{"_score": "desc"}, {"source_id": "asc"}, {"chunk_id": "asc"}],
            "knn": {
                "field": "embedding",
                "query_vector": deterministic_query_embedding(query),
                "k": RECALL_LIMIT,
                "num_candidates": RECALL_LIMIT * 2,
                "filter": {
                    "bool": {
                        "filter": [
                            {"term": {"published": True}},
                            {"term": {"deleted": False}},
                        ]
                    }
                },
            },
        }
        _, payload = self._request(
            "POST", f"/{quote(self._alias)}/_search", body, charge, "dense_recall"
        )
        return self._parse_candidates(payload, index)

    @staticmethod
    def _parse_candidates(payload: dict[str, Any], index: str) -> list[_RankedCandidate]:
        hits_wrapper = payload.get("hits")
        hits = hits_wrapper.get("hits") if isinstance(hits_wrapper, dict) else None
        if not isinstance(hits, list) or len(hits) > RECALL_LIMIT:
            raise KnowledgeSearchFailure("malformed_candidate")
        candidates: list[_RankedCandidate] = []
        for rank, hit in enumerate(hits, start=1):
            if not isinstance(hit, dict):
                raise KnowledgeSearchFailure("malformed_candidate")
            document_id = hit.get("_id")
            hit_index = hit.get("_index")
            source = hit.get("_source")
            if (
                not isinstance(document_id, str)
                or hit_index != index
                or not isinstance(source, dict)
            ):
                raise KnowledgeSearchFailure("malformed_candidate")
            try:
                validated = _IndexedKnowledgeSource.model_validate(source)
            except ValidationError as error:
                raise KnowledgeSearchFailure("malformed_candidate") from error
            identity = f"{validated.source_id}:{validated.chunk_id}"
            if document_id != identity:
                raise KnowledgeSearchFailure("malformed_candidate")
            candidates.append(_RankedCandidate(identity, validated, rank))
        return candidates

    @staticmethod
    def _fuse(ranked_lists: list[list[_RankedCandidate]]) -> list[KnowledgeSearchResult]:
        accumulated: dict[str, tuple[_IndexedKnowledgeSource, float, int]] = {}
        for ranked_list in ranked_lists:
            seen: set[str] = set()
            for candidate in ranked_list:
                if candidate.identity in seen:
                    raise KnowledgeSearchFailure("malformed_candidate")
                seen.add(candidate.identity)
                increment = 1.0 / (RRF_CONSTANT + candidate.rank)
                existing = accumulated.get(candidate.identity)
                if existing is None:
                    accumulated[candidate.identity] = (
                        candidate.source,
                        increment,
                        candidate.rank,
                    )
                else:
                    if existing[0] != candidate.source:
                        raise KnowledgeSearchFailure("malformed_candidate")
                    accumulated[candidate.identity] = (
                        existing[0],
                        existing[1] + increment,
                        min(existing[2], candidate.rank),
                    )
        ordered = sorted(
            accumulated.values(),
            key=lambda item: (-item[1], item[2], item[0].source_id, item[0].chunk_id),
        )[:FINAL_RESULT_LIMIT]
        results: list[KnowledgeSearchResult] = []
        for final_rank, (source, score, _) in enumerate(ordered, start=1):
            results.append(
                KnowledgeSearchResult(
                    source_id=source.source_id,
                    chunk_id=source.chunk_id,
                    source_version=source.source_version,
                    doc_type=source.doc_type,
                    title=source.title,
                    excerpt=source.content[:600],
                    public_metadata=source.public_metadata,
                    rank=final_rank,
                    rrf_score=round(score, 8),
                )
            )
        return results

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, object] | None,
        charge: Callable[[str, str], None],
        target: str,
        *,
        expected: tuple[int, ...] = (200,),
    ) -> tuple[int, dict[str, Any]]:
        charge("knowledge_http", target)
        try:
            response = httpx.request(
                method,
                f"{self._base_url}{path}",
                json=payload,
                timeout=self._timeout_seconds,
            )
        except (httpx.TimeoutException, httpx.NetworkError) as error:
            raise KnowledgeSearchFailure("knowledge_unavailable") from error
        if response.status_code not in expected:
            code = "knowledge_unavailable" if response.status_code >= 500 else "knowledge_rejected"
            raise KnowledgeSearchFailure(code)
        if not response.content:
            return response.status_code, {}
        try:
            decoded = response.json()
        except ValueError as error:
            raise KnowledgeSearchFailure("malformed_backend") from error
        if not isinstance(decoded, dict):
            raise KnowledgeSearchFailure("malformed_backend")
        return response.status_code, cast(dict[str, Any], decoded)
