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
the platform's own cost.

| | |
|---|---|
| Host | MacBook Pro M4, 10 cores, 24 GB |
| Docker Desktop | 14 GB / 8 CPU allocation |
| Agent | `agent-service` as a container, single uvicorn process, sync endpoints on the AnyIO worker pool |
| Dependencies | MySQL 8, Elasticsearch 8 + IK, `auth-service` and `commerce-service` as containers |
| Generator | k6, `grafana/k6:latest` at run time, inside the agent's network namespace |

## Method

The method follows [the seckill measurement](../README.md), with additions forced by this
service's shape.

1. **Steps, not a continuous ramp.** Each rate is its own `constant-arrival-rate` scenario, so a
   percentile is read from a constant arrival rate rather than across a moving one.
2. **The generator is measured too, and every step reports what it could not do.** Generator CPU
   is sampled throughout. An open-model executor still needs a free VU to start an iteration, so
   once latency reaches tens of seconds the VU pool becomes the binding constraint and that step's
   rate would describe the generator; `maxVUs` is sized for the collapsed steps rather than the
   healthy ones, and every row prints offered alongside measured so a step where the generator
   could not keep up is visible rather than inferred.
3. **Setup is excluded.** Users, paid orders, tokens and sessions are all created before the
   measured window.
4. **One user, one order and one session per iteration.** Sharing would not just add noise, it
   would change which path is measured: an order that already carries an outstanding prepared
   action answers the *next* preparation with a clarification instead of preparing again, and two
   turns on one session serialize on that conversation's row. The pool must therefore be larger
   than `sum(rate x step_seconds) + 20 per step`, and exhausting it aborts the run through a
   threshold — a thrown k6 iteration on its own does not fail a run, and the runner also checks
   k6's exit code, because a run that dies during init would otherwise leave a results file that
   reads clean.
5. **Everything runs in one network namespace.** The agent binds `127.0.0.1`, so it is not
   reachable across a Docker network, and publishing a port does not help either — the forwarder
   connects to the container's bridge address, where nothing is listening. Rather than change
   production code or put a proxy hop inside the path being measured, the agent, the model
   fixture, the fixture builder and k6 all share one namespace and talk over loopback. This also
   keeps the Docker Desktop host-to-VM hop out of the measurement, which the seckill work found
   to be 77 % of observed latency when it is included.
6. **Steps are isolated from each other.** A collapsed step keeps completing requests long past
   its own window, so the gap between steps has to exceed k6's graceful stop or the next step's
   percentiles are taken on top of the previous step's backlog. The gap defaults to 55 s against a
   45 s graceful stop.
7. **Each ladder starts from a rebuilt fixture on an otherwise idle host.** A path measured on a
   system still busy with something else reads very differently, so the host has to be quiet.
   Setup stops the previous bench services before clearing the fixture, because a collapsed step
   can still have turns in flight and a turn that lands mid-teardown leaves rows behind.

## Results

### 1. What each path serves, and where it stops

Each path gets its own ladder over a freshly rebuilt fixture, one HTTP request per iteration,
real JWT verification, real retrieval, real MySQL writes (`../results/agent_*_steps.txt`).
`offered` is what the constant arrival rate asked for and `measured` is what produced a timing;
where they diverge, k6 could not start the iteration or it was still in flight when the step's
graceful stop expired, and the row is not a throughput measurement of the server. The percentiles
are over every request the step measured, a rejection included, so on a step that sheds they
describe the mix rather than the latency of a served turn — another reason to read the collapsed
rows as a shape and not as a number.

**Plain chat turn** — one model call and the durable turn record:

| Target | Offered | Measured | Served/s | p50 | p95 | p99 | Outcomes |
|---:|---:|---:|---:|---:|---:|---:|---|
| 10 | 200 | 201 | 10.1 | 22.0 ms | 30.6 ms | 32.2 ms | all completed |
| 25 | 500 | 501 | 25.1 | 20.3 ms | 30.6 ms | 38.6 ms | all completed |
| 50 | 1000 | 1000 | 50.0 | 18.3 ms | 30.9 ms | **36.5 ms** | all completed |
| 75 | 1500 | 1482 | 74.1 | 828.6 ms | 2126.1 ms | 2489.2 ms | 937 completed, 545 HTTP 503 |
| 100 | 2000 | 1914 | 95.7 | 1074.4 ms | 3338.1 ms | 3880.6 ms | 959 completed, 955 HTTP 503 |

**Knowledge retrieval** — alias resolution, mapping validation, BM25 and dense retrieval, RRF
fusion, rerank, then the closing model call:

