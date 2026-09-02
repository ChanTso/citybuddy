# Seckill measurement

The agent's three support paths are measured separately in [agent/README.md](agent/README.md).

Local measurement of the CityBuddy seckill path: correctness under contention first, then
component and full-path throughput. Every number here was produced by the scripts in this
directory against the real local topology, and the raw tool output is in `results/`.

The k6 per-request point streams (~100 MB per run) are not committed; the k6 console summaries,
the summary exports, and the per-step analysis derived from those streams are.

## Environment

| | |
|---|---|
| Host | MacBook Pro M4, 10 cores, 24 GB |
| Docker Desktop | 14 GB / 8 CPU allocation |
| Services | `auth-service`, `commerce-service` as containers on the compose network; commerce limited to 4 CPUs |
| Dependencies | MySQL 8, Redis (commerce), Elasticsearch 8 + IK, RocketMQ 5 Broker/Proxy — all pinned by digest |
| Generators | k6 v2.2.0 and memtier_benchmark 2.5.1 |

Everything runs on one machine. These are **not** capacity or production claims; the value is
the shape of the curve and where the ceiling comes from, not the absolute number.

## Method

1. **Correctness before throughput.** The correctness verdict does not depend on hardware, so it
   is established first and is unaffected by whatever the throughput numbers turn out to be.
2. **Setup is excluded.** Users, activities, tokens, and logins are created before measurement.
   The token pool is sized larger than the total iteration count so that no user is reused —
   a reused user is rejected by the one-order-per-user rule and would stop exercising the
   admission path.
3. **Steps, not a continuous ramp.** Each rate is its own `constant-arrival-rate` scenario with a
   fixed steady-state window, so percentiles come from a constant arrival rate. The open-model
   executor keeps request generation independent of server response time.
4. **The generator is measured too.** Generator CPU is sampled throughout. A percentile taken
   while the generator is saturated describes the generator, not the server, and is discarded.
5. **Two vantage points for Redis.** Docker Desktop is a virtual machine, so a host-to-container
   measurement includes a transport cost that can exceed the work being measured. Redis is
   measured from both the host and inside the compose network, and the difference is reported
   as a result in its own right.
6. **Formal runs are commit-bound.** Setup requires a clean source tree, records the full HEAD and
   auth/commerce JAR digests in `bench/.run/bench.env`, and clears only synthetic activity, user and
   rebuild Redis keys. Runners check that record before load and confirm HEAD afterward.
7. **Results never overwrite.** Every run needs a unique safe `LABEL`. k6 is digest-pinned and its
   exit code is checked; every result records the measured commit, UTC window and configuration.

## Results

### 1. Correctness under contention — 10/10 PASS

600 concurrent reservations against one activity with `allocated_quota = 100`
(`results/correctness_sql.txt`, `results/correctness_http.txt`):

- exactly 100 `ADMITTED`, 500 rejected `EXHAUSTED`; no oversell
- 100 durable orders, one per admitted reservation, one per user
- inventory ledger: 100 movements, `inventory_delta = -100`, `activity_quota_delta = -100`
- product stock decremented by exactly 100
- no orphaned orders, no duplicate orders, no admitted reservation left without an outcome

### 2. Redis component and transport cost

30 s, 4 threads x 25 connections, 1:1 GET/SET, 64-byte values
(`results/memtier_host.txt`, `results/memtier_network.txt`):

| Vantage point | Ops/sec | Avg latency | p99 |
|---|---:|---:|---:|
| macOS host to published port | 68,691 | 1.455 ms | 3.951 ms |
| Inside the compose network | 300,918 | 0.332 ms | 0.727 ms |

Measured from the host, **77 % of the observed latency is Docker Desktop's host-to-VM hop**, and
throughput is 4.4x lower. A host-side Redis number on this setup describes port forwarding, not
Redis. All full-path measurements below therefore run inside the network.

### 3. Full authenticated seckill path

Five steps, 15 s each, one HTTP request per iteration, real JWT verification, real Lua admission,
real MySQL transaction (`results/ladder_*_steps.txt`).

**One activity — every request contends for the same activity row:**

| Target rate | Achieved/s | p50 | p95 | p99 |
|---:|---:|---:|---:|---:|
| 50 | 51.3 | 6.6 ms | 13.0 ms | 206.9 ms |
| 100 | 100.1 | 3.8 ms | 8.9 ms | 13.5 ms |
| 200 | 200.1 | 2.9 ms | 8.5 ms | 11.7 ms |
| 400 | 400.2 | 3.9 ms | 31.6 ms | 59.2 ms |
| 800 | **528.5** | 2327.6 ms | 3899.8 ms | 4023.8 ms |

