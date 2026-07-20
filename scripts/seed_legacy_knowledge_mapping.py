"""Seed the exact pre-CB-111 CB-090 mapping for upgrade integration evidence."""

from __future__ import annotations

import argparse
import json
from copy import deepcopy
from typing import Any, cast
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen

from citybuddy_indexer.knowledge import (
    KNOWLEDGE_INDEX_MAPPING,
    KNOWLEDGE_SYNC_MAPPING_PROPERTIES,
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--elasticsearch-url", required=True)
    parser.add_argument("--index", required=True)
    args = parser.parse_args()

    mapping = deepcopy(KNOWLEDGE_INDEX_MAPPING)
    mappings = cast(dict[str, Any], mapping["mappings"])
    properties = cast(dict[str, Any], mappings["properties"])
    for field in KNOWLEDGE_SYNC_MAPPING_PROPERTIES:
        properties.pop(field)
    request = Request(
        f"{args.elasticsearch_url.rstrip('/')}/{quote(args.index)}",
        data=json.dumps(mapping, separators=(",", ":")).encode(),
        headers={"Content-Type": "application/json"},
        method="PUT",
    )
    try:
        with urlopen(request, timeout=10) as response:  # noqa: S310
            status = response.status
    except HTTPError as error:
        raise SystemExit(f"legacy mapping seed rejected with status {error.code}") from error
    if status not in {200, 201}:
        raise SystemExit(f"legacy mapping seed returned status {status}")
    print("Seeded exact pre-CB-111 knowledge mapping.")


if __name__ == "__main__":
    main()
