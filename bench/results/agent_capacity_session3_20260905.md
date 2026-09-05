# Agent retrieval and chat capacity, session 3

This normal-awake local session measured the current Agent configuration as a whole. Retrieval's
last clean tested point was 90 offered turns/s and its first bad tested point was 120 turns/s.
Chat remained clean at 100 and 150 offered turns/s and first became bad at 200 turns/s. Both bad
classifications came from the registered adjacent-clean p99 two-times rule; every completed
formal turn at all five points was served, with zero nonserved, dropped, interrupted, HTTP 5xx or
other HTTP-error outcomes. These are 30-second local benchmark points, not production capacity
or model-quality evidence.

## Source, configuration and host boundary

- Every bundle records CityBuddy, SUT and benchmark harness at full commit
  `c5af89d5e07fa5a20f0a32b865557fbbbb08aabd`.
- Each setup requested and observed four Agent workers, one shared outbound HTTP-client layout
  and attempt budget 16. Docker had 8 allocated CPUs and 14,638,391,296 bytes of memory. The
  model endpoint and supporting services were local benchmark containers; this is not a
  real-provider run or a normal-task acceptance result.
- MySQL `max_connections` was changed from its original 151 to 1,000 at 05:31:17 UTC, remained
  1,000 in all five before/after snapshots, and was restored to 151 at 05:57:09 UTC. The
  connection setting is part of the tested configuration, not an independently isolated cause.
- The session began at 05:29:35 UTC with `AppleClamshellState=No` and an operator-confirmed
  `FullWake` precondition. The end record reports the lid still open and no sleep since the
  03:46:40 UTC full wake. Load ended at 05:57:08 UTC and the benchmark services were stopped.
- Three pre-session Docker idle samples recorded the retained shared Broker proxy at
  104.60/104.16/104.81% CPU, MySQL at 0.10/1.50/1.45%, support Redis at
  0.88/0.02/0.87%, and Elasticsearch at 0.09/0.08/0.11%. In Docker's convention, the Broker
  observation is roughly one CPU core of existing shared background activity. It belongs to
  the local environment boundary and is not Agent CPU.
- The power, idle, configuration-change and cleanup facts are retained in the operator boundary
  record `bench/.run/agent-capacity-session3-20260905T052935Z.txt`. That ignored operator file
  is not part of the published per-point bundle.

Each k6 invocation contained a 5 turns/s, 30-second pre-step, a wait, and one 30-second target
step. Only the target-rate tagged metrics are formal results below. The CPU samplers span the
whole invocation rather than a separately delimited formal window, so their raw values are not
reported as formal-window CPU, method cost or CPU per turn.

This session did not toggle worker count, shared-client layout, TLS behavior, digest behavior or
the MySQL connection setting one at a time. Its latency and stopping points therefore describe
the combined current configuration and local topology. They must not be attributed to TLS reuse,
digest verification, pooling or another single mechanism, and are not a continuation of the
historical retrieval 10-to-60 single-variable result.

## Formal target-step results

`Started`, `finished`, `served`, outcome counters and percentiles below are copied from each
target-rate summary and retained step row, not from whole-invocation totals. The one extra
iteration at each target is the recorded constant-arrival-rate boundary result and is not
normalized away.

| Path / offered rate | Nominal | Started / finished / served | Nonserved / dropped / interrupted / 5xx / errors | p50 ms | p99 ms | Classification |
|---|---:|---|---|---:|---:|---|
| Retrieval 90/s | 2,700 | 2,701 / 2,701 / 2,701 | 0 / 0 / 0 / 0 / 0 | 19.385417 | 59.1235 | Last clean tested retrieval point |
| Retrieval 120/s | 3,600 | 3,601 / 3,601 / 3,601 | 0 / 0 / 0 / 0 / 0 | 25.419708 | 152.306167 | First bad retrieval point: p99 stop |
| Chat 100/s | 3,000 | 3,001 / 3,001 / 3,001 | 0 / 0 / 0 / 0 / 0 | 6.477125 | 25.211625 | Clean |
| Chat 150/s | 4,500 | 4,501 / 4,501 / 4,501 | 0 / 0 / 0 / 0 / 0 | 8.065667 | 39.877125 | Last clean tested chat point |
| Chat 200/s | 6,000 | 6,001 / 6,001 / 6,001 | 0 / 0 / 0 / 0 / 0 | 16.400875 | 1185.239417 | First bad chat point: p99 stop |

The workload contracts record every target turn as completed with the expected benchmark route
profile (`all` for retrieval and `read` for chat) and zero unexpected-routing events. The
contracts themselves label their role as workload-shape evidence, not a performance or business
grader. Thus `served` and profile matching establish the benchmark outcome shape; they do not
establish answer relevance, semantic quality or real-model usefulness.

## MySQL observations

These are the exact server-status rows captured immediately before and after each invocation.
`Connections` is the server's cumulative connection counter; `Max_used_connections` is its
observed high-water counter. Neither is a formal-only CPU or latency measurement.

| Path / offered rate | `Connections` before -> after | `Max_used_connections` before -> after | `Connection_errors_max_connections` before -> after | `max_connections` before / after |
|---|---:|---:|---:|---:|
| Retrieval 90/s | 8,283 -> 22,598 | 97 -> 112 | 0 -> 0 | 1,000 / 1,000 |
| Retrieval 120/s | 30,883 -> 49,697 | 97 -> 112 | 0 -> 0 | 1,000 / 1,000 |
| Chat 100/s | 59,998 -> 75,813 | 97 -> 109 | 0 -> 0 | 1,000 / 1,000 |
| Chat 150/s | 86,114 -> 109,434 | 97 -> 117 | 0 -> 0 | 1,000 / 1,000 |
| Chat 200/s | 119,717 -> 150,532 | 97 -> 138 | 0 -> 0 | 1,000 / 1,000 |

