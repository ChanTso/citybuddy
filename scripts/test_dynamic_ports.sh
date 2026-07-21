#!/usr/bin/env bash

runtime_port_is_valid() {
  local port="$1"
  [[ "$port" =~ ^[0-9]{1,5}$ ]] && ((10#$port > 0 && 10#$port <= 65535))
}

compose_host_port() {
  local variable_name="$1"
  local service="$2"
  local container_port="$3"
  local binding
  binding="$("${compose[@]}" port "$service" "$container_port")"
  local port="${binding##*:}"
  if [[ ! "$binding" =~ ^127\.0\.0\.1:[0-9]{1,5}$ ]] || ! runtime_port_is_valid "$port"; then
    echo "Unexpected published port for $service:$container_port: $binding" >&2
    return 1
  fi
  printf -v "$variable_name" '%s' "$port"
}

container_host_port() {
  local variable_name="$1"
  local container="$2"
  local container_port="$3"
  local binding
  binding="$(docker port "$container" "$container_port/tcp")"
  local port="${binding##*:}"
  if [[ ! "$binding" =~ ^127\.0\.0\.1:[0-9]{1,5}$ ]] || ! runtime_port_is_valid "$port"; then
    echo "Unexpected published port for container $container:$container_port: $binding" >&2
    return 1
  fi
  printf -v "$variable_name" '%s' "$port"
}

port_log_offset() {
  local variable_name="$1"
  local log="$2"
  local lines=0
  if [[ -f "$log" ]]; then
    lines="$(wc -l <"$log" | tr -d ' ')"
  fi
  printf -v "$variable_name" '%s' "$lines"
}

process_bound_port() {
  local variable_name="$1"
  local process_kind="$2"
  local pid="$3"
  local log="$4"
  local offset="$5"
  local pattern
  case "$process_kind" in
    spring)
      pattern='s/.*Tomcat started on port ([0-9]+).*/\1/p'
      ;;
    uvicorn)
      pattern='s#.*Uvicorn running on http://[^:]+:([0-9]+).*#\1#p'
      ;;
    proxy)
      pattern='s/.*drop_proxy_listening_port=([0-9]+).*/\1/p'
      ;;
    *)
      echo "Unknown process port kind: $process_kind" >&2
      return 2
      ;;
  esac
  local port=""
  local first_line=$((offset + 1))
  for _ in {1..600}; do
    if [[ -f "$log" ]]; then
      port="$(tail -n +"$first_line" "$log" | sed -En "$pattern" | tail -n 1)"
    fi
    if runtime_port_is_valid "$port"; then
      printf -v "$variable_name" '%s' "$port"
      return 0
    fi
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      cat "$log" >&2
      echo "Process exited before publishing its kernel-assigned port." >&2
      return 1
    fi
    sleep 0.1
  done
  cat "$log" >&2
  echo "Timed out reading the kernel-assigned port from $log." >&2
  return 1
}

finish_test_cleanup() {
  local original_status="$1"
  local resource_stop_status="$2"
  if ((resource_stop_status != 0)); then
    echo "Test resource cleanup failed with status $resource_stop_status (original test status: $original_status)." >&2
  fi
  if ((original_status != 0)); then
    return "$original_status"
  fi
  if ((resource_stop_status != 0)); then
    trap - EXIT
    exit "$resource_stop_status"
  fi
}
