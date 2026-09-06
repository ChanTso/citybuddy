# Local sold-out attribution registration — 2026-09-06

Intended measured CityBuddy: `16cb21154d1ae94c65fff56b8a96ea7f4514f924`.
No point in this series has run at registration time. Actual full HEAD and JAR hashes
must match setup and be recorded with every result. Complete the sustained-order
comparison before starting this series; no parallel build, model batch or load point.

## Question and fixed workload

Answer whether the current local 4,000/s sold-out workload is fully scheduled and
served, and which resource evidence explains any incomplete scheduling or queuing.
Do not infer a unique cause of the historical 4,000/s point from a later topology.
Do not test intermediate 3,000–4,000 rates or continue to higher peaks.

Each fresh fixture has 32 activities, quota 10 each, one shared product with stock
2,000,000. Legally admit 320 distinct preparation users through the actual API and
wait for all 320 orders and timeout dispatches. Before input: SQL shows 320 unique
UNPAID orders, 320 matching CREATE ledger movements, stock 1,999,680, quota allocation
10 per activity; Redis remaining quota is zero for each activity, with no handoffs;
explicit transaction-topic MQ Diff/Inflight are zero. These values must remain
unchanged through both sold-out windows. Never delete intents or alter TTL to prepare.

Use original seckill_ladder.js in one k6 process with RATES=1000,target, STEP_SECONDS=30,
GAP_SECONDS=5, ACTIVITIES=32. Precede k6 with 20s resource sampling lead-in. The 1,000/s
30-second scenario is a fixed warmup, followed by a fixed five-second gap, then the
complete 30-second 3,000/s or 4,000/s formal scenario. Do not discard formal startup
seconds, tune warmup based on favorable latency, or combine both scenarios' statistics.
If warmup iterations continue into the formal scenario, retain the overlap and do not
call it an independent formal window. Use raw iteration end timestamps/durations.

| Formal rate | User pool | Warmup with margin | Formal with margin | 320 preparation users |
|---|---:|---|---|---|
| 3,000/s | 123,600 | [0,30050) | [30050,120100) | [120100,120420) |
| 4,000/s | 153,600 | [0,30050) | [30050,150100) | [150100,150420) |

Use a unique label as REQUEST_KEY_PREFIX in either generator location; report the
existing replay response field. Before the 20s lead-in, all warmup/formal token expiry
and the earliest preparation-order payment deadline must have at least 300s left.
Helper process success alone is not readiness. The generator's 180s wall timeout
protects against a stuck process; it does not extend the formal 30s load window.

## Local sequence and environment

1. VM8, container k6, formal 3,000/s control.
2. VM8, container k6, formal 4,000/s diagnosis, if the control has a valid fixture
   and functioning observation. A split-window p99 ratio is diagnostic, not a failure rule.
3. If shared generator/service competition still prevents a useful answer, temporarily
   set Docker VM to 6 CPU and run native macOS k6 against 127.0.0.1:18081 at 3,000/s.
4. Only if that host path is usable, run its fresh 4,000/s point; restore Docker to 8 CPU.

Host is the same MacBook Pro M4, 10 cores / 24GB. Docker memory stays 14,638,391,296 bytes
and Commerce stays capped at 4 CPU throughout. The initial container image remains
grafana/k6@sha256:5221b620a4f874faff6e32ba597aa667c058391fe4898b1c6f6377f062c6cdec.
Record the actual native and container k6 builds, not an assumed identical binary.
Broker, database, Redis, app source, VU formula and output format remain fixed across
compared points. Host6 is a separate topology, not an extension of the old VM8 ladder.

The host-path screen is full scheduling/expected decisions plus formal p99 no greater
than max(1.5*VM3_p99, VM3_p99+10ms), with p50/p95 and connection times also reported.
This screen asks whether the port-forwarded route can help this diagnosis; it is not
a service SLO or a new capacity failure rule. Failure warrants explanation, not an
automatic cloud run. VM6 still shares physical CPU/memory with host k6 and gives less
CPU room to other services; it is not two-machine isolation.

## Observation and interpretation

Retain native JSON point output, summary and console. Filter formal scenario/rate for
counts and percentiles; k6's default HTTP-failure metric includes expected 409, so
report EXHAUSTED separately. Track scheduled/completed, dropped/interrupted, decisions
and replay. Preserve tail completions. A p99 doubling alone is not capacity exhaustion.

Sample k6, Commerce and dependency CPU, Commerce cgroup usage/throttling, VM/host load,
memory and pressure. Align CPU samples to actual HTTP windows; k6's later JSON flush
CPU is not request-window saturation. Do not add profiling or compress files under load.
Per-point before/after Redis INFO memory/stats/persistence records retained terminal
key cost, expiration/eviction, AOF rewrites/delayed fsync. Each fresh rejection creates
three finite-TTL keys, so previous completed keys are not assumed cost-free. If memory,
expiration or AOF activity prevents attribution, retain the point and wait for natural
recovery before deciding whether another point has value.

A resource limiter needs converging evidence: e.g. sustained four-CPU use plus growing
cgroup throttling and latency while the generator/dependencies have room; or generator
scheduling losses with service room and a useful generator-location comparison. A
single docker-stats peak cannot identify which side limited the historical run.

If JSON output itself remains a concrete generator-side suspect, at most one separately
registered same-point logging comparison may omit point output while retaining native
per-scenario summary and console. Confirm installed-version support first. Do not call
an output-format or warmup change a service optimization, and do not compare a bad JSON
point to a good CSV point as if the measurement method were unchanged.

Stop for invalid credentials/fixture, unexpected business changes, sampler/process or
memory failure; preserve every attempted point. If local work still cannot separate
limiting factors, explain the remaining ambiguity and what two machines would resolve.
Do not provision cloud resources without returning to the owner first. There is no
requirement to add host/cloud points when they cannot change the engineering answer.
