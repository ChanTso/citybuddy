"""Fixed five-second order progress and resources; full business SQL only before/after."""

from __future__ import annotations

import argparse
import os
import re
import signal
import subprocess
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("label")
    parser.add_argument("--snapshot", choices=("before", "after"))
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,39}", args.label):
        parser.error("label must be 1-40 safe characters")
    root = Path(__file__).resolve().parents[1]
    settings = dict(
        line.split("=", 1)
        for line in (root / "bench/.run/bench.env").read_text().splitlines()
        if "=" in line
    )
    private = dict(
        line.split("=", 1)
        for line in (root / ".env").read_text().splitlines()
        if line and not line.startswith("#") and "=" in line
    )
    sha = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    changes = subprocess.check_output(
        [
            "git",
            "-C",
            str(root),
            "status",
            "--porcelain",
            "--untracked-files=all",
            "--",
            ".",
            ":(exclude)bench/results/**",
            ":(exclude)bench/.run/**",
        ],
        text=True,
    ).strip()
    if (
        changes
        or sha != settings["CITYBUDDY_COMMIT"]
        or args.label != settings["TOPIC_SUFFIX"]
        or settings["BENCH_WORKLOAD"] != "seckill"
        or settings["BENCH_ACTIVITIES"] != "1"
    ):
        parser.error("observer requires this clean one-activity admission setup")
    activity = settings["ACTIVITY_PREFIX"] + "0"
    if activity != f"bench-activity-{args.label}-0":
        parser.error("unexpected fixture activity")
    header = f"SET SESSION time_zone='+00:00'; SET @citybuddy_sha='{sha}',@activity='{activity}';\n"
    mysql = [
        "mysql",
        "--protocol=TCP",
        "-h",
        "127.0.0.1",
        "-P",
        settings["MYSQL_PORT"],
        "-u",
        "root",
        "-D",
        "commerce_db",
        "--batch",
        "--raw",
        "--table",
    ]
    child = os.environ | {"MYSQL_PWD": private["MYSQL_BOOTSTRAP_PASSWORD"]}

    def query(statement, output):
        start = time.monotonic_ns()
        output.write(
            f"\nhost_wall_ns={time.time_ns()} host_monotonic_ns={start}\n" + statement + "\n"
        )
        output.flush()
        result = subprocess.run(
            mysql,
            input=statement,
            text=True,
            capture_output=True,
            env=child,
            timeout=60 if args.snapshot else 15,
        )
        output.write(
            result.stdout + result.stderr + f"\nquery_end_monotonic_ns={time.monotonic_ns()}\n"
        )
        output.flush()
        result.check_returncode()

    if args.snapshot:
        path = root / f"bench/results/orders_{args.label}_{args.snapshot}.txt"
        with path.open("x") as output:
            output.write(f"citybuddy_commit={sha}\nlabel={args.label}\n")
            query(header + (root / "bench/sql/seckill_orders.sql").read_text(), output)
        return
    groups = """SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION READ ONLY;
SELECT state,decision_code,COUNT(*) AS reservations FROM seckill_reservation
 WHERE activity_id=@activity GROUP BY state,decision_code;
SELECT status,timeout_dispatch_state,COUNT(*) AS orders FROM seckill_order
 WHERE activity_id=@activity GROUP BY status,timeout_dispatch_state;
"""
    oldest = """SELECT MIN(r.created_at) AS oldest_pending_created_at,
 TIMESTAMPDIFF(MICROSECOND,MIN(r.created_at),UTC_TIMESTAMP(6))/1000000.0 AS oldest_pending_seconds
 FROM seckill_reservation r LEFT JOIN seckill_order o ON o.reservation_id=r.reservation_id
 WHERE r.activity_id=@activity AND r.decision_code='ADMITTED' AND o.order_id IS NULL;
SELECT MIN(unpaid_deadline) AS earliest_unpaid_deadline FROM seckill_order
 WHERE activity_id=@activity AND status='UNPAID';
"""
    stop = False

    def interrupted(_signum, _frame):
        nonlocal stop
        stop = True

    signal.signal(signal.SIGINT, interrupted)
    signal.signal(signal.SIGTERM, interrupted)
    with (
        (root / f"bench/results/orders_{args.label}_progress.txt").open("x") as sql,
        (root / f"bench/results/orders_{args.label}_resources.txt").open("x") as resources,
    ):
        for output in (sql, resources):
            output.write(f"citybuddy_commit={sha}\nlabel={args.label}\ninterval_seconds=5\n")
            output.flush()
        started = next_oldest = time.monotonic()
        while not stop and time.monotonic() - started < 650:
            sample = time.monotonic()
            detailed = sample >= next_oldest
            query(header + groups + (oldest if detailed else "") + "COMMIT;\n", sql)
            if detailed:
                next_oldest = sample + 60
            resources.write(
                f"\nhost_wall_ns={time.time_ns()} host_monotonic_ns={time.monotonic_ns()}\n"
            )
            resources.flush()
            subprocess.run(
                [
                    "docker",
                    "exec",
                    "citybuddy-bench-commerce",
                    "sh",
                    "-c",
                    "date -u +vm_wall_epoch=%s; cat /proc/uptime "
                    "/sys/fs/cgroup/cpu.max /sys/fs/cgroup/cpu.stat "
                    "/sys/fs/cgroup/memory.current /sys/fs/cgroup/memory.events "
                    "/proc/pressure/cpu /proc/pressure/memory",
                ],
                stdout=resources,
                stderr=subprocess.STDOUT,
                timeout=10,
                check=True,
            )
            active = subprocess.check_output(
                ["docker", "ps", "--format", "{{.Names}}"], text=True
            ).splitlines()
            names = [
                name
                for name in (
                    "citybuddy-bench-k6",
                    "citybuddy-bench-commerce",
                    "citybuddy-bench-auth",
                    "citybuddy-mysql-1",
                    "citybuddy-redis-commerce-1",
                    "citybuddy-rocketmq-broker-proxy-1",
                    "citybuddy-rocketmq-namesrv-1",
                )
                if name in active
            ]
            if not names:
                raise RuntimeError("No measurement containers remain")
            subprocess.run(
                [
                    "docker",
                    "stats",
                    "--no-stream",
                    "--format",
                    "{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}",
                    *names,
                ],
                stdout=resources,
                stderr=subprocess.STDOUT,
                timeout=10,
                check=True,
            )
            resources.flush()
            # Never catch up an overlong observation with a burst of additional SQL.
            until = max(sample + 5, time.monotonic())
            while not stop and time.monotonic() < until:
                time.sleep(min(0.2, until - time.monotonic()))
        resources.write(
            f"finished_wall_ns={time.time_ns()} "
            f"finished_monotonic_ns={time.monotonic_ns()} stopped={stop}\n"
        )


if __name__ == "__main__":
    main()
