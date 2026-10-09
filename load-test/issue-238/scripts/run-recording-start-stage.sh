#!/usr/bin/env bash
set -euo pipefail

REQUESTS="${REQUESTS:?REQUESTS is required}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
ACTUATOR_URL="${ACTUATOR_URL:-http://localhost:8081}"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
FIXTURE_FILE="${FIXTURE_FILE:-load-test/issue-238/out/recording-start-fixture-${REQUESTS}.json}"
OUT_DIR="${OUT_DIR:-load-test/issue-238/out}"
K6_BIN="${K6_BIN:-k6}"
SAMPLE_INTERVAL_SECONDS="${SAMPLE_INTERVAL_SECONDS:-0.2}"
REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-240s}"
APP_LOG_FILE="${APP_LOG_FILE:-}"

mkdir -p "$OUT_DIR"

summary_file="$OUT_DIR/recording-start-${REQUESTS}-summary.json"
log_file="$OUT_DIR/recording-start-${REQUESTS}.log"
prometheus_file="$OUT_DIR/recording-start-${REQUESTS}-prometheus.txt"
actuator_file="$OUT_DIR/recording-start-${REQUESTS}-actuator.tsv"
app_stage_log="$OUT_DIR/recording-start-${REQUESTS}-app.log"
db_verify_file="$OUT_DIR/recording-start-${REQUESTS}-db.tsv"
times_file="$OUT_DIR/stage-times-recording-start.tsv"

if [[ ! -f "$FIXTURE_FILE" ]]; then
  echo "Fixture file not found: $FIXTURE_FILE"
  exit 1
fi

if [[ "$FIXTURE_FILE" = /* ]]; then
  K6_FIXTURE_FILE="$FIXTURE_FILE"
else
  K6_FIXTURE_FILE="$PWD/$FIXTURE_FILE"
fi

if [[ -z "$APP_LOG_FILE" ]]; then
  APP_LOG_FILE="$(ls -t "$HOME"/.gradle/daemon/*/daemon-*.out.log 2>/dev/null | head -n 1 || true)"
fi

if [[ ! -f "$times_file" ]]; then
  printf 'requests\tstart_iso\tend_iso\tstart_epoch\tend_epoch\n' > "$times_file"
fi

printf 'ts_epoch\thikari_active\thikari_idle\thikari_pending\thikari_max\thikari_timeout_total\tprocess_cpu_usage\tjvm_heap_used_bytes\tjvm_live_threads\tjvm_gc_pause_count\tjvm_gc_pause_seconds_max\n' > "$actuator_file"

sample_once() {
  local ts
  ts="$(date +%s)"
  curl -sS --max-time 2 "$ACTUATOR_URL/actuator/prometheus" | awk -v ts="$ts" '
    BEGIN {
      active="NA"; idle="NA"; pending="NA"; max_conn="NA"; timeout_total="NA";
      cpu="NA"; heap=0; heap_seen=0; threads="NA"; gc_count=0; gc_seen=0; gc_max="NA";
    }
    /^hikaricp_connections_active\{pool="HikariPool-1"\}/ { active=$NF }
    /^hikaricp_connections_idle\{pool="HikariPool-1"\}/ { idle=$NF }
    /^hikaricp_connections_pending\{pool="HikariPool-1"\}/ { pending=$NF }
    /^hikaricp_connections_max\{pool="HikariPool-1"\}/ { max_conn=$NF }
    /^hikaricp_connections_timeout_total\{pool="HikariPool-1"\}/ { timeout_total=$NF }
    /^process_cpu_usage / { cpu=$NF }
    /^jvm_memory_used_bytes\{.*area="heap"/ { heap += $NF; heap_seen=1 }
    /^jvm_threads_live_threads / { threads=$NF }
    /^jvm_gc_pause_seconds_count\{/ { gc_count += $NF; gc_seen=1 }
    /^jvm_gc_pause_seconds_max\{/ {
      if (gc_max == "NA" || $NF > gc_max) {
        gc_max=$NF
      }
    }
    END {
      if (!heap_seen) {
        heap="NA"
      }
      if (!gc_seen) {
        gc_count="NA"
      }
      printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n",
        ts, active, idle, pending, max_conn, timeout_total, cpu, heap, threads, gc_count, gc_max;
    }
  ' >> "$actuator_file" || true
}

stop_file="$(mktemp)"
rm -f "$stop_file"
(
  while [[ ! -f "$stop_file" ]]; do
    sample_once
    sleep "$SAMPLE_INTERVAL_SECONDS"
  done
) &
sampler_pid="$!"

app_log_offset=0
if [[ -n "$APP_LOG_FILE" && -f "$APP_LOG_FILE" ]]; then
  app_log_offset="$(wc -c < "$APP_LOG_FILE" | tr -d ' ')"
fi

start_iso="$(date -Iseconds)"
start_epoch="$(date +%s)"
echo "recording-start stage=$REQUESTS start=$start_iso"

set +e
"$K6_BIN" run \
  --summary-export "$summary_file" \
  -e BASE_URL="$BASE_URL" \
  -e REQUESTS="$REQUESTS" \
  -e REQUEST_TIMEOUT="$REQUEST_TIMEOUT" \
  -e FIXTURE_FILE="$K6_FIXTURE_FILE" \
  load-test/issue-238/recording-start-baseline.js 2>&1 | tee "$log_file"
exit_code="${PIPESTATUS[0]}"
set -e

end_iso="$(date -Iseconds)"
end_epoch="$(date +%s)"
printf '%s\t%s\t%s\t%s\t%s\n' "$REQUESTS" "$start_iso" "$end_iso" "$start_epoch" "$end_epoch" >> "$times_file"
echo "recording-start stage=$REQUESTS end=$end_iso exit=$exit_code"

touch "$stop_file"
wait "$sampler_pid" 2>/dev/null || true
rm -f "$stop_file"

sample_once

if [[ -n "$APP_LOG_FILE" && -f "$APP_LOG_FILE" ]]; then
  tail -c +"$((app_log_offset + 1))" "$APP_LOG_FILE" > "$app_stage_log" || true
else
  : > "$app_stage_log"
fi

START_EPOCH="$((start_epoch - 5))" END_EPOCH="$((end_epoch + 20))" PROMETHEUS_URL="$PROMETHEUS_URL" \
  ./load-test/issue-238/scripts/query-prometheus-range.sh > "$prometheus_file" || true

FIXTURE_FILE="$FIXTURE_FILE" ./load-test/issue-238/scripts/verify-recording-start-stage.sh > "$db_verify_file"

echo "summary=$summary_file"
echo "k6_log=$log_file"
echo "actuator=$actuator_file"
echo "prometheus=$prometheus_file"
echo "app_log=$app_stage_log"
echo "db_verify=$db_verify_file"

if [[ "$exit_code" != "0" ]]; then
  exit "$exit_code"
fi
