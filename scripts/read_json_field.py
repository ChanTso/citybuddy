"""Read one top-level string field without logging the surrounding credential payload."""

import argparse
import json
from pathlib import Path
from typing import Any


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("path", type=Path)
    parser.add_argument("field")
    args = parser.parse_args()
    payload: Any = json.loads(args.path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict) or not isinstance(payload.get(args.field), str):
        raise ValueError(f"Missing string field: {args.field}")
    print(payload[args.field])


if __name__ == "__main__":
    main()
