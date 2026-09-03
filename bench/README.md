# Seckill measurement

The agent's four workloads are measured separately in [agent/README.md](agent/README.md).

Local measurement of the CityBuddy seckill path: correctness under contention first, then
component and full-path throughput. The retained raw and derived artifacts are in `results/`;
the boundary between them is stated below.

No k6 per-request point stream is Git-tracked; their console summaries, summary exports, and
per-step analyses are. Four older seckill point streams were present locally during this audit,
but they are ignored files rather than repository evidence and are not provenance for a fresh
checkout.

## Environment

| | |
|---|---|
| Host | MacBook Pro M4, 10 cores, 24 GB |
| Docker Desktop | 14 GB / 8 CPU allocation |
| Services | `auth-service`, `commerce-service` as containers on the compose network; commerce requested at 4 CPUs inside Docker's 8-CPU allocation |
| Dependencies | MySQL 8, Redis (commerce), Elasticsearch 8 + IK, RocketMQ 5 Broker/Proxy — all pinned by digest |
| Generators | k6 v2.2.0 and memtier_benchmark 2.5.1 |

Everything runs on one machine. These are **not** capacity or production claims; the value is
the shape of the curve and the observed workload boundaries, not the absolute number.
The four-CPU commerce limit is a local fixture choice. The repository contains no production-sizing
derivation or other rationale for choosing four rather than another share of the eight allocated
CPUs, so results at that boundary do not establish a production resource requirement.

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
6. **Formal runs are boundary-bound.** Setup requires a clean source tree; records full HEAD,
   auth/commerce host and mounted JAR digests, and the identity/start/restart state of every runtime
   dependency; and clears only synthetic activity, user and rebuild Redis keys. Runners check the
   source, artifacts, containers and CPU controls before and after load.
7. **A result bundle publishes atomically.** Every run needs a unique safe `LABEL`. A hidden staging
   directory and a same-label claim live on the `bench/results` filesystem. Only after k6 or SQL
   validation and the final boundary gate passes is that directory renamed once to
   `results/ladder_${LABEL}` or `results/correctness_${LABEL}`. Failure removes the hidden staging
   directory and claim, so no partial bundle looks published and the label remains reusable.

### CPU artifact boundary

Docker reports whole-container CPU with 100% equal to one logical CPU. It is not a share of the
container limit: a commerce reading of 387% is about 3.87 CPUs and is near this fixture's 400%
ceiling. Memory is the container's current usage and Docker VM limit, not a per-process allocation.

- `k6_*_cpu.txt` files are raw `docker stats --no-stream` snapshots from the seckill runner. Their
  observed cadence is roughly four to five seconds because each stats call takes time before the
  runner's three-second sleep. They sample k6, commerce, MySQL and Redis, not Java stacks, host CPU,
  auth, RocketMQ, or scheduler and I/O wait separately.
- `agent_*_cpu.txt` files are the Agent runner's raw Docker snapshots, generally about two seconds
  apart. Current commit-bound bundles sample k6, Agent, the model fixture, auth, commerce, the
  dedicated benchmark Elasticsearch container and MySQL. Older unbound Agent files sampled the
  ordinary Compose Elasticsearch container instead of the SUT's dedicated one; their dependency
  attribution is correspondingly incomplete.
- `peak_cpu.txt` is a retained convenience transcription of maxima for k6, commerce and MySQL from
  the legacy contended/spread seckill CPU files. It has no commit or timestamp metadata and is not
  raw profiler output. `agent_*_cpu_by_step.txt` files are likewise historical windowed summaries
  derived from timestamped raw samples; the current runner does not generate them and the original
  derivation command was not retained.

These files measure CPU consumption for sampled containers and, when a container has a recorded
quota, its proximity to that quota. A controlled counterfactual can support a component-level
attribution. The series alone cannot prove saturation, name a Java method, distinguish user time
from kernel time or waiting, or prove that an unsampled dependency had headroom.

Existing setup and CPU artifacts remain historical facts. Their `commerce_cpu_limit=4` field was
written from the same hard-coded value passed to Docker; it was not a live observation. New setup
records use the single requested fixture value plus Docker's inspected `HostConfig.NanoCpus`, and
record an empty `HostConfig.CpusetCpus`; setup and runners fail if either control drifts. That new
guard does not retroactively add a live resource-limit observation to older files.

## Results

### 1. Correctness under contention — 10/10 PASS

600 reservation requests against one activity with `allocated_quota = 100`, issued by a client
pool capped at 128 concurrent workers
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

The inside-network run observed 77.16% lower mean latency and 4.38x the throughput of the host-to-
published-port run. That is consistent with avoiding Docker Desktop's published-port/VM transport,
but generator placement changes at the same time, so this pair does not isolate a numerical “hop
cost.” It does establish that host-side and inside-network Redis figures are different measurement
boundaries. All full-path measurements below therefore run inside the network.

