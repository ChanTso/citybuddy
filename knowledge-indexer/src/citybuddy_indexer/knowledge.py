"""Initial versioned public-knowledge index bootstrap for CB-090."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any, cast
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

KNOWLEDGE_ALIAS = "knowledge_docs_read"
KNOWLEDGE_SCHEMA_VERSION = "cb090-v1"
KNOWLEDGE_SYNC_SCHEMA_VERSION = "cb111-v1"
EMBEDDING_DIMS = 8
_INDEX_NAME = re.compile(r"^knowledge_docs_v[1-9][0-9]*$")

KNOWLEDGE_SYNC_MAPPING_PROPERTIES: dict[str, object] = {
    "sync_record_type": {"type": "keyword"},
    "sync_event_id": {"type": "keyword"},
    "sync_event_commitment": {"type": "keyword"},
    "sync_occurred_at": {"type": "date", "format": "strict_date_optional_time_nanos"},
}

KNOWLEDGE_INDEX_MAPPING: dict[str, object] = {
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
            **KNOWLEDGE_SYNC_MAPPING_PROPERTIES,
        },
    }
}


class KnowledgeBootstrapError(Exception):
    """A bounded bootstrap failure without raw Elasticsearch payload disclosure."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class PublicMetadata:
    category: str
    language: str
    product_id: str | None = None

    def as_source(self) -> dict[str, str]:
        result = {"category": self.category, "language": self.language}
        if self.product_id is not None:
            result["product_id"] = self.product_id
        return result


@dataclass(frozen=True)
class KnowledgeDocument:
    source_id: str
    source_version: int
    chunk_id: str
    doc_type: str
    title: str
    content: str
    embedding: tuple[float, ...]
    public_metadata: PublicMetadata

    def __post_init__(self) -> None:
        if (
            not self.source_id
            or not self.chunk_id
            or self.source_version < 1
            or self.doc_type not in {"faq", "product"}
            or not self.title
            or not self.content
            or len(self.embedding) != EMBEDDING_DIMS
        ):
            raise ValueError("Invalid bounded public-knowledge document")
        if self.doc_type == "product" and self.public_metadata.product_id is None:
            raise ValueError("Product knowledge requires a stable public product id")

    @property
    def document_id(self) -> str:
        return f"{self.source_id}:{self.chunk_id}"

    def as_source(self) -> dict[str, object]:
        return {
            "schema_version": KNOWLEDGE_SCHEMA_VERSION,
            "source_id": self.source_id,
            "source_version": self.source_version,
            "chunk_id": self.chunk_id,
            "doc_type": self.doc_type,
            "published": True,
            "deleted": False,
            "title": self.title,
            "content": self.content,
            "embedding": list(self.embedding),
            "public_metadata": self.public_metadata.as_source(),
        }


def _unit(dimension: int) -> tuple[float, ...]:
    return tuple(1.0 if index == dimension else 0.0 for index in range(EMBEDDING_DIMS))


INITIAL_PUBLIC_CORPUS: tuple[KnowledgeDocument, ...] = (
    KnowledgeDocument(
        source_id="faq-refund-policy",
        source_version=1,
        chunk_id="overview",
        doc_type="faq",
        title="退款政策 Refund policy",
        content=(
            "CityBuddy public guidance: eligible unused goods may be requested for return or "
            "refund under the merchant policy. Current order and refund status must be checked "
            "through the commerce service. 符合商家政策的未使用商品可以申请退货或退款。"
        ),
        embedding=_unit(0),
        public_metadata=PublicMetadata(category="policy", language="zh-en"),
    ),
    KnowledgeDocument(
        source_id="product-jasmine-tea",
        source_version=3,
        chunk_id="description",
        doc_type="product",
        title="茉莉绿茶 Jasmine green tea",
        content=(
            "A public product description for jasmine green tea with a floral aroma. "
            "茉莉绿茶带有清新的花香；实时价格、库存和可售状态以 commerce service 为准。"
        ),
        embedding=_unit(1),
        public_metadata=PublicMetadata(
            category="tea", language="zh-en", product_id="product-jasmine-tea"
        ),
    ),
    KnowledgeDocument(
        source_id="faq-delivery",
        source_version=2,
        chunk_id="coverage",
        doc_type="faq",
        title="配送说明 Delivery guide",
        content=(
            "Public delivery guidance describes the merchant delivery area and estimated "
            "handoff process. 配送范围与预计时间是说明信息，不代表当前订单状态。"
        ),
        embedding=_unit(2),
        public_metadata=PublicMetadata(category="delivery", language="zh-en"),
    ),
    KnowledgeDocument(
        source_id="faq-store-hours",
        source_version=1,
        chunk_id="general",
        doc_type="faq",
        title="营业时间 Store hours",
        content=(
            "Public store-hours guidance explains where merchants publish their ordinary opening "
            "hours. 临时营业状态和商品可售性仍须查询 commerce service。"
        ),
        embedding=_unit(3),
        public_metadata=PublicMetadata(category="store", language="zh-en"),
    ),
)