| Target | Offered | Measured | Served/s | p50 | p95 | p99 | Outcomes |
|---:|---:|---:|---:|---:|---:|---:|---|
| 2 | 60 | 61 | 2.0 | 119.6 ms | 138.9 ms | 143.1 ms | all completed |
| 5 | 150 | 151 | 5.0 | 120.4 ms | 140.0 ms | 147.9 ms | all completed |
| 8 | 240 | 241 | 8.0 | 118.3 ms | 146.0 ms | **171.9 ms** | all completed |
| 10 | 300 | 301 | 10.0 | 133.0 ms | 196.9 ms | 236.9 ms | all completed |
| 12 | 360 | 254 | 8.5 | 20809.3 ms | 28402.7 ms | 29063.7 ms | 254 of 360 measured |

**Refund preparation** — a tool call, a just-in-time on-behalf-of token exchange, and a durable
`PendingAction` written through commerce:

| Target | Offered | Measured | Served/s | p50 | p95 | p99 | Outcomes |
|---:|---:|---:|---:|---:|---:|---:|---|
| 5 | 150 | 151 | 5.0 | 294.5 ms | 320.3 ms | 344.7 ms | 151 action_pending |
| 10 | 300 | 301 | 10.0 | 309.5 ms | 370.5 ms | **380.8 ms** | 301 action_pending |
| 15 | 450 | 451 | 15.0 | 414.3 ms | 675.0 ms | 832.6 ms | 450 action_pending, 1 HTTP 429 |
| 20 | 600 | 553 | 18.4 | 3826.1 ms | 5680.4 ms | 6276.3 ms | 466 action_pending, 87 HTTP 503 |
| 30 | 900 | 671 | 22.4 | 10577.1 ms | 16376.5 ms | 17416.0 ms | 541 action_pending, 130 HTTP 503 |

The rate each path serves with every request measured and no error:

| Path | Serves | p99 there |
|---|---:|---:|
| Plain chat | 50 req/s | 36.5 ms |
| Knowledge retrieval | 10 req/s | 236.9 ms |
| Refund preparation | 10 req/s | 380.8 ms |

**The knee is sharp, and the same rate can land on either side of it.** Two retrieval ladders are
committed here: `../results/agent_retrieval_repeat_steps.txt` serves 10 req/s at p99 237 ms, and
`../results/agent_retrieval_steps.txt` — same fixture and same script, a different run and a
different ladder around it — collapses at that same 10 req/s to a 16.6 s p50 and measures only 223
of 300. The second is kept precisely
because it is the counterexample. So the rates above are the ones that held, not rates that always
hold, and a step past the knee shows what collapse looks like rather than a capacity number.

### 2. At the rate each path serves, nothing is saturated

Peak CPU over a whole ladder is dominated by whichever step collapsed, so it says nothing about
what serving the load costs. The runner reports each step's own window instead
(`../results/agent_chat_cpu_by_step.txt`, `agent_retrieval_repeat_cpu_by_step.txt`,
`agent_prepare_cpu_by_step.txt`), median agent CPU:

| Plain chat | 10 | 25 | **50** | 75 | 100 |
|---|---:|---:|---:|---:|---:|
| median agent CPU | 16 % | 39 % | **73 %** | 483 % | 591 % |

| Knowledge retrieval | 2 | 5 | 8 | **10** | 12 |
|---|---:|---:|---:|---:|---:|
| median agent CPU | 22 % | 54 % | 86 % | **126 %** | 665 % |

| Refund preparation | 5 | **10** | 15 | 20 | 30 |
|---|---:|---:|---:|---:|---:|
| median agent CPU | 20 % | **44 %** | 105 % | 113 % | 109 % |

Columns are request rates; the bold column is the rate that path serves.

At the rate each path actually serves, the agent uses about one core of the eight available, and
MySQL, commerce and Elasticsearch are all well under half a core. Nothing here is at its limit.

Past the knee chat and retrieval jump five- to eight-fold in CPU while latency rises a hundredfold
and finished work falls — the shape of congestion collapse, where the extra CPU is the cost of
being overloaded rather than the cost of the work. Preparation behaves differently: its CPU stays
near 110 % even when it is shedding, because it spends its turn waiting on commerce. Its limit is
downstream, and this measurement does not resolve where.

### 3. Most of the agent's on-CPU time builds TLS trust stores for plaintext URLs

The agent reaches every dependency through the module-level `httpx` helpers — `httpx.post`,
`httpx.get` and `httpx.request` at seven call sites, plus `httpx.stream` at two more — and holds
**no reused `httpx.Client` anywhere**. Each helper call constructs a whole client, and
constructing a client constructs a default SSL context, which loads and parses the system CA
bundle. Measured in the same container:
**13.1 ms of CPU per construction**. Every one of those URLs is `http://` — the model fixture,
commerce, the auth service and Elasticsearch are all plaintext here — so no handshake ever
follows the trust store that was just built.

