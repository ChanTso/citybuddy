"""Drives the flagship CityBuddy flow end to end against the running demonstration stack.

Every claim printed here is read back from the authoritative database rather than from the HTTP
response that produced it, because the whole point of the flow is that the response text is not
the authority on what happened.

Run scripts/demo.sh first; it writes the configuration this reads.
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import pathlib
import time
import urllib.error
import urllib.request
import uuid

import pymysql

RUN_DIR = pathlib.Path(__file__).resolve().parent.parent / ".citybuddy-demo"
CALLBACK_EVENT_NAMESPACE = uuid.UUID("6f9619ff-8b86-d011-b42d-00c04fc964ff")
RULE = "─" * 78

pace_seconds = 0.0


def load_configuration() -> dict[str, str]:
    path = RUN_DIR / "demo.env"
    if not path.exists():
        raise SystemExit("demo configuration is missing; run ./scripts/demo.sh first")
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line:
            name, _, value = line.partition("=")
            values[name] = value
    return values


def beat(number: int, title: str, claim: str) -> None:
    print()
    print(RULE)
    print(f"  {number}. {title}")
    print(f"     {claim}")
    print(RULE)
    # A heading introduces a claim, so it holds longer than the lines that then evidence it.
    time.sleep(pace_seconds * 2)


def say(line: str) -> None:
    print(f"  {line}")
    time.sleep(pace_seconds)


def post(url: str, body: dict[str, object], headers: dict[str, str]) -> dict[str, object]:
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", **headers},
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = response.read()
        return dict(json.loads(payload)) if payload else {}
    except urllib.error.HTTPError as error:
        # The status alone rarely says which precondition failed; the body names it.
        raise SystemExit(f"{url} -> HTTP {error.code}: {error.read().decode()}") from error


class Database:
    def __init__(self, configuration: dict[str, str]) -> None:
        self._configuration = configuration

    def rows(self, schema: str, statement: str, *parameters: object) -> list[tuple[object, ...]]:
        connection = pymysql.connect(
            host="127.0.0.1",
            port=int(self._configuration["CITYBUDDY_DEMO_MYSQL_PORT"]),
            user="root",
            password=self._configuration["CITYBUDDY_DEMO_MYSQL_ROOT_PASSWORD"],
            database=schema,
            autocommit=True,
        )
        with connection, connection.cursor() as cursor:
            cursor.execute(statement, parameters)
            return list(cursor.fetchall())


class Client:
    def __init__(self, configuration: dict[str, str]) -> None:
        self._configuration = configuration
        self.token = ""
        self.session_id = ""
        self.order_id = ""

    def _url(self, service: str, path: str) -> str:
        return f"{self._configuration[f'CITYBUDDY_DEMO_{service}_URL']}{path}"

    def log_in(self) -> None:
        self.token = str(
            post(
                self._url("AUTH", "/auth/login"),
                {
                    "loginIdentifier": self._configuration["CITYBUDDY_DEMO_SUBJECT"],
                    "password": self._configuration["CITYBUDDY_DEMO_PASSWORD"],
                },
                {},
            )["accessToken"]
        )

    def buy_and_pay(self) -> None:
        """Creates and settles one order through the public endpoints.

        Not written straight into MySQL: preparing a refund verifies durable payment truth, and a
        hand-inserted PAID row has no payment attempt, callback or ledger behind it and is
        rejected as ACTION_PREPARATION_DURABLE_TRUTH_INCONSISTENT.
        """
        amount = int(self._configuration["CITYBUDDY_DEMO_PRICE_MINOR"])
        key = uuid.uuid4().hex
        auth = {"Authorization": f"Bearer {self.token}"}
        self.order_id = str(
            post(
                self._url("COMMERCE", "/api/orders"),
                {
                    "productId": self._configuration["CITYBUDDY_DEMO_PRODUCT"],
                    "quantity": 1,
                    "expectedProductVersion": 1,
                },
                {**auth, "Idempotency-Key": f"demo-order-{key}"},
            )["orderId"]
        )
        attempt = post(
            self._url("COMMERCE", f"/api/orders/{self.order_id}/mock-payment"),
            {"amountMinor": amount, "currency": "CNY"},
            {**auth, "Idempotency-Key": f"demo-pay-{key}"},
        )
        event_id = str(uuid.uuid5(CALLBACK_EVENT_NAMESPACE, key))
        correlation_id = str(attempt["callbackCorrelationId"])
        idempotency_key = f"demo-callback-{key}"
        # Epoch seconds: the authenticator rejects a timestamp that is not all digits, so an
        # ISO-8601 instant fails the freshness check with 401 before the signature is compared.
        timestamp = str(int(time.time()))
        key_id = self._configuration["CITYBUDDY_DEMO_PAYMENT_KEY_ID"]
        canonical = "\n".join(
            [
                key_id,
                timestamp,
                idempotency_key,
                event_id,
                correlation_id,
                self.order_id,
                str(amount),
                "CNY",
                "SUCCEEDED",
                "",
                "",
                "",
                "",
            ]
        ).encode("utf-8")
        post(
            self._url("COMMERCE", "/internal/mock-payments/callback"),
            {
                "callbackEventId": event_id,
                "callbackCorrelationId": correlation_id,
                "orderId": self.order_id,
                "amountMinor": amount,
                "currency": "CNY",
                "outcome": "SUCCEEDED",
            },
            {
                "X-Mock-Payment-Key-Id": key_id,
                "X-Mock-Payment-Timestamp": timestamp,
                "X-Mock-Payment-Signature": hmac.new(
                    self._configuration["CITYBUDDY_DEMO_PAYMENT_SECRET"].encode("utf-8"),
                    canonical,
                    hashlib.sha256,
                ).hexdigest(),
                "Idempotency-Key": idempotency_key,
            },
        )

    def open_session(self) -> None:
        self.session_id = str(
            post(
                self._url("AGENT", "/api/sessions"), {}, {"Authorization": f"Bearer {self.token}"}
            )["sessionId"]
        )

    def _headers(self, key: str) -> dict[str, str]:
        return {
            "Authorization": f"Bearer {self.token}",
            "X-Session-Id": self.session_id,
            "Idempotency-Key": key,
        }

    def say_to_agent(self, message: str, key: str) -> dict[str, object]:
        return post(self._url("AGENT", "/api/chat"), {"message": message}, self._headers(key))

    def stream_to_agent(self, message: str, key: str) -> str:
        request = urllib.request.Request(
            self._url("AGENT", "/api/chat/stream"),
            data=json.dumps({"message": message}).encode("utf-8"),
            headers={"Content-Type": "application/json", **self._headers(key)},
        )
        with urllib.request.urlopen(request, timeout=30) as response:
            body: bytes = response.read()
        return body.decode("utf-8")


def main() -> None:
    global pace_seconds
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--pace",
        type=float,
        default=2.2,
        help="seconds to hold on each line; the default paces the whole run to about 90 seconds",
    )
    arguments = parser.parse_args()
    pace_seconds = max(arguments.pace, 0.0)

    configuration = load_configuration()
    database = Database(configuration)
    client = Client(configuration)
    subject = configuration["CITYBUDDY_DEMO_SUBJECT"]
    started = time.perf_counter()

    beat(
        1,
        "A real order, paid through the real endpoints",
        "Business truth comes from commerce, not from anything the agent says later.",
    )
    client.log_in()
    client.buy_and_pay()
    client.open_session()
    state, total_minor = database.rows(
        "commerce_db",
        "SELECT status, total_price_minor FROM standard_order WHERE order_id = %s",
        client.order_id,
    )[0]
    say(f"order      {client.order_id}")
    say(f"MySQL      status={state}, {total_minor} minor units paid")
    say(f"session    {client.session_id} for {subject}")

    beat(
        2,
        "The agent answers from the knowledge base, and shows its evidence",
        "Retrieval is a decision with a persisted record, not a hidden step.",
    )
    answer = client.say_to_agent("retrieval-sufficient 退款政策是怎样的", "demo-retrieval")
    say(f"outcome    {answer['outcome']}")
    say(f"reply      {answer['reply']}")
    citations = answer["citations"]
    if not isinstance(citations, list):
        raise SystemExit("the answer carried no citation list")
    for citation in citations:
        say(f"citation   {citation['title']} · {citation['docType']} v{citation['sourceVersion']}")
    outcome, reason, evidence = database.rows(
        "cs_db",
        "SELECT sufficiency_outcome, reason_code, evidence_count FROM retrieval_decision "
        "WHERE session_id = %s ORDER BY created_at DESC LIMIT 1",
        client.session_id,
    )[0]
    say(f"MySQL      {outcome} ({reason}) on {evidence} pieces of evidence")

    beat(
        3,
        "The model claims the refund already happened",
        "Prose is not a state. Saying it happened does not make it true anywhere that counts.",
    )
    say('the model answers  "Your refund has been issued."')
    claimed = client.say_to_agent("unsafe-action-claim 我的退款到账了吗", "demo-unsafe")
    say(f"JSON path  outcome={claimed['outcome']} receiptId={claimed['receiptId']}")
    say(f"           the sentence is passed through: {claimed['reply']!r}")
    say("           it carries no action state and no receipt, so no client can render one")
    stream = client.stream_to_agent("unsafe-action-claim 我的退款到账了吗", "demo-unsafe-stream")
    emitted = " ".join(line for line in stream.splitlines() if line)
    say(f"SSE  path  {emitted}")
    say("           the egress filter refuses the claim outright rather than tokenising it")
    refunds = database.rows(
        "commerce_db",
        "SELECT COUNT(*) FROM mock_refund WHERE order_id = %s",
        client.order_id,
    )[0][0]
    say(f"MySQL      {refunds} refunds exist for this order")

    beat(
        4,
        "The agent prepares the refund. It cannot execute it.",
        "Preparation writes a PendingAction in commerce and stops there.",
    )
    prepared = client.say_to_agent(
        f"action-prepare 我要退款，订单 {client.order_id}", "demo-prepare"
    )
    say(f"outcome    {prepared['outcome']}")
    say(f"reply      {prepared['reply']}")
    say(f"receiptId  {prepared['receiptId']}")
    action_id, action_state = database.rows(
        "commerce_db",
        "SELECT pending_action_id, state FROM pending_action WHERE order_id = %s "
        "ORDER BY created_at DESC LIMIT 1",
        client.order_id,
    )[0]
    say(f"MySQL      pending action {action_id} is {action_state}")

    beat(
        5,
        "The user confirms. Commerce executes, and the agent projects the receipt.",
        "The receipt is the only thing that lets a client render a success state.",
    )
    confirmed = client.say_to_agent("confirm", "demo-confirm")
    say(f"outcome    {confirmed['outcome']}")
    say(f"reply      {confirmed['reply']}")
    say(f"receiptId  {confirmed['receiptId']}")
    receipt_state, refund_id = database.rows(
        "commerce_db",
        "SELECT result_state, refund_id FROM action_receipt WHERE receipt_id = %s",
        confirmed["receiptId"],
    )[0]
    say(f"MySQL      receipt {receipt_state}, refund {refund_id}")
    consumed = database.rows(
        "commerce_db",
        "SELECT state FROM pending_action WHERE pending_action_id = %s",
        action_id,
    )[0][0]
    say(f"MySQL      pending action is now {consumed}")

    beat(
        6,
        "Confirming again does not refund again",
        "One prepared action can produce one refund, however many times it is confirmed.",
    )
    replay = client.say_to_agent("confirm", "demo-confirm")
    say(f"same key   replayed the stored turn byte for byte: {replay == confirmed}")
    again = client.say_to_agent("confirm", "demo-confirm-again")
    say(f"fresh key  outcome={again['outcome']} receiptId={again['receiptId']}")
    say("           the action is CONSUMED, so a second confirmation has nothing to confirm")
    total = database.rows(
        "commerce_db",
        "SELECT COUNT(*) FROM mock_refund WHERE order_id = %s",
        client.order_id,
    )[0][0]
    say(f"MySQL      {total} refund exists for this order")
    if total != 1:
        raise SystemExit(f"expected exactly one refund, found {total}")

    print()
    print(RULE)
    print(f"  ran in {time.perf_counter() - started:.0f}s")
    print(RULE)


if __name__ == "__main__":
    main()
