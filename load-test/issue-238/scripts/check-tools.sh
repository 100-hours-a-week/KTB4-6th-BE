#!/usr/bin/env bash
set -euo pipefail

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6: missing"
  exit 1
fi

echo "k6: $(k6 version)"

tmp_script="$(mktemp)"
trap 'rm -f "$tmp_script"' EXIT

printf 'import sse from "k6/x/sse"; export default function() {}\n' > "$tmp_script"

if k6 run "$tmp_script" >/tmp/issue238-k6-sse-check.log 2>&1; then
  echo "k6-sse: available"
else
  echo "k6-sse: unavailable"
  cat /tmp/issue238-k6-sse-check.log
  exit 1
fi

