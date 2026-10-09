#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${OUT_DIR:-load-test/issue-238/out}"
STAGES="${STAGES:-1 10 30 50}"

json_metric() {
  local file="$1"
  local expr="$2"
  if [[ ! -f "$file" ]]; then
    echo "NA"
    return
  fi
  jq -r "$expr // \"NA\"" "$file"
}

max_column() {
  local file="$1"
  local column="$2"
  if [[ ! -f "$file" ]]; then
    echo "NA"
    return
  fi
  awk -F'\t' -v col="$column" '
    NR == 1 {
      for (i = 1; i <= NF; i++) {
        if ($i == col) {
          idx = i
        }
      }
      next
    }
    idx && $idx != "NA" {
      value = $idx + 0
      if (!seen || value > max) {
        max = value
        seen = 1
      }
    }
    END { if (seen) print max; else print "NA" }
  ' "$file"
}

min_column() {
  local file="$1"
  local column="$2"
  if [[ ! -f "$file" ]]; then
    echo "NA"
    return
  fi
  awk -F'\t' -v col="$column" '
    NR == 1 {
      for (i = 1; i <= NF; i++) {
        if ($i == col) {
          idx = i
        }
      }
      next
    }
    idx && $idx != "NA" {
      value = $idx + 0
      if (!seen || value < min) {
        min = value
        seen = 1
      }
    }
    END { if (seen) print min; else print "NA" }
  ' "$file"
}

delta_column() {
  local file="$1"
  local column="$2"
  if [[ ! -f "$file" ]]; then
    echo "NA"
    return
  fi
  awk -F'\t' -v col="$column" '
    NR == 1 {
      for (i = 1; i <= NF; i++) {
        if ($i == col) {
          idx = i
        }
      }
      next
    }
    idx && $idx != "NA" {
      value = $idx + 0
      if (!seen) {
        first = value
        seen = 1
      }
      last = value
    }
    END { if (seen) print last - first; else print "NA" }
  ' "$file"
}

prom_value() {
  local file="$1"
  local name="$2"
  if [[ ! -f "$file" ]]; then
    echo "NA"
    return
  fi
  awk -F'=' -v name="$name" '$1 == name { print $2; found = 1 } END { if (!found) print "NA" }' "$file"
}

max_log_elapsed() {
  local file="$1"
  local pattern="$2"
  if [[ ! -f "$file" ]]; then
    echo "NA"
    return
  fi
  sed -nE "s/.*${pattern}.*elapsedMs=([0-9]+).*/\\1/p" "$file" \
    | awk 'BEGIN { max = "NA" } { if (max == "NA" || $1 > max) max=$1 } END { print max }'
}

max_log_hikari() {
  local file="$1"
  local key="$2"
  if [[ ! -f "$file" ]]; then
    echo "NA"
    return
  fi
  sed -nE "s/.*\\[HIKARI\\].*${key}=([0-9-]+).*/\\1/p" "$file" \
    | awk 'BEGIN { max = "NA" } { if (max == "NA" || $1 > max) max=$1 } END { print max }'
}

min_log_hikari() {
  local file="$1"
  local key="$2"
  if [[ ! -f "$file" ]]; then
    echo "NA"
    return
  fi
  sed -nE "s/.*\\[HIKARI\\].*${key}=([0-9-]+).*/\\1/p" "$file" \
    | awk 'BEGIN { min = "NA" } { if (min == "NA" || $1 < min) min=$1 } END { print min }'
}

combined_max() {
  awk '
    $1 != "NA" {
      value = $1 + 0
      if (!seen || value > max) {
        max = value
        seen = 1
      }
    }
    END { if (seen) print max; else print "NA" }
  '
}

combined_min() {
  awk '
    $1 != "NA" {
      value = $1 + 0
      if (!seen || value < min) {
        min = value
        seen = 1
      }
    }
    END { if (seen) print min; else print "NA" }
  '
}

value_for_stage() {
  local stage="$1"
  local metric="$2"
  local summary="$OUT_DIR/recording-start-${stage}-summary.json"
  local actuator="$OUT_DIR/recording-start-${stage}-actuator.tsv"
  local app_log="$OUT_DIR/recording-start-${stage}-app.log"
  local prometheus="$OUT_DIR/recording-start-${stage}-prometheus.txt"

  case "$metric" in
    requests) json_metric "$summary" '.metrics.http_reqs.count' ;;
    success) json_metric "$summary" '.metrics.recording_start_successes.count // 0' ;;
    failure) json_metric "$summary" '.metrics.recording_start_failures.count // 0' ;;
    avg_latency) json_metric "$summary" '.metrics.http_req_duration.avg' ;;
    p95) json_metric "$summary" '.metrics.http_req_duration["p(95)"]' ;;
    p99) json_metric "$summary" '.metrics.http_req_duration["p(99)"]' ;;
    max_latency) json_metric "$summary" '.metrics.http_req_duration.max' ;;
    throughput) json_metric "$summary" '.metrics.http_reqs.rate' ;;
    hikari_active)
      { max_log_hikari "$app_log" "active"; max_column "$actuator" "hikari_active"; } | combined_max
      ;;
    hikari_idle)
      { min_log_hikari "$app_log" "idle"; min_column "$actuator" "hikari_idle"; } | combined_min
      ;;
    hikari_pending)
      { max_log_hikari "$app_log" "pending"; max_column "$actuator" "hikari_pending"; } | combined_max
      ;;
    hikari_max) max_column "$actuator" "hikari_max" ;;
    timeout) delta_column "$actuator" "hikari_timeout_total" ;;
    meeting_lock) max_log_elapsed "$app_log" "meeting lock acquired" ;;
    credit_lock) max_log_elapsed "$app_log" "credit lock acquired" ;;
    cpu) { max_column "$actuator" "process_cpu_usage"; prom_value "$prometheus" "process_cpu_usage_max"; } | combined_max ;;
    heap) { max_column "$actuator" "jvm_heap_used_bytes"; prom_value "$prometheus" "jvm_heap_used_bytes_max"; } | combined_max ;;
    threads) { max_column "$actuator" "jvm_live_threads"; prom_value "$prometheus" "jvm_live_threads_max"; } | combined_max ;;
    *) echo "NA" ;;
  esac
}

print_row() {
  local label="$1"
  local metric="$2"
  printf '| %s |' "$label"
  for stage in $STAGES; do
    printf ' %s |' "$(value_for_stage "$stage" "$metric")"
  done
  printf '\n'
}

printf '| Metric |'
for stage in $STAGES; do
  printf ' %s |' "$stage"
done
printf '\n'
printf '|---|'
for _stage in $STAGES; do
  printf '%s' '---:|'
done
printf '\n'

print_row "Requests" "requests"
print_row "Success" "success"
print_row "Failure" "failure"
print_row "Avg Latency (ms)" "avg_latency"
print_row "P95 (ms)" "p95"
print_row "P99 (ms)" "p99"
print_row "Max Latency (ms)" "max_latency"
print_row "Throughput (req/s)" "throughput"
print_row "Max Hikari Active" "hikari_active"
print_row "Min Hikari Idle" "hikari_idle"
print_row "Max Hikari Pending" "hikari_pending"
print_row "Max Pool Size" "hikari_max"
print_row "Connection Timeout" "timeout"
print_row "Max Meeting Lock Wait (ms)" "meeting_lock"
print_row "Max Credit Lock Wait (ms)" "credit_lock"
print_row "Max Process CPU" "cpu"
print_row "Max Heap Used (bytes)" "heap"
print_row "Max Live Threads" "threads"
