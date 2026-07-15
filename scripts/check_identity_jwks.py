"""Assert the integration JWKS contains only expected public RSA fields."""

import argparse
import json
from pathlib import Path
from typing import Any


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("path", type=Path)
    parser.add_argument("kids", nargs="+")
    args = parser.parse_args()
    payload: Any = json.loads(args.path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict) or not isinstance(payload.get("keys"), list):
        raise ValueError("Malformed JWKS")
    keys = payload["keys"]
    if {key.get("kid") for key in keys if isinstance(key, dict)} != set(args.kids):
        raise ValueError("Unexpected published key set")
    private_fields = {"d", "p", "q", "dp", "dq", "qi", "oth"}
    for key in keys:
        if not isinstance(key, dict) or key.get("kty") != "RSA" or private_fields & key.keys():
            raise ValueError("JWKS contains malformed or private key material")


if __name__ == "__main__":
    main()
