"""Real owner-snapshot, Broker-journal, Elasticsearch rebuild evidence for CB-113."""

from __future__ import annotations

import argparse
import base64
import json
from typing import Any, cast
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from citybuddy_indexer.rebuild import ElasticsearchRebuildClient, KnowledgeRebuildCoordinator
from citybuddy_indexer.rebuild_runtime import HttpOwnerSnapshotSource, RocketMqAcceptedEventJournal


def request(
    base_url: str,
    method: str,
    path: str,
    *,
    authorization: str | None = None,
    expected: tuple[int, ...] = (200,),
) -> tuple[int, dict[str, Any]]:
    headers = {} if authorization is None else {"Authorization": authorization}
    http_request = Request(f"{base_url.rstrip('/')}{path}", headers=headers, method=method)
    try:
        with urlopen(http_request, timeout=10) as response:  # noqa: S310
            status = response.status
            body = response.read()
    except HTTPError as error:
        status = error.code
        body = error.read()
    if status not in expected:
        raise AssertionError(f"{method} {path} returned {status}")
    if not body:
        return status, {}
    decoded = json.loads(body)
    if not isinstance(decoded, dict):
        raise AssertionError("HTTP response was not an object")
    return status, cast(dict[str, Any], decoded)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--elasticsearch-url", required=True)
    parser.add_argument("--owner-url", required=True)
    parser.add_argument("--owner-client-id", required=True)
    parser.add_argument("--owner-client-secret", required=True)
    parser.add_argument("--endpoints", required=True)
    parser.add_argument("--topic", required=True)
    parser.add_argument("--group", required=True)
    args = parser.parse_args()

    status, _ = request(
        args.owner_url,
        "GET",
        "/internal/knowledge/snapshot",
        expected=(401,),
    )
    assert status == 401
    credential = base64.b64encode(
        f"{args.owner_client_id}:{args.owner_client_secret}".encode()
    ).decode()
    _, owner_payload = request(
        args.owner_url,
        "GET",
        "/internal/knowledge/snapshot",
        authorization=f"Basic {credential}",
    )
    assert owner_payload["recordCount"] == 4
    assert owner_payload["sourceCount"] == 4

    source = HttpOwnerSnapshotSource(
        f"{args.owner_url.rstrip('/')}/internal/knowledge/snapshot",
        args.owner_client_id,
        args.owner_client_secret,
    )
    elasticsearch = ElasticsearchRebuildClient(args.elasticsearch_url)
    with RocketMqAcceptedEventJournal(args.endpoints, args.topic, args.group) as journal:
        result = KnowledgeRebuildCoordinator(elasticsearch).rebuild(source, journal)
    assert result.predecessor == "knowledge_docs_v1"
    assert result.candidate == "knowledge_docs_v2"
    assert result.document_count == 4
    assert result.replayed is False

    _, alias = request(args.elasticsearch_url, "GET", "/_alias/knowledge_docs_read")
    assert set(alias) == {"knowledge_docs_v2"}
    _, old = request(
        args.elasticsearch_url,
        "GET",
        "/knowledge_docs_v1/_doc/faq-refund-policy%3Aoverview",
    )
    assert old["found"] is True
    _, control = request(
        args.elasticsearch_url,
        "GET",
        "/knowledge_docs_v2/_doc/__rebuild_switch__",
    )
    control_source = control.get("_source")
    assert isinstance(control_source, dict)
    persisted = json.loads(cast(str, control_source["content"]))
    assert persisted["predecessor"] == "knowledge_docs_v1"
    assert persisted["candidate"] == "knowledge_docs_v2"
    assert persisted["state"] == "SWITCHED"
    assert persisted["handoffWatermark"] == result.handoff_watermark
    assert persisted["rollbackLeaseExpiresAt"] == result.rollback_lease_expires_at

    with RocketMqAcceptedEventJournal(
        args.endpoints, args.topic, f"{args.group}-replay"
    ) as journal:
        replay = KnowledgeRebuildCoordinator(elasticsearch).rebuild(source, journal)
    assert replay.candidate == "knowledge_docs_v2"
    assert replay.replayed is True
    status, _ = request(
        args.elasticsearch_url,
        "GET",
        "/knowledge_docs_v3",
        expected=(404,),
    )
    assert status == 404

    print(
        json.dumps(
            {
                "alias": result.candidate,
                "atomicSwitch": True,
                "documentCount": result.document_count,
                "event": "cb113-knowledge-rebuild-evidence",
                "ownerSnapshotCommitment": owner_payload["contentCommitment"],
                "predecessorRetained": True,
                "restartReplay": True,
                "rollbackLeaseRecorded": True,
            },
            separators=(",", ":"),
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
