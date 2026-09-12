# Capacity attempt — 2026-09-04

Measured main: `c5af89d5e07fa5a20f0a32b865557fbbbb08aabd`. Sources were committed and source-clean; the recorded JARs were built before load, with no concurrent local build or CI. All times below are UTC on 2026-09-04.

**No normal-awake capacity bound or service ceiling was established.** The only completed formal point ran during DarkWake and is retained as an observation, not a resume result. No first-bad formal point was measured.

Individual evidence filenames below are packaged in the [historical raw-output archive](historical-supplement-20260906-raw.tar.gz), rather than tracked as separate files.

## E: sold-out admission entry

The fixture had 32 activities, quota 100 each, stock 2,000,000 and 48,600 users. Separate preparation users consumed all 3,200 quota through HTTP; before formal load, SQL showed the admitted work ordered, and all 32 Redis activity projections had remaining quota 0 and handoff count 0. The recorded local boundary was Docker 8 CPUs / 14,638,391,296 bytes, Commerce limited to 4 CPUs. This was rejection-entry traffic, not order-completion throughput. See the setup (`seckill_capacityE_20260904T160937Z_c5af89d_r1500_setup.txt`), preparation HTTP (`seckill_capacityE_20260904T160937Z_c5af89d_r1500_prep_http.txt`) and before SQL (`seckill_capacityE_20260904T160937Z_c5af89d_r1500_before_sql.txt`).

The 1,500 requests/s × 30-second formal point had runner timestamps 16:48:34–16:49:09; HTTP completions ran from 16:48:35.404666676 to 16:49:05.386878676. It completed 45,000 requests/iterations, all `409 EXHAUSTED`, with 0 dropped and 0 interrupted iterations. HTTP duration p50 was 0.34527050000000004 ms and p99 was 33.05214643 ms. The unchanged harness counts these expected 409 responses as HTTP failures; they are not unexpected business errors. See the raw console (`k6_capacityE_20260904T160937Z_c5af89d_r1500_console.txt`), summary (`k6_capacityE_20260904T160937Z_c5af89d_r1500_summary.json`), points (`k6_capacityE_20260904T160937Z_c5af89d_r1500_points.json.gz`) and small numeric summary (`seckill_capacityE_20260904T160937Z_c5af89d_r1500_analysis.txt`).

| Authoritative SQL observation | Before: 16:47:43.265704 | After: 16:49:58.448382 |
| --- | ---: | ---: |
| Reservations: ORDERED / ADMITTED | 3,200 | 3,200 |
| Orders: UNPAID / timeout dispatch SENT | 3,200 | 3,200 |
| SECKILL_ORDER_CREATE ledger rows | 3,200 | 3,200 |
| Ledger inventory / activity-quota deltas | −3,200 / −3,200 | −3,200 / −3,200 |
| Product stock | 1,996,800 | 1,996,800 |

The formal rejection traffic added no durable MySQL reservation, order or ledger rows between these observations; these counts belong to preparation, not formal admitted orders. The first unpaid deadline was 16:52:07.551684, after the formal window and SQL observation. Before SQL (`seckill_capacityE_20260904T160937Z_c5af89d_r1500_before_sql.txt`), after SQL (`seckill_capacityE_20260904T160937Z_c5af89d_r1500_after_sql.txt`).

The 2,000 requests/s candidate never reached preparation HTTP admission or formal load. Its setup ran 16:52:38–17:09:18 with 63,600 users. At 17:11:28.429038, real HTTP checks using token indices 0 and 60,100 both returned `401 Expired token`; their expirations were 17:08:11 and 17:08:39. SQL at 17:12:57.494893 showed 0 reservations, 0 orders and 0 ledger rows in this fresh fixture. This is preparation invalidation, not a bad capacity point. Setup (`seckill_capacityE_20260904T160937Z_c5af89d_r2000_setup.txt`), HTTP checks (`seckill_capacityE_20260904T160937Z_c5af89d_r2000_preparation_auth_check.txt`), SQL and stop record (`seckill_capacityE_20260904T160937Z_c5af89d_r2000_preparation_failure_stop.txt`).

## Power interruption and P

The [retained, sanitized pmset/ioreg excerpt](capacity_20260904_power_interruption.txt) records clamshell sleep at 16:11:00, DarkWake at 16:29:30, and a `Dark Wake Thermal Emergency` sleep from 16:53:41 to 17:09:17. The E 1,500 point was inside that DarkWake period, not a normal-awake run. The later sleep interval overlaps the E 2,000 setup and both token expirations; the setup duration must not be attributed to slow token minting or service saturation.

P preparation began at 17:19:03 and was stopped on identifying the power-state issue. Only Broker recreation and its existing route/readiness commands completed. No P fixture setup, business HTTP, warmup or formal k6 run started; no new A–B comparison was produced. At 17:20:17 all `citybuddy-bench-*` containers were stopped and no k6 container remained. Shared infrastructure was left running. This is an environment interruption, not a P capacity result. Stop-state record (`seckill_capacityP_20260904T171903Z_c5af89d_environment_interruption.txt`).

## Not executed

- Agent sessions 3 and 4 were not started; there is no new capacity result for them.
- The conditional pooling criterion was not evaluated or triggered by this attempt.
- The separate 12-task × 3 real-model acceptance (36 runs) was not run.

These are unexecuted work, not passing or failing results. Existing raw E bundles remain under `bench/results/` with the `capacityE_20260904T160937Z_c5af89d_r1500` and `..._r2000` labels; private setup and maintenance logs remain under ignored `bench/.run/`. No raw result was replaced. Further load was stopped; power protection was not bypassed and no persistent power settings were changed.

The supplementary artifacts are included in the [historical raw-output archive](historical-supplement-20260906-raw.tar.gz); the original main report and previously committed compressed artifacts remain intact.