def validate_knowledge_mapping(payload: dict[str, Any], index: str) -> None:
    index_payload = payload.get(index)
    if not isinstance(index_payload, dict):
        raise KnowledgeBootstrapError("incompatible_mapping")
    mappings = index_payload.get("mappings")
    if not isinstance(mappings, dict) or mappings.get("dynamic") != "strict":
        raise KnowledgeBootstrapError("incompatible_mapping")
    properties = mappings.get("properties")
    if not isinstance(properties, dict):
        raise KnowledgeBootstrapError("incompatible_mapping")
    required_types = {
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
        "sync_record_type": "keyword",
        "sync_event_id": "keyword",
        "sync_event_commitment": "keyword",
        "sync_occurred_at": "date",
    }
    if set(properties) != set(required_types):
        raise KnowledgeBootstrapError("incompatible_mapping")
    for field, expected_type in required_types.items():
        definition = properties.get(field)
        if not isinstance(definition, dict):
            raise KnowledgeBootstrapError("incompatible_mapping")
        actual_type = definition.get("type")
        if field == "public_metadata":
            if actual_type not in {None, expected_type}:
                raise KnowledgeBootstrapError("incompatible_mapping")
        elif actual_type != expected_type:
            raise KnowledgeBootstrapError("incompatible_mapping")
    embedding = cast(dict[str, Any], properties["embedding"])
    if (
        embedding.get("dims") != EMBEDDING_DIMS
        or embedding.get("index") is not True
        or embedding.get("similarity") != "cosine"
    ):
        raise KnowledgeBootstrapError("incompatible_mapping")
    for field in ("title", "content"):
        definition = cast(dict[str, Any], properties[field])
        if (
            definition.get("analyzer") != "ik_max_word"
            or definition.get("search_analyzer") != "ik_smart"
        ):
            raise KnowledgeBootstrapError("incompatible_mapping")
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
        raise KnowledgeBootstrapError("incompatible_mapping")
    if any(
        not isinstance(metadata_properties[field], dict)
        or metadata_properties[field].get("type") != "keyword"
        for field in metadata_properties
    ):
        raise KnowledgeBootstrapError("incompatible_mapping")
    occurred_at = cast(dict[str, Any], properties["sync_occurred_at"])
    if occurred_at.get("format") != "strict_date_optional_time_nanos":
        raise KnowledgeBootstrapError("incompatible_mapping")


@dataclass(frozen=True)
class BootstrapResult:
    index: str
    alias: str
    document_count: int


