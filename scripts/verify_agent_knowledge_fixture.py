#!/usr/bin/env python3
"""Verify the exact Elasticsearch corpus exposed to the agent benchmark."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from citybuddy_indexer.knowledge import (
    INITIAL_PUBLIC_CORPUS,
    KnowledgeBootstrapError,
    validate_knowledge_mapping,
)

BENCH_INDEX = "knowledge_docs_v1"
BENCH_ALIAS = "knowledge_docs_read"


class FixtureVerificationError(Exception):
    """The benchmark knowledge fixture does not match committed source truth."""


def _load_object(path: Path, label: str) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise FixtureVerificationError(f"invalid_{label}_json") from error
    if not isinstance(payload, dict):
        raise FixtureVerificationError(f"invalid_{label}_shape")
    return payload


def _canonical_json(value: object) -> str:
    try:
        return json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
    except (TypeError, ValueError) as error:
        raise FixtureVerificationError("invalid_document_source") from error


def _required_int(payload: dict[str, Any], key: str, label: str) -> int:
    value = payload.get(key)
    if isinstance(value, bool) or not isinstance(value, int):
        raise FixtureVerificationError(f"invalid_{label}_{key}")
    return value


def _verify_search(payload: dict[str, Any], label: str) -> None:
    if payload.get("timed_out") is not False:
        raise FixtureVerificationError(f"{label}_timed_out")

    shards = payload.get("_shards")
    if not isinstance(shards, dict):
        raise FixtureVerificationError(f"invalid_{label}_shards")
    total_shards = _required_int(shards, "total", f"{label}_shards")
    successful_shards = _required_int(shards, "successful", f"{label}_shards")
    skipped_shards = _required_int(shards, "skipped", f"{label}_shards")
    failed_shards = _required_int(shards, "failed", f"{label}_shards")
    if (
        total_shards < 1
        or successful_shards != total_shards
        or skipped_shards != 0
        or failed_shards != 0
    ):
        raise FixtureVerificationError(f"incomplete_{label}_shards")

    hits = payload.get("hits")
    if not isinstance(hits, dict):
        raise FixtureVerificationError(f"invalid_{label}_hits")
    total = hits.get("total")
    if not isinstance(total, dict) or total.get("relation") != "eq":
        raise FixtureVerificationError(f"invalid_{label}_total")

    expected = {document.document_id: document.as_source() for document in INITIAL_PUBLIC_CORPUS}
    if _required_int(total, "value", f"{label}_total") != len(expected):
        raise FixtureVerificationError(f"unexpected_{label}_count")

    raw_hits = hits.get("hits")
    if not isinstance(raw_hits, list) or len(raw_hits) != len(expected):
        raise FixtureVerificationError(f"unexpected_{label}_hits")

    actual: dict[str, dict[str, Any]] = {}
    for raw_hit in raw_hits:
        if not isinstance(raw_hit, dict):
            raise FixtureVerificationError(f"invalid_{label}_hit")
        document_id = raw_hit.get("_id")
        source = raw_hit.get("_source")
        if (
            raw_hit.get("_index") != BENCH_INDEX
            or not isinstance(document_id, str)
            or not isinstance(source, dict)
        ):
            raise FixtureVerificationError(f"invalid_{label}_hit_identity")
        if document_id in actual:
            raise FixtureVerificationError(f"duplicate_{label}_document:{document_id}")
        if document_id != f"{source.get('source_id')}:{source.get('chunk_id')}":
            raise FixtureVerificationError(f"inconsistent_{label}_document:{document_id}")
        actual[document_id] = source

    if set(actual) != set(expected):
        raise FixtureVerificationError(f"unexpected_{label}_document_ids")
    for document_id, expected_source in expected.items():
        if _canonical_json(actual[document_id]) != _canonical_json(expected_source):
            raise FixtureVerificationError(f"unexpected_{label}_source:{document_id}")


def verify_fixture(
    *,
    bootstrap_path: Path,
    alias_path: Path,
    mapping_path: Path,
    visible_path: Path,
    all_path: Path,
) -> dict[str, object]:
    expected_count = len(INITIAL_PUBLIC_CORPUS)
    bootstrap = _load_object(bootstrap_path, "bootstrap")
    if bootstrap != {
        "alias": BENCH_ALIAS,
        "documentCount": expected_count,
        "indexVersion": BENCH_INDEX,
    }:
        raise FixtureVerificationError("unexpected_bootstrap_result")

    alias = _load_object(alias_path, "alias")
    if alias != {BENCH_INDEX: {"aliases": {BENCH_ALIAS: {}}}}:
        raise FixtureVerificationError("unexpected_alias_boundary")

    mapping = _load_object(mapping_path, "mapping")
    if set(mapping) != {BENCH_INDEX}:
        raise FixtureVerificationError("unexpected_mapping_index")
    try:
        validate_knowledge_mapping(mapping, BENCH_INDEX)
    except KnowledgeBootstrapError as error:
        raise FixtureVerificationError("unexpected_mapping") from error

    _verify_search(_load_object(visible_path, "visible"), "visible")
    _verify_search(_load_object(all_path, "all"), "all")
    return {
        "alias": BENCH_ALIAS,
        "documentCount": expected_count,
        "documentIds": sorted(document.document_id for document in INITIAL_PUBLIC_CORPUS),
        "index": BENCH_INDEX,
        "visibleDocumentCount": expected_count,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bootstrap", type=Path, required=True)
    parser.add_argument("--alias", type=Path, required=True)
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--visible", type=Path, required=True)
    parser.add_argument("--all", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = verify_fixture(
            bootstrap_path=args.bootstrap,
            alias_path=args.alias,
            mapping_path=args.mapping,
            visible_path=args.visible,
            all_path=args.all,
        )
    except FixtureVerificationError as error:
        parser.exit(1, f"Agent knowledge fixture verification failed: {error}\n")
    print(json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
