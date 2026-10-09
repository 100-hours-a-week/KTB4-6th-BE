#!/usr/bin/env bash
set -euo pipefail

PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
WINDOW="${WINDOW:-5m}"

query() {
  local name="$1"
  local expr="$2"
  local value

  value="$(curl -sS --get "$PROMETHEUS_URL/api/v1/query" --data-urlencode "query=$expr" \
    | jq -r '.data.result[0].value[1] // "NA"')"
  printf '%s=%s\n' "$name" "$value"
}

query "hikari_active_max" "max_over_time(hikaricp_connections_active{pool=\"HikariPool-1\"}[$WINDOW])"
query "hikari_idle_min" "min_over_time(hikaricp_connections_idle{pool=\"HikariPool-1\"}[$WINDOW])"
query "hikari_pending_max" "max_over_time(hikaricp_connections_pending{pool=\"HikariPool-1\"}[$WINDOW])"
query "hikari_max" "max_over_time(hikaricp_connections_max{pool=\"HikariPool-1\"}[$WINDOW])"
query "hikari_timeout_increase" "increase(hikaricp_connections_timeout_total{pool=\"HikariPool-1\"}[$WINDOW])"
query "hikari_acquire_seconds_max" "max_over_time(hikaricp_connections_acquire_seconds_max{pool=\"HikariPool-1\"}[$WINDOW])"
query "process_cpu_usage_max" "max_over_time(process_cpu_usage[$WINDOW])"
query "jvm_heap_used_bytes_max" "max_over_time(sum(jvm_memory_used_bytes{area=\"heap\"})[$WINDOW:])"
query "jvm_live_threads_max" "max_over_time(jvm_threads_live_threads[$WINDOW])"
query "jvm_gc_pause_count_increase" "increase(jvm_gc_pause_seconds_count[$WINDOW])"
query "jvm_gc_pause_seconds_max" "max_over_time(jvm_gc_pause_seconds_max[$WINDOW])"

