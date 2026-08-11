"""Independent CB-155 bundle reconstruction checker."""

from __future__ import annotations

import argparse
from pathlib import Path


class BundleError(ValueError):
    """A stable, target-specific bundle rejection."""


def verify_bundle(bundle: Path) -> dict[str, object]:
    raise BundleError(f"CHECKER_IN_PROGRESS: {bundle.name}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("bundle", type=Path)
    args = parser.parse_args()
    verify_bundle(args.bundle)


if __name__ == "__main__":
    main()
