# Agent three-path latency

Local measurement of the three paths a CityBuddy support turn can take: a plain answer, an answer
grounded in retrieved knowledge, and a refund preparation that writes a durable pending action.
Every number here was produced by the scripts in this directory against the real local topology,
and the raw tool output is in `../results/`.

## What is and is not being measured

The model provider is [`scripts/fake_litellm_server.py`](../../scripts/fake_litellm_server.py),
a deterministic fixture that answers immediately. **Inference time is therefore zero**, and these
numbers describe CityBuddy's own orchestration around the model: session lookup, RS256
verification, the on-behalf-of token exchange, Elasticsearch retrieval and reranking, the commerce
tool boundary, and the MySQL writes that make a turn durable. They are not end-to-end user
latency, and they are not a capacity claim — one machine, one process, one of everything.

That constraint is what makes the result useful: with inference held at zero, whatever remains is
the platform's own cost, and it turns out to be large enough to be the ceiling.

| | |
|---|---|
| Host | MacBook Pro M4, 10 cores, 24 GB |
| Docker Desktop | 14 GB / 8 CPU allocation |
| Agent | `agent-service` as a container, single uvicorn process, sync endpoints on the AnyIO worker pool |
| Dependencies | MySQL 8, Elasticsearch 8 + IK, `auth-service` and `commerce-service` as containers |
| Generator | k6 v2.2.0, inside the agent's network namespace |

## Method

The method follows [the seckill measurement](../README.md), with three additions forced by this
service's shape.

1. **Steps, not a continuous ramp.** Each rate is its own `constant-arrival-rate` scenario, so a
   percentile is read from a constant arrival rate rather than across a moving one.
2. **The generator is measured too.** Generator CPU is sampled throughout, and `maxVUs` is sized
   for the collapsed steps rather than the healthy ones — an open-model executor still needs a
   free VU to start an iteration, so a modest VU pool becomes the binding constraint once latency
   reaches tens of seconds, and that step's achieved rate would then describe the generator.
3. **Setup is excluded.** Users, paid orders, tokens and sessions are all created before the
   measured window.
4. **One user, one order and one session per iteration.** The pool is sized past the ladder's
   total iteration count and the generator fails loudly rather than wrapping. Sharing would not
   just add noise, it would change which path is measured: an order that already carries an
   outstanding prepared action answers the *next* preparation with a clarification instead of
   preparing again, and two turns on one session serialize on that conversation's row.
5. **Everything runs in one network namespace.** The agent binds `127.0.0.1`, so it is not
   reachable across a Docker network, and publishing a port does not help either — the forwarder
   connects to the container's bridge address, where nothing is listening. Rather than change
   production code or put a proxy hop inside the path being measured, the agent, the model
   fixture, the fixture builder and k6 all share one namespace and talk over loopback. This also
   keeps the Docker Desktop host-to-VM hop out of the measurement, which the seckill work found
   to be 77 % of observed latency when it is included.
6. **Each ladder starts from a rebuilt fixture on an otherwise idle host.** A path measured on a
   system still degraded by the previous path's overload reads very differently: one retrieval
   ladder run while the host was still tearing down a CI run reported 20 s at the 10 step, where
   three runs on an idle host all reported 292–296 ms. Setup stops the previous bench services
   before clearing the fixture, because a collapsed step can still have turns in flight and a
   turn that lands mid-teardown leaves rows behind.

## Results

### 1. The three paths, and where each one stops

Each path gets its own ladder over a freshly rebuilt fixture, one HTTP request per iteration,
real JWT verification, real retrieval, real MySQL writes (`../results/agent_*_steps.txt`).

**Plain chat turn** — one model call and the durable turn record:

| Target rate | Achieved/s | p50 | p95 | p99 | Outcomes |
|---:|---:|---:|---:|---:|---|
| 10 | 10.1 | 19.2 ms | 25.7 ms | 28.9 ms | all completed |
| 25 | 25.1 | 18.3 ms | 27.0 ms | 31.5 ms | all completed |
| 50 | 50.1 | 17.3 ms | 31.2 ms | **40.4 ms** | all completed |
| 75 | 59.4 | 2084.8 ms | 4829.4 ms | 5350.0 ms | 1109 completed, 276 HTTP 503 |
| 100 | 76.1 | 1958.5 ms | 5910.3 ms | 6387.6 ms | 929 completed, 837 HTTP 503 |

**Knowledge retrieval** — alias resolution, mapping validation, BM25 and dense retrieval, RRF
fusion, rerank, then the closing model call:

