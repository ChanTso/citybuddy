# Agent workload latency

This document preserves the historical three-path measurement of a plain answer, an answer grounded
in retrieved knowledge, and a refund preparation that writes a durable pending action. The current
harness defines four workload selectors, including a separate bare greeting. Every measured result
reported here was produced by the scripts in this directory against the real local topology, and
the raw tool output is in `../results/`; no current four-selector result is reported here.

The first measurement found that most of the agent's CPU went on work it threw away. This
document now records that measurement, the change it led to, and the paired re-measurement that
says what the change was worth.

## Version boundary

Repository history reconstructs the paired baseline boundary as
`272eecdfb79b73811bc6fff677360a0d79a07991` and the post-client-reuse boundary as
`6acf856716ff0b926bb147c0e1d99614a8d9e9c8`. The artifacts were committed immediately after the
passes but do not contain those SHAs, so they cannot independently prove the checkout that
produced them.

PR #107 later introduced route-specific tool profiles. The retrieval and preparation inputs both
contain `refund` and still select `refund_context` with all tools. The chat input, `hello, can you
tell me about delivery times`, now selects the smaller `read` profile. Every chat number and CPU
reading in this document therefore describes the earlier all-tools-schema path, not the current
read-profile path. No post-#107 ladder is reported here.

## Current four-workload contract

Future results from the current harness are a **post-memory, empty-history, first-turn end-to-end
workload baseline**. `chat` remains the historical selector name, but now carries the delivery/read
workload; there is no renamed selector or compatibility alias.

| Selector | Exact message | Expected tool profile | Attempt ceiling under the default fixture | Work performed |
|---|---|---|---:|---|
| `greeting` | `hello` | `none` | 3 | Direct fixture answer with no tool schemas. |
| `chat` | `hello, can you tell me about delivery times` | `read` | 16 | Read schemas are visible; the fixture answers directly without a tool call. |
| `retrieval` | `retrieval-sufficient what does the refund policy cover` | `all` | 16 | Knowledge retrieval, reranking, and a closing model call. |
| `prepare` | `action-prepare refund my order <owned-order-id>` | `all` | 16 | OBO exchange and commerce preparation of a durable `PendingAction`. |

The greeting ceiling is `min(AGENT_ATTEMPT_BUDGET, 3)`; the other selectors use the configured
budget. The values above reflect the fixture's default budget of 16. The four workloads differ in
message, attempt ceiling, visible schemas, model calls, retrieval and commerce work. Their latency
cannot be compared to attribute memory SQL or tool-schema cost. The workload-contract SQL artifact
checks the expected route and zero loaded/included history for completed turns after the measured
window; it is neither a performance result nor a business grader. Defining this contract does not
make the current baseline ready to run: the separately scoped runtime and provenance prerequisites
remain incomplete.

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
8. **Before and after are measured in one sitting.** The before column in §1 is not the original
   run from the day before; it is a fresh baseline taken from the unmodified code on the same
   host, immediately before the after pass, because host state moves enough between days to be
   the weakest link in a comparison. The original runs are still in `../results/`:
   `agent_chat_steps.txt` gives 50 req/s at p99 36.5 ms against the fresh baseline's 50.1 ms, and
   `agent_retrieval_repeat_cpu_by_step.txt` gives 126 % median agent CPU at 10 req/s against the
   fresh baseline's 126 %.
9. **One of the retrieval baselines is a counterexample, and it is kept.** `agent_retrieval_*`
   without a suffix is a third retrieval ladder that **collapsed at 10 req/s** — 610 % median
   agent CPU, p99 24.8 s, 223 of 300 measured — where both `agent_retrieval_repeat_*` and the
   fresh `agent_retrieval_before_*` served that rate cleanly at p99 237 ms and 222 ms. Same
   fixture, same script, different run. So the retrieval before column in §1 is a rate that held
   on two of three attempts rather than one that always holds, and the knee is sharp enough that
   the same rate can land on either side of it. That cuts both ways here: it makes the retrieval
   before column the optimistic reading of the old behaviour, which understates rather than
   overstates the improvement.

## Results

### 1. What each path serves, before and after

Each path gets its own ladder over a freshly rebuilt fixture, one HTTP request per iteration,
real JWT verification, real retrieval, real MySQL writes. Both columns were measured in one
sitting, the baseline first from unmodified code
(`../results/agent_*_before_steps.txt`, `agent_*_after_steps.txt`).

`offered` is what the constant arrival rate asked for and `measured` is what produced a timing;
where they diverge, k6 could not start the iteration or it was still in flight when the step's
graceful stop expired, and the row is not a throughput measurement of the server. The percentiles
are over every request the step measured, a rejection included, so on a step that sheds they
describe the mix rather than the latency of a served turn.