The absence of max-connection errors is a business-adjacent environment observation, not proof
that raising the limit caused the measured capacity. The original value 151 was restored after
the final snapshot.

## Raw per-point bundles

Each row links all eight retained small artifacts. No point stream was generated for these Agent
runs, so there is no point-file link and no reconstructed percentile calculation.

| Path / rate | Setup environment | Console | Summary / step row | Workload contract | MySQL | Whole-invocation CPU |
|---|---|---|---|---|---|---|
| Retrieval 90/s | [setup](agent_capacityS3_retrieval_r90_20260905T0533Z_setup_environment.json) | [console](agent_capacityS3_retrieval_r90_20260905T0533Z_console.txt.gz) | [summary](agent_capacityS3_retrieval_r90_20260905T0533Z_summary.json), [steps](agent_capacityS3_retrieval_r90_20260905T0533Z_steps.txt) | [contract](agent_capacityS3_retrieval_r90_20260905T0533Z_workload_contract.tsv) | [snapshot](agent_capacityS3_retrieval_r90_20260905T0533Z_mysql.txt) | [samples](agent_capacityS3_retrieval_r90_20260905T0533Z_cpu.txt), [sampler stderr](agent_capacityS3_retrieval_r90_20260905T0533Z_cpu_errors.txt) |
| Retrieval 120/s | [setup](agent_capacityS3_retrieval_r120_20260905T0540Z_setup_environment.json) | [console](agent_capacityS3_retrieval_r120_20260905T0540Z_console.txt.gz) | [summary](agent_capacityS3_retrieval_r120_20260905T0540Z_summary.json), [steps](agent_capacityS3_retrieval_r120_20260905T0540Z_steps.txt) | [contract](agent_capacityS3_retrieval_r120_20260905T0540Z_workload_contract.tsv) | [snapshot](agent_capacityS3_retrieval_r120_20260905T0540Z_mysql.txt) | [samples](agent_capacityS3_retrieval_r120_20260905T0540Z_cpu.txt), [sampler stderr](agent_capacityS3_retrieval_r120_20260905T0540Z_cpu_errors.txt) |
| Chat 100/s | [setup](agent_capacityS3_chat_r100_20260905T0545Z_setup_environment.json) | [console](agent_capacityS3_chat_r100_20260905T0545Z_console.txt.gz) | [summary](agent_capacityS3_chat_r100_20260905T0545Z_summary.json), [steps](agent_capacityS3_chat_r100_20260905T0545Z_steps.txt) | [contract](agent_capacityS3_chat_r100_20260905T0545Z_workload_contract.tsv) | [snapshot](agent_capacityS3_chat_r100_20260905T0545Z_mysql.txt) | [samples](agent_capacityS3_chat_r100_20260905T0545Z_cpu.txt), [sampler stderr](agent_capacityS3_chat_r100_20260905T0545Z_cpu_errors.txt) |
| Chat 150/s | [setup](agent_capacityS3_chat_r150_20260905T0550Z_setup_environment.json) | [console](agent_capacityS3_chat_r150_20260905T0550Z_console.txt.gz) | [summary](agent_capacityS3_chat_r150_20260905T0550Z_summary.json), [steps](agent_capacityS3_chat_r150_20260905T0550Z_steps.txt) | [contract](agent_capacityS3_chat_r150_20260905T0550Z_workload_contract.tsv) | [snapshot](agent_capacityS3_chat_r150_20260905T0550Z_mysql.txt) | [samples](agent_capacityS3_chat_r150_20260905T0550Z_cpu.txt), [sampler stderr](agent_capacityS3_chat_r150_20260905T0550Z_cpu_errors.txt) |
| Chat 200/s | [setup](agent_capacityS3_chat_r200_20260905T0555Z_setup_environment.json) | [console](agent_capacityS3_chat_r200_20260905T0555Z_console.txt.gz) | [summary](agent_capacityS3_chat_r200_20260905T0555Z_summary.json), [steps](agent_capacityS3_chat_r200_20260905T0555Z_steps.txt) | [contract](agent_capacityS3_chat_r200_20260905T0555Z_workload_contract.tsv) | [snapshot](agent_capacityS3_chat_r200_20260905T0555Z_mysql.txt) | [samples](agent_capacityS3_chat_r200_20260905T0555Z_cpu.txt), [sampler stderr](agent_capacityS3_chat_r200_20260905T0555Z_cpu_errors.txt) |

## Stop results and unrun points

Retrieval 90/s set the next p99 line at `2 x 59.1235 = 118.247 ms`. Retrieval 120/s recorded
p99 152.306167 ms, above the line, and was the first bad retrieval point. Retrieval 160, 200,
250 and any doubling point were not run.

Chat 100/s was clean. Chat 150/s remained below the preceding line because
`39.877125 < 2 x 25.211625 = 50.42325 ms`; it set the next line at
`2 x 39.877125 = 79.75425 ms`. Chat 200/s recorded p99 1185.239417 ms, above that line, and was
the first bad chat point. Chat 300 and any doubling point were not run.

Within the recorded combined configuration and local benchmark topology, the supported result
is: **retrieval last clean tested at 90 turns/s and first bad at 120 turns/s; chat last clean
tested at 150 turns/s and first bad at 200 turns/s**. The two intervals are conditional
30-second stopping boundaries. They are not sustained production limits, single-mechanism gains,
or evidence about real-model answer quality.
