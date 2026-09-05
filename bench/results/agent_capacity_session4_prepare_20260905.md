# Agent action-prepare capacity, session 4

This normal-awake local session measured the current Agent action-prepare configuration as a
whole. The last clean tested point was 60 offered turns/s. The first bad tested point was
90 turns/s: p99 crossed the adjacent-clean two-times line and 20 turns were nonserved with the
persisted failure code `agent_execution_failed`. No scheduled iteration was dropped or
interrupted and no HTTP 5xx occurred. These are 30-second local benchmark entry points, not
production capacity, confirmed-action throughput or a model-quality result.

## Source, configuration and host boundary

- Every bundle records CityBuddy, SUT and benchmark harness at full commit
  `c5af89d5e07fa5a20f0a32b865557fbbbb08aabd`.
- Both setups requested and observed four Agent workers, one shared outbound HTTP-client layout,
  attempt budget 16 and a 7,000-user benchmark pool. Docker had 8 allocated CPUs and
  14,638,391,296 bytes of memory. The model endpoint and supporting services were local
  benchmark containers; this is not a real-provider or normal-task acceptance run.
- MySQL `max_connections` was changed from its original 151 to 1,000 at 05:59:48 UTC, remained
  1,000 in both before/after snapshots, and was restored to 151 after load. The restored server
  also reported `Connection_errors_max_connections=0`.
- The session began at 05:59:25 UTC with no benchmark containers running,
  `AppleClamshellState=No` and continuous full wake since 03:46:40 UTC. The 06:14:05 UTC power
  check recorded the lid still open and no new sleep or thermal event. Cleanup at 06:14:41 UTC
  found no benchmark container or k6 process; the session record closed at 06:15:06 UTC.
- Three pre-session Docker idle samples recorded the retained shared Broker proxy at
  104.91/104.79/104.74% CPU, MySQL at 1.52/0.10/1.45%, support Redis at
  0.03/0.89/0.92%, and shared Elasticsearch at 1.45/0.09/0.10%. The Broker therefore supplied
  roughly one CPU core of existing shared background activity. It is an environment boundary,
  not Agent work.
- Power, idle, MySQL reconfiguration and cleanup facts are retained in the operator boundary
  record `bench/.run/agent-capacity-session4-20260905T055925Z.txt`. That ignored operator file
  is not part of the published per-point bundle.

Each k6 invocation contained a 5 turns/s, 30-second pre-step, a wait, and one 30-second target
step. The 5/s rows and their SQL effects are reported separately and excluded from the formal
classification. The CPU samples cover the whole invocation, including the pre-step, wait,
formal step and shutdown; their medians and maxima are not formal-window or per-turn CPU.

This session did not toggle worker count, shared-client layout, TLS behavior, digest behavior,
MySQL limits or any connection-pool implementation one at a time. Its result describes the
combined current configuration and local topology. It cannot be attributed to TLS reuse,
digest verification, connection creation or another single mechanism.

## Excluded 5 turns/s pre-steps

| Target bundle | Started / finished / served | Nonserved / dropped / interrupted / 5xx / errors | p50 ms | p99 ms | Max ms | Treatment |
|---|---|---|---:|---:|---:|---|
| Prepare 60/s | 151 / 151 / 151 | 0 / 0 / 0 / 0 / 0 | 17.478083 | 32.61847949999999 | 54.788208 | Excluded 5/s pre-step |
| Prepare 90/s | 150 / 150 / 150 | 0 / 0 / 0 / 0 / 0 | 17.3073335 | 32.79303840999999 | 55.254792 | Excluded 5/s pre-step |

The authoritative SQL below includes these pre-step rows, which remain distinguishable by their
rate tag. They are not pooled into either target-rate result.

## Formal target-step results

All counts and percentiles are copied from each target-rate summary and retained step row. The
one extra started iteration at each target is the recorded constant-arrival-rate boundary result
and is not normalized away.

| Offered rate | Nominal | Started / finished / served | Nonserved / dropped / interrupted / 5xx / errors | p50 ms | p99 ms | Max ms | Classification |
|---:|---:|---|---|---:|---:|---:|---|
| 60/s | 1,800 | 1,801 / 1,801 / 1,801 | 0 / 0 / 0 / 0 / 0 | 13.462333 | 34.58225 | 166.3145 | Last clean tested point |
| 90/s | 2,700 | 2,701 / 2,701 / 2,681 | 20 / 0 / 0 / 0 / 20 | 21.205 | 227.595376 | 354.202 | First bad tested point: error and p99 stops |

The 60/s point set the next p99 line at `2 x 34.58225 = 69.1645 ms`. At 90/s, p99 was
227.595376 ms, above that line, while 20 finished turns were nonserved and were also counted by
`agent_http_errors`. The 90/s point therefore triggered both the latency and unexpected-outcome
stop conditions. Prepare 120, 160, 200 and any doubling point were not run.

## Authoritative pending-action SQL

The SQL groups state by the per-bundle correlation boundary and keeps the 5/s pre-step separate.
`PREPARED` is a pending action awaiting the client confirmation protocol; `action_receipt=0`
means this session did not confirm or execute those actions.

| Bundle / rate | Support-turn state and outcome | Commerce pending action | Agent pending reference | Action receipts |
|---|---|---|---|---:|
| 60 bundle, excluded 5/s | 151 `COMPLETED / action_pending` | 151 `PREPARED` | 151 `PENDING` | 0 |
| 60 bundle, formal 60/s | 1,801 `COMPLETED / action_pending` | 1,801 `PREPARED` | 1,801 `PENDING` | 0 |
| 90 bundle, excluded 5/s | 150 `COMPLETED / action_pending` | 150 `PREPARED` | 150 `PENDING` | 0 |
| 90 bundle, formal 90/s | 2,681 `COMPLETED / action_pending`; 20 `FAILED / agent_execution_failed` | 2,681 `PREPARED` | 2,681 `PENDING` | 0 |

