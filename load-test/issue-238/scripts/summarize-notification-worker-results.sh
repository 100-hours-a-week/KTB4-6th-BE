#!/usr/bin/env bash
set -euo pipefail

OUT_ROOT="${OUT_ROOT:-load-test/issue-238/out}"
WORKERS="${WORKERS:-1 2 4 8}"
REQUESTS="${REQUESTS:-50}"

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

kv_value() {
  local file="$1"
  local key="$2"
  if [[ ! -f "$file" ]]; then
    echo "NA"
    return
  fi
  awk -F'\t' -v key="$key" '$1 == key { print $2; found = 1 } END { if (!found) print "NA" }' "$file"
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

value_for_worker() {
  local worker="$1"
  local metric="$2"
  local dir="$OUT_ROOT/executor-worker-${worker}"
  local summary="$dir/recording-start-${REQUESTS}-summary.json"
  local actuator="$dir/recording-start-${REQUESTS}-actuator.tsv"
  local app_log="$dir/recording-start-${REQUESTS}-app.log"
  local prometheus="$dir/recording-start-${REQUESTS}-prometheus.txt"
  local notification="$dir/recording-start-${REQUESTS}-notification.tsv"

  case "$metric" in
    requests) json_metric "$summary" '.metrics.http_reqs.count' ;;
    success) json_metric "$summary" '.metrics.recording_start_successes.count // 0' ;;
    failure) json_metric "$summary" '.metrics.recording_start_failures.count // 0' ;;
    avg_latency) json_metric "$summary" '.metrics.http_req_duration.avg' ;;
    p95) json_metric "$summary" '.metrics.http_req_duration["p(95)"]' ;;
    p99) json_metric "$summary" '.metrics.http_req_duration["p(99)"]' ;;
    max_latency) json_metric "$summary" '.metrics.http_req_duration.max' ;;
    hikari_active)
      { max_log_hikari "$app_log" "active"; max_column "$actuator" "hikari_active"; prom_value "$prometheus" "hikari_active_max"; } | combined_max
      ;;
    hikari_idle)
      { min_log_hikari "$app_log" "idle"; min_column "$actuator" "hikari_idle"; prom_value "$prometheus" "hikari_idle_min"; } | combined_min
      ;;
    hikari_pending)
      { max_log_hikari "$app_log" "pending"; max_column "$actuator" "hikari_pending"; prom_value "$prometheus" "hikari_pending_max"; } | combined_max
      ;;
    timeout) delta_column "$actuator" "hikari_timeout_total" ;;
    executor_active)
      { max_column "$actuator" "notification_executor_active"; prom_value "$prometheus" "notification_executor_active_max"; } | combined_max
      ;;
    executor_queue)
      { max_column "$actuator" "notification_executor_queue_size"; prom_value "$prometheus" "notification_executor_queue_max"; } | combined_max
      ;;
    notification_expected) kv_value "$notification" "notificationExpected" ;;
    notification_created) kv_value "$notification" "notificationCreated" ;;
    notification_failed) kv_value "$notification" "notificationFailed" ;;
    notification_drain) kv_value "$notification" "notificationDrainSeconds" ;;
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
  for worker in $WORKERS; do
    printf ' %s |' "$(value_for_worker "$worker" "$metric")"
  done
  printf '\n'
}

printf '| Metric |'
for worker in $WORKERS; do
  printf ' Worker %s |' "$worker"
done
printf '\n'
printf '|---|'
for _worker in $WORKERS; do
  printf '%s' '---:|'
done
printf '\n'

print_row "Recording Requests" "requests"
print_row "Recording Success" "success"
print_row "Recording Failure" "failure"
print_row "Avg" "avg_latency"
print_row "P95" "p95"
print_row "P99" "p99"
print_row "Max Latency" "max_latency"
print_row "Max Hikari Active" "hikari_active"
print_row "Min Hikari Idle" "hikari_idle"
print_row "Max Hikari Pending" "hikari_pending"
print_row "Connection Timeout" "timeout"
print_row "Executor Active Max" "executor_active"
print_row "Executor Queue Max" "executor_queue"
print_row "Notification Expected" "notification_expected"
print_row "Notification Created" "notification_created"
print_row "Notification Failed" "notification_failed"
print_row "Notification Drain Time" "notification_drain"
print_row "Max CPU" "cpu"
print_row "Max Heap" "heap"
print_row "Max Live Threads" "threads"
