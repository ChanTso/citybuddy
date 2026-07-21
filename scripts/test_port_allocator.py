#!/usr/bin/env python3
"""Allocate probed, process-scoped ports for local integration suites."""

from __future__ import annotations

import argparse
import fcntl
import os
import socket
import subprocess
import tempfile
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path

PORT_MIN = 20_000
PORT_MAX = 31_999
MAX_CANDIDATE_ATTEMPTS = 4_096
DEFAULT_LEASE_ROOT = Path(tempfile.gettempdir()) / "citybuddy-test-port-leases"


def _owner_pid(owner: str) -> int | None:
    prefix = owner.split(":", 1)[0]
    return int(prefix) if prefix.isdecimal() else None


def _process_start_token(pid: int | None) -> str | None:
    if pid is None:
        return None
    proc_stat = Path(f"/proc/{pid}/stat")
    try:
        stat = proc_stat.read_text(encoding="utf-8")
    except OSError:
        stat = ""
    if stat:
        command_end = stat.rfind(")")
        fields_after_command = stat[command_end + 2 :].split()
        if command_end >= 0 and len(fields_after_command) > 19:
            return f"proc:{fields_after_command[19]}"
    result = subprocess.run(
        ["ps", "-o", "lstart=", "-p", str(pid)],
        check=False,
        capture_output=True,
        text=True,
    )
    started_at = result.stdout.strip()
    return f"ps:{started_at}" if result.returncode == 0 and started_at else None


def _lease_record(owner: str) -> str:
    if "\n" in owner:
        raise ValueError("owner must be a single line")
    pid = _owner_pid(owner)
    process_start = _process_start_token(pid)
    if pid is None or process_start is None:
        raise ValueError("owner must begin with a live process id")
    return f"{owner}\n{pid}\n{process_start}\n"


def _read_lease(lease: Path) -> tuple[str, int, str] | None:
    try:
        lines = lease.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError):
        return None
    if len(lines) != 3 or not lines[1].isdecimal() or not lines[0] or not lines[2]:
        return None
    pid = int(lines[1])
    if _owner_pid(lines[0]) != pid:
        return None
    return lines[0], pid, lines[2]


@contextmanager
def _locked_registry(lease_root: Path) -> Iterator[None]:
    directory_fd = os.open(lease_root, os.O_RDONLY)
    try:
        fcntl.flock(directory_fd, fcntl.LOCK_EX)
        yield
    finally:
        fcntl.flock(directory_fd, fcntl.LOCK_UN)
        os.close(directory_fd)


def _reclaim_stale_lease(lease: Path) -> bool:
    if lease.is_dir():
        owner_file = lease / "owner"
        entries = list(lease.iterdir())
        if entries:
            if entries != [owner_file]:
                return False
            try:
                legacy_owner = owner_file.read_text(encoding="utf-8").strip()
            except (OSError, UnicodeError):
                legacy_owner = ""
            legacy_pid = _owner_pid(legacy_owner)
            if legacy_pid is not None and _process_start_token(legacy_pid) is not None:
                return False
            try:
                owner_file.unlink()
            except OSError:
                return False
        try:
            lease.rmdir()
        except OSError:
            return False
        return True
    record = _read_lease(lease)
    if record is not None:
        _, pid, recorded_start = record
        if _process_start_token(pid) == recorded_start:
            return False
    try:
        lease.unlink()
    except OSError:
        return False
    return True


def _publish_claim(lease: Path, record: str) -> bool:
    with tempfile.NamedTemporaryFile(
        mode="w", encoding="utf-8", dir=lease.parent, prefix=".lease-", delete=False
    ) as temporary:
        temporary.write(record)
        temporary.flush()
        os.fsync(temporary.fileno())
        temporary_path = Path(temporary.name)
    try:
        if lease.exists() and not _reclaim_stale_lease(lease):
            return False
        if lease.exists():
            return False
        os.replace(temporary_path, lease)
        return True
    finally:
        temporary_path.unlink(missing_ok=True)


def _port_is_available(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind(("127.0.0.1", port))
        except OSError:
            return False
    return True


def _release_lease(lease: Path, owner: str) -> None:
    record = _read_lease(lease)
    if record is None or record[0] != owner:
        return
    try:
        lease.unlink()
    except OSError:
        return


def release_ports(owner: str, lease_root: Path = DEFAULT_LEASE_ROOT) -> None:
    if not lease_root.exists():
        return
    with _locked_registry(lease_root):
        for lease in lease_root.iterdir():
            if not lease.name.isdecimal():
                continue
            _release_lease(lease, owner)


def _reclaim_orphaned_temporary_claims(lease_root: Path) -> None:
    for temporary in lease_root.glob(".lease-*"):
        try:
            temporary.unlink()
        except OSError:
            continue


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
    with _locked_registry(lease_root):
        _reclaim_orphaned_temporary_claims(lease_root)
        record = _lease_record(owner)
        allocated: list[int] = []
        try:
            for offset in range(min(span, MAX_CANDIDATE_ATTEMPTS)):
                candidate = PORT_MIN + ((preferred_start - PORT_MIN + offset) % span)
                lease = lease_root / str(candidate)
                if not _port_is_available(candidate):
                    continue
                if not _publish_claim(lease, record):
                    continue
                if not _port_is_available(candidate):
                    _release_lease(lease, owner)
                    continue
                allocated.append(candidate)
                if len(allocated) == count:
                    return allocated
        except BaseException:
            for port in allocated:
                _release_lease(lease_root / str(port), owner)
            raise

        for port in allocated:
            _release_lease(lease_root / str(port), owner)
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
