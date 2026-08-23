"""Drive one refund from preparation to receipt against the running bench services.

This is the flagship flow end to end: the agent prepares a real PendingAction in commerce, the
user confirms it in a second turn, commerce executes the refund, and the agent projects the
receipt. It runs inside the bench network namespace, against the same fixture the ladders use.

Business truth is read with SQL against the authoritative databases afterwards, not inferred from
the HTTP responses.
"""

from __future__ import annotations

import json
import sys
import urllib.request

BASE = "http://127.0.0.1:8001"


def turn(entry: dict[str, str], message: str, key: str, *, stream: bool = False) -> str:
    request = urllib.request.Request(
        f"{BASE}/api/chat/stream" if stream else f"{BASE}/api/chat",
        data=json.dumps({"message": message}).encode(),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {entry['token']}",
            "X-Session-Id": entry["sessionId"],
            "Idempotency-Key": key,
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read().decode()


def main() -> None:
    pool = json.load(open("/run-data/agent_pool.json"))
    if len(pool) < 2:
        raise SystemExit("fixture needs at least two entries: one JSON turn, one stream turn")

    print("== JSON path ==")
    entry = pool[0]
    prepared = json.loads(turn(entry, f"action-prepare refund my order {entry['orderId']}", "p1"))
    print("prepare:", json.dumps(prepared, ensure_ascii=False))
    if prepared["outcome"] != "action_pending":
        raise SystemExit(f"preparation did not pend: {prepared['outcome']}")

    confirmed = json.loads(turn(entry, "confirm", "c1"))
    print("confirm:", json.dumps(confirmed, ensure_ascii=False))
    if confirmed["outcome"] != "action_completed":
        raise SystemExit(f"confirmation did not complete: {confirmed['outcome']}")
    if not confirmed["receiptId"]:
        raise SystemExit("confirmation completed without a receipt identifier")

    # A repeat of the same idempotency key must replay the same turn rather than refund again.
    replay = json.loads(turn(entry, "confirm", "c1"))
    if replay != confirmed:
        raise SystemExit("confirmation replay did not return the stored turn")
    print("replay of the same key returned the identical turn")

    print()
    print("== stream path ==")
    stream_entry = pool[1]
    stream_prepared = json.loads(
        turn(stream_entry, f"action-prepare refund my order {stream_entry['orderId']}", "p2")
    )
    if stream_prepared["outcome"] != "action_pending":
        raise SystemExit(f"stream preparation did not pend: {stream_prepared['outcome']}")
    body = turn(stream_entry, "confirm", "c2", stream=True)
    sys.stdout.write(body)
    if "event: action_receipt" not in body or '"outcome":"action_completed"' not in body:
        raise SystemExit("stream did not carry a receipt and a committed terminal")

    print()
    print(f"json_receipt_id={confirmed['receiptId']}")
    print(f"json_session_id={entry['sessionId']}")
    print(f"json_order_id={entry['orderId']}")


if __name__ == "__main__":
    main()