The workload was essentially all `ADMITTED`, so this is the real admission path rather than cheap
rejections. Throughput stops scaling between 400 and 800 requests/s and latency collapses.

**Nothing was resource-saturated at that ceiling** (`results/peak_cpu.txt`): peak commerce CPU
145 % of 400 % available, MySQL 61 %, and the k6 generator 60 % of a single core. The ceiling is
queueing on a serialized section, not CPU, and the generator was not starving.

### 4. Where the serialization is

`SeckillReservationService.reserveIntent` opens its transaction with:

```sql
SELECT ... FROM seckill_activity WHERE activity_id = ? FOR UPDATE
```

and holds that single row through the idempotency lookup, the reservation insert, and a re-read
until commit. Every reservation for one activity passes through that row. The Redis Lua quota
decision happens *after* this transaction, so the ceiling above is MySQL row serialization, not
the Lua admission.

## Finding and fix: the serializing lock was also suppressing a deadlock

Repeating the identical ladder with traffic spread over 32 activity rows removed the queueing,
but from 400 requests/s upward the service began returning HTTP 500. That run produced roughly
6,200 deadlock events; the single-activity run produced **zero**
(`results/deadlock_count.txt`).

`SHOW ENGINE INNODB STATUS` (`results/innodb_deadlock.txt`) showed both transactions holding an X
gap lock on the `supremum` pseudo-record of `uq_seckill_reservation_idempotency`, each then
waiting for an insert-intention lock in that same gap:

- `findByIdempotencyForUpdate` ran `SELECT ... FOR UPDATE` for a row that does not exist yet, so
  InnoDB took a gap lock rather than a record lock;
- gap locks are mutually compatible, so every concurrent transaction acquired it;
- the following `INSERT` needed an insert-intention lock in that gap, which conflicts with the gap
  locks the others held — a cycle, and InnoDB rolled one back.

With a single activity this cannot happen: the `seckill_activity` row lock totally orders every
entrant, so only one transaction is ever inside the insert section. **The row lock that caps
throughput was also what prevented this deadlock class**, which is why a single-activity load test
could never surface it.

The fix inserts first and lets the unique key decide whether the request is a replay. On
`DuplicateKeyException` the existing row is read back with `FOR SHARE`: a current read, because a
REPEATABLE READ snapshot predates the concurrent insert that produced the duplicate, and shared
rather than exclusive, because `INSERT` already holds a shared lock on the duplicated record and
upgrading it would recreate the S-to-X cycle recorded for payment callbacks. Details are in
[docs/LESSONS.md](../docs/LESSONS.md).

## Results after the fix

Same build, same fixture, same ladder.

| | Contended (1 activity) | Spread (32 activities) |
|---|---:|---:|
| Achieved at 800 target | 511.5 req/s | 799.9 req/s |
| p50 at that step | 2844.9 ms | 7.5 ms |
| p99 at that step | 4466.5 ms | 39.4 ms |
| Dropped iterations | 2,075 | 0 |
| Failed requests | 0.00 % | 0.00 % |
| Deadlocks | 0 | 0 |

Both sides now complete with zero errors, so the comparison is clean: concentrating the same
offered load on one activity row costs about 1.56x throughput and moves p50 from 7.5 ms to 2.8 s.
That isolates the `seckill_activity` row serialization as the ceiling, and the single-activity
ceiling itself is unchanged by the fix (528.5 req/s before, 511.5 after), as expected — the gap
lock was never the constraint when one row already serialized every entrant.

Correctness was re-verified on the fixed build: 10/10 checks pass, exactly 100 admitted and 500
exhausted, ledger and stock conserved (`results/correctness_sql.txt`).

## Shared activity lock result

The hot activity read was then changed from `FOR UPDATE` to `FOR SHARE`. Reservation intent
transactions for the same activity can now overlap, while projection rebuilds retain their
exclusive activity lock. The formal same-session comparison measured these exact commits:

- before: `c049ac305b607b46c9e545473d01063f7ea96339`
- after: `4f40cd2f0159b4c4118b9b3724235a0b3ddbd390`

Both commits passed the fixed 600-request correctness workload: exactly 100 `ADMITTED`, 500
`EXHAUSTED`, and Q01 through Q10 all PASS. The request wall time was 2.17 s before and 1.57 s
after. The raw HTTP and SQL evidence is in
[`correctness_seckill_s1_c049ac3_20260902T114422Z_correctness_*`](results/correctness_seckill_s1_c049ac3_20260902T114422Z_correctness_http.txt)
and
[`correctness_seckill_s1_4f40cd2_20260902T121356Z_correctness_*`](results/correctness_seckill_s1_4f40cd2_20260902T121356Z_correctness_http.txt).