| Target rate | Achieved/s | p50 | p95 | p99 | Outcomes |
|---:|---:|---:|---:|---:|---|
| 2 | 2.0 | 127.5 ms | 180.3 ms | 206.2 ms | all completed |
| 5 | 5.0 | 119.9 ms | 153.0 ms | 172.5 ms | all completed |
| 10 | 10.0 | 135.6 ms | 201.1 ms | **292.5 ms** | all completed |
| 15 | 2.6 | 29899.7 ms | 41553.8 ms | 43278.5 ms | all completed, 30–44 s deep |
| 20 | — | — | — | — | no request finished inside the run window |

**Refund preparation** — a tool call, a just-in-time on-behalf-of token exchange, and a durable
`PendingAction` written through commerce:

| Target rate | Achieved/s | p50 | p95 | p99 | Outcomes |
|---:|---:|---:|---:|---:|---|
| 5 | 5.0 | 292.8 ms | 321.4 ms | 341.9 ms | 150 action_pending, 1 HTTP 502 |
| 10 | 10.0 | 307.0 ms | 370.7 ms | 391.2 ms | 301 action_pending |
| 15 | 14.9 | 415.6 ms | 626.3 ms | **700.6 ms** | 449 action_pending, 2 rejected |
| 20 | 16.9 | 3756.9 ms | 5710.4 ms | 6126.1 ms | 446 action_pending, 107 HTTP 503 |
| 30 | 17.0 | 10128.0 ms | 16606.8 ms | 17610.7 ms | 535 action_pending, 138 HTTP 503 |

Summarising the region each path holds without error:

| Path | Holds cleanly to | p99 there | Behaviour past it |
|---|---:|---:|---|
| Plain chat | 50 req/s | 40 ms | sheds as HTTP 503 |
| Knowledge retrieval | 10 req/s | 293 ms | queues to 30–44 s, goodput falls to 2.6/s |
| Refund preparation | 15 req/s | 701 ms | sheds as HTTP 503 at 20 |

Two things about the edges of that table. The chat knee sits between 50 and 75 but its exact
position moves between runs — an earlier identical ladder ran the 75 step clean at p99 51 ms —
because it is a threshold on a shared connection limit rather than a gradual saturation, so 50 is
the rate that held in every run. And retrieval's collapse is real rather than a generator
artifact: at the 15 step the VU pool would have permitted 10 req/s at the observed latency and
only 2.6 req/s finished, so offering half again as much work returned a quarter as much.

### 2. For two of the three paths the ceiling is the agent's own CPU

Peak CPU over each run (`../results/agent_*_cpu.txt`):

| | chat | retrieval | prepare |
|---|---:|---:|---:|
| agent | **675 %** | **691 %** | 193 % |
| commerce | 0.9 % | 2.0 % | 40 % |
| MySQL | 27 % | 13 % | 25 % |
| Elasticsearch | 2.4 % | 6.4 % | 2.8 % |
| k6 generator | 11.5 % | 7.5 % | 7.6 % |

Chat and retrieval put the agent process at 675–691 % — near 7 of the 8 cores Docker Desktop is
given — while nothing downstream is close to saturated. Preparation is the exception: its agent
cost is 193 % and its ceiling is on the commerce side, a different mechanism this measurement
does not resolve. The generator never exceeded 12 % of one core, so no percentile above describes
the generator.

### 3. Two thirds of that CPU builds TLS trust stores for plaintext HTTP

py-spy at 100 Hz for 25 s per path, while each path was driven at concurrency 8
(`../results/agent_cpu_profile.txt`):

| Path | Samples in `ssl.create_default_context` | Share of all agent CPU |
|---|---:|---:|
| Plain chat | 8,018 of 11,794 | **68.0 %** |
| Knowledge retrieval | 9,086 of 10,492 | **86.6 %** |
| Refund preparation | 776 of 2,012 | **38.6 %** |

The dominant stack is the same every time:

```
citybuddy_agent/agent_control.py:390   httpx.post(f"{self._url}/v1/chat/completions", ...)
  httpx/_api.py:102                    request()
    httpx/_client.py:688               Client.__init__
      httpx/_client.py:731             _init_transport
        httpx/_config.py:40            create_ssl_context
          ssl.py:770                   create_default_context
```

The agent reaches every dependency through the module-level `httpx` helpers — `httpx.post`,
`httpx.get`, `httpx.request` — at seven call sites, and holds **no reused `httpx.Client`
anywhere**. Each helper call constructs a whole client, and constructing a client constructs a
default SSL context, which loads and parses the system CA bundle. Measured in the same container:
**12.8 ms of CPU per construction**.

Every one of those URLs is `http://`. The model fixture, commerce, the auth service and
Elasticsearch are all plaintext in this deployment, so no TLS handshake ever follows the trust
store that was just built. The share tracks how many outbound calls a path makes, which is why
retrieval — alias, mapping, two BM25 queries, two dense queries, the reranker and two model calls
— spends the most, and why preparation, which spends most of its turn waiting on commerce, spends
the least.

