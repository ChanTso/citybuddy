"""Use 320 separate buyers to exhaust 32 activities, without retries or direct SQL writes."""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import json
import re
import signal
import subprocess
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--label", required=True)
    parser.add_argument("--rate", type=int, required=True)
    parser.add_argument("--seconds", type=int, choices=(30, 120), default=30)
    parser.add_argument("--citybuddy-sha", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,39}", args.label) or args.rate < 1:
        parser.error("label must be 1-40 safe characters; rate must be positive")
    if not re.fullmatch(r"[0-9a-f]{40}", args.citybuddy_sha):
        parser.error("citybuddy-sha must be a full SHA")
    root = Path(__file__).resolve().parents[1]

    def git(*parts):
        return subprocess.check_output(["git", "-C", str(root), *parts], text=True).strip()

    settings = dict(
        row.split("=", 1)
        for row in (root / "bench/.run/bench.env").read_text().splitlines()
        if "=" in row
    )
    expected = {
        "CITYBUDDY_COMMIT": args.citybuddy_sha,
        "TOPIC_SUFFIX": args.label,
        "BENCH_WORKLOAD": "seckill-rejection",
        "BENCH_ACTIVITIES": "32",
        "BENCH_QUOTA": "10",
        "BENCH_USERS": "16704",
        "ACTIVITY_PREFIX": f"bench-activity-{args.label}-",
        "COMMERCE_URL": "http://127.0.0.1:18081",
    }
    if (
        any(settings.get(key) != value for key, value in expected.items())
        or git("rev-parse", "HEAD") != args.citybuddy_sha
    ):
        parser.error("preparation must use the recorded bounded-pool setup and source SHA")
    if git(
        "status",
        "--porcelain",
        "--untracked-files=all",
        "--",
        ".",
        ":(exclude)bench/results/**",
        ":(exclude)bench/.run/**",
    ):
        parser.error("preparation requires a source-clean checkout")
    tokens = json.loads((root / "bench/.run/tokens.json").read_text())
    if not isinstance(tokens, list) or len(tokens) != 16704:
        parser.error("expected 16384 load users followed by 320 preparation users")
    try:
        expiry = min(
            json.loads(base64.urlsafe_b64decode(t.split(".")[1] + "==="))["exp"] for t in tokens
        )
    except (ValueError, KeyError, TypeError, IndexError):
        parser.error("invalid token metadata; values withheld")
    if expiry - time.time() < 500:
        parser.error("token pool needs 500 seconds remaining before preparation")
    stop = threading.Event()
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    signal.signal(signal.SIGTERM, lambda *_: stop.set())

    def reserve(index):
        result = {
            "index": 16384 + index,
            "activity": settings["ACTIVITY_PREFIX"] + str(index % 32),
            "outcome": "not_sent",
            "status": None,
        }
        if stop.is_set():
            return result
        request = urllib.request.Request(
            settings["COMMERCE_URL"]
            + "/api/seckill/activities/"
            + result["activity"]
            + "/reservations",
            data=b'{"quantity":1,"expectedActivityVersion":1}',
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer " + tokens[16384 + index],
                "Idempotency-Key": f"REJECT-PREP-{args.label}-{index}",
            },
        )
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        result["outcome"] = "unknown"
        try:
            try:
                response = opener.open(request, timeout=10)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                result["status"] = response.code
                raw = response.read(16385)
        except (urllib.error.URLError, OSError) as error:
            result["error_type"] = type(error).__name__
            return result
        try:
            if len(raw) > 16384:
                raise ValueError("oversized response")
            body = json.loads(raw)
            if not isinstance(body, dict):
                raise ValueError("invalid shape")
        except (ValueError, UnicodeError):
            result["error_type"] = "invalid_response"
            return result
        if (
            result["status"] in (200, 201)
            and body.get("decisionCode") == "ADMITTED"
            and body.get("state") in ("ADMITTED", "ORDERED")
            and body.get("replay") is False
            and body.get("activityId") == result["activity"]
            and type(body.get("quantity")) is int
            and body.get("quantity") == 1
        ):
            result["outcome"] = "admitted"
        else:
            result["error_type"] = "not_a_fresh_admission"
        return result

    output = root / f"bench/results/rejection_prep_{args.label}.jsonl"
    admitted = completed = 0
    with output.open("x") as log:

        def emit(value):
            log.write(json.dumps(value) + "\n")
            log.flush()

        emit(
            {
                "type": "metadata",
                "citybuddy_commit": args.citybuddy_sha,
                "label": args.label,
                "phase": "fixture_preparation_not_load",
                "started_wall_ns": time.time_ns(),
                "planned_requests": 320,
                "workers": 16,
                "timeout_seconds": 10,
                "load_users": "[0,16384)",
                "preparation_users": "[16384,16704)",
                "warmup_rate": 1000,
                "warmup_seconds": 30,
                "gap_seconds": 5,
                "formal_rate": args.rate,
                "formal_seconds": args.seconds,
            }
        )
        with concurrent.futures.ThreadPoolExecutor(max_workers=16) as executor:
            for result in executor.map(reserve, range(320)):
                emit({"type": "result", "at_wall_ns": time.time_ns(), **result})
                completed += 1
                admitted += result["outcome"] == "admitted"
        ok = admitted == 320 and not stop.is_set()
        emit(
            {
                "type": "finished",
                "citybuddy_commit": args.citybuddy_sha,
                "completed": completed,
                "admitted": admitted,
                "outcome": "admitted_all" if ok else "failed",
                "interrupted": stop.is_set(),
                "finished_wall_ns": time.time_ns(),
            }
        )
    print(f"citybuddy_commit={args.citybuddy_sha} preparation_output={output}")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