**One activity:**

| Target | Before achieved/s | Before p99 | Before dropped | After achieved/s | After p99 | After dropped |
|---:|---:|---:|---:|---:|---:|---:|
| 50 | 50.1 | 213.6 ms | 0 | 50.1 | 279.0 ms | 0 |
| 100 | 100.0 | 8.4 ms | 0 | 100.1 | 8.9 ms | 0 |
| 200 | 200.1 | 10.5 ms | 0 | 200.1 | 10.6 ms | 0 |
| 400 | 400.1 | 21.3 ms | 0 | 400.1 | 28.4 ms | 0 |
| 800 | 736.7 | 2080.3 ms | 949 | 800.1 | 26.0 ms | 0 |

At the 800 target, p50 fell from 1535.1 ms to 6.1 ms and p99 fell by about 80x. The after run
kept up with the offered rate with zero failed requests, zero dropped iterations, and only
`ADMITTED` decisions. The exact rows are in
[`ladder_seckill_s1_c049ac3_20260902T115646Z_contended_steps.txt`](results/ladder_seckill_s1_c049ac3_20260902T115646Z_contended_steps.txt)
and
[`ladder_seckill_s1_4f40cd2_20260902T121945Z_contended_steps.txt`](results/ladder_seckill_s1_4f40cd2_20260902T121945Z_contended_steps.txt).

**Traffic spread over 32 activities:**

| Target | Before achieved/s | Before p99 | After achieved/s | After p99 |
|---:|---:|---:|---:|---:|
| 50 | 50.0 | 240.7 ms | 50.0 | 250.5 ms |
| 100 | 100.0 | 8.3 ms | 100.0 | 9.4 ms |
| 200 | 200.1 | 9.5 ms | 200.1 | 10.5 ms |
| 400 | 400.1 | 16.2 ms | 400.0 | 20.0 ms |
| 800 | 800.1 | 45.5 ms | 800.1 | 70.0 ms |

Every spread point had zero failures, zero dropped iterations, and only `ADMITTED` decisions.
The after change therefore removed the single-activity cliff without reducing achieved spread
throughput. The exact rows are in
[`ladder_seckill_s1_c049ac3_20260902T120426Z_spread_steps.txt`](results/ladder_seckill_s1_c049ac3_20260902T120426Z_spread_steps.txt)
and
[`ladder_seckill_s1_4f40cd2_20260902T122838Z_spread_steps.txt`](results/ladder_seckill_s1_4f40cd2_20260902T122838Z_spread_steps.txt).

The 50/s point includes the first post-start request tail on both builds; the sustained curve is
the 100/s-and-above sequence. Across all four ladders, peak generator CPU was 63.10% of one core,
commerce peaked at 186.07% of its 400% allowance, and MySQL peaked at 91.57% of one core.

The default Compose topology was first brought fully healthy. Before timed runs, the user paused
host activity, the standalone RocketMQ probe was stopped, and the periodic NameServer and
Broker/Proxy healthchecks were disabled with [`compose.quiet.yaml`](compose.quiet.yaml). Those
checks each launched a full `mqadmin` JVM every three seconds and were control-plane noise; the
override does not change the RocketMQ image, command, port, network, broker configuration, or
store volume. Its SHA-256 is
`2d6c652bb20cfa61a8365d10b3cd7429c8b3cda9bea0aa39737a55a1c8988246`.

Each formal runner used a fresh Broker/Proxy process on the same image and store, a unique topic
suffix, a fresh fixture, one-shot cluster and route checks, and three accepted idle samples. Five
of the six setup files record zero due `PENDING` reservations before the run. The baseline
correctness setup file does not contain that field, so its post-run HTTP and SQL closure—not an
unrecorded pre-run value—is the retained correctness evidence. The setup evidence also records
the Compose and override hashes, image, container start time, restart count, and Docker boundary.
These absolute numbers describe that healthcheck-disabled local measurement boundary and are not
directly interchangeable with the older default-Compose results above or with production
capacity.

## Reproducing

Build the exact clean commit that will be measured; setup mounts these host JARs.

```bash
make init-local && make up
./mvnw --batch-mode --no-transfer-progress \
  -pl auth-service,commerce-service -am clean package
```

After the default topology has reached healthy once, remove the periodic RocketMQ control-plane
checks outside the measured window. Validate both data-plane hops once, then let the host settle:

