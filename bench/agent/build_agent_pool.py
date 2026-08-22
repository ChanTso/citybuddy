"""Builds the agent bench fixture pool: tokens, one paid order per user, and open sessions.

Every entry the generator will use is created here, before measurement starts. A pool entry
carries a token, the paid order that token's user owns, and an already-open support session, so a
measured iteration is exactly one POST /api/chat and nothing else.

One user, one order and one session per entry, with the pool sized past the ladder's total
iteration count so that nothing is reused. Sharing would not merely add noise, it would change
which path is measured: an order that already carries an outstanding prepared action answers the
next preparation with a clarification instead of preparing again, and two turns on one session
serialize on that conversation's row.

The order is paid through the real endpoints rather than written into MySQL: the action path
verifies durable payment truth, and a hand-inserted PAID row has no payment attempt, callback or
ledger behind it and is rejected as ACTION_PREPARATION_DURABLE_TRUTH_INCONSISTENT.

Logins and order creation run in parallel, but payment settlement is serialized. Both
mock-payment attempt lookups are locking full table scans, so two concurrent settlements for
unrelated orders exclusively lock the same rows and deadlock; the evidence is in
bench/results/mock_payment_callback_deadlock.txt. Serializing this phase avoids the defect
deterministically rather than retrying through it, which would hide it.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import hmac
import json
import time
import urllib.error
import urllib.request
import uuid

ORDER_AMOUNT_MINOR = 1990
RETRYABLE_STATUSES = frozenset({429, 503})
RETRY_ATTEMPTS = 20
# Every fixture order is for the same product, so order creation contends on that one product row
# and answers 429 once the contention is heavy enough that the outcome is indeterminate. Setup is
# excluded from measurement, so the fixture trades width for a clean build rather than pushing the
# contention up and retrying through it.
ORDER_WORKERS = 4
CALLBACK_EVENT_NAMESPACE = uuid.UUID("6f9619ff-8b86-d011-b42d-00c04fc964ff")


def post(url: str, body: dict[str, object], headers: dict[str, str]) -> dict[str, object]:
    # Under a parallel setup burst the commerce endpoints answer 429 CONCURRENCY_EXHAUSTED or
    # 503 UNAVAILABLE and ask for the same idempotency key to be retried. Both are retryable by
    # contract, and every request here carries an idempotency key, so a bounded retry resolves
    # the same intent rather than creating a second one.
    for attempt in range(RETRY_ATTEMPTS):
        request = urllib.request.Request(
            url,
            data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json", **headers},
        )
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                payload = response.read()
            return json.loads(payload) if payload else {}
        except urllib.error.HTTPError as error:
            detail = error.read().decode()
            if error.code in RETRYABLE_STATUSES and attempt < RETRY_ATTEMPTS - 1:
                time.sleep(min(0.1 * (attempt + 1), 1.0))
                continue
            # The status alone rarely says which precondition failed; the body names it.
            raise RuntimeError(f"{url} -> HTTP {error.code}: {detail}") from error
    raise RuntimeError(f"{url} -> bounded retry did not terminate")


def signature(secret: str, fields: list[str]) -> str:
    canonical = "\n".join(fields).encode("utf-8")
    return hmac.new(secret.encode("utf-8"), canonical, hashlib.sha256).hexdigest()


def create_order(index: int, args: argparse.Namespace) -> dict[str, str]:
    subject = f"bench-user-{index}"
    token = str(
        post(
            f"{args.auth_url}/auth/login",
            {"loginIdentifier": subject, "password": args.password},
            {},
        )["accessToken"]
    )
    order_id = str(
        post(
            f"{args.commerce_url}/api/orders",
            {"productId": "bench-product", "quantity": 1, "expectedProductVersion": 1},
            {"Authorization": f"Bearer {token}", "Idempotency-Key": f"bench-order-{subject}"},
        )["orderId"]
    )
    return {"subject": subject, "token": token, "orderId": order_id}


def settle_payment(entry: dict[str, str], args: argparse.Namespace) -> None:
    subject, order_id = entry["subject"], entry["orderId"]
    auth = {"Authorization": f"Bearer {entry['token']}"}
    attempt = post(
        f"{args.commerce_url}/api/orders/{order_id}/mock-payment",
        {"amountMinor": ORDER_AMOUNT_MINOR, "currency": "CNY"},
        {**auth, "Idempotency-Key": f"bench-pay-{subject}"},
    )

    # Derived from the subject rather than random: the callback idempotency key is per subject and
    # the stored intent covers the event id, so a rerun that generated a fresh one would conflict
    # with its own earlier callback instead of replaying it.
    event_id = str(uuid.uuid5(CALLBACK_EVENT_NAMESPACE, subject))
    # The correlation id is issued by the payment start and binds the callback to that attempt;
    # a freshly generated one is rejected with 409 rather than settling the payment.
    correlation_id = str(attempt["callbackCorrelationId"])
    idempotency_key = f"bench-callback-{subject}"
    # Epoch seconds: the authenticator rejects a timestamp that is not all digits, so an
    # ISO-8601 instant fails the freshness check with 401 before the signature is compared.
    timestamp = str(int(time.time()))
    post(
        f"{args.commerce_url}/internal/mock-payments/callback",
        {
            "callbackEventId": event_id,
            "callbackCorrelationId": correlation_id,
            "orderId": order_id,
            "amountMinor": ORDER_AMOUNT_MINOR,
            "currency": "CNY",
            "outcome": "SUCCEEDED",
        },
        {
            "X-Mock-Payment-Key-Id": args.payment_key_id,
            "X-Mock-Payment-Timestamp": timestamp,
            "X-Mock-Payment-Signature": signature(
                args.payment_secret,
                [
                    args.payment_key_id,
                    timestamp,
                    idempotency_key,
                    event_id,
                    correlation_id,
                    order_id,
                    str(ORDER_AMOUNT_MINOR),
                    "CNY",
                    "SUCCEEDED",
                    "",
                    "",
                    "",
                    "",
                ],
            ),
            "Idempotency-Key": idempotency_key,
        },
    )


def open_session(entry: dict[str, str], agent_url: str) -> dict[str, str]:
    session_id = str(
        post(f"{agent_url}/api/sessions", {}, {"Authorization": f"Bearer {entry['token']}"})[
            "sessionId"
        ]
    )
    return {"token": entry["token"], "orderId": entry["orderId"], "sessionId": session_id}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--users", type=int, required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--auth-url", required=True)
    parser.add_argument("--commerce-url", required=True)
    parser.add_argument("--agent-url", required=True)
    parser.add_argument("--payment-key-id", required=True)
    parser.add_argument("--payment-secret", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=ORDER_WORKERS) as pool:
        users = list(pool.map(lambda i: create_order(i, args), range(args.users)))
    print(f"logged in and created {len(users)} orders in {time.perf_counter() - started:.1f}s")

    started = time.perf_counter()
    for entry in users:
        settle_payment(entry, args)
    print(f"settled {len(users)} payments in {time.perf_counter() - started:.1f}s")

    # One session per user, so a token and the session it is used with always belong to the same
    # subject; a mismatch is rejected as a conversation ownership error rather than measured.
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
        entries = list(pool.map(lambda user: open_session(user, args.agent_url), users))
    print(f"opened {len(entries)} sessions in {time.perf_counter() - started:.1f}s")

    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(entries, handle)
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
