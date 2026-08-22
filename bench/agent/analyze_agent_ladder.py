"""Per-step steady-state statistics for one agent path from a k6 JSON point stream.

Takes the step duration rather than inferring it. An overloaded step keeps completing requests
after its window closes, so the span between the first and last completion is not the step, and
dividing by it would make throughput incomparable between a healthy row and a collapsed one.

Offered is what the constant arrival rate asked for; completed is what produced a measurement.
The difference is requests k6 could not start for want of a free VU plus requests still in flight
when the step's graceful stop expired. A row where those diverge is not a throughput measurement
of the server, and printing both is what makes that visible.
"""

import collections
import json
import sys

path, label, step_seconds = sys.argv[1], sys.argv[2], float(sys.argv[3])
# The rates the ladder was configured with, not the rates that happen to appear in the stream. A
# step where every iteration was dropped emits no point at all, and inferring the rows from the
# stream would drop that step from the table silently rather than showing it produced nothing.
configured = [rate.strip() for rate in sys.argv[4].split(",") if rate.strip()]
durations: dict[str, list[float]] = collections.defaultdict(list)
outcomes: dict[str, collections.Counter[str]] = collections.defaultdict(collections.Counter)


with open(path) as handle:
    for line in handle:
        try:
            record = json.loads(line)
        except json.JSONDecodeError:
            continue
        if record.get("type") != "Point":
            continue
        metric, data = record.get("metric"), record["data"]
        rate = data.get("tags", {}).get("rate")
        if not rate:
            continue
        if metric == "http_req_duration":
            durations[rate].append(data["value"])
        elif metric == "agent_outcomes":
            outcomes[rate][data.get("tags", {}).get("outcome", "?")] += 1


def percentile(values: list[float], point: float) -> float:
    values = sorted(values)
    position = (len(values) - 1) * point / 100
    low, high = int(position), min(int(position) + 1, len(values) - 1)
    return values[low] + (values[high] - values[low]) * (position - low)


print(f"\n=== {label} (step {step_seconds:g}s) ===")
header = (
    f"{'target':>7} {'offered':>8} {'measured':>9} {'served/s':>9} "
    f"{'p50 ms':>9} {'p95 ms':>9} {'p99 ms':>9} {'max ms':>9}"
)
print(header)
print("-" * len(header))
for rate in sorted(configured, key=int):
    offered = round(float(rate) * step_seconds)
    values = durations.get(rate)
    if not values:
        print(f"{rate:>7} {offered:>8} {0:>9}   no request finished inside the run window")
        continue
    print(
        f"{rate:>7} {offered:>8} {len(values):>9} {len(values) / step_seconds:>9.1f} "
        f"{percentile(values, 50):>9.1f} {percentile(values, 95):>9.1f} "
        f"{percentile(values, 99):>9.1f} {max(values):>9.1f}"
    )

print("\noutcomes by step (every class, none elided):")
for rate in sorted(configured, key=int):
    mix = ", ".join(f"{name}={count}" for name, count in sorted(outcomes[rate].items()))
    print(f"  {rate:>4}: {mix or 'none'}")