### 3. Full authenticated seckill path

Five steps, 15 s each, one HTTP request per iteration, real JWT verification, real Lua admission,
real MySQL transaction (`results/ladder_*_steps.txt`).

In these legacy tables, `Achieved/s` is completed requests divided by the elapsed timestamp span
from the first to last completion in that rate. It is a completion-density statistic whose
denominator can extend with a backlog; it is not the current runner's `done / nominal step seconds`
field and must not be compared to that newer field as though the denominators were identical.

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

Among the three containers transcribed in `results/peak_cpu.txt`, none saturated CPU at that point:
commerce peaked at 145% of its 400% allowance, MySQL at 61%, and k6 at 60% of one CPU. The raw CPU
series also sampled Redis. This rules out CPU saturation in those sampled containers; it does not
by itself prove the cause of the queue or rule out an unsampled resource. The topology comparison
below is the evidence that connects the collapse to same-row serialization.

### 4. Where the serialization is

`SeckillReservationService.reserveIntent` opens its transaction with:

```sql
SELECT ... FROM seckill_activity WHERE activity_id = ? FOR UPDATE
```

and holds that single row through the idempotency lookup, the reservation insert, and a re-read
until commit. Every reservation for one activity passes through that row. The Redis Lua quota
decision happens *after* this transaction, making the activity-row lock the serialization candidate
that the 32-activity counterfactual below tests; the CPU samples alone do not establish it.

## Finding and fix: the serializing lock was also suppressing a deadlock

Repeating the same workload shape with traffic spread over 32 activity rows removed the queueing,
but the service returned 6,202 HTTP 500 responses over the whole 23,254-request ladder: 6 at 100,
3 at 200, 1,803 at 400 and 4,390 at 800 requests/s. The corresponding raw log grep retained in
`results/deadlock_count.txt` reports 12,404 text matches, numerically 2 × 6,202, while its printed
minute subtotal sums to 6,222 and does not reconcile. No retained correlation binds two log lines
to each individual request, so it establishes repeated deadlock diagnostics, not an independent
exact deadlock-event count. The single-activity point stream contains zero HTTP 500 responses but
one k6 `http_req_failed` out of 21,268 requests: an HTTP 409 `DUPLICATE_USER` decision.

The retained `SHOW ENGINE INNODB STATUS` file contains only the section header, not the lock graph.
The gap-lock mechanism below is therefore a reconstruction from the pre-fix query order, InnoDB
locking semantics, the failing insert site and the post-fix disappearance—not a claim that
`results/innodb_deadlock.txt` preserves these details:

- `findByIdempotencyForUpdate` ran `SELECT ... FOR UPDATE` for a row that does not exist yet, which
  can take a gap lock rather than a record lock;
- concurrent gap locks are compatible, so multiple transactions can reach the next statement;
- the following `INSERT` requires an insert-intention lock that conflicts with those gap locks,
  providing the reconstructed cycle for InnoDB to break by rolling one transaction back.

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

The fix necessarily changed the build. These legacy before/after runs used the same machine,
workload shape and fixture dimensions, but their artifacts predate the current commit-bound setup
record. They are retained observations, not a same-session paired estimate.

The results below use the same legacy `Achieved/s` denominator defined above.

| | Contended (1 activity) | Spread (32 activities) |
|---|---:|---:|
| Achieved at 800 target | 511.5 req/s | 799.9 req/s |
| p50 at that step | 2844.9 ms | 7.5 ms |
| p99 at that step | 4466.5 ms | 39.4 ms |
| Dropped iterations | 2,075 | 0 |
| Failed requests | 0.00 % | 0.00 % |
| Commerce-log deadlock diagnostic matches | Not retained | 0 |

Both post-fix topology runs complete with zero errors. Within that build, concentrating the same
offered load on one activity row is associated with about 1.56x less completed throughput and moves
p50 from 7.5 ms to 2.8 s. That isolates concentration on one activity as the material topology
difference; the code path and later lock-mode treatment attribute it to the activity-row lock rather
than this topology pair doing so alone. The older 528.5 req/s and post-fix 511.5 req/s single-
activity observations are consistent with the gap lock not being that path's limit, but their
difference is not a controlled estimate of “unchanged.”

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

## Throughput-knee protocol after removing the activity-row X lock

The next run bracketed the knee with a deterministic stop rule. For each topology, run fresh
single-rate points at 1000, 1200, 1600, then 2000 requests/s and stop at the first point with any
failed request, any dropped iteration, or a p99 at least twice the immediately preceding clean
point. Both topologies stopped at 1000 because k6 dropped iterations; no 1200, 1600, or 2000 point
was run.

