# Scarce-quota exploratory runs, 2026-09-03

Status: exploratory probes, not formal performance evidence or the before arm of a later
comparison. Measured CityBuddy commit: `98d0b116cbe4c9c18faf6e1f205591b28605a64b`.
The summary, CPU, setup and step files retain their original names and bytes. The
`*_points.json.gz` and `*_console.txt.gz` files losslessly compress k6's original JSON-lines
and console output; decompress them to recover the original bytes, including console trailing
spaces. This archive does not change their measurement status.

## Workload and observed whole-run results

Each fresh fixture had 32 activities, quota 100 per activity, 25,000 users and product stock
2,000,000. Each run offered one rate for 15 seconds. The setup and raw output, rather than the
label timestamp, describe the actual run. The runs used the existing MySQL-first path.

| Offered/s | Completed iterations | Dropped iterations | ADMITTED | EXHAUSTED |
|---:|---:|---:|---:|---:|
| 800 | 11,922 | 78 | 3,200 | 8,722 |
| 1,000 | 14,583 | 418 | 3,200 | 11,383 |
| 1,500 | 20,652 | 1,849 | 3,200 | 17,452 |

The raw `http_req_failed` count includes expected HTTP 409 quota rejections. It is not a count
of unexpected business failures. The decision counter records `EXHAUSTED` separately; the
HTTP duration samples do not carry a decision tag. No runner or measurement code is changed
by archiving these outputs.

## What the short window obscured

The full runs include the initial admission burst, depletion of 3,200 units, and later
rejections. Retrospective inspection finds low-latency rejection tails with no newly recorded
drops; full-run drops do not mean that every later interval also dropped work. Conversely,
selecting those tails after seeing the data does not establish a predeclared clean capacity
target, and it does not erase the full-run drops shown above.

Time origins matter: the raw `run_started_at_utc` tag and the first completed HTTP duration
sample are different timestamps. Bucketing relative to the latter shifts the apparent tail;
the final bucket is partial. Per-second percentile ranges are not pooled-window percentiles.
Warm-up, backlog drainage and outcome mix change together here, so these probes do not isolate
cold-start cost, prove that all transient behavior ended after three seconds, locate a CPU
bottleneck, or measure the effect of Redis-first.

Any later performance comparison must collect a new same-session pair with fixed warm-up,
fresh measurement fixtures and predeclared reporting windows. These probes remain exploration.
