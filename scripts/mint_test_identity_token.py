"""Mint a synthetic direct-user JWT for local integration tests."""

import argparse
import time
import uuid
from pathlib import Path

import jwt


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--private-key", required=True, type=Path)
    parser.add_argument("--key-id", required=True)
    parser.add_argument("--issuer", required=True)
    parser.add_argument("--audience", required=True)
    parser.add_argument("--subject", required=True)
    parser.add_argument("--permission", action="append", required=True)
    parser.add_argument("--lifetime-seconds", type=int, default=900)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    now = int(time.time())
    claims = {
        "iss": args.issuer,
        "aud": args.audience,
        "sub": args.subject,
        "iat": now,
        "nbf": now,
        "exp": now + args.lifetime_seconds,
        "jti": str(uuid.uuid4()),
        "token_type": "direct_user",
        "principal_state": "ACTIVE",
        "permissions": args.permission,
    }
    token = jwt.encode(
        claims,
        args.private_key.read_text(encoding="utf-8"),
        algorithm="RS256",
        headers={"kid": args.key_id},
    )
    args.output.write_text(token, encoding="utf-8")


if __name__ == "__main__":
    main()
