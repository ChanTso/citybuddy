#!/usr/bin/env bash
# Sourced by the ladder and profiler. The saved result record is the expected setup instance;
# the live record, fixed container names, labels and mounted artifacts must still describe it.

agent_setup_sha256() {
  local path="$1" output digest
  if command -v sha256sum >/dev/null 2>&1; then
    output="$(sha256sum "$path")"
  elif command -v shasum >/dev/null 2>&1; then
    output="$(shasum -a 256 "$path")"
  else
    echo "Neither sha256sum nor shasum is available." >&2
    return 1
  fi
  digest="${output%% *}"
  if [[ ! "$digest" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Invalid SHA-256 for $path." >&2
    return 1
  fi
  printf '%s\n' "$digest"
}

agent_setup_container_sha256() {
  local container="$1" path="$2" output digest
  output="$(docker exec "$container" sha256sum "$path")"
  digest="${output%% *}"
  if [[ ! "$digest" =~ ^[0-9a-f]{64}$ ]]; then
    echo "Invalid mounted SHA-256 in $container." >&2
    return 1
  fi
  printf '%s\n' "$digest"
}

agent_setup_json_string() {
  local file="$1" expression="$2"
  jq -er "$expression | select(type == \"string\" and length > 0)" "$file"
}

verify_agent_setup_environment() {
  local expected_record="$1" phase="$2"
  local live_record="$repo_root/bench/.run/agent_setup_environment.json"
  local commit_marker="$repo_root/bench/.run/citybuddy_commit"
  local expected_format expected_commit expected_nonce live_format live_commit live_nonce
  local current_commit source_changes name expected_id expected_image_id live_id live_image_id
  local actual_id actual_image_id running
  local expected_nonce_label expected_commit_label live_nonce_label live_commit_label
  local actual_nonce_label actual_commit_label

  if [ ! -s "$expected_record" ] || [ ! -s "$live_record" ] || [ ! -s "$commit_marker" ]; then
    echo "Agent setup environment gate failed ($phase): a completed record is missing." >&2
    return 1
  fi
  expected_format="$(agent_setup_json_string "$expected_record" '.formatVersion')"
  expected_commit="$(agent_setup_json_string "$expected_record" '.citybuddyCommit')"
  expected_nonce="$(agent_setup_json_string "$expected_record" '.setupNonce')"
  live_format="$(agent_setup_json_string "$live_record" '.formatVersion')"
  live_commit="$(agent_setup_json_string "$live_record" '.citybuddyCommit')"
  live_nonce="$(agent_setup_json_string "$live_record" '.setupNonce')"
  if [ "$expected_format" != citybuddy-agent-setup-environment-v1 ] \
    || [ "$live_format" != "$expected_format" ] \
    || [[ ! "$expected_commit" =~ ^[0-9a-f]{40}$ ]] \
    || [[ ! "$expected_nonce" =~ ^[0-9a-f]{32}$ ]] \
    || [ "$live_commit" != "$expected_commit" ] \
    || [ "$live_nonce" != "$expected_nonce" ]; then
    echo "Agent setup environment gate failed ($phase): the live setup record changed." >&2
    return 1
  fi
  if [ "$(cat "$commit_marker")" != "$expected_commit" ]; then
    echo "Agent setup environment gate failed ($phase): the completion marker changed." >&2
    return 1
  fi
  current_commit="$(git rev-parse --verify HEAD)"
  source_changes="$(git status --porcelain --untracked-files=all -- . \
    ':(exclude)bench/results/**' \
    ':(exclude)bench/.run/**')"
  if [ "$current_commit" != "$expected_commit" ] || [ -n "$source_changes" ]; then
    echo "Agent setup environment gate failed ($phase): the source checkout changed." >&2
    [ -z "$source_changes" ] || printf '%s\n' "$source_changes" >&2
    return 1
  fi

  for name in citybuddy-bench-elasticsearch citybuddy-bench-auth citybuddy-bench-commerce \
    citybuddy-bench-net citybuddy-bench-model citybuddy-bench-agent; do
    expected_id="$(jq -er --arg name "$name" \
      '.containers[$name].id | select(type == "string" and test("^[0-9a-f]{64}$"))' \
      "$expected_record")"
    expected_image_id="$(jq -er --arg name "$name" \
      '.containers[$name].imageId
       | select(type == "string" and test("^sha256:[0-9a-f]{64}$"))' \
      "$expected_record")"
    expected_nonce_label="$(jq -er --arg name "$name" \
      '.containers[$name].labels["citybuddy.bench.setup-nonce"]' "$expected_record")"
    expected_commit_label="$(jq -er --arg name "$name" \
      '.containers[$name].labels["citybuddy.bench.citybuddy-commit"]' "$expected_record")"
    live_id="$(jq -er --arg name "$name" '.containers[$name].id' "$live_record")"
    live_image_id="$(jq -er --arg name "$name" '.containers[$name].imageId' "$live_record")"
    live_nonce_label="$(jq -er --arg name "$name" \
      '.containers[$name].labels["citybuddy.bench.setup-nonce"]' "$live_record")"
    live_commit_label="$(jq -er --arg name "$name" \
      '.containers[$name].labels["citybuddy.bench.citybuddy-commit"]' "$live_record")"
    actual_id="$(docker inspect --format '{{.Id}}' "$name")"
    actual_image_id="$(docker inspect --format '{{.Image}}' "$name")"
    running="$(docker inspect --format '{{.State.Running}}' "$name")"
    actual_nonce_label="$(docker inspect --format \
      '{{ index .Config.Labels "citybuddy.bench.setup-nonce" }}' "$name")"
    actual_commit_label="$(docker inspect --format \
      '{{ index .Config.Labels "citybuddy.bench.citybuddy-commit" }}' "$name")"
    if [ "$expected_nonce_label" != "$expected_nonce" ] \
      || [ "$expected_commit_label" != "$expected_commit" ] \
      || [ "$live_id" != "$expected_id" ] \
      || [ "$live_image_id" != "$expected_image_id" ] \
      || [ "$live_nonce_label" != "$expected_nonce" ] \
      || [ "$live_commit_label" != "$expected_commit" ] \
      || [ "$actual_id" != "$expected_id" ] \
      || [ "$actual_image_id" != "$expected_image_id" ] \
      || [ "$running" != true ] \
      || [ "$actual_nonce_label" != "$expected_nonce" ] \
      || [ "$actual_commit_label" != "$expected_commit" ]; then
      echo "Agent setup environment gate failed ($phase): container identity changed for $name." >&2
      return 1
    fi
  done

  local auth_host="$repo_root/auth-service/target/auth-service-0.0.1-SNAPSHOT.jar"
  local commerce_host="$repo_root/commerce-service/target/commerce-service-0.0.1-SNAPSHOT.jar"
  local expected_auth_host expected_auth_mounted expected_commerce_host expected_commerce_mounted
  local live_auth_host live_auth_mounted live_commerce_host live_commerce_mounted
  expected_auth_host="$(agent_setup_json_string "$expected_record" '.java.authService.hostJarSha256')"
  expected_auth_mounted="$(agent_setup_json_string "$expected_record" '.java.authService.mountedJarSha256')"
  expected_commerce_host="$(agent_setup_json_string "$expected_record" '.java.commerceService.hostJarSha256')"
  expected_commerce_mounted="$(agent_setup_json_string "$expected_record" '.java.commerceService.mountedJarSha256')"
  live_auth_host="$(agent_setup_json_string "$live_record" '.java.authService.hostJarSha256')"
  live_auth_mounted="$(agent_setup_json_string "$live_record" '.java.authService.mountedJarSha256')"
  live_commerce_host="$(agent_setup_json_string "$live_record" '.java.commerceService.hostJarSha256')"
  live_commerce_mounted="$(agent_setup_json_string "$live_record" '.java.commerceService.mountedJarSha256')"
  if [ "$expected_auth_host" != "$expected_auth_mounted" ] \
    || [ "$expected_commerce_host" != "$expected_commerce_mounted" ] \
    || [ "$live_auth_host" != "$expected_auth_host" ] \
    || [ "$live_auth_mounted" != "$expected_auth_mounted" ] \
    || [ "$live_commerce_host" != "$expected_commerce_host" ] \
    || [ "$live_commerce_mounted" != "$expected_commerce_mounted" ] \
    || [ "$(agent_setup_sha256 "$auth_host")" != "$expected_auth_host" ] \
    || [ "$(agent_setup_container_sha256 citybuddy-bench-auth /opt/citybuddy/auth.jar)" \
      != "$expected_auth_mounted" ] \
    || [ "$(agent_setup_sha256 "$commerce_host")" != "$expected_commerce_host" ] \
    || [ "$(agent_setup_container_sha256 citybuddy-bench-commerce /opt/citybuddy/commerce.jar)" \
      != "$expected_commerce_mounted" ]; then
    echo "Agent setup environment gate failed ($phase): a mounted JAR boundary changed." >&2
    return 1
  fi
}