The SQL establishes the persisted result categories but does not decompose
`agent_execution_failed`. In particular, it does not identify connection creation, application
connection-pool exhaustion, MySQL, the model, or another dependency as the cause of those 20
failures. The workload contracts likewise identify route shape, not business or performance
causation.

## MySQL status snapshots

`Connections` is MySQL's cumulative connection counter across the whole invocation and all
clients; `Max_used_connections` is a server high-water counter. Neither is formal-only or a
per-turn connection count.

| Offered rate | `Connections` before -> after (delta) | `Max_used_connections` before -> after | `Connection_errors_max_connections` before -> after | `max_connections` before / after |
|---:|---:|---:|---:|---:|
| 60/s | 157,963 -> 169,736 (+11,773) | 97 -> 105 | 0 -> 0 | 1,000 / 1,000 |
| 90/s | 177,074 -> 194,241 (+17,167) | 97 -> 115 | 0 -> 0 | 1,000 / 1,000 |

These bounded aggregate increments and zero max-connection errors do not demonstrate that
connection creation caused the 90/s failures. Raising `max_connections` was part of the combined
setup, and the original value 151 was restored after the final point.

## Whole-invocation CPU observations

Each row is the median and maximum of 59 retained, approximately two-second `docker stats`
samples for that container. Because each file spans the complete pre-step/wait/formal lifecycle,
the values cannot be assigned to the formal 30-second step or to a code method.

| Container | 60 bundle median / max CPU % | 90 bundle median / max CPU % |
|---|---:|---:|
| Agent | 3.04 / 32.54 | 2.51 / 135.28 |
| Auth | 0.99 / 18.33 | 0.85 / 36.12 |
| Commerce | 1.02 / 32.15 | 1.04 / 47.39 |
| Elasticsearch | 0.21 / 1.38 | 0.32 / 1.83 |
| k6 | 0.41 / 3.99 | 0.41 / 7.82 |
| Local model | 0.19 / 1.98 | 0.18 / 5.77 |
| MySQL | 4.20 / 27.85 | 2.53 / 104.33 |

At 06:12:21 UTC, the same instantaneous frame recorded Agent at 135.28% and MySQL at 104.33%
under the Docker allocation of 8 CPUs. This shows simultaneous use of roughly 1.35 and 1.04 CPU
cores in one frame; it does not establish aggregate saturation, a formal-window peak, or that
one component caused the other's work or the 20 failures.

## Conditional-pool decision

The conditional connection-pool branch was not triggered and no pool was implemented. The only
candidate signals were whole-invocation CPU samples, aggregate MySQL connection counters and the
generic persisted failure code `agent_execution_failed`. MySQL reported no max-connection error,
the 90/s high Agent and MySQL samples occurred in the same single frame, and the failure category
was not decomposed. That evidence does not establish connection creation as the bottleneck, so it
does not satisfy the causal precondition for a pool change.

The k6 `pool_exhausted=0` metric only records that the fixture's token/order/session pool was not
exhausted. It neither measures nor rules out exhaustion of an application HTTP or database
connection pool.

## Raw per-point bundles

Each row links all nine retained small artifacts. No point stream was generated for these Agent
runs, so there is no point-file link or reconstructed percentile calculation.

| Rate | Setup environment | Console | Summary / step row | Workload contract | Business SQL | MySQL status | Whole-invocation CPU |
|---:|---|---|---|---|---|---|---|
| 60/s | [setup](agent_capacityS4_prepare_r60_20260905T0603Z_setup_environment.json) | [console](agent_capacityS4_prepare_r60_20260905T0603Z_console.txt.gz) | [summary](agent_capacityS4_prepare_r60_20260905T0603Z_summary.json), [steps](agent_capacityS4_prepare_r60_20260905T0603Z_steps.txt) | [contract](agent_capacityS4_prepare_r60_20260905T0603Z_workload_contract.tsv) | [pending-action SQL](agent_capacityS4_prepare_r60_20260905T0603Z_pending_action_sql.txt) | [snapshot](agent_capacityS4_prepare_r60_20260905T0603Z_mysql.txt) | [samples](agent_capacityS4_prepare_r60_20260905T0603Z_cpu.txt), [sampler stderr](agent_capacityS4_prepare_r60_20260905T0603Z_cpu_errors.txt) |
| 90/s | [setup](agent_capacityS4_prepare_r90_20260905T0610Z_setup_environment.json) | [console](agent_capacityS4_prepare_r90_20260905T0610Z_console.txt.gz) | [summary](agent_capacityS4_prepare_r90_20260905T0610Z_summary.json), [steps](agent_capacityS4_prepare_r90_20260905T0610Z_steps.txt) | [contract](agent_capacityS4_prepare_r90_20260905T0610Z_workload_contract.tsv) | [pending-action SQL](agent_capacityS4_prepare_r90_20260905T0610Z_pending_action_sql.txt) | [snapshot](agent_capacityS4_prepare_r90_20260905T0610Z_mysql.txt) | [samples](agent_capacityS4_prepare_r90_20260905T0610Z_cpu.txt), [sampler stderr](agent_capacityS4_prepare_r90_20260905T0610Z_cpu_errors.txt) |

Within the recorded combined configuration and local benchmark topology, the supported result
is: **last clean tested action-prepare point 60 turns/s; first bad tested point 90 turns/s**. The
interval is a conditional 30-second stopping boundary. It is not a sustained production limit,
a confirmed-action rate, a single-mechanism gain, or evidence about real-model answer quality.
