from collections.abc import Callable
from copy import deepcopy

import pytest
from citybuddy_indexer.knowledge import (
    EMBEDDING_DIMS,
    INITIAL_PUBLIC_CORPUS,
    KNOWLEDGE_ALIAS,
    KNOWLEDGE_INDEX_MAPPING,
    KnowledgeBootstrapError,
    KnowledgeDocument,
    PublicMetadata,
    validate_knowledge_mapping,
)


def mapping_response(index: str = "knowledge_docs_v1") -> dict[str, object]:
    return {index: deepcopy(KNOWLEDGE_INDEX_MAPPING)}


def test_initial_corpus_is_bounded_public_and_has_stable_identity() -> None:
    sources = [document.as_source() for document in INITIAL_PUBLIC_CORPUS]

    assert KNOWLEDGE_ALIAS == "knowledge_docs_read"
    assert len(sources) == 4
    assert len({document.document_id for document in INITIAL_PUBLIC_CORPUS}) == len(sources)
    assert {source["doc_type"] for source in sources} == {"faq", "product"}
    for source in sources:
        assert set(source) == {
            "schema_version",
            "source_id",
            "source_version",
            "chunk_id",
            "doc_type",
            "published",
            "deleted",
            "title",
            "content",
            "embedding",
            "public_metadata",
        }
        assert source["published"] is True
        assert source["deleted"] is False
        assert len(source["embedding"]) == EMBEDDING_DIMS  # type: ignore[arg-type]
        assert not {
            "user_subject",
            "session_id",
            "order_id",
            "refund_id",
            "price_minor",
            "stock_quantity",
            "credential",
            "sandbox_id",
            "evaluation_id",
        }.intersection(source)


def test_public_document_validation_rejects_invalid_shape() -> None:
    with pytest.raises(ValueError):
        KnowledgeDocument(
            source_id="product-without-public-id",
            source_version=1,
            chunk_id="description",
            doc_type="product",
            title="Product",
            content="Public description",
            embedding=(1.0,) * EMBEDDING_DIMS,
            public_metadata=PublicMetadata(category="product", language="en"),
        )
    with pytest.raises(ValueError):
        KnowledgeDocument(
            source_id="faq-invalid-vector",
            source_version=1,
            chunk_id="answer",
            doc_type="faq",
            title="FAQ",
            content="Public answer",
            embedding=(1.0,),
            public_metadata=PublicMetadata(category="faq", language="en"),
        )


def test_mapping_is_strict_versioned_ik_and_dense_vector() -> None:
    payload = mapping_response()

    validate_knowledge_mapping(payload, "knowledge_docs_v1")
    index_payload = payload["knowledge_docs_v1"]
    assert isinstance(index_payload, dict)
    mappings = index_payload["mappings"]
    assert isinstance(mappings, dict)
    assert mappings["dynamic"] == "strict"

    properties = mappings["properties"]
    assert isinstance(properties, dict)
    public_metadata = properties["public_metadata"]
    assert isinstance(public_metadata, dict)
    public_metadata.pop("type")
    validate_knowledge_mapping(payload, "knowledge_docs_v1")


@pytest.mark.parametrize(
    "mutation",
    [
        lambda properties: properties.pop("source_version"),
        lambda properties: properties["embedding"].update({"dims": 3}),
        lambda properties: properties["content"].update({"analyzer": "standard"}),
        lambda properties: properties["public_metadata"].update({"dynamic": True}),
        lambda properties: properties.update({"user_subject": {"type": "keyword"}}),
        lambda properties: properties["public_metadata"]["properties"]["category"].update(
            {"type": "text"}
        ),
    ],
)
def test_mapping_validation_rejects_incompatible_boundary(
    mutation: Callable[[dict[str, object]], object],
) -> None:
    payload = mapping_response()
    index_payload = payload["knowledge_docs_v1"]
    assert isinstance(index_payload, dict)
    mappings = index_payload["mappings"]
    assert isinstance(mappings, dict)
    properties = mappings["properties"]
    assert isinstance(properties, dict)
    mutation(properties)

    with pytest.raises(KnowledgeBootstrapError, match="incompatible_mapping"):
        validate_knowledge_mapping(payload, "knowledge_docs_v1")