class ElasticsearchBootstrapClient:
    def __init__(self, base_url: str, *, timeout_seconds: float = 5.0) -> None:
        if not base_url.startswith(("http://", "https://")) or timeout_seconds <= 0:
            raise ValueError("Elasticsearch bootstrap configuration is incomplete")
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds

    def bootstrap(
        self,
        *,
        index: str,
        alias: str = KNOWLEDGE_ALIAS,
        documents: tuple[KnowledgeDocument, ...] = INITIAL_PUBLIC_CORPUS,
    ) -> BootstrapResult:
        if not _INDEX_NAME.fullmatch(index):
            raise KnowledgeBootstrapError("invalid_index_name")
        if alias != KNOWLEDGE_ALIAS or not documents:
            raise KnowledgeBootstrapError("invalid_bootstrap_policy")
        document_ids = [document.document_id for document in documents]
        if len(document_ids) != len(set(document_ids)):
            raise KnowledgeBootstrapError("duplicate_document_identity")

        status, mapping = self._request("GET", f"/{quote(index)}/_mapping", expected=(200, 404))
        if status == 404:
            self._request("PUT", f"/{quote(index)}", KNOWLEDGE_INDEX_MAPPING)
            _, mapping = self._request("GET", f"/{quote(index)}/_mapping")
        else:
            mapping = self._upgrade_incremental_mapping(index, mapping)
        validate_knowledge_mapping(mapping, index)

        for document in documents:
            self._request(
                "PUT",
                f"/{quote(index)}/_doc/{quote(document.document_id, safe='')}",
                document.as_source(),
            )
        self._request("POST", f"/{quote(index)}/_refresh")
        _, count_payload = self._request(
            "POST",
            f"/{quote(index)}/_count",
            {"query": {"term": {"schema_version": KNOWLEDGE_SCHEMA_VERSION}}},
        )
        if count_payload.get("count") != len(documents):
            raise KnowledgeBootstrapError("unexpected_document_count")

        alias_status, alias_payload = self._request(
            "GET", f"/_alias/{quote(alias)}", expected=(200, 404)
        )
        if alias_status == 404:
            self._request(
                "POST",
                "/_aliases",
                {"actions": [{"add": {"index": index, "alias": alias}}]},
            )
        elif not self._alias_points_exactly(alias_payload, index, alias):
            raise KnowledgeBootstrapError("ambiguous_alias")
        _, final_alias = self._request("GET", f"/_alias/{quote(alias)}")
        if not self._alias_points_exactly(final_alias, index, alias):
            raise KnowledgeBootstrapError("ambiguous_alias")
        return BootstrapResult(index=index, alias=alias, document_count=len(documents))

    def _upgrade_incremental_mapping(self, index: str, payload: dict[str, Any]) -> dict[str, Any]:
        index_payload = payload.get(index)
        mappings = index_payload.get("mappings") if isinstance(index_payload, dict) else None
        properties = mappings.get("properties") if isinstance(mappings, dict) else None
        if not isinstance(properties, dict):
            raise KnowledgeBootstrapError("incompatible_mapping")
        missing = set(KNOWLEDGE_SYNC_MAPPING_PROPERTIES).difference(properties)
        if not missing:
            return payload
        if missing != set(KNOWLEDGE_SYNC_MAPPING_PROPERTIES):
            raise KnowledgeBootstrapError("incompatible_mapping")
        self._request(
            "PUT",
            f"/{quote(index)}/_mapping",
            {"properties": KNOWLEDGE_SYNC_MAPPING_PROPERTIES},
        )
        _, upgraded = self._request("GET", f"/{quote(index)}/_mapping")
        return upgraded

    @staticmethod
    def _alias_points_exactly(payload: dict[str, Any], index: str, alias: str) -> bool:
        if set(payload) != {index}:
            return False
        index_payload = payload[index]
        if not isinstance(index_payload, dict):
            return False
        aliases = index_payload.get("aliases")
        return isinstance(aliases, dict) and set(aliases) == {alias}

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, object] | None = None,
        expected: tuple[int, ...] = (200, 201),
    ) -> tuple[int, dict[str, Any]]:
        data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        request = Request(
            f"{self._base_url}{path}",
            data=data,
            headers={"Content-Type": "application/json"},
            method=method,
        )
        try:
            with urlopen(request, timeout=self._timeout_seconds) as response:  # noqa: S310
                status = response.status
                body = response.read()
        except HTTPError as error:
            status = error.code
            body = error.read()
        except (URLError, TimeoutError) as error:
            raise KnowledgeBootstrapError("elasticsearch_unavailable") from error
        if status not in expected:
            raise KnowledgeBootstrapError("elasticsearch_rejected_bootstrap")
        if not body:
            return status, {}
        try:
            decoded = json.loads(body)
        except (json.JSONDecodeError, UnicodeDecodeError) as error:
            raise KnowledgeBootstrapError("malformed_elasticsearch_response") from error
        if not isinstance(decoded, dict):
            raise KnowledgeBootstrapError("malformed_elasticsearch_response")
        return status, cast(dict[str, Any], decoded)
