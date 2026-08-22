"""Drives one agent path at a fixed concurrency, for profiling rather than for latency numbers."""

import concurrent.futures
import json
import sys
import time
import urllib.request
import uuid

pool = json.load(open("/run-data/agent_pool.json"))
message, concurrency, count = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
if count > len(pool):
    raise SystemExit(f"pool holds {len(pool)} entries, which is fewer than the {count} requested")


def turn(index: int) -> bool:
    # No wrapping: a reused entry would measure a different path — for a preparation, the order
    # already carries a prepared action and answers with a clarification instead.
    entry = pool[index]
    body = message.replace("ORDER", entry["orderId"])
    request = urllib.request.Request(
        "http://127.0.0.1:8001/api/chat",
        data=json.dumps({"message": body}).encode(),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {entry['token']}",
            "X-Session-Id": entry["sessionId"],
            "Idempotency-Key": f"profile-{uuid.uuid4()}",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=120):
            pass
        return True
    except Exception:
        return False


started = time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
    answered = sum(executor.map(turn, range(count)))
elapsed = time.perf_counter() - started
print(f"drove {count} turns at concurrency {concurrency} in {elapsed:.1f}s, {answered} answered")
# A load where nothing succeeds still keeps the process busy failing, and the profile would then
# describe the failure path while reading like a measurement of the real one.
if answered == 0:
    raise SystemExit("no turn was answered; the fixture or the agent is not usable")
