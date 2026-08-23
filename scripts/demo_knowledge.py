"""Publishes the demonstration FAQ set through the real knowledge projection.

The indexer normally receives these events from RocketMQ. Running the same projection directly
puts the documents in Elasticsearch by the identical code path — same identity, same version
gate, same deterministic document embedding — without standing up a publisher for four rows.
"""

from __future__ import annotations

import argparse
import json

import httpx
from citybuddy_indexer import ElasticsearchKnowledgeProjection, FaqKnowledgeEvent

DOCUMENTS: tuple[tuple[str, str, str], ...] = (
    (
        "faq-refund-policy",
        "退款政策是怎样的",
        "已支付订单可在签收后 7 天内申请退款。退款按原支付路径退回，到账时间以支付渠道为准。",
    ),
    (
        "faq-refund-partial",
        "可以只退一部分金额吗",
        "可以。部分退款按实际申请金额结算，同一笔订单的累计退款不会超过已支付金额。",
    ),
    (
        "faq-delivery-window",
        "配送需要多久",
        "同城订单一般在下单后 2 小时内送达，跨区订单按配送方给出的时间为准。",
    ),
    (
        "faq-invoice",
        "发票怎么开",
        "订单完成后可在订单详情申请电子发票，开票信息以下单时填写的抬头为准。",
    ),
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--elasticsearch-url", required=True)
    args = parser.parse_args()

    base = args.elasticsearch_url.rstrip("/")
    # Other local fixtures leave their own documents behind in this index, and they would answer
    # the demonstration query alongside these. The citations shown have to be the ones seeded here.
    httpx.post(
        f"{base}/knowledge_docs_v1/_delete_by_query?refresh=true",
        json={"query": {"match_all": {}}},
        timeout=10,
    )

    projection = ElasticsearchKnowledgeProjection(args.elasticsearch_url)
    for index, (source_id, question, answer) in enumerate(DOCUMENTS):
        payload = {
            "eventId": f"d0000000-0000-4000-8000-{index:012d}",
            "sourceId": source_id,
            "sourceType": "faq",
            "sourceVersion": 1,
            "publicationState": "PUBLISHED",
            "tombstone": False,
            "occurredTime": f"2026-08-01T00:00:{index:02d}Z",
            "content": {"question": question, "answer": answer},
        }
        event = FaqKnowledgeEvent.from_bytes(json.dumps(payload, separators=(",", ":")).encode())
        print(f"{source_id}: {projection.apply(event).name}")

    # Search is not read-your-writes, and the demonstration queries immediately afterwards.
    httpx.post(f"{base}/knowledge_docs_v1/_refresh", timeout=10)


if __name__ == "__main__":
    main()
