# Seckill measurement

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

## Finding: the serializing lock is also suppressing a deadlock

Repeating the identical ladder with traffic spread over 32 activity rows removes the queueing —
800 requests/s sustained, p99 26.8 ms, no dropped iterations — but from 400 requests/s upward the
service starts returning HTTP 500. That run produced roughly 6,200 deadlock events; the
single-activity run produced **zero** (`results/deadlock_count.txt`).

`SHOW ENGINE INNODB STATUS` (`results/innodb_deadlock.txt`) shows both transactions holding an X
gap lock on the `supremum` pseudo-record of `uq_seckill_reservation_idempotency`, each then
waiting for an insert-intention lock in that same gap:

- `findByIdempotencyForUpdate` runs `SELECT ... FOR UPDATE` for a row that does not exist yet, so
  InnoDB takes a gap lock rather than a record lock;
- gap locks are mutually compatible, so concurrent transactions all acquire it;
- the following `INSERT` needs an insert-intention lock in that gap, which conflicts with the gap
  locks the others hold — a cycle, and InnoDB rolls one back.

With a single activity this cannot happen: the `seckill_activity` row lock totally orders every
entrant, so only one transaction is ever inside the insert section. **The row lock that caps
throughput is simultaneously what prevents this deadlock class.** Concurrent activities are the
realistic production shape, so this is a real defect rather than a benchmark artifact, and it is
the same lock-ordering family already recorded for payments and refunds in
[docs/LESSONS.md](../docs/LESSONS.md).

Because those 500s are cheap failures, the 800 requests/s spread figure is **not** a capacity
claim and is not comparable to the contended ceiling. It is reported only as the evidence that
isolates the serialization point.

A separate, lower-volume error appears in a background scheduled task during the spread run
(`IllegalStateException: Committed reservation is missing`, 50 occurrences). It is not on the
request path and has not been diagnosed.

## Reproducing

```bash
make init-local && make up
```

```bash
BENCH_USERS=25000 ./bench/setup_bench_env.sh
```

```bash
./bench/run_correctness.sh
```

```bash
./bench/run_ladder.sh contended 1
```

```bash
./bench/run_ladder.sh spread 32
```
