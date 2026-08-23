"""Drive one refund from preparation to receipt against the running bench services.

This is the flagship flow end to end: the agent prepares a real PendingAction in commerce, the
user confirms it in a second turn, commerce executes the refund, and the agent projects the
receipt. It runs inside the bench network namespace, against the same fixture the ladders use.

Business truth is read with SQL against the authoritative databases afterwards, not inferred from
the HTTP responses.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.request

import pymysql

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


def reclaim(session_id: str) -> None:
    """Restore the exact state a lost commerce response leaves behind.

    The agent claimed the reference, commerce committed the refund, and the local transaction
    never ran: the reference is CONFIRMING, there is no projection, and the confirmation turn does
    not exist. Erasing the committed turn is what makes this the real case rather than a
    half-rolled-back one.
    """
    connection = pymysql.connect(
        host=os.environ["MYSQL_HOST"],
        port=int(os.environ.get("MYSQL_PORT", "3306")),
        user="root",
        password=os.environ["MYSQL_ROOT_PASSWORD"],
        database="cs_db",
        autocommit=True,
    )
    with connection, connection.cursor() as cursor:
        cursor.execute(
            "SELECT resolution_turn_id FROM pending_action_reference "
            "WHERE session_id = %s AND state = 'CONFIRMED'",
            (session_id,),
        )
        rows = cursor.fetchall()
        if len(rows) != 1:
            raise SystemExit("could not restore the claim for the recovery check")
        turn_id = rows[0][0]
        cursor.execute("DELETE FROM action_receipt_projection WHERE session_id = %s", (session_id,))
        cursor.execute(
            "UPDATE pending_action_reference SET state = 'CONFIRMING', resolved_at = NULL, "
            "resolution_turn_id = NULL, resolution_trace_id = NULL "
            "WHERE session_id = %s AND state = 'CONFIRMED'",
            (session_id,),
        )
        cursor.execute("DELETE FROM support_event WHERE turn_id = %s", (turn_id,))
        cursor.execute("DELETE FROM support_turn WHERE turn_id = %s", (turn_id,))


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

    # A repeat of the same idempotency key must replay the stored turn without reaching commerce.
    replay = json.loads(turn(entry, "confirm", "c1"))
    if replay != confirmed:
        raise SystemExit("confirmation replay did not return the stored turn")
    print("replay of the same key returned the identical turn")

    # A confirmation that was claimed but whose local commit never happened is the case the claim
    # state exists for. Put the reference back into CONFIRMING and confirm again with a fresh key:
    # this reaches commerce a second time, and commerce must replay its committed receipt rather
    # than issue a second refund.
    reclaim(entry["sessionId"])
    recovered = json.loads(turn(entry, "confirm", "c2-recovery"))
    print("recovery:", json.dumps(recovered, ensure_ascii=False))
    if recovered["outcome"] != "action_completed":
        raise SystemExit(f"recovery did not complete: {recovered['outcome']}")
    if recovered["receiptId"] != confirmed["receiptId"]:
        raise SystemExit("commerce issued a different receipt instead of replaying the first")
    print("a second confirmation replayed the same commerce receipt")

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
