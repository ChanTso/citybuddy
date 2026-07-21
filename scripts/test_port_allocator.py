#!/usr/bin/env python3
"""Allocate probed, process-scoped ports for local integration suites."""

from __future__ import annotations

import argparse
import os
import socket
import tempfile
from pathlib import Path

PORT_MIN = 20_000
PORT_MAX = 31_999
MAX_CANDIDATE_ATTEMPTS = 4_096
DEFAULT_LEASE_ROOT = Path(tempfile.gettempdir()) / "citybuddy-test-port-leases"


def _owner_pid(owner: str) -> int | None:
    prefix = owner.split(":", 1)[0]
    return int(prefix) if prefix.isdecimal() else None


def _process_is_alive(pid: int | None) -> bool:
    if pid is None:
        return True
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def _reclaim_stale_lease(lease: Path) -> bool:
    owner_file = lease / "owner"
    try:
        owner = owner_file.read_text(encoding="utf-8").strip()
    except (FileNotFoundError, OSError):
        return False
    if _process_is_alive(_owner_pid(owner)):
        return False
    try:
        owner_file.unlink()
        lease.rmdir()
    except OSError:
        return False
    return True


def _claim(lease: Path, owner: str) -> bool:
    try:
        lease.mkdir()
    except FileExistsError:
        if not _reclaim_stale_lease(lease):
            return False
        try:
            lease.mkdir()
        except FileExistsError:
            return False
    try:
        (lease / "owner").write_text(f"{owner}\n", encoding="utf-8")
    except OSError:
        lease.rmdir()
        raise
    return True


def _port_is_available(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind(("127.0.0.1", port))
        except OSError:
            return False
    return True


def _release_lease(lease: Path, owner: str) -> None:
    owner_file = lease / "owner"
    try:
        recorded_owner = owner_file.read_text(encoding="utf-8").strip()
    except OSError:
        return
    if recorded_owner != owner:
        return
    try:
        owner_file.unlink()
        lease.rmdir()
    except OSError:
        return


def release_ports(owner: str, lease_root: Path = DEFAULT_LEASE_ROOT) -> None:
    if not lease_root.exists():
        return
    for lease in lease_root.iterdir():
        if not lease.is_dir() or not lease.name.isdecimal():
            continue
        _release_lease(lease, owner)


def allocate_ports(
    owner: str,
    count: int,
    *,
    lease_root: Path = DEFAULT_LEASE_ROOT,
    preferred_start: int | None = None,
) -> list[int]:
    if count < 1:
        raise ValueError("count must be positive")
    span = PORT_MAX - PORT_MIN + 1
    if preferred_start is None:
        pid = _owner_pid(owner) or os.getpid()
        preferred_start = PORT_MIN + (pid % span)
    if not PORT_MIN <= preferred_start <= PORT_MAX:
        raise ValueError(f"preferred start must be within {PORT_MIN}-{PORT_MAX}")

    lease_root.mkdir(mode=0o700, parents=True, exist_ok=True)
    allocated: list[int] = []
    try:
        for offset in range(min(span, MAX_CANDIDATE_ATTEMPTS)):
            candidate = PORT_MIN + ((preferred_start - PORT_MIN + offset) % span)
            lease = lease_root / str(candidate)
            if not _claim(lease, owner):
                continue
            if not _port_is_available(candidate):
                _release_lease(lease, owner)
                continue
            allocated.append(candidate)
            if len(allocated) == count:
                return allocated
    except BaseException:
        release_ports(owner, lease_root)
        raise

    release_ports(owner, lease_root)
    raise RuntimeError(
        f"unable to allocate {count} test ports after {MAX_CANDIDATE_ATTEMPTS} candidates"
    )


def _lease_root_from_environment() -> Path:
    configured = os.environ.get("CITYBUDDY_TEST_PORT_LEASE_ROOT")
    return Path(configured) if configured else DEFAULT_LEASE_ROOT


def main() -> None:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    allocate = subparsers.add_parser("allocate")
    allocate.add_argument("--owner", required=True)
    allocate.add_argument("--count", required=True, type=int)
    allocate.add_argument("--start", type=int)
    release = subparsers.add_parser("release")
    release.add_argument("--owner", required=True)
    args = parser.parse_args()
    lease_root = _lease_root_from_environment()
    if args.command == "allocate":
        start = args.start
        if start is None and os.environ.get("CITYBUDDY_TEST_PORT_START"):
            start = int(os.environ["CITYBUDDY_TEST_PORT_START"])
        for port in allocate_ports(
            args.owner, args.count, lease_root=lease_root, preferred_start=start
        ):
            print(port)
    else:
        release_ports(args.owner, lease_root)


if __name__ == "__main__":
    main()
