"""Per-step steady-state statistics from a k6 JSON point stream."""
import json, sys, collections, statistics

path, label = sys.argv[1], sys.argv[2]
dur = collections.defaultdict(list)
decisions = collections.defaultdict(collections.Counter)
first_t, last_t = {}, {}

with open(path) as fh:
    for line in fh:
        try: rec = json.loads(line)
        except Exception: continue
        if rec.get("type") != "Point": continue
        m, d = rec.get("metric"), rec["data"]
        rate = d.get("tags", {}).get("rate")
        if not rate: continue
        if m == "http_req_duration":
            dur[rate].append(d["value"])
            t = d["time"]
            first_t.setdefault(rate, t); last_t[rate] = t
        elif m == "seckill_decisions":
            decisions[rate][d.get("tags", {}).get("decision", "?")] += 1

def pct(xs, p):
    xs = sorted(xs); k = (len(xs) - 1) * p / 100
    lo, hi = int(k), min(int(k) + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)

print(f"\n=== {label} ===")
hdr = f"{'target':>7} {'done':>7} {'achieved/s':>11} {'p50 ms':>9} {'p95 ms':>9} {'p99 ms':>9} {'max ms':>9}  decisions"
print(hdr); print("-" * len(hdr))
rows = []
for rate in sorted(dur, key=lambda r: int(r)):
    xs = dur[rate]
    from datetime import datetime
    def parse(t): return datetime.fromisoformat(t.replace("Z", "+00:00"))
    span = (parse(last_t[rate]) - parse(first_t[rate])).total_seconds() or 1
    achieved = len(xs) / span
    mix = ", ".join(f"{k}={v}" for k, v in decisions[rate].most_common(3))
    print(f"{rate:>7} {len(xs):>7} {achieved:>11.1f} {pct(xs,50):>9.1f} {pct(xs,95):>9.1f} "
          f"{pct(xs,99):>9.1f} {max(xs):>9.1f}  {mix}")
    rows.append((rate, achieved, pct(xs, 99)))
