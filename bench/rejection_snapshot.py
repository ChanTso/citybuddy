"""Authoritative SQL plus native Redis/MQ observations for one sold-out fixture."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import time
from pathlib import Path


def run(command, *, input=None, env=None):
    return subprocess.run(command, input=input, text=True, capture_output=True, check=True, env=env)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("label")
    parser.add_argument("--phase", choices=("before", "after"), required=True)
    parser.add_argument("--require-ready", action="store_true")
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
    sha = run(["git", "-C", str(root), "rev-parse", "HEAD"]).stdout.strip()
    if (
        sha != settings["CITYBUDDY_COMMIT"]
        or args.label != settings["TOPIC_SUFFIX"]
        or settings["BENCH_WORKLOAD"] != "seckill-rejection"
    ):
        parser.error("snapshot must match the committed rejection setup")
    prefix, product = settings["ACTIVITY_PREFIX"], settings["BENCH_PRODUCT_ID"]
    if prefix != f"bench-activity-{args.label}-" or product != f"bench-product-{args.label}":
        parser.error("unexpected fixture identifiers")
    activities = [prefix + str(i) for i in range(32)]
    member = ",".join("'" + item + "'" for item in activities)
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
    ]
    child = os.environ | {"MYSQL_PWD": private["MYSQL_BOOTSTRAP_PASSWORD"]}
    output = root / f"bench/results/rejection_{args.label}_{args.phase}.txt"
    guard = f"""SET SESSION time_zone='+00:00';
SELECT
 (SELECT COUNT(*) FROM seckill_activity WHERE activity_id IN ({member}) AND allocated_quota=10),
 (SELECT COUNT(*) FROM seckill_reservation WHERE activity_id IN ({member})),
 (SELECT COUNT(*) FROM seckill_reservation WHERE activity_id IN ({member})
  AND state='ORDERED' AND decision_code='ADMITTED'),
 COUNT(*),COALESCE(SUM(status='UNPAID' AND timeout_dispatch_state='SENT'),0),
 TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),MIN(unpaid_deadline)),
 (SELECT stock_quantity FROM product WHERE product_id='{product}')
 FROM seckill_order WHERE activity_id IN ({member});"""
    with output.open("x") as log:
        log.write(f"citybuddy_commit={sha}\nlabel={args.label}\nphase={args.phase}\n")
        if args.require_ready:
            deadline = time.monotonic() + 180
            while True:
                raw = run(mysql + ["--skip-column-names"], input=guard, env=child).stdout
                log.write("preparation SQL readiness: " + raw)
                log.flush()
                columns = raw.strip().split("\t")
                if (
                    columns[:5] == ["32", "320", "320", "320", "320"]
                    and columns[5] != "NULL"
                    and int(columns[5]) >= 300
                    and columns[6] == str(int(settings["BENCH_STOCK"]) - 320)
                ):
                    break
                if columns[:5] == ["32", "320", "320", "320", "320"] and (
                    columns[5] == "NULL" or int(columns[5]) < 300
                ):
                    raise SystemExit(
                        "Prepared orders do not cover the window; do not alter their deadlines."
                    )
                if time.monotonic() >= deadline:
                    raise SystemExit(
                        "Preparation not durably ready; preserve this label and raw output."
                    )
                time.sleep(2)
        header = (
            f"SET @citybuddy_sha='{sha}',@label='{args.label}',@activity_prefix='{prefix}',"
            f"@product_id='{product}',@stock_before={int(settings['BENCH_STOCK'])};\n"
        )
        sql = header + (root / "bench/sql/seckill_rejection.sql").read_text()
        log.write("\n" + sql + "\n")
        result = run(mysql + ["--table"], input=sql, env=child)
        log.write(result.stdout)
        log.flush()
        redis_env = os.environ | {"REDISCLI_AUTH": private["REDIS_COMMERCE_PASSWORD"]}
        redis = [
            "docker",
            "exec",
            "--env",
            "REDISCLI_AUTH",
            "citybuddy-redis-commerce-1",
            "redis-cli",
            "--no-auth-warning",
            "--raw",
        ]
        projection = run(
            redis + ["MGET", *["commerce:seckill:activity:" + a for a in activities]], env=redis_env
        ).stdout
        log.write("\nRedis projections:\n" + projection)
        if args.require_ready:
            values = [json.loads(line) for line in projection.splitlines()]
            if len(values) != 32 or any(
                v["activityId"] != activity or v["remainingQuota"] != 0 or v["state"] != "ACTIVE"
                for v, activity in zip(values, activities, strict=False)
            ):
                raise SystemExit("Preparation Redis projection is not sold out; preserve fixture.")
        for section in ("memory", "persistence", "stats", "keyspace"):
            log.write(
                "\nRedis INFO "
                + section
                + "\n"
                + run(redis + ["INFO", section], env=redis_env).stdout
            )
        for setting in ("maxmemory", "maxmemory-policy", "appendonly", "appendfsync"):
            log.write("\nRedis CONFIG GET " + setting + "\n")
            log.write(run(redis + ["CONFIG", "GET", setting], env=redis_env).stdout)
        command = [
            "docker",
            "compose",
            "--project-name",
            "citybuddy",
            "--env-file",
            ".env",
            "--file",
            "compose.yaml",
            "run",
            "--rm",
            "--no-deps",
            "rocketmq-admin",
            "consumerProgress",
            "-n",
            "rocketmq-namesrv:9876",
            "-g",
            "cb060-seckill-order-consumer-" + args.label,
            "-t",
            "cb060-seckill-transaction-" + args.label,
        ]
        result = subprocess.run(command, cwd=root, text=True, capture_output=True)
        log.write(
            "\nMQ consumerProgress (explicit transaction topic):\n"
            + result.stdout
            + result.stderr
            + f"\nexit_code={result.returncode}\n"
        )
        if result.returncode:
            raise SystemExit("MQ observation failed; preserve fixture and inspect its raw output.")
    print(f"citybuddy_commit={sha} snapshot={output}")


if __name__ == "__main__":
    main()
