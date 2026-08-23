# The 90-second demonstration

CityBuddy exists to hold one line: an LLM agent may read business data and *prepare* a sensitive
action, but it never becomes the authority on whether the transaction happened. This demonstration
walks that line from both sides in about ninety seconds — the agent answering with evidence, the
model claiming a refund that never happened, and a real refund that only completes because a human
confirmed it and commerce committed it.

## Bring the stack up

Requires the local topology (`make init-local && make up`) and both service jars
(`./mvnw -pl auth-service,commerce-service package -DskipTests`).

```bash
make demo
```

That seeds a demonstration identity, product and FAQ set; starts auth-service and
commerce-service as containers on the compose network; starts the model fixture and agent-service
on the host; and prints the login it created. It takes a couple of minutes, and none of it is part
of the ninety seconds.

Two services run on the host rather than in a container because agent-service binds `127.0.0.1`,
so a published port would not reach it and the browser has to. The ports are the ones
`web/.env.example` already proxies to, so the web surface needs no configuration beyond
`cp web/.env.example web/.env.local`.

## The terminal run

```bash
make demo-story
```

Six beats, each one asserting something and then reading the answer back out of the authoritative
database rather than believing the HTTP response that produced it.

| | Beat | What it proves |
|---|---|---|
| 1 | A real order, paid through the real endpoints | Refund preparation verifies durable payment truth. A hand-written `PAID` row is rejected as `ACTION_PREPARATION_DURABLE_TRUTH_INCONSISTENT`, so the fixture has to buy and pay like anyone else. |
| 2 | The agent answers from the knowledge base | Retrieval is a decision with a persisted record — sufficiency outcome, calibration version, candidate and evidence counts — not a hidden step inside a prompt. |
| 3 | The model claims the refund already happened | The JSON path passes the sentence through with `outcome=completed` and no receipt, so no client can render a success state from it. The SSE path refuses to tokenise it at all and fails the turn with `unsafe_output`. Commerce still holds zero refunds. |
| 4 | The agent prepares the refund | Preparation writes a `PendingAction` in commerce and stops. The turn carries `action_pending` and a null receipt. |
| 5 | The user confirms | The agent claims the action, commerce executes the refund, and the agent projects an `ActionReceipt`. The receipt is the only thing that lets a client render a success state. |
| 6 | Confirming again does not refund again | The same idempotency key replays the stored turn byte for byte; a fresh confirmation finds a `CONSUMED` action and nothing to confirm. Exactly one refund exists. |

`--pace 0` runs the same thing with no pauses, in about a second, which is the form to use when
checking that the flow still works rather than watching it.

## The browser run

```bash
npm --prefix web run dev
```

Log in at <http://localhost:5173> with the credentials `make demo` printed, then:

1. **Support → 消息或澄清说明.** Send `retrieval-sufficient 退款政策是怎样的`. The reply renders
   with its citations underneath — title, document type and source version for each.
2. Send `unsafe-action-claim 我的退款到账了吗` with **流式回复** ticked. The turn ends in an
   error notice instead of the model's sentence — the client is told the stream failed, not what
   the model wanted to say. Untick it and send again: now the sentence appears, and no success
   state appears with it.
3. Send `action-prepare 我要退款，订单 <order id>`. A **BOUNDARY NOTICE** card appears —
   敏感动作等待处理 — with 确认此动作 and 拒绝此动作. Nothing has executed.
4. Press **确认此动作**. The card becomes 敏感动作已提交 and shows the receipt identifier. The
   copy says the refund *request* was recorded, because that is what happened.

The order identifier for step 3 comes from the terminal run, or from
`uv run python scripts/demo_story.py --pace 0` run once beforehand.

## What is real here and what is a fixture

- **There is no model-provider access.** `scripts/fake_litellm_server.py` answers the completion
  API deterministically, and the scenario is selected by a keyword in the message — which is why
  the demonstration messages start with `retrieval-sufficient`, `unsafe-action-claim` or
  `action-prepare`. Everything the demonstration is about happens after that response arrives.
- **The payment and refund providers are mocked**, and deliberately: `result_state=REQUESTED` on
  the receipt means the refund request is durably recorded and owned by commerce, not that money
  moved. `refunded_amount_minor` stays 0 until a settlement that this repository does not have.
- **Everything else is real.** MySQL holds the order, the payment, the `PendingAction` and the
  `ActionReceipt`; Elasticsearch holds the FAQ documents and answers a real hybrid query;
  auth-service mints and exchanges real RS256 tokens; the OBO token bound to that one tool call is
  what commerce checks before it will prepare anything.

Two local details worth knowing. The demonstration owns the auth signing metadata while it is up,
because every published `kid` has to resolve to a configured runtime key and a leftover row from
another fixture makes the whole JWKS document fail. And the agent runs with an attempt budget of
16: a retrieval turn charges the default budget of 8 before the model is ever asked to compose the
answer, so every retrieval turn would otherwise end `budget_exhausted`.

## Stop

```bash
make demo-stop
```

The data topology and the seeded fixture are left alone, so the next `make demo` is quick.