| Topology | Prior clean anchor | First protocol stop | Done / dropped / failed | Achieved/s | p50 | p95 | p99 | Peak CPU: commerce / MySQL / k6 / Redis |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| One activity | 800: 800.1/s, p99 26.0 ms, 0 dropped | 1000 | 14,735 / 266 / 0 | 982.3 | 24.6 ms | 1078.9 ms | 1206.7 ms | 387.54% / 206.70% / 70.99% / 8.72% |
| 32 activities | 800: 800.1/s, p99 70.0 ms, 0 dropped | 1000 | 14,660 / 340 / 0 | 977.3 | 26.0 ms | 1113.1 ms | 1213.4 ms | 383.16% / 209.58% / 77.49% / 8.86% |

`Achieved/s` is completed iterations divided by the nominal 15-second step. The k6 summaries use
their actual elapsed windows and therefore report 969.13/s and 976.60/s; those rates answer a
different timing question.

The measured source was merge commit
`f4ae145bfa8bc292f6c13efa973f2f899d13efa0`; the production path is unchanged from the Stage 1
after build. The clean 800 anchors above are the fifth, warmed step of that Stage 1 ladder, whereas
each 1000 point used a new setup and was the only rate in its process. The result is therefore a
workload-bounded protocol bracket—800 was clean and 1000 was not—not a claim that an exact
threshold was measured between them.

At 1000, both topologies reached about 980 requests/s, then accumulated roughly 1.21 s p99 and
dropped work. Commerce briefly used nearly all of its four-CPU allowance while MySQL used about
two cores and the generator stayed below one core. That makes the commerce CPU allowance the
leading saturation hypothesis, but the samples do not isolate a definitive code-level
bottleneck.

The raw point streams add an important limit to that inference: every dropped iteration occurred
in the first four one-second buckets. From the seventh bucket onward, both runs completed about
1000 iterations/s and their per-bucket p99 was approximately 17–82 ms. The stop rule was still
correctly triggered, but the observed bad point was dominated by a fresh-process startup
transient. This evidence does not establish that sustained capacity ends at 800 or 1000; locating
that ceiling would require a separately declared warm-up protocol and new measurements.

Each point used 16,000 fresh users, 32 fresh activities, a fresh Broker/Proxy process, a unique
topic, a verified route, and the same 8-CPU / 14-GB Docker allocation and quiet-healthcheck
boundary as Stage 1. The spread run's first host-idle gate (`85.36,70.54,81.29`) was rejected; its
accepted retry was `86.66,83.68,85.91`. The contended gate was
`85.83,84.72,84.93`. Both postchecks recorded zero pending or due-pending reservations, zero
MySQL deadlocks, zero relevant commerce error matches, and zero RocketMQ restarts. These retained
postcheck scalars close the admission-run bookkeeping; they are not a standalone business-
correctness proof.

The asynchronous consumer tail is deliberately outside this admission-path measurement. At the
postcheck, 240 of 14,735 admitted contended reservations and 272 of 14,660 admitted spread
reservations had become orders. Consumers were stopped before preparing the next fresh fixture,
and the unique topic prevented that excluded tail from entering the other run. These are local
single-machine results, not production-capacity claims.

The retained evidence is:

- one activity: [steps](results/ladder_seckill_s2_f4ae145_20260902T132826Z_contended_r1000_steps.txt),
  [k6 summary](results/k6_seckill_s2_f4ae145_20260902T132826Z_contended_r1000_summary.json),
  [k6 console](results/k6_seckill_s2_f4ae145_20260902T132826Z_contended_r1000_console.txt),
  [CPU samples](results/k6_seckill_s2_f4ae145_20260902T132826Z_contended_r1000_cpu.txt), and
  [setup and postcheck](results/seckill_seckill_s2_f4ae145_20260902T132826Z_contended_r1000_setup.txt)
- 32 activities: [steps](results/ladder_seckill_s2_f4ae145_20260902T133609Z_spread_r1000_steps.txt),
  [k6 summary](results/k6_seckill_s2_f4ae145_20260902T133609Z_spread_r1000_summary.json),
  [k6 console](results/k6_seckill_s2_f4ae145_20260902T133609Z_spread_r1000_console.txt),
  [CPU samples](results/k6_seckill_s2_f4ae145_20260902T133609Z_spread_r1000_cpu.txt), and
  [setup and postcheck](results/seckill_seckill_s2_f4ae145_20260902T133609Z_spread_r1000_setup.txt)

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

The ladder runner atomically publishes `results/ladder_${LABEL}/`, containing
`k6_${LABEL}_{summary,console,cpu,points}.…`, `ladder_${LABEL}_steps.txt`, and
`seckill_${LABEL}_setup.txt`; points remain ignored but available to the automatic calculator.
Correctness atomically publishes `results/correctness_${LABEL}/`, containing
`correctness_${LABEL}_{http,sql}.txt` and the setup record. Historical flat artifacts remain in
place; neither a bundle nor its active same-label claim is replaced.

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
