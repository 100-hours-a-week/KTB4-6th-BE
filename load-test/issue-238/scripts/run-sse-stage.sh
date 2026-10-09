#!/usr/bin/env bash
set -euo pipefail

CONNECTIONS="${CONNECTIONS:?CONNECTIONS is required}"
HOLD_SECONDS="${HOLD_SECONDS:-60}"
BASE_URL="${BASE_URL:-http://host.docker.internal:8080}"
FIXTURE_FILE="${FIXTURE_FILE:-out/fixture.json}"
HOST_FIXTURE_FILE="${HOST_FIXTURE_FILE:-load-test/issue-238/out/fixture.json}"
K6_DOCKER_IMAGE="${K6_DOCKER_IMAGE:-grafana/xk6}"
CUSTOM_K6="${CUSTOM_K6:-/work/load-test/issue-238/out/k6-sse}"
OUT_DIR="${OUT_DIR:-load-test/issue-238/out}"

mkdir -p "$OUT_DIR"

summary_file="$OUT_DIR/sse-${CONNECTIONS}-summary.json"
log_file="$OUT_DIR/sse-${CONNECTIONS}.log"
prometheus_file="$OUT_DIR/sse-${CONNECTIONS}-prometheus.txt"
times_file="$OUT_DIR/stage-times.tsv"

if [[ ! -f "$times_file" ]]; then
  printf 'connections\tstart_iso\tend_iso\tstart_epoch\tend_epoch\n' > "$times_file"
fi

start_iso="$(date -Iseconds)"
start_epoch="$(date +%s)"
echo "stage=$CONNECTIONS start=$start_iso"

set +e
docker run --rm \
  -v "$PWD":/work \
  -w /work \
  --entrypoint "$CUSTOM_K6" \
  -e BASE_URL="$BASE_URL" \
  -e CONNECTIONS="$CONNECTIONS" \
  -e HOLD_SECONDS="$HOLD_SECONDS" \
  -e FIXTURE_FILE="$FIXTURE_FILE" \
  "$K6_DOCKER_IMAGE" \
  run --summary-export "$summary_file" load-test/issue-238/sse-baseline.js 2>&1 | tee "$log_file"
exit_code="${PIPESTATUS[0]}"
set -e

end_iso="$(date -Iseconds)"
end_epoch="$(date +%s)"
printf '%s\t%s\t%s\t%s\t%s\n' "$CONNECTIONS" "$start_iso" "$end_iso" "$start_epoch" "$end_epoch" >> "$times_file"
echo "stage=$CONNECTIONS end=$end_iso exit=$exit_code"

if [[ "$exit_code" != "0" ]]; then
  exit "$exit_code"
fi

START_EPOCH="$start_epoch" END_EPOCH="$end_epoch" \
  ./load-test/issue-238/scripts/query-prometheus-range.sh > "$prometheus_file"

CONNECTIONS="$CONNECTIONS" FIXTURE_FILE="$HOST_FIXTURE_FILE" \
  ./load-test/issue-238/scripts/reset-sse-participants.sh

echo "summary=$summary_file"
echo "prometheus=$prometheus_file"

