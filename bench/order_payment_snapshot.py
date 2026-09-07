"""Read the ordinary-order fixture and authoritative payment/inventory rows."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("label")
    parser.add_argument("--phase", choices=("before", "after"), required=True)
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
    if (
        sha != settings["CITYBUDDY_COMMIT"]
        or args.label != settings["TOPIC_SUFFIX"]
        or settings["BENCH_WORKLOAD"] != "order-payment"
    ):
        parser.error("snapshot must match the committed normal-order setup")
    products = json.loads(
        (root / "bench/.run" / settings["FIXTURE_REL"] / "products.json").read_text()
    )
    expected_ids = {f"bench-normal-{args.label}-{i:02d}" for i in range(32)}
    if len(products) != 32 or {p["productId"] for p in products} != expected_ids:
        parser.error("snapshot must contain this label's exact 32 products")
    encoded = json.dumps(products, separators=(",", ":")).encode().hex()
    prefix = settings["USER_PREFIX"]
    if prefix != f"bench-user-{args.label}-":
        parser.error("unexpected owner prefix")
    header = (
        f"SET SESSION time_zone='+00:00'; SET @citybuddy_sha='{sha}',@label='{args.label}',"
        f"@user_prefix='{prefix}',@products_json=CONVERT(0x{encoded} USING utf8mb4);\n"
    )
    sql = header + (root / "bench/sql/order_payment.sql").read_text()
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
    path = root / f"bench/results/order_payment_{args.label}_{args.phase}.txt"
    with path.open("x") as log:
        log.write(
            f"citybuddy_commit={sha}\nlabel={args.label}\nphase={args.phase}\n\n" + sql + "\n"
        )
        result = subprocess.run(
            mysql + ["--table"], input=sql, env=child, text=True, capture_output=True
        )
        log.write(result.stdout + result.stderr)
        log.flush()
        result.check_returncode()
        if args.phase == "before":
            guard = (
                header
                + """WITH fixture AS (
 SELECT * FROM JSON_TABLE(@products_json,'$[*]' COLUMNS (
  product_id VARCHAR(64) PATH '$.productId', initial_stock BIGINT PATH '$.initialStock',
  price BIGINT PATH '$.unitPriceMinor', currency CHAR(3) PATH '$.currency',
  version BIGINT PATH '$.productVersion')) AS source
)
SELECT COUNT(*),COALESCE(SUM(p.stock_quantity=f.initial_stock AND f.initial_stock=1000
 AND p.price_minor=f.price AND BINARY p.currency=BINARY f.currency
 AND p.publication_version=f.version
 AND p.publication_state='PUBLISHED' AND p.available=TRUE),0),
 (SELECT COUNT(*) FROM standard_order o JOIN fixture f ON BINARY f.product_id=BINARY o.product_id)
FROM fixture f JOIN product p ON BINARY p.product_id=BINARY f.product_id;"""
            )
            result = subprocess.run(
                mysql + ["--skip-column-names"],
                input=guard,
                env=child,
                text=True,
                capture_output=True,
            )
            log.write("\nPreflight SQL:\n" + guard + "\n" + result.stdout + result.stderr)
            result.check_returncode()
            if result.stdout.strip() != "32\t32\t0":
                raise SystemExit(
                    "Normal fixture changed or was used; preserve it and choose a fresh setup."
                )
    print(f"citybuddy_commit={sha} snapshot={path}")


if __name__ == "__main__":
    main()
