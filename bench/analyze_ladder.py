"""Per-step counts and latency percentiles from a k6 JSON point stream."""

import collections
import json
import re
import sys

path, label = sys.argv[1:3]
rates = sys.argv[sys.argv.index("--rates") + 1].split(",")
step_seconds = int(sys.argv[sys.argv.index("--step-seconds") + 1])
durations = collections.defaultdict(list)
decisions = collections.defaultdict(collections.Counter)
failures = collections.defaultdict(float)
drops = collections.defaultdict(float)


def point_rate(tags):
    rate = tags.get("rate")
    if rate and str(rate).isdigit():
        return str(rate)
    match = re.fullmatch(r"rate_([0-9]+)", str(tags.get("scenario", "")))
    return match.group(1) if match else None


with open(path) as stream:
    for line in stream:
        try:
            record = json.loads(line)
        except json.JSONDecodeError:
            continue
        if record.get("type") != "Point" or not isinstance(record.get("data"), dict):
            continue
        data = record["data"]
        tags = data.get("tags", {})
        rate = point_rate(tags)
        if rate is None or not isinstance(data.get("value"), int | float):
            continue
        metric, value = record.get("metric"), float(data["value"])
        if metric == "http_req_duration":
            durations[rate].append(value)
        elif metric == "http_req_failed":
            failures[rate] += value
        elif metric == "dropped_iterations":
            drops[rate] += value
        elif metric == "seckill_decisions":
            decisions[rate][str(tags.get("decision", "?"))] += value


def percentile(values, percent):
    values = sorted(values)
    position = (len(values) - 1) * percent / 100
    lower, upper = int(position), min(int(position) + 1, len(values) - 1)
    return values[lower] + (values[upper] - values[lower]) * (position - lower)


print(f"=== {label} ===")
header = (
    f"{'target':>7} {'nominal':>8} {'done':>8} {'dropped':>8} {'failed':>7} "
    f"{'fail %':>7} {'achieved/s':>11} {'p50 ms':>9} {'p95 ms':>9} "
    f"{'p99 ms':>9} {'max ms':>9}  decisions"
)
print(header)
print("-" * len(header))
for rate in rates:
    values = durations[rate]
    done = len(values)
    failed, dropped = int(failures[rate]), int(drops[rate])
    latencies = [percentile(values, p) for p in (50, 95, 99)] + [max(values)] if values else []
    latency_text = (
        " ".join(f"{value:>9.1f}" for value in latencies)
        or "        -         -         -         -"
    )
    mix = ", ".join(f"{key}={int(value)}" for key, value in decisions[rate].most_common(3))
    print(
        f"{rate:>7} {int(rate) * step_seconds:>8} {done:>8} {dropped:>8} {failed:>7} "
        f"{failed * 100 / done if done else 0:>7.2f} {done / step_seconds:>11.1f} "
        f"{latency_text}  {mix}"
    )
