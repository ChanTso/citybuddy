# Clock anomaly during the 80/s sustained point

Measured CityBuddy: `16cb21154d1ae94c65fff56b8a96ea7f4514f924`.
Label: `ordersteady_16cb211_20260906T071124Z_r80_s300`. Keep the complete original point.

All 24,001 non-replayed admissions completed correctly and the sampled order/dispatch
queues stayed bounded. Final SQL nonetheless found one order created 305.595ms earlier
than its reservation. The reservation's updated_at also preceded its created_at.
Both timestamps use the database wall clock; no row or recorded duration is altered.

Native k6 point timestamps went backward about 344.93ms at approximately 07:15:55 UTC,
and host/SQL sample intervals shortened by about 0.36s at the same phase. The macOS
`timed` log independently records successful `settimeofday` with adjustment -0.357229233s
at local 15:15:53 (UTC+8); the observed container step followed. The selected original
log lines and the exact negative-wait row are retained with this point. The full host
log remains private because only the time-adjustment lines are relevant.

The point establishes completed counts and bounded work under the registered input,
but is not clean SQL latency evidence. Preserve the negative value and reported
percentiles as raw observations; do not remove it and recompute a favorable percentile.
Minute throughput denominators based on UTC also carry this clock caveat.

For subsequent points, the existing five-second observer adds `time.monotonic_ns()`
to each host SQL/resource sample header, alongside UTC, to identify clock steps without
changing the business path or cadence. No system clock or synchronization setting is
changed. Continue the registered 160/s point; repeat 80/s only if its clean time metrics
are still needed after the higher-rate results. This issue does not justify cloud work.

The later 160/s probe and 200/s input have now completed; their results and distinct
qualifications are in [the sustained report](seckill_sustained_orders_20260906.md).
The complete 80/s point, selected time-adjustment excerpt and negative-wait SQL are
in [the adjusted raw bundle](seckill-sustained-after-20260906.tar.gz). No repeat 80/s
point or clock-setting change was needed for the adopted comparison.