**Plain chat turn** — one model call and the durable turn record:

| Target | | before | after |
|---:|---|---:|---:|
| 10 | p99 | 35.7 ms | **21.4 ms** |
| | outcomes | all 200 completed | all 200 completed |
| 25 | p99 | 31.9 ms | **20.8 ms** |
| | outcomes | all 500 completed | all 501 completed |
| 50 | p99 | 50.1 ms | **20.2 ms** |
| | outcomes | all 1001 completed | all 1001 completed |
| 75 | p99 | 1164.9 ms | **31.3 ms** |
| | outcomes | 1131 completed, **369 HTTP 503** | **all 1501 completed** |
| 100 | p99 | 1028.5 ms | **159.2 ms** |
| | outcomes | 1194 completed, **807 HTTP 503** | **all 2000 completed** |

**Knowledge retrieval** — alias resolution, mapping validation, BM25 and dense retrieval, RRF
fusion, rerank, then the closing model call:

| Target | | before | after |
|---:|---|---:|---:|
| 2 | p99 | 210.9 ms | **36.0 ms** |
| | outcomes | all 60 completed | all 60 completed |
| 5 | p99 | 176.5 ms | **35.5 ms** |
| | outcomes | all 151 completed | all 150 completed |
| 8 | p99 | 169.5 ms | **42.9 ms** |
| | outcomes | all 241 completed | all 241 completed |
| 10 | p99 | 221.8 ms | **42.2 ms** |
| | outcomes | all 301 completed | all 300 completed |
| 12 | p99 | 27375.5 ms | **40.2 ms** |
| | outcomes | **257 of 360 measured**, 8.6/s served | **all 361 completed**, 12.0/s served |

**Refund preparation** — a tool call, a just-in-time on-behalf-of token exchange, and a durable
`PendingAction` written through commerce:

| Target | | before | after |
|---:|---|---:|---:|
| 5 | p99 | 428.8 ms | **306.3 ms** |
| | outcomes | 151 action_pending | 150 action_pending, 1 HTTP 502 |
| 10 | p99 | 425.8 ms | **343.5 ms** |
| | outcomes | 299 action_pending, 1 HTTP 502 | 298 action_pending, 2 HTTP 502 |
| 15 | p99 | 945.0 ms | **424.4 ms** |
| | outcomes | 451 action_pending | 449 action_pending, 1 HTTP 502 |
| 20 | p99 | 6325.7 ms | **1227.2 ms** |
| | outcomes | 439 pending, **114 HTTP 503**, 1 HTTP 429; 18.5/s served | 596 pending, 3 HTTP 429, 1 HTTP 502; **20.0/s served** |
| 30 | p99 | 18445.8 ms | **11930.4 ms** |
| | outcomes | 511 pending, 169 HTTP 503; 22.7/s served | 669 pending, 60 HTTP 503, 5 HTTP 429; 24.5/s served |