### 4. The database connection limit is the system's only backpressure

The agent's conversation store opens a **fresh `pymysql.connect` per persistence call and pools
nothing** (`conversation.py:1205`, six call sites). Connections opened against MySQL per HTTP
request over a whole ladder:

| Path | HTTP requests | MySQL connections opened | Per request |
|---|---:|---:|---:|
| Plain chat | 4,854 | 20,961 | 4.32 |
| Knowledge retrieval | 667 | 5,092 | 7.63 |
| Refund preparation | 2,131 | 12,879 | 6.04 |

At the default `max_connections = 151` the chat ladder reaches 152 concurrent connections and
MySQL rejects 1,169 attempts, which the agent surfaces as
`ACTION_SESSION_PERSISTENCE_UNAVAILABLE` and HTTP 503. Raising the limit to 1000 and repeating
the identical ladder isolates what that limit was doing
(`../results/agent_chat_control_steps.txt`):

| Target rate | | `max_connections=151` | `max_connections=1000` |
|---:|---|---:|---:|
| 50 | achieved/s | 50.1 | 50.1 |
| | p99 | 40.4 ms | 36.7 ms |
| 75 | achieved/s | 59.4 | **75.0** |
| | p99 | 5350.0 ms | **58.9 ms** |
| | HTTP 503 | 276 | 0 |
| 100 | turns completed | 929 | **1,445** |
| | p99 | 6387.6 ms | 17224.2 ms |
| | HTTP 503 | 837 | 1 |

The limit was holding the chat path below its actual capacity: at 75 req/s the server can serve
every request at a 59 ms p99, and with the default limit it instead refused 276 of them and
served the rest at 5.3 s. Raising it moves the knee from between 50 and 75 up to between 75 and
100.

It does not remove the ceiling, it changes how the ceiling is expressed. At 100 req/s the raised
limit completes 56 % more turns and almost no errors, but takes 11–17 s per turn, because nothing
else in the system applies backpressure — the agent has no concurrency bound of its own, so once
the database stops refusing work, overload turns from fast failure into unbounded queueing. Agent
CPU is 675 % in the first column and 665 % in the second: the ceiling did not move.

## Two defects found while building the fixture

**The mock-payment attempt lookup is a locking full table scan.**
`../results/mock_payment_callback_deadlock.txt` has `EXPLAIN` showing `type: ALL, key: NULL` for

```sql
SELECT ... FROM mock_payment_attempt
 WHERE attempt_id = ? OR callback_correlation_id = ? OR order_id = ? LIMIT 2 FOR UPDATE
```

even though all three columns are uniquely indexed — the three-way `OR` disqualifies every index.
The payment-start lookup scans too, because `uq_mock_payment_order` is `(order_kind, order_id)`
and the predicate names only `order_id`. Under `FOR UPDATE` each scan takes an exclusive lock on
every row it examines, so settling a payment for one order locks every other user's payment
attempt. Two concurrent settlements for unrelated orders deadlock, and it surfaces as an
unhandled HTTP 500 rather than a retryable status. The fixture builder settles payments serially
to avoid it deterministically rather than retrying through it.

**The default attempt budget cannot fit a successful retrieval turn.** With
`AGENT_ATTEMPT_BUDGET=8`, a retrieval turn spends all eight attempts on alias resolution, mapping
validation, two BM25 queries, two dense queries, the reranker and the opening model call — so
retrieval *succeeds*, and the turn then ends `budget_exhausted` with no attempt left for the
closing model call. The count follows from `knowledge.py`, where `search` resolves the alias,
validates the mapping, and then runs one BM25 and one dense query per query text, the rewrite
included. This bench runs at 16.

## Reproducing

```bash
make init-local && make up
```

```bash
BENCH_USERS=25000 ./bench/setup_bench_env.sh
```

The agent fixture reuses the users, product and signing key that script creates.

```bash
AGENT_BENCH_USERS=6000 ./bench/agent/setup_agent_bench.sh
```

```bash
RATES=10,25,50,75,100 STEP_SECONDS=20 ./bench/agent/run_agent_ladder.sh chat
```

```bash
uv run python bench/agent/analyze_agent_ladder.py bench/results/agent_chat_points.json chat
```

Rerun `setup_agent_bench.sh` before each path. `AGENT_BENCH_USERS` must exceed the ladder's total
iteration count, and the prepare ladder refuses to start if the fixture still holds prepared
actions, because it would otherwise measure the clarification path and still report a clean run.

`LABEL` names the output files, so a control run against a changed setting does not overwrite the
baseline it is meant to be compared with:

```bash
LABEL=chat_control RATES=10,25,50,75,100 ./bench/agent/run_agent_ladder.sh chat
```
