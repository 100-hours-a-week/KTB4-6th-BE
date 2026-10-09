#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
COUNT="${COUNT:?COUNT is required}"
LOGIN_PREFIX="${LOGIN_PREFIX:-issue238-rec-$(date +%Y%m%d%H%M%S)}"
FIXTURE_FILE="${FIXTURE_FILE:-load-test/issue-238/out/recording-start-fixture-${COUNT}.json}"
PASSWORD="${LOAD_TEST_PASSWORD:-}"

if [[ -z "$PASSWORD" ]]; then
  echo "LOAD_TEST_PASSWORD is required and must be a disposable local password."
  exit 1
fi

mkdir -p "$(dirname "$FIXTURE_FILE")"

request_json() {
  local method="$1"
  local path="$2"
  local token="$3"
  local body="${4:-}"

  local args=(-sS -X "$method" "$BASE_URL$path" -H "Content-Type: application/json" -w $'\n%{http_code}')
  if [[ -n "$token" ]]; then
    args+=(-H "Cookie: accessToken=$token")
  fi
  if [[ -n "$body" ]]; then
    args+=(-d "$body")
  fi

  curl "${args[@]}"
}

body_of() {
  sed '$d'
}

status_of() {
  tail -n 1
}

require_success() {
  local body="$1"
  local context="$2"
  if ! jq -e '.success == true' >/dev/null <<<"$body"; then
    echo "API failed: $context"
    jq . <<<"$body"
    exit 1
  fi
}

signup() {
  local login_id="$1"
  local signup_body response status body

  signup_body="$(jq -n --arg loginId "$login_id" --arg password "$PASSWORD" \
    '{loginId: $loginId, password: $password}')"
  response="$(request_json POST "/api/v1/auth/local/signup" "" "$signup_body")"
  status="$(status_of <<<"$response")"
  body="$(body_of <<<"$response")"

  if [[ "$status" != "201" ]]; then
    echo "Signup failed: loginId=$login_id status=$status"
    jq . <<<"$body"
    exit 1
  fi

  require_success "$body" "signup $login_id"
  jq -c '.data' <<<"$body"
}

join_participant() {
  local token="$1"
  local meeting_id="$2"
  local response status body

  response="$(request_json POST "/api/v1/meetings/$meeting_id/participants" "$token")"
  status="$(status_of <<<"$response")"
  body="$(body_of <<<"$response")"

  if [[ "$status" != "201" ]]; then
    echo "Join participant failed: meetingId=$meeting_id status=$status"
    jq . <<<"$body"
    exit 1
  fi
  require_success "$body" "join participant meetingId=$meeting_id"
}

get_credit_balance() {
  local token="$1"
  local team_id="$2"
  local response body

  response="$(request_json GET "/api/v1/teams/$team_id/credits" "$token")"
  body="$(body_of <<<"$response")"
  require_success "$body" "get credit teamId=$team_id"
  jq -r '.data.balance' <<<"$body"
}

tmp_fixture="$(mktemp)"
trap 'rm -f "$tmp_fixture"' EXIT

jq -n \
  --arg createdAt "$(date -Iseconds)" \
  --arg baseUrl "$BASE_URL" \
  --arg loginPrefix "$LOGIN_PREFIX" \
  --argjson count "$COUNT" \
  '{
    createdAt: $createdAt,
    baseUrl: $baseUrl,
    loginPrefix: $loginPrefix,
    count: $count,
    targets: []
  }' > "$tmp_fixture"

for target_no in $(seq 1 "$COUNT"); do
  target_code="$(printf '%03d' "$target_no")"
  login_id="${LOGIN_PREFIX}-t${target_code}-u01"
  auth="$(signup "$login_id")"
  user_id="$(jq -r '.userId' <<<"$auth")"
  token="$(jq -r '.accessToken' <<<"$auth")"

  team_body="$(jq -n --arg name "RS${target_code}" --arg displayName "rs${target_code}" \
    '{name: $name, displayName: $displayName}')"
  team_response="$(request_json POST "/api/v1/teams" "$token" "$team_body")"
  team_status="$(status_of <<<"$team_response")"
  team_body_response="$(body_of <<<"$team_response")"
  if [[ "$team_status" != "201" ]]; then
    echo "Team create failed: index=$target_no status=$team_status"
    jq . <<<"$team_body_response"
    exit 1
  fi
  require_success "$team_body_response" "create team index=$target_no"
  team_id="$(jq -r '.data.teamId' <<<"$team_body_response")"

  meeting_body="$(jq -n --arg title "RS$target_code" \
    '{title: $title, purpose: "issue238-recording-start-baseline", note: "fixture", targetDurationMinutes: 30}')"
  meeting_response="$(request_json POST "/api/v1/teams/$team_id/meetings" "$token" "$meeting_body")"
  meeting_status="$(status_of <<<"$meeting_response")"
  meeting_body_response="$(body_of <<<"$meeting_response")"
  if [[ "$meeting_status" != "201" ]]; then
    echo "Meeting create failed: teamId=$team_id status=$meeting_status"
    jq . <<<"$meeting_body_response"
    exit 1
  fi
  require_success "$meeting_body_response" "create meeting teamId=$team_id"
  meeting_id="$(jq -r '.data.meetingId' <<<"$meeting_body_response")"
  meeting_status_value="$(jq -r '.data.status' <<<"$meeting_body_response")"

  join_participant "$token" "$meeting_id"
  credit_balance="$(get_credit_balance "$token" "$team_id")"

  target_json="$(jq -n \
    --argjson targetIndex "$target_no" \
    --argjson teamId "$team_id" \
    --argjson meetingId "$meeting_id" \
    --argjson userId "$user_id" \
    --arg loginId "$login_id" \
    --arg accessToken "$token" \
    --arg meetingStatus "$meeting_status_value" \
    --argjson initialCreditBalance "$credit_balance" \
    '{
      targetIndex: $targetIndex,
      teamId: $teamId,
      meetingId: $meetingId,
      userId: $userId,
      loginId: $loginId,
      accessToken: $accessToken,
      meetingStatus: $meetingStatus,
      initialCreditBalance: $initialCreditBalance
    }')"

  jq --argjson target "$target_json" '.targets += [$target]' "$tmp_fixture" > "$tmp_fixture.next"
  mv "$tmp_fixture.next" "$tmp_fixture"

  echo "prepared target=$target_no teamId=$team_id meetingId=$meeting_id userId=$user_id credit=$credit_balance"
done

jq . "$tmp_fixture" > "$FIXTURE_FILE"

echo "fixture written: $FIXTURE_FILE"
echo "targets: $(jq '.targets | length' "$FIXTURE_FILE")"
