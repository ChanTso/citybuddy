#!/usr/bin/env bash

# This is one benchmark-fixture boundary, shared by both setup paths and their runners.
BENCH_COMMERCE_CPU_LIMIT_REQUESTED_CPUS=4

bench_capture_container_state() {
  local container="$1" phase="$2" snapshot
  local container_id image_id started_at running restart_count
  if ! snapshot="$(docker inspect --format \
    '{{.Id}}|{{.Image}}|{{.State.StartedAt}}|{{.State.Running}}|{{.RestartCount}}' \
    "$container" 2>/dev/null)"; then
    echo "Benchmark container is missing ($phase): $container." >&2
    return 1
  fi
  IFS='|' read -r container_id image_id started_at running restart_count <<< "$snapshot"
  if [ -z "$container_id" ] || [ -z "$image_id" ] || [ -z "$started_at" ] \
    || [ "$running" != true ] || [[ ! "$restart_count" =~ ^[0-9]+$ ]]; then
    echo "Benchmark container is not the required running instance ($phase): $container." >&2
    return 1
  fi
  printf '%s\t%s\t%s\t%s\t%s\n' \
    "$container_id" "$image_id" "$started_at" "$running" "$restart_count"
}

bench_verify_dependency_container() {
  local container="$1" recorded_id="$2" recorded_image_id="$3" recorded_started_at="$4"
  local recorded_running="$5" recorded_restart_count="$6" phase="$7" live
  local live_id live_image_id live_started_at live_running live_restart_count
  if ! live="$(bench_capture_container_state "$container" "$phase")"; then
    return 1
  fi
  IFS=$'\t' read -r live_id live_image_id live_started_at live_running live_restart_count \
    <<< "$live"
  if [ "$recorded_id" != "$live_id" ] \
    || [ "$recorded_image_id" != "$live_image_id" ] \
    || [ "$recorded_started_at" != "$live_started_at" ] \
    || [ "$recorded_running" != true ] \
    || [ "$live_running" != "$recorded_running" ] \
    || [ "$recorded_restart_count" != "$live_restart_count" ]; then
    echo "Benchmark container identity drifted ($phase): $container." >&2
    return 1
  fi
}

bench_capture_fixture_container() {
  local container="$1" mounted_jar="$2" expected_jar_sha256="$3" phase="$4"
  local state output mounted_jar_sha256
  if ! state="$(bench_capture_container_state "$container" "$phase")"; then
    return 1
  fi
  if ! output="$(docker exec "$container" sha256sum "$mounted_jar" 2>/dev/null)"; then
    echo "Cannot hash the mounted JAR ($phase): $container:$mounted_jar." >&2
    return 1
  fi
  mounted_jar_sha256="${output%% *}"
  if [[ ! "$mounted_jar_sha256" =~ ^[0-9a-f]{64}$ ]] \
    || [ "$mounted_jar_sha256" != "$expected_jar_sha256" ]; then
    echo "Mounted JAR does not match the recorded host artifact ($phase): $container." >&2
    return 1
  fi
  printf '%s\t%s\n' "$state" "$mounted_jar_sha256"
}

bench_verify_fixture_container() {
  local container="$1" mounted_jar="$2" recorded_id="$3" recorded_image_id="$4"
  local recorded_started_at="$5" recorded_running="$6" recorded_restart_count="$7"
  local recorded_mounted_sha256="$8" recorded_host_sha256="$9" phase="${10}" live
  local live_id live_image_id live_started_at live_running live_restart_count
  local live_mounted_sha256
  if ! live="$(bench_capture_fixture_container \
    "$container" "$mounted_jar" "$recorded_host_sha256" "$phase")"; then
    return 1
  fi
  IFS=$'\t' read -r live_id live_image_id live_started_at live_running live_restart_count \
    live_mounted_sha256 <<< "$live"
  if [ "$recorded_id" != "$live_id" ] \
    || [ "$recorded_image_id" != "$live_image_id" ] \
    || [ "$recorded_started_at" != "$live_started_at" ] \
    || [ "$recorded_running" != true ] \
    || [ "$live_running" != "$recorded_running" ] \
    || [ "$recorded_restart_count" != "$live_restart_count" ] \
    || [ "$recorded_mounted_sha256" != "$recorded_host_sha256" ] \
    || [ "$live_mounted_sha256" != "$recorded_mounted_sha256" ]; then
    echo "Benchmark container identity drifted ($phase): $container." >&2
    return 1
  fi
}

bench_commerce_cpu_limit_expected_nano_cpus() {
  printf '%s\n' "$((BENCH_COMMERCE_CPU_LIMIT_REQUESTED_CPUS * 1000000000))"
}

bench_verify_commerce_cpu_limit() {
  local container="$1" recorded_requested_cpus="$2" recorded_nano_cpus="$3"
  local recorded_cpuset_cpus="$4" phase="$5" expected_nano_cpus snapshot
  local actual_nano_cpus actual_cpuset_cpus
  expected_nano_cpus="$(bench_commerce_cpu_limit_expected_nano_cpus)"
  snapshot="$(docker inspect --format \
    '{{.HostConfig.NanoCpus}}|{{.HostConfig.CpusetCpus}}' "$container")"
  IFS='|' read -r actual_nano_cpus actual_cpuset_cpus <<< "$snapshot"
  if [ "$recorded_requested_cpus" != "$BENCH_COMMERCE_CPU_LIMIT_REQUESTED_CPUS" ] \
    || [ "$recorded_nano_cpus" != "$expected_nano_cpus" ] \
    || [ "$actual_nano_cpus" != "$recorded_nano_cpus" ] \
    || [ -n "$recorded_cpuset_cpus" ] \
    || [ "$actual_cpuset_cpus" != "$recorded_cpuset_cpus" ]; then
    echo "Commerce CPU limit drifted ($phase): requested=$recorded_requested_cpus," \
      "recorded_nano_cpus=$recorded_nano_cpus, live_nano_cpus=$actual_nano_cpus," \
      "recorded_cpuset_cpus=${recorded_cpuset_cpus:-<empty>}," \
      "live_cpuset_cpus=${actual_cpuset_cpus:-<empty>}," \
      "fixture_requested=$BENCH_COMMERCE_CPU_LIMIT_REQUESTED_CPUS." >&2
    return 1
  fi
  printf '%s\n' "$actual_nano_cpus"
}
