# Real-model normal-task acceptance: smoke-gate stop

The planned 12-task, three-repetition normal-task acceptance did not enter its formal phase.
Its first grounded-retrieval smoke reached the real Agent, but the configured upstream model
route was unavailable. The formal set therefore has **0 of 36 tasks executed and none scored**;
this is an environment/connectivity stop, not an answer-quality result.

## Frozen boundary

- CityBuddy source was clean at full commit
  `c5af89d5e07fa5a20f0a32b865557fbbbb08aabd`.
- The run began at 2026-09-05 06:21:52 UTC while the host was fully awake. The real Agent used
  one worker, shared HTTP clients, attempt budget 16, model and client timeouts of 120 and 180
  seconds, and evaluation mode disabled.
- Primary and fallback role aliases were both `gpt-5.4`, temperature was 0, and the configured
  reranker alias was `support-reranker-standard`. The provider base and key came from the
  protected local environment and are not published.
- The client connected to a separate real Agent on port 18000. It did not connect to the Demo
  Agent on 8000 or the fake model on 8100.
- `knowledge_docs_read` resolved to `knowledge_docs_v1`, containing the four frozen documents:
  `faq-refund-policy/overview/v1`, `product-jasmine-tea/description/v3`,
  `faq-delivery/coverage/v2`, and `faq-store-hours/general/v1`.

The public-safe [run boundary](normal_task_acceptance_20260905_run_boundary.txt) records the
configuration, fixture, smoke gate, zero formal executions and cleanup facts without credentials.

## Smoke result and stop

The smoke question asked for the knowledge-base description of jasmine tea and the authority for
live price, inventory and availability. The Agent returned HTTP 200 after 4.260190583 seconds,
but its business outcome was `provider_denied`, with the fixed provider-unavailable reply, no
citations and no receipt. The persisted event sequence was:

1. the request was accepted and routed to the read profile;
2. one primary model attempt was charged against the attempt budget;
3. the `gpt-5.4` primary route recorded `MODEL_OUTCOME result=denied`;
4. the turn completed as `provider_denied` before any retrieval or reranking event.

The frozen gate required a successful grounded-retrieval, refusal/clarification and owned-refund
prepare smoke before the formal set. The first smoke failed, so the other two smokes and all 36
formal tasks were not run. No model alias, key, question, threshold or reranker behavior was
changed, and the failed request was not retried.

The retained public-safe raw records are the
[HTTP response](normal_task_acceptance_20260905_smoke_response.json) and
[persisted trace](normal_task_acceptance_20260905_smoke_trace.tsv).

## Direct route diagnosis

After cleanup, one minimal request was sent directly to the same normalized proxy endpoint with
the same protected key and `model=gpt-5.4`. At 2026-09-05 06:37:41 UTC the proxy returned HTTP
503 and the raw JSON error `auth_unavailable: no auth available (providers=codex,
model=gpt-5.4)`. The public-safe [probe record](normal_task_acceptance_20260905_provider_probe_record.json)
captures its status and non-secret request boundary, while the
[raw response body](normal_task_acceptance_20260905_provider_probe.json.gz) is retained byte-for-byte
in lossless gzip form. Neither contains a credential or proxy base URL.

The direct response is consistent with the earlier `provider_denied`, but it occurred after the
Agent smoke and does not prove that the earlier upstream response had the same status or body.
It confirms that upstream authentication was unavailable at the later diagnostic point. It does
not test CityBuddy's retrieval quality, the reranker mapping, refusal behavior or action protocol.
The real Agent and Demo-owned Auth/Commerce processes were stopped at 06:30:39 UTC; shared
infrastructure was preserved, the lid remained open, and source stayed clean.

## Result boundary

The only supported conclusion is that the 2026-09-05 normal-task acceptance stopped at its first
smoke after the configured model route returned `provider_denied`; a later direct probe reported
no available upstream authentication. A, B and C are each **0/12 unexecuted and unscored**. No
accuracy, refusal, action-success, latency or provider-availability rate may be derived from this
attempt.