py-spy at 100 Hz agrees. It excludes idle threads, so the denominator is threads that were
actually running, and the share of those samples sitting in `ssl.create_default_context` is
(`../results/agent_pyspy_*.txt`, tallied in `../results/agent_cpu_profile.txt`):

| Path | concurrency 1 | concurrency 8 |
|---|---:|---:|
| Plain chat | 64.3 % | 71.2 % |
| Knowledge retrieval | 83.8 % | 94.9 % |
| Refund preparation | 57.0 % | 55.4 % |

Concurrency 1 is at or below where each path sits at the rate it serves — chat holds about one
turn in flight at 50 req/s, preparation about three at 10 req/s — and concurrency 8 is well past
every knee. **The share is dominant in both**, so this is not an artifact of overload — it is the
bulk of the agent's on-CPU work whenever it is doing anything. The share tracks how many outbound
calls a path makes, which is why retrieval — alias, mapping, two BM25 queries, two dense queries,
the reranker and two model calls — spends the most, and preparation, which spends much of its turn
blocked on commerce rather than on CPU, the least. Repeat runs move these by about ten points
either way.

The dominant stack is the same every time:

```
citybuddy_agent/agent_control.py:390   httpx.post(f"{self._url}/v1/chat/completions", ...)
  httpx/_api.py:102                    request()
    httpx/_client.py:688               Client.__init__
      httpx/_client.py:731             _init_transport
        httpx/_config.py:40            create_ssl_context
          ssl.py:770                   create_default_context
```

What this does **not** show is that removing it would raise the rate each path serves. §2 shows
the agent is not CPU-bound where it serves cleanly — about one core of eight — so the immediate
effect would be a cheaper turn rather than a higher ceiling. Where it plausibly does matter is the
knee: every extra concurrent turn adds another trust store to build, which is the kind of positive
feedback that turns a knee into a cliff, and §1 shows a very sharp one. Removing the waste and
rerunning these ladders is what would settle it, and the harness is here to do exactly that.

### 4. The connection limit decides how overload fails, not where the ceiling is

The agent's conversation store opens a **fresh `pymysql.connect` per persistence call and pools
nothing** (`conversation.py:1205`, six call sites). Connections opened against MySQL per HTTP
request over a whole ladder:

| Path | HTTP requests | MySQL connection attempts | Per request |
|---|---:|---:|---:|
| Plain chat | 5,098 | 21,343 | 4.19 |
| Knowledge retrieval | 1,008 | 5,245 | 5.20 |
| Refund preparation | 2,127 | 12,954 | 6.09 |

At the default `max_connections = 151` the chat ladder reaches 152 concurrent connections and
MySQL rejects 1,584 attempts, which the agent surfaces as
`ACTION_SESSION_PERSISTENCE_UNAVAILABLE` and HTTP 503. Raising the limit to 1000 and repeating
the identical ladder (`../results/agent_chat_control_steps.txt`):

| Target | | `max_connections=151` | `max_connections=1000` |
|---:|---|---:|---:|
| 50 | served/s | 50.0 | 50.0 |
| | p99 | 36.5 ms | 48.6 ms |
| | HTTP 503 | 0 | 0 |
| 75 | turns completed | 937 | **1,276** |
| | p99 | 2489.2 ms | **6872.9 ms** |
| | HTTP 503 | **545** | **3** |
| 100 | turns completed | 959 | **1,412** |
| | p99 | 3880.6 ms | **17136.7 ms** |
| | HTTP 503 | **955** | **2** |

The limit is doing admission control. Removing it makes almost nothing shed — 545 rejections
become 3 — and lets more turns finish, but the same load then queues to seven and seventeen
seconds instead of failing fast. It does not move the rate the path serves cleanly: 50 req/s is
identical in both columns, and both hit their knee immediately after.

On the chat path, then, the database's connection limit is the only thing applying backpressure,
and whether overload appears as a fast 503 or as a seventeen-second wait is decided by a database
setting rather than by the service. That is a design gap rather than a tuning opportunity: nothing
in the agent bounds its own concurrency.

It is only the chat path. Retrieval and preparation shed too — 218 requests on the prepare ladder
— with `attempts rejected at max_connections: 0` in both
(`../results/agent_prepare_mysql.txt`, `../results/agent_retrieval_repeat_mysql.txt`). Their
shedding comes from the commerce tool boundary, which turns a 429 or 503 from commerce into the
same for the caller. So the agent has no backpressure of its own on any path; on two of the three
the limit that bites is somewhere else again.

## Two things found while building the fixture