```bash
docker stop citybuddy-rocketmq-probe-1
docker compose --project-name citybuddy --env-file .env \
  --file compose.yaml --file bench/compose.quiet.yaml \
  up --detach --no-deps --force-recreate rocketmq-namesrv
docker exec citybuddy-rocketmq-namesrv-1 sh mqadmin getNamesrvConfig \
  --namesrvAddr localhost:9876
docker compose --project-name citybuddy --env-file .env \
  --file compose.yaml --file bench/compose.quiet.yaml \
  up --detach --no-deps --force-recreate rocketmq-broker-proxy
docker exec citybuddy-rocketmq-broker-proxy-1 sh mqadmin clusterList \
  --namesrvAddr rocketmq-namesrv:9876 --clusterName DefaultCluster
docker run --rm --network citybuddy_default citybuddy-rocketmq-probe:5.2.1 \
  route rocketmq-broker-proxy:8081 cb013-readiness
```

Before the formal commands below, stop other builds and workloads, ask the machine's user to pause
their work, and wait for confirmation. After confirmation, let the host settle and record three
idle CPU/process/container samples plus `pmset -g therm`. Confirm Docker Desktop still exposes 8
CPUs and about 14 GB; do not change its allocation.

Use a new `TOPIC_SUFFIX` for every setup so prior asynchronous work cannot enter the run.
`BENCH_USERS=25000` covers the five default rate steps. The formal evidence above used a separate
setup for every runner. Before each setup, remove the prior benchmark auth/commerce containers,
recreate only `rocketmq-broker-proxy` through `compose.quiet.yaml`, perform the one-shot cluster
check, and wait for another three-sample idle gate. This prevents one runner's asynchronous tail
from entering the next runner.

```bash
SHORT="$(git rev-parse --short=7 HEAD)"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

TOPIC_SUFFIX="s1-correctness-${SHORT}-${STAMP}" \
  BENCH_USERS=25000 BENCH_ACTIVITIES=32 \
  ./bench/setup_bench_env.sh

CORRECTNESS_LABEL="seckill_s1_${SHORT}_${STAMP}_correctness"
./bench/run_correctness.sh "$CORRECTNESS_LABEL"
```

After the correctness post-check, recreate Broker/Proxy through the quiet override as described
above, use another timestamp and topic, rerun setup, then measure the contended path:

```bash
SHORT="$(git rev-parse --short=7 HEAD)"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

TOPIC_SUFFIX="s1-contended-${SHORT}-${STAMP}" \
  BENCH_USERS=25000 BENCH_ACTIVITIES=32 \
  ./bench/setup_bench_env.sh

CONTENDED_LABEL="seckill_s1_${SHORT}_${STAMP}_contended"
RATES=50,100,200,400,800 STEP_SECONDS=15 \
  ./bench/run_ladder.sh "$CONTENDED_LABEL" 1
```

The 32-activity comparison needs another setup and topic:

```bash
SHORT="$(git rev-parse --short=7 HEAD)"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
TOPIC_SUFFIX="s1-spread-${SHORT}-${STAMP}" \
  BENCH_USERS=25000 BENCH_ACTIVITIES=32 \
  ./bench/setup_bench_env.sh

SPREAD_LABEL="seckill_s1_${SHORT}_${STAMP}_spread"
RATES=50,100,200,400,800 STEP_SECONDS=15 \
  ./bench/run_ladder.sh "$SPREAD_LABEL" 32
```

The runner writes `k6_${LABEL}_{summary,console,cpu}.…`, `ladder_${LABEL}_steps.txt`, and
`seckill_${LABEL}_setup.txt`; points remain ignored but available to the automatic calculator.
Correctness writes `correctness_${LABEL}_{http,sql}.txt`. Existing paths are never replaced.

To locate the next ceiling, run one rate at a time from a fresh setup. Inspect the automatically
generated step row and summary after each command; stop that topology at the first non-zero HTTP
failure count, non-zero dropped count, or clear p99 knee. Only proceed from 1000 to 1200, 1600 and
2000 when the previous rate is clean.

```bash
rate=1000
activities=1
topology=contended
users=$((rate * 15 + 1000))
SHORT="$(git rev-parse --short=7 HEAD)"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"

TOPIC_SUFFIX="s2-${topology}-${rate}-${SHORT}-${STAMP}" \
  BENCH_USERS="$users" BENCH_ACTIVITIES=32 \
  ./bench/setup_bench_env.sh

LABEL="seckill_s2_${SHORT}_${STAMP}_${topology}_r${rate}"
RATES="$rate" STEP_SECONDS=15 ./bench/run_ladder.sh "$LABEL" "$activities"
```

Repeat the same block independently with `activities=32` and `topology=spread`. Advance `rate` to
1200, 1600 and 2000 only while the prior point is clean.
