"""Per-step steady-state statistics for one agent path from a k6 JSON point stream."""

import collections
import json
import sys
from datetime import datetime

path, label = sys.argv[1], sys.argv[2]
durations: dict[str, list[float]] = collections.defaultdict(list)
outcomes: dict[str, collections.Counter[str]] = collections.defaultdict(collections.Counter)
first_seen: dict[str, str] = {}
last_seen: dict[str, str] = {}

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
            first_seen.setdefault(rate, data["time"])
            last_seen[rate] = data["time"]
        elif metric == "agent_outcomes":
            outcomes[rate][data.get("tags", {}).get("outcome", "?")] += 1


def percentile(values: list[float], point: float) -> float:
    values = sorted(values)
    position = (len(values) - 1) * point / 100
    low, high = int(position), min(int(position) + 1, len(values) - 1)
    return values[low] + (values[high] - values[low]) * (position - low)


def parse(stamp: str) -> datetime:
    return datetime.fromisoformat(stamp.replace("Z", "+00:00"))


print(f"\n=== {label} ===")
header = (
    f"{'target':>7} {'done':>7} {'achieved/s':>11} {'p50 ms':>9} {'p95 ms':>9} "
    f"{'p99 ms':>9} {'max ms':>9}  outcomes"
)
print(header)
print("-" * len(header))
for rate in sorted(durations, key=int):
    values = durations[rate]
    span = (parse(last_seen[rate]) - parse(first_seen[rate])).total_seconds() or 1
    mix = ", ".join(f"{name}={count}" for name, count in outcomes[rate].most_common(3))
    print(
        f"{rate:>7} {len(values):>7} {len(values) / span:>11.1f} "
        f"{percentile(values, 50):>9.1f} {percentile(values, 95):>9.1f} "
        f"{percentile(values, 99):>9.1f} {max(values):>9.1f}  {mix}"
    )