**Concurrent mock-payment settlement deadlocks, and the payment-start endpoint answers HTTP 500.**
Building 6,000 paid orders in parallel produced `CannotAcquireLockException` out of
`POST /api/orders/{orderId}/mock-payment`. The recorded deadlock
(`../results/mock_payment_callback_deadlock.txt`) is between the callback's attempt lookup —

```sql
SELECT ... FROM mock_payment_attempt
 WHERE attempt_id = ? OR callback_correlation_id = ? OR order_id = ? LIMIT 2 FOR UPDATE
```

— and the payment *start*'s lookup on `order_id`, for two different orders, each holding an X
lock on `PRIMARY` that the other waits for. InnoDB rolled back the start, which is consistent with
where the 500 came from: `MockPaymentService.withCallbackDeadlockRetry` retries and then falls
back to resolving the competing result, while `withStartCompetitionRetry` rethrows once its
attempts are spent, and no handler maps `CannotAcquireLockException`.

`EXPLAIN` on the callback query reports
`type: ALL, key: NULL` despite `attempt_id` and `callback_correlation_id` both being uniquely
indexed, so under `FOR UPDATE` it locks every row it examines. Two caveats on that plan: the
fixture table held six rows, and on a table that small the optimizer would choose a scan whatever
the predicate looked like, so this `EXPLAIN` is consistent with the three-way `OR` disqualifying
every index but does not prove it. The start lookup's plan is a covering index scan of
`uq_mock_payment_order`, not a table scan, because that index is `(order_kind, order_id)` and the
predicate names only `order_id`. Confirming the mechanism needs the same `EXPLAIN` against a table
with realistic cardinality. What is not in doubt is that the deadlock happens under ordinary
parallel fixture building. That it reaches the client as a 500 rather than a retryable status is
read from the exception handling above and from the failure that stopped the fixture build; no
committed artifact here records the status, so treat it as a lead to confirm rather than a
measured result. The fixture builder settles payments serially to avoid the deadlock
deterministically rather than retrying through it.

**The default attempt budget cannot fit a successful retrieval turn.** This one is read from the
code, not observed in these runs — no `budget_exhausted` appears in any committed result, because
the bench sets `AGENT_ATTEMPT_BUDGET=16` precisely to avoid it. In `knowledge.py`, `search`
resolves the alias, validates the mapping, and then runs one BM25 and one dense query per query
text including the rewrite; with the reranker and the opening model call that is eight charged
attempts. At the default budget of 8 the retrieval itself succeeds and the turn then ends
`budget_exhausted` with nothing left for the closing model call. Reproducing it deliberately would
make it a finding rather than a reading.

## Reproducing

```bash
make init-local && make up
```

```bash
BENCH_USERS=25000 ./bench/setup_bench_env.sh
```

The agent fixture reuses the users, product and signing key that script creates.

Rerun the setup before each ladder — it rebuilds the fixture and restarts the bench services, and
the prepare ladder refuses to start if the fixture still holds prepared actions, because it would
otherwise measure the clarification path and still report a clean run. `AGENT_BENCH_USERS` must
exceed the ladder's `sum(rate x step_seconds) + 20 per step`.

```bash
AGENT_BENCH_USERS=6000 ./bench/agent/setup_agent_bench.sh
```

```bash
RATES=10,25,50,75,100 STEP_SECONDS=20 ./bench/agent/run_agent_ladder.sh chat
```

```bash
AGENT_BENCH_USERS=2500 ./bench/agent/setup_agent_bench.sh
```

```bash
RATES=2,5,8,10,12 STEP_SECONDS=30 ./bench/agent/run_agent_ladder.sh retrieval
```

```bash
AGENT_BENCH_USERS=3000 ./bench/agent/setup_agent_bench.sh
```

```bash
RATES=5,10,15,20,30 STEP_SECONDS=30 ./bench/agent/run_agent_ladder.sh prepare
```

The runner prints the per-step table and writes it to `bench/results/agent_<label>_steps.txt`.

The CPU attribution in §3 comes from a separate script, because it drives a fixed concurrency
rather than a fixed arrival rate:

```bash
CONCURRENCY=1 ./bench/agent/profile_agent_cpu.sh retrieval
```

It writes the raw py-spy collapsed stacks to
`bench/results/agent_pyspy_<path>_c<concurrency>.txt` and prints the tally. Check the header line:
`load-still-running-at-end-of-sample=false` means the load finished early and part of the window
sampled an idle process, which dilutes every share — raise `REQUESTS` and run it again.

`LABEL` names the output files, so a control run against a changed setting does not overwrite the
baseline it is meant to be compared with. The `max_connections` comparison in §4 is:

```bash
LABEL=chat_control RATES=10,25,50,75,100 STEP_SECONDS=20 ./bench/agent/run_agent_ladder.sh chat
```

run with `SET GLOBAL max_connections = 1000` and the fixture rebuilt in between.
