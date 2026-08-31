#!/usr/bin/env bash
# Samples the agent's stacks with py-spy while one path is driven at a fixed concurrency, and
# writes the raw collapsed-stack output plus the command that produced it.
#
# This is a closed-loop operating point, not a ladder step: it holds a fixed number of turns in
# flight rather than a fixed arrival rate. It answers "what is the agent's CPU spent on" and not
# "what does the agent cost at rate R" — the per-step CPU windows in run_agent_ladder.sh answer
# that. setup_agent_bench.sh grants the agent SYS_PTRACE so py-spy can attach; the capability
# only permits the attach and does not otherwise change how the service runs.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$repo_root"
out="$repo_root/bench/results"; mkdir -p "$out"
live_setup_environment="$repo_root/bench/.run/agent_setup_environment.json"
# shellcheck source=bench/agent/setup_environment_gate.sh
source "$repo_root/bench/agent/setup_environment_gate.sh"

PATH_NAME="$1"                                   # chat | retrieval | prepare
CONCURRENCY="${CONCURRENCY:-8}"
# Output files are named by LABEL, not by path, so a profile taken after a change does not
# overwrite the baseline it is meant to be compared with, the same way the ladder runner does.
LABEL="${LABEL:-$PATH_NAME}"
SECONDS_TO_SAMPLE="${SECONDS_TO_SAMPLE:-25}"
HERTZ="${HERTZ:-100}"

# Sized per path so the load outlasts the sampling window: a load that finishes early leaves the
# rest of the window sampling an idle process, which dilutes every share the tally reports.
case "$PATH_NAME" in
  retrieval) message='retrieval-sufficient what does the refund policy cover'; default_requests=1200 ;;
  prepare)   message='action-prepare refund my order ORDER';                   default_requests=1200 ;;
  *)         message='hello, can you tell me about delivery times';             default_requests=4000 ;;
esac
REQUESTS="${REQUESTS:-$default_requests}"
profile_path="$out/agent_pyspy_${LABEL}_c${CONCURRENCY}.txt"
setup_environment_path="$out/agent_pyspy_${LABEL}_c${CONCURRENCY}_setup_environment.json"
for target_path in "$profile_path" "$setup_environment_path" "$setup_environment_path.tmp"; do
  if [ -e "$target_path" ]; then
    echo "Refusing to overwrite existing agent profile output: $target_path" >&2
    exit 1
  fi
done
cp "$live_setup_environment" "$setup_environment_path.tmp"
mv "$setup_environment_path.tmp" "$setup_environment_path"
verify_agent_setup_environment "$setup_environment_path" "before profile"
citybuddy_commit="$(agent_setup_json_string "$setup_environment_path" '.citybuddyCommit')"
setup_nonce="$(agent_setup_json_string "$setup_environment_path" '.setupNonce')"
agent_image_id="$(jq -er \
  '.containers["citybuddy-bench-agent"].imageId
   | select(type == "string" and test("^sha256:[0-9a-f]{64}$"))' \
  "$setup_environment_path")"

docker rm -f citybuddy-bench-profile-load >/dev/null 2>&1 || true
profile_load_id="$(docker run --detach --rm --name citybuddy-bench-profile-load \
  --label "citybuddy.bench.setup-nonce=$setup_nonce" \
  --label "citybuddy.bench.citybuddy-commit=$citybuddy_commit" \
  --network "container:citybuddy-bench-net" \
  --volume "$repo_root/bench/agent/drive_concurrency.py:/opt/drive.py:ro" \
  --volume "$repo_root/bench/.run:/run-data:ro" \
  --entrypoint /opt/citybuddy/.venv/bin/python \
  "$agent_image_id" /opt/drive.py "$message" "$CONCURRENCY" "$REQUESTS")"
if [ "$(docker inspect --format '{{.Id}}' "$profile_load_id")" != "$profile_load_id" ] \
  || [ "$(docker inspect --format '{{.Image}}' "$profile_load_id")" != "$agent_image_id" ] \
  || [ "$(docker inspect --format \
    '{{ index .Config.Labels "citybuddy.bench.setup-nonce" }}' "$profile_load_id")" \
    != "$setup_nonce" ] \
  || [ "$(docker inspect --format \
    '{{ index .Config.Labels "citybuddy.bench.citybuddy-commit" }}' "$profile_load_id")" \
    != "$citybuddy_commit" ]; then
  echo "The profile load container does not belong to the saved setup environment." >&2
  exit 1
fi
agent_container_id="$(jq -er '.containers["citybuddy-bench-agent"].id' \
  "$setup_environment_path")"
sleep 5

command="py-spy record --pid 1 --duration $SECONDS_TO_SAMPLE --rate $HERTZ --nonblocking --format raw"
docker exec "$agent_container_id" /bin/uvx $command \
  --output "/tmp/$PATH_NAME.txt" >/dev/null 2>&1
# The load container must still be running when sampling ends, or part of the window sampled an
# idle process and the shares below are diluted.
still_running="$(docker inspect -f '{{.State.Running}}' "$profile_load_id" 2>/dev/null || true)"
still_running="$(printf '%s' "$still_running" | tr -d '\n')"
still_running="${still_running:-false}"
if [ "$still_running" != true ]; then
  echo "The profile load ended before sampling completed; the profile is invalid." >&2
  exit 1
fi
docker rm -f "$profile_load_id" >/dev/null

{
  echo "# citybuddy_commit=$citybuddy_commit"
  echo "# setup_nonce=$setup_nonce"
  echo "# path=$PATH_NAME concurrency=$CONCURRENCY load-still-running-at-end-of-sample=$still_running"
  echo "# $command"
  echo "# Collapsed stacks, one per line, trailing field is the sample count."
  docker exec "$agent_container_id" cat "/tmp/$PATH_NAME.txt"
} > "$profile_path"

# Collapsed stacks run to thousands of characters, past what the system awk will read as one
# record, so the tally is done in python.
uv run python - "$profile_path" "$PATH_NAME" <<'TALLY'
import sys

path, name = sys.argv[1], sys.argv[2]
total = matched = 0
# py-spy can emit a frame name that is not valid UTF-8, and one such byte anywhere in the
# file would otherwise abort the tally over an entire profile.
with open(path, encoding="utf-8", errors="replace") as handle:
    for line in handle:
        if line.startswith("#"):
            continue
        # A sampled frame can carry an embedded newline (a docstring line, for instance), which
        # splits one collapsed stack across two lines. Only the part ending in a sample count is
        # a whole record; the stray remainder is skipped rather than parsed.
        tail = line.rsplit(" ", 1)[-1].strip()
        if not tail.isdigit():
            continue
        count = int(tail)
        total += count
        if "create_default_context" in line:
            matched += count
share = 100 * matched / total if total else 0
print(f"{name:<11} {matched:>8} of {total:>8} samples in ssl.create_default_context = {share:.1f}%")
TALLY
verify_agent_setup_environment "$setup_environment_path" "after profile"