These HTTP 502s are historical observations from before commerce pinned action timestamps to six
UTC fractional digits. The failure was not load-dependent or created by the shared client; its
timestamp-format mechanism and fix are written up under
[three things found while building the fixture](#three-things-found-while-building-the-fixture).
Because the measured code produced it on both sides, the serving rates below use shedding — HTTP
429 and 503 — as the collapse signal rather than "no error of any kind".

The highest step where nothing was shed:

| Path | before | after |
|---|---|---|
| Plain chat | 50 req/s, p99 50.1 ms | **75 req/s, p99 31.3 ms** |
| Knowledge retrieval | 10 req/s, p99 221.8 ms | **60 req/s, p99 688.7 ms** |
| Refund preparation | 15 req/s, p99 945.0 ms | 15 req/s, **p99 424.4 ms** |

Both the chat and the retrieval ladder ran off the top of their range after the change, so where
each one stops comes from extension ladders that start where the original ones stopped
(`../results/agent_chat_ext_after_steps.txt`, `agent_retrieval_ext_after_steps.txt`,
`agent_retrieval_ext2_after_steps.txt`). Chat's clean step above is still the 75 of the main
ladder, and the extension is what shows it shedding from 100 onward; retrieval's 60 comes from
the second extension:

| Chat, after | 100 | 110 | 120 | 130 | 150 |
|---|---:|---:|---:|---:|---:|
| p99 | 221.4 ms | 338.5 ms | 262.5 ms | 636.2 ms | 428.8 ms |
| shed (HTTP 503) | 8 | 25 | 21 | 158 | 395 |

| Retrieval, after | 15 | 20 | 25 | 30 | 40 | 50 | 60 | 75 | 90 | 110 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| p99 | 43.7 ms | 44.4 ms | 38.6 ms | 35.3 ms | 45.5 ms | 206.8 ms | 688.7 ms | 6826.5 ms | 11839.9 ms | 19041.4 ms |
| served/s | 15.0 | 20.0 | 25.0 | 30.0 | 40.0 | 50.0 | 60.0 | **66.2** | **72.1** | **79.7** |

**100 req/s on chat is a boundary, not a served rate.** Three ladders reached it and they
disagree: the main after-ladder served it clean at p99 159.2 ms with 147 peak MySQL connections,
the extension ladder shed 8 of 2001, and a third run served it clean at p99 40.5 ms with 135
peak. The honest reading is 75 req/s clean and 100 req/s marginal, not "serves 100".

That third run is also a check on the after column itself. The cookie-discarding transport in §3
was added after these ladders had been taken, so the chat ladder was run again against the source
snapshot recorded in `3a3d6b0946565281bcb1c6de8c25944cb46d7887`
(`../results/agent_chat_recheck_after_steps.txt`): p99 19.1 ms at 50 req/s against 20.2,
27.3 ms at 75 against 31.3, and every step clean. **Only the chat ladder was re-run.** The
retrieval and preparation columns, every CPU table in §2, all six profiles in §3 and the
connection figures in §4 were taken against the code as it stood before that transport was added.
The transport adds one dictionary membership test per response, on responses that never carry the
header, and the chat re-run shows no effect — but that is one ladder of three, so the scope of
the check is stated rather than generalised.

**The failure mode changed as much as the rate did.** Before, one step past the knee meant p99 in
seconds and the agent burning five to six cores. After, chat at 150 req/s — twice the old knee —
holds p99 at 429 ms and sheds the excess. Retrieval degrades the same way rather than collapsing:
p99 at 60 req/s is 689 ms against 27 s for the old 12 req/s step.

### 2. What a turn costs, and what the ceiling is now

Peak CPU over a whole ladder is dominated by whichever step collapsed, so it says nothing about
what serving the load costs. The runner reports each step's own window instead
(`../results/agent_*_cpu_by_step.txt`). Median agent CPU, before and after:

| Plain chat | 10 | 25 | 50 | 75 | 100 |
|---|---:|---:|---:|---:|---:|
| before | 15 % | 36 % | 74 % | 506 % | 315 % |
| after | 4 % | 9 % | 19 % | 29 % | 40 % |

| Knowledge retrieval | 2 | 5 | 8 | 10 | 12 |
|---|---:|---:|---:|---:|---:|
| before | 22 % | 56 % | 85 % | 126 % | 589 % |
| after | 2 % | 4 % | 6 % | 7 % | 10 % |

| Refund preparation | 5 | 10 | 15 | 20 | 30 |
|---|---:|---:|---:|---:|---:|
| before | 21 % | 46 % | 96 % | 112 % | 113 % |
| after | 3 % | 7 % | 16 % | 17 % | 19 % |

At the rates where the before ladder was still serving rather than collapsing, a chat turn costs
about a quarter of what it did (74 % to 19 % at 50 req/s) and a retrieval turn a tenth to a
twentieth (126 % to 7 % at 10 req/s). The two collapsed before-steps are not a comparison: 506 %
and 589 % are the cost of being overloaded, not the cost of the work.

Pushed past the old range, the agent climbs to a plateau and stops there
(`agent_chat_ext_after_cpu_by_step.txt`, `agent_retrieval_ext*_after_cpu_by_step.txt`):

| Chat, after | 100 | 110 | 120 | 130 | 150 |
|---|---:|---:|---:|---:|---:|
| median agent CPU | 35 % | 48 % | 78 % | 133 % | 145 % |

| Retrieval, after | 15 | 20 | 25 | 30 | 40 | 50 | 60 | 75 | 90 | 110 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| median agent CPU | 12 % | 14 % | 17 % | 23 % | 31 % | 35 % | 61 % | 138 % | 135 % | 133 % |

**Both paths stop at about 1.4 cores of the eight available, and nothing downstream is busy when
they do.** Median CPU per container inside the collapsed steps, read from the same raw samples
(`../results/agent_retrieval_ext2_after_cpu.txt`, `agent_chat_ext_after_cpu.txt`):

| Step | agent | MySQL | Elasticsearch | model fixture | commerce |
|---|---:|---:|---:|---:|---:|
| retrieval, 75 req/s | **138.0 %** | 38.3 % | 17.8 % | 6.6 % | 0.2 % |
| retrieval, 90 req/s | **134.6 %** | 38.0 % | 17.4 % | 6.4 % | 0.2 % |
| retrieval, 110 req/s | **133.0 %** | 39.3 % | 18.3 % | 6.3 % | 0.1 % |
| chat, 130 req/s | **133.2 %** | 49.0 % | 0.2 % | 5.1 % | 0.1 % |
| chat, 150 req/s | **145.2 %** | 54.5 % | 0.2 % | 5.0 % | 0.1 % |

Each row is the median of the `docker stats` samples inside that step's own twenty- or
thirty-second window, the same windows the runner prints; a window spanning the gaps between
steps would mix in the drain and understate every figure.

Throughput stops rising, latency absorbs the rest, and every dependency has capacity to spare.
Before the change the ceiling was the agent's own wasted CPU; after it, the ceiling is the agent
process itself.

**What saturates inside the process is not established here, and there are two candidates.** The
first is the interpreter lock: the endpoints are sync handlers on AnyIO's 40-thread pool, and
forty threads contending for one GIL produce roughly this shape — a little over one core of
aggregate progress, the excess coming from C extensions and syscalls that release it.

The second is the connection pool this change introduced. In the retrieval profile at concurrency
8, 11.5 % of on-CPU samples sit in one httpcore lock, and they split almost exactly in half
between taking a connection out of the pool (174 samples at `connection_pool.py:218`) and putting
one back (173 at `:416`). That is contention on a single shared mutex, and §3's claim that the
pool cannot become a new queue is narrower than it sounds: it is about connection *count*, which
never binds at 48 against 40 threads, and says nothing about the lock that guards the pool.

**Both are readings, not measurements**, and the evidence available here does not separate them —
the pool lock is where the samples are, but samples pile up at whichever lock is contended and
that does not make it the cause. Two cheap experiments would: serve the same ladders from N
uvicorn worker processes and see whether the plateau moves with N, which separates
process-from-everything-else; and vary the pool between one shared client and one per dependency,
which separates the pool mutex from the GIL. Neither has been run. What the measurement does
support is only the outer claim: the ceiling is the agent process, not any dependency it talks
to.

### 3. Most of the agent's on-CPU time built TLS trust stores for plaintext URLs

The agent reached every dependency through the module-level `httpx` helpers — `httpx.post`,
`httpx.get` and `httpx.request` at seven call sites, plus `httpx.stream` at two more — and held
**no reused `httpx.Client` anywhere**. Each helper call constructs a whole client, and
constructing a client constructs a default SSL context, which loads and parses the system CA
bundle. Measured in the same container: **13.1 ms of CPU per construction**. Every one of those
URLs is `http://` — the model fixture, commerce, the auth service and Elasticsearch are all
plaintext here — so no handshake ever followed the trust store that had just been built.

py-spy at 100 Hz agreed. It excludes idle threads, so the denominator is threads that were
actually running, and the share of those samples sitting in `ssl.create_default_context`
(`../results/agent_pyspy_*.txt`, tallied with the call sites and the per-construction cost in
`../results/agent_cpu_profile.txt`, which records the state before the change):

| Path | concurrency 1 | concurrency 8 |
|---|---:|---:|
| Plain chat | 64.3 % | 71.2 % |
| Knowledge retrieval | 83.8 % | 94.9 % |
| Refund preparation | 57.0 % | 55.4 % |

Concurrency 1 is at or below where each path sat at the rate it served, and concurrency 8 is well
past every knee. The share was dominant in both, so it was not an artifact of overload.

**The fix** is [`http_client.py`](../../agent-service/src/citybuddy_agent/http_client.py): one
process-wide `httpx.Client`, with the nine call sites routed through it. Three details matter for
reading the numbers below.

The pool is sized at 48 connections, above the 40-thread AnyIO pool that Starlette runs the sync
handlers on. A turn holds one outbound request at a time, so forty is the concurrency the
handlers can reach and the pool cannot become a new queue — otherwise the after column would be
measuring the new pool limit rather than the change.

A pooled connection can be closed by the peer between two requests, which arrives as
`httpx.RemoteProtocolError` rather than `httpx.NetworkError`, so the transport-failure
classification now includes it. Its base class is deliberately not used: the sibling
`LocalProtocolError` is this service violating HTTP itself, and proxy and unsupported-protocol
errors are configuration faults. None of those is a dependency failure.

A shared client also carries cookies between requests, where a client built per call was
discarded along with anything it had picked up. The agent reaches commerce and auth for many
different users with one just-in-time token per request, so a stored cookie would travel from one
user's request into another's. None of the four boundaries sets a cookie today, so this was
latent rather than live; the client discards them in its transport so that staying true is not a
precondition for the change.

Re-profiled after the change, same script, same concurrencies
(`../results/agent_pyspy_*_after_c*.txt`, all six with
`load-still-running-at-end-of-sample=true`):

| Path | concurrency 1 | | concurrency 8 | |
|---|---:|---|---:|---|
| | before | after | before | after |
| Plain chat | 64.3 % | **0.0 %** (525 samples) | 71.2 % | **0.0 %** (3188 samples) |
| Knowledge retrieval | 83.8 % | **0.0 %** (512 samples) | 94.9 % | **0.0 %** (3022 samples) |
| Refund preparation | 57.0 % | **0.0 %** (24 samples) | 55.4 % | **0.0 %** (353 samples) |

Sample counts are given because they vary by three orders of magnitude across these cells. The
preparation path at concurrency 1 yields only 24 on-CPU samples in fifteen seconds — the agent is
almost never running, because the turn is spent blocked on commerce — so 0.0 % there is not a
precise estimate. It is still decisive against the old share: zero of twenty-four is not a
sample drawn from a population where 57 % of samples match.

What the agent's CPU is spent on instead, at retrieval and concurrency 8:

| before | after |
|---|---|
| 94.4 % `ssl.create_default_context` | 19.4 % + 11.2 % + 3.0 % `pymysql` socket read and write |
| 0.8 % `socket.readinto` | 13.8 % `socket.readinto` |
| 0.8 % `pymysql` socket read | 11.5 % httpcore connection-pool lock |
| 0.7 % `pymysql` socket write | 7.1 % httpcore socket-readable check |

The profile is now dominated by real I/O, and its single largest item is MySQL wire traffic —
which is consistent with 5.2 connections per retrieval turn and no pooling, and corroborates §4
from a second direction.

**A prediction in the first version of this document was wrong.** It said removing this would
produce "a cheaper turn rather than a higher ceiling", on the grounds that §2 showed the agent
was not CPU-bound where it served cleanly, and allowed only that the knee might move. The turn
did get cheaper, but the ceiling moved a great deal too: chat from 50 to 75 req/s clean with
100 marginal, retrieval from 10 to 60. Being not-CPU-bound at the serving rate did not imply the
wasted CPU was irrelevant to where serving stopped.

### 4. The connection limit decides how overload fails, and now it decides it later

The agent's conversation store opens a **fresh `pymysql.connect` per persistence call and pools
nothing** (`conversation.py:1205`, six call sites). This change does not touch that layer, and
connections opened per measured request over a whole ladder are unchanged:

| Path | before | after |
|---|---:|---:|
| Knowledge retrieval | 5.21 | 5.18 |
| Refund preparation | 6.09 | 6.09 |

Both are whole-ladder totals divided by whole-ladder measured requests, and every one of these
four ladders contains steps that shed or failed to measure — a rejected request does less database
work than a completed turn, so these are a coarse check that the layer is untouched, not a
per-turn cost.

What changed is when the limit is reached. A shorter turn holds its connections for less time, so
the same arrival rate keeps fewer of them open at once
(`../results/agent_*_mysql.txt`):

| Ladder | attempts rejected at `max_connections` | peak concurrent | limit |
|---|---:|---:|---:|
| chat before, 10–100 req/s | **1,260** | **152** | 151 |
| chat after, 10–100 req/s | **0** | 147 | 151 |
| chat after, 100–150 req/s | 618 | 152 | 151 |
| retrieval before, 2–12 req/s | 0 | 133 | 151 |
| retrieval after, 50–110 req/s | **0** | 141 | 151 |

The counters are whole-ladder totals, so they say the limit was crossed but not at which step.
The outcome columns say that: before, chat's first shedding step is 75 req/s; after, it is 100.
Retrieval never rejects at all, even at 110 req/s where it is thoroughly collapsed — its ceiling
is elsewhere, which is what §2 shows.

The original control experiment still stands and was not repeated: raising
`max_connections` to 1000 and rerunning the identical before-ladder
(`../results/agent_chat_control_steps.txt`) left the served rate unchanged at 50 req/s, turned
545 rejections into 3, and let the same load queue to a 6.9 s p99 instead of failing fast. The
limit was doing admission control, not capping throughput.

MySQL is at 49–54 % of one core when the limit bites, so this is a configured cap on a database
with capacity to spare rather than a loaded database. That makes connection pooling in the
conversation store look like free headroom, and the control experiment says it is not: removing
the cap did not raise what the path served, it converted fast rejections into long queues. Read
alongside §2 — the agent plateaus at about 1.4 cores while MySQL sits at half of one — the
expectation is that pooling would change how the chat path fails, and would cut the MySQL wire
traffic that is now the largest single item in the agent's own profile, without moving the rate
it serves. Retrieval, which reaches its ceiling without ever touching the limit, is the control
for that reading.

**Nothing in the agent bounds its own concurrency on any path.** What differs is only what
happens to bite first: a configured database limit on chat, the commerce tool boundary on
preparation, and on retrieval nothing external at all — just the process running out of itself.

## Three things found while building the fixture

**Concurrent mock-payment settlement exposed two deadlocks; both lock paths are now removed.**
Building 6,000 paid orders in parallel historically produced
`CannotAcquireLockException` out of `POST /api/orders/{orderId}/mock-payment`. The recorded raw
deadlock (`../results/mock_payment_callback_deadlock.txt`) is between the callback's attempt
lookup —

```sql
SELECT ... FROM mock_payment_attempt
 WHERE attempt_id = ? OR callback_correlation_id = ? OR order_id = ? LIMIT 2 FOR UPDATE
```

— and the payment *start*'s lookup on `order_id`, for two different orders, each holding an X lock
on `PRIMARY` that the other waits for. InnoDB rolled back the start. At the time,
`MockPaymentService.withCallbackDeadlockRetry` retried and then resolved the competing callback
result, while `withStartCompetitionRetry` rethrew once its attempts were spent and no handler
mapped `CannotAcquireLockException`; the fixture failure therefore surfaced as HTTP 500.
The raw InnoDB artifact records the database cycle, not the HTTP response; the response is the
observed fixture failure explained by that exception path.

`EXPLAIN` on the historical callback query reported `type: ALL, key: NULL` despite `attempt_id`
and `callback_correlation_id` both being uniquely indexed, so under `FOR UPDATE` it could lock
every row it examined. That plan came from a six-row table, where the optimizer can prefer a scan
regardless of predicate shape; it is consistent with the three-way `OR` defeating point access,
but is not proof by itself. The start lookup used a covering scan of `uq_mock_payment_order`
because the index was `(order_kind, order_id)` while the predicate named only `order_id`.

The first correction removed both scan shapes. Migration V016 preserves the uniqueness rule but
reverses that index to `(order_id, order_kind)`. Attempt-closure enumeration decomposes the `OR`
into separately bounded reads and uses `FORCE INDEX` for `PRIMARY`,
`uq_mock_payment_callback_correlation`, or `uq_mock_payment_order` according to the locator. A
real-MySQL lock regression holds an unrelated attempt row locked and observes the callback and
order closure reads for two other orders complete before that lock is released.

Restoring four-way settlement then exposed a second cycle during payment start. A new command
looked up both its request key and order relation with `FOR UPDATE` before inserting the attempt.
When neither row existed, InnoDB held compatible next-key gap locks in
`uq_mock_payment_request` and `uq_mock_payment_order`; two transactions could therefore reach the
insert together, then each one's insert-intention lock waited for the other's gap lock. The first
6,000-user run with only the scan correction converged only through the fixture's outer same-key
retry: it took 36.2 seconds and commerce logged 184 typed
`DEPENDENCY_OBSERVATION_INDETERMINATE` responses.

Start resolution now performs each bounded attempt lookup as a consistent read. A miss remains
unlocked and the existing unique constraints adjudicate any competing insert. A hit is immediately
read again through the same forced index with `FOR UPDATE`; it must still identify the same
attempt, and the resolver uses that current locked row for complete closure validation. The
existing duplicate-key retry starts a fresh transaction after the losing insert rolls back. If
the current locked order differs from the order seen alongside the unlocked attempt misses, the
resolver likewise abandons that snapshot and retries from a fresh transaction. A deterministic
start/callback race requires that path to return the committed replay rather than misclassify the
changed order as an integrity failure; exhausting repeated observation changes returns the same
typed retryable 503 used for recognized MySQL lock contention. A real-MySQL regression also pauses
two distinct starts immediately before insert and requires both to commit without any internal
lock failure.

The acceptance run separates fixture phases because the full setup also has an independent gap
lock cycle in `order_idempotency`. Creating 6,000 orders raised MySQL's cumulative 1213 counter
from 26,179 to 26,589. The following 6,000 four-way parallel payment settlements completed in
15.6 seconds and left it at 26,589, with the 1205 counter at zero, no typed payment 503s, and SQL
counts of 6,000 paid orders, attempts, succeeded attempts, callbacks, and payment ledger movements.
The workload, environment, raw counters, and queries are preserved in
[`../results/mock_payment_parallel_settlement_fix.txt`](../results/mock_payment_parallel_settlement_fix.txt).
This proves the measured payment boundary; it does not claim that every possible database
deadlock is impossible. The order-creation defect isolated from that result is measured separately
below.

Payment start still has bounded retries for MySQL 1205/1213 contention. If a recognized conflict
exhausts them, commerce now returns typed 503
`DEPENDENCY_OBSERVATION_INDETERMINATE`, which is retryable with the same idempotency key, instead
of exposing the lock exception as 500. The fixture builder has returned to bounded parallel
settlement and already retries 503. The deterministic MySQL cases and isolated acceptance run are
the regression boundary, and the raw historical deadlock remains preserved above.

**The isolated order-idempotency deadlock had the same locking-miss-then-insert mechanism.** A new
order first selected its exact `(user_subject, idempotency_key)` primary-key entry with
`FOR UPDATE`. When the entry was absent, InnoDB protected the containing gap. Two unrelated orders
in the same gap could both hold those compatible locks; each following insert then waited for an
insert-intention lock that conflicted with the other's gap lock. The historical isolated phase
raised the cumulative 1213 counter by 410 while eventually creating all 6,000 orders through the
fixture's bounded same-key retries.

Mutation discovery now uses a consistent exact-key read. A miss remains unlocked and the primary
key arbitrates a same-key insert race; a positive discovery is immediately reread with `FOR UPDATE`
and the current locked row is used for intent and replay validation. Recovery observations remain
locking current reads because they may follow a rolled-back competing transaction and must classify
committed truth. A real-MySQL regression pauses two distinct transactions immediately before inserts
into one primary-key gap and requires two reservations, orders and outbox events with no vendor
1213.

The matching four-worker acceptance reran 6,000 distinct order creations in 16.9 seconds. MySQL's
1205 counter remained zero and its 1213 counter remained 26,589; authoritative SQL found 6,000
unpaid orders, matched idempotency rows, order-created outbox events and distinct owners, with no
orphaned idempotency row. The printed duration includes each user's login as well as order creation,
as did the historical fixture phase. The exact workload, raw counter samples and SQL are preserved
in
[`../results/order_idempotency_parallel_creation_fix.txt`](../results/order_idempotency_parallel_creation_fix.txt).

**The sporadic refund-preparation HTTP 502 was a timestamp-format mismatch.** A small fraction of
preparations ended `ACTION_PREPARATION_RESPONSE_INVALID` and HTTP 502
(`agent_control.py`, the handler around `strict_json_object` and
`PreparedActionResponse.model_validate`): commerce answered 200 or 201, and the agent could not
turn that body into the expected document. Commerce stores microseconds, but Jackson's default
`Instant` rendering uses fractional digits in groups of three. A microsecond timestamp whose last
three digits are zero is therefore rendered with milliseconds (`.123Z`) while the agent's action
boundary deliberately requires canonical UTC microseconds (`.123000Z`). The `PendingAction` is
already durable when that response is rejected. This is independent of load and of connection
reuse. Four historical ladders, counted from the outcome columns of
`../results/agent_prepare_*_steps.txt`:

| Code | HTTP 502 | preparations measured | rate |
|---|---:|---:|---:|
| before (`agent_prepare_steps.txt`, `agent_prepare_before_steps.txt`) | 1 | 4,263 | 1 in 4,263 |
| after (`agent_prepare_after_steps.txt`, `agent_prepare_after2_steps.txt`) | **8** | 4,475 | **1 in 559** |

Across both columns the observed rate is 9 in 8,738, or 1 in 971, consistent with one affected
microsecond value in every 1,000. The before/after split was real evidence that the failure could
not be dismissed, but it was not evidence of a client-reuse mechanism. Commerce now renders both
`expiresAt` and the receipt's identically strict `committedAt` with exactly six UTC fractional
digits. Controller regressions use millisecond-aligned instants, so the former variable-width
rendering fails deterministically.

**The former default attempt budget could not fit a successful retrieval turn.** In
`knowledge.py`, `search` resolves the alias, validates the mapping, and then runs one BM25 and one
dense query per query text including the rewrite; with the reranker and the opening model call
that is eight charged attempts. The old default of 8 left nothing for the closing model call. The
default is now 16, matching the benchmark's pinned workload setting, and a regression test proves
that a rewrite retrieval reaches composition after exactly nine successful charges. The existing
bounded model fallback and reranker retry policy can raise a successful rewrite path to 14
physical attempts, so setting the default to the bare minimum of 9 would still turn an ordinary
transient response into budget exhaustion.

## What to measure next

The ceiling is now the agent process rather than anything it depends on (§2), and the single
cheapest experiment that would settle the mechanism is to serve the same ladders from N uvicorn
worker processes and see whether the plateau at ~1.4 cores moves with N. If it does, the
structural change is that the agent must not be one process, and MySQL connection pooling becomes
a follow-on that raises the chat path's own limit rather than the headline.

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

`LABEL` names the output files. The ladder runner requires every target file to be absent and
refuses to overwrite an existing label; choose a fresh label for every run. Both the ladder runner
and the profiler take it. Setup first removes any previous completed boundary, then builds the
current auth and commerce JARs, applies all three canonical migration streams, and creates the
knowledge fixture in an isolated, tmpfs-backed Elasticsearch container. A completed setup uses
atomic renames to publish `bench/.run/agent_setup_boundary.json` first and the compatibility
`bench/.run/citybuddy_commit` gate last. The boundary records the full source commit, actual host
and mounted JAR hashes, Java runtime versions, every migration version and checksum, and the exact
knowledge alias, mapping and corpus. Raw MySQL rows and Elasticsearch JSON remain beside it under
the ignored `bench/.run/` directory. A failed setup leaves neither completed marker usable. The
runner and profiler require the same source-clean checkout and write that SHA into every result
they produce.

### The paired ladders

Each pass is three setups and three ladders. Run the baseline pass from
`272eecdfb79b73811bc6fff677360a0d79a07991` and the post-client-reuse pass from
`6acf856716ff0b926bb147c0e1d99614a8d9e9c8`, back to back on an idle host, and give them
different labels:

```bash
AGENT_BENCH_USERS=6000 ./bench/agent/setup_agent_bench.sh
```

```bash
LABEL=chat_reproduction_$(date -u +%Y%m%dT%H%M%SZ) RATES=10,25,50,75,100 STEP_SECONDS=20 ./bench/agent/run_agent_ladder.sh chat
```

```bash
AGENT_BENCH_USERS=2500 ./bench/agent/setup_agent_bench.sh
```

```bash
LABEL=retrieval_reproduction_$(date -u +%Y%m%dT%H%M%SZ) RATES=2,5,8,10,12 STEP_SECONDS=30 ./bench/agent/run_agent_ladder.sh retrieval
```

```bash
AGENT_BENCH_USERS=3000 ./bench/agent/setup_agent_bench.sh
```

```bash
LABEL=prepare_reproduction_$(date -u +%Y%m%dT%H%M%SZ) RATES=5,10,15,20,30 STEP_SECONDS=30 ./bench/agent/run_agent_ladder.sh prepare
```

The runner prints the per-step table and writes it to `bench/results/agent_<label>_steps.txt`.

### The extension ladders

After the change both chat and retrieval run off the top of the range above, so the knee comes
from ladders that start where those stop:

```bash
AGENT_BENCH_USERS=12500 ./bench/agent/setup_agent_bench.sh
```

```bash
LABEL=chat_extension_$(date -u +%Y%m%dT%H%M%SZ) RATES=100,110,120,130,150 STEP_SECONDS=20 ./bench/agent/run_agent_ladder.sh chat
```

```bash
AGENT_BENCH_USERS=11800 ./bench/agent/setup_agent_bench.sh
```

```bash
LABEL=retrieval_extension_$(date -u +%Y%m%dT%H%M%SZ) RATES=50,60,75,90,110 STEP_SECONDS=30 ./bench/agent/run_agent_ladder.sh retrieval
```

### The CPU attribution

This is a separate script, because it drives a fixed concurrency rather than a fixed arrival
rate. Each profile needs its own fixture: the driver takes pool entries from index 0, so a second
profile over one fixture would put two turns on every session.

```bash
AGENT_BENCH_USERS=7500 ./bench/agent/setup_agent_bench.sh
```

```bash
LABEL=retrieval_after CONCURRENCY=8 REQUESTS=7200 SECONDS_TO_SAMPLE=15 ./bench/agent/profile_agent_cpu.sh retrieval
```

It writes the raw py-spy collapsed stacks to
`bench/results/agent_pyspy_<label>_c<concurrency>.txt` and prints the tally. Check the header
line: `load-still-running-at-end-of-sample=false` means the load finished early and part of the
window sampled an idle process, which dilutes every share — raise `REQUESTS` and run it again.
`REQUESTS` has to be sized for the code being profiled, and the sizes above are for the faster
post-change agent; the pre-change profiles in `../results/` used a quarter of them.
