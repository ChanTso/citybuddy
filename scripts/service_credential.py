"""Generate and hash versioned high-entropy machine credentials."""

from __future__ import annotations

import argparse
import hashlib
import re
import secrets
import sys
from typing import Any

DOMAIN = b"citybuddy-service-credential-v1"
SECRET_PATTERN = re.compile(r"^cbsvc_v1_[0-9a-f]{64}$")


def _update(digest: Any, value: bytes) -> None:
    digest.update(len(value).to_bytes(4, byteorder="big"))
    digest.update(value)


def encoded_digest(client_id: str, secret: str) -> str:
    if not client_id or not SECRET_PATTERN.fullmatch(secret):
        raise ValueError("a client id and generated service credential are required")
    digest = hashlib.sha256()
    _update(digest, DOMAIN)
    _update(digest, client_id.encode("utf-8"))
    _update(digest, secret.encode("utf-8"))
    return f"sha256$v1${digest.hexdigest()}"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("operation", choices=("generate", "hash", "validate"))
    parser.add_argument("client_id", nargs="?")
    args = parser.parse_args()

    if args.operation == "generate":
        if args.client_id is not None:
            parser.error("generate does not accept a client id")
        sys.stdout.write(f"cbsvc_v1_{secrets.token_hex(32)}")
        return 0

    secret = sys.stdin.read()
    if args.operation == "validate":
        if args.client_id is not None:
            parser.error("validate does not accept a client id")
        return 0 if SECRET_PATTERN.fullmatch(secret) else 1

    if args.client_id is None:
        parser.error("hash requires a client id")
    try:
        print(encoded_digest(args.client_id, secret))
    except ValueError:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
