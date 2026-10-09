#!/usr/bin/env bash
set -euo pipefail

PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
START_EPOCH="${START_EPOCH:?START_EPOCH is required}"
END_EPOCH="${END_EPOCH:?END_EPOCH is required}"
STEP="${STEP:-5s}"

query_range() {
  local expr="$1"
  curl -sS --get "$PROMETHEUS_URL/api/v1/query_range" \
    --data-urlencode "query=$expr" \
    --data-urlencode "start=$START_EPOCH" \
    --data-urlencode "end=$END_EPOCH" \
    --data-urlencode "step=$STEP"
}

max_value() {
  jq -r '[.data.result[].values[][1] | tonumber] | if length == 0 then "NA" else max end'
}

min_value() {
  jq -r '[.data.result[].values[][1] | tonumber] | if length == 0 then "NA" else min end'
}

increase_value() {
  jq -r '[.data.result[].values[][1] | tonumber] | if length < 2 then 0 else (.[-1] - .[0]) end'
}

metric_max() {
  local name="$1"
  local expr="$2"
  local value
  value="$(query_range "$expr" | max_value)"
  printf '%s=%s\n' "$name" "$value"
}

metric_min() {
  local name="$1"
  local expr="$2"
  local value
  value="$(query_range "$expr" | min_value)"
  printf '%s=%s\n' "$name" "$value"
}

metric_increase() {
  local name="$1"
  local expr="$2"
  local value
  value="$(query_range "$expr" | increase_value)"
  printf '%s=%s\n' "$name" "$value"
}

metric_max "hikari_active_max" 'hikaricp_connections_active{pool="HikariPool-1"}'
metric_min "hikari_idle_min" 'hikaricp_connections_idle{pool="HikariPool-1"}'
metric_max "hikari_pending_max" 'hikaricp_connections_pending{pool="HikariPool-1"}'
metric_max "hikari_max" 'hikaricp_connections_max{pool="HikariPool-1"}'
metric_increase "hikari_timeout_increase" 'hikaricp_connections_timeout_total{pool="HikariPool-1"}'
metric_max "hikari_acquire_seconds_max" 'hikaricp_connections_acquire_seconds_max{pool="HikariPool-1"}'
metric_max "process_cpu_usage_max" 'process_cpu_usage'
metric_max "jvm_heap_used_bytes_max" 'sum(jvm_memory_used_bytes{area="heap"})'
metric_max "jvm_live_threads_max" 'jvm_threads_live_threads'
metric_increase "jvm_gc_pause_count_increase" 'sum(jvm_gc_pause_seconds_count)'
metric_max "jvm_gc_pause_seconds_max" 'jvm_gc_pause_seconds_max'

