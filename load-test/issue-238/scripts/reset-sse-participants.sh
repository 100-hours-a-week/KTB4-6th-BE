#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
FIXTURE_FILE="${FIXTURE_FILE:-load-test/issue-238/out/fixture.json}"
CONNECTIONS="${CONNECTIONS:-1}"

request_status() {
  local method="$1"
  local path="$2"
  local token="$3"

  curl -sS -o /tmp/issue238-reset-response.json -w '%{http_code}' \
    -X "$method" "$BASE_URL$path" \
    -H "Cookie: accessToken=$token"
}

require_status() {
  local expected="$1"
  local actual="$2"
  local context="$3"

  if [[ "$actual" != "$expected" ]]; then
    echo "Unexpected status for $context: expected=$expected actual=$actual"
    cat /tmp/issue238-reset-response.json
    exit 1
  fi
}

total="$(jq '.connections | length' "$FIXTURE_FILE")"
if (( CONNECTIONS > total )); then
  echo "CONNECTIONS=$CONNECTIONS exceeds fixture connections=$total"
  exit 1
fi

for index in $(seq 0 $((CONNECTIONS - 1))); do
  meeting_id="$(jq -r ".connections[$index].meetingId" "$FIXTURE_FILE")"
  token="$(jq -r ".connections[$index].accessToken" "$FIXTURE_FILE")"

  status="$(request_status DELETE "/api/v1/meetings/$meeting_id/participants/me" "$token")"
  require_status "204" "$status" "leave meetingId=$meeting_id index=$index"
done

for index in $(seq 0 $((CONNECTIONS - 1))); do
  meeting_id="$(jq -r ".connections[$index].meetingId" "$FIXTURE_FILE")"
  token="$(jq -r ".connections[$index].accessToken" "$FIXTURE_FILE")"

  status="$(request_status POST "/api/v1/meetings/$meeting_id/participants" "$token")"
  require_status "201" "$status" "rejoin meetingId=$meeting_id index=$index"
done

rm -f /tmp/issue238-reset-response.json
echo "reset participants: $CONNECTIONS"

