#!/usr/bin/env bash

if [[ -z "${CITYBUDDY_TEST_PORT_ALLOCATOR_LOADED:-}" ]]; then
  CITYBUDDY_TEST_PORT_ALLOCATOR_LOADED=1
  citybuddy_test_port_allocator_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  citybuddy_test_port_owner="${BASHPID:-$$}:${RANDOM}:${RANDOM}"

  allocate_test_ports() {
    if (($# == 0)); then
      echo "allocate_test_ports requires at least one variable name" >&2
      return 2
    fi
    local output
    if ! output="$(python3 "$citybuddy_test_port_allocator_dir/test_port_allocator.py" \
      allocate --owner "$citybuddy_test_port_owner" --count "$#")"; then
      return 1
    fi
    local ports=()
    local port
    while IFS= read -r port; do
      [[ -n "$port" ]] && ports+=("$port")
    done <<<"$output"
    if ((${#ports[@]} != $#)); then
      python3 "$citybuddy_test_port_allocator_dir/test_port_allocator.py" \
        release --owner "$citybuddy_test_port_owner" >/dev/null 2>&1 || true
      echo "test port allocator returned ${#ports[@]} ports for $# variables" >&2
      return 1
    fi
    local index=0
    local variable
    for variable in "$@"; do
      printf -v "$variable" '%s' "${ports[$index]}"
      ((index += 1))
    done
  }

  release_test_ports() {
    python3 "$citybuddy_test_port_allocator_dir/test_port_allocator.py" \
      release --owner "$citybuddy_test_port_owner" >/dev/null 2>&1 || true
  }

  finalize_test_port_cleanup() {
    local original_status="$1"
    local resource_stop_status="$2"
    if ((resource_stop_status == 0)); then
      release_test_ports
    else
      echo "Retaining test-port leases because suite resources did not stop cleanly." >&2
    fi
    if ((original_status != 0)); then
      return "$original_status"
    fi
    if ((resource_stop_status != 0)); then
      trap - EXIT
      exit "$resource_stop_status"
    fi
  }
fi
