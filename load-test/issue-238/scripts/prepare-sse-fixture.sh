#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TEAMS="${TEAMS:-50}"
MEMBERS_PER_TEAM="${MEMBERS_PER_TEAM:-6}"
LOGIN_PREFIX="${LOGIN_PREFIX:-issue238-$(date +%Y%m%d%H%M%S)}"
FIXTURE_FILE="${FIXTURE_FILE:-load-test/issue-238/out/fixture.json}"
PASSWORD="${LOAD_TEST_PASSWORD:-}"

if [[ -z "$PASSWORD" ]]; then
  echo "LOAD_TEST_PASSWORD is required and must be a disposable local password."
  exit 1
fi

if (( MEMBERS_PER_TEAM < 1 || MEMBERS_PER_TEAM > 10 )); then
  echo "MEMBERS_PER_TEAM must be between 1 and 10 because TeamMemberService caps active members at 10."
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

signup_or_login() {
  local login_id="$1"
  local signup_body
  signup_body="$(jq -n --arg loginId "$login_id" --arg password "$PASSWORD" \
    '{loginId: $loginId, password: $password}')"

  local response status body
  response="$(request_json POST "/api/v1/auth/local/signup" "" "$signup_body")"
  status="$(status_of <<<"$response")"
  body="$(body_of <<<"$response")"

  if [[ "$status" != "201" ]]; then
    response="$(request_json POST "/api/v1/auth/local/login" "" "$signup_body")"
    body="$(body_of <<<"$response")"
  fi

  require_success "$body" "auth $login_id"
  jq -c '.data' <<<"$body"
}

join_participant() {
  local token="$1"
  local meeting_id="$2"
  local response body

  response="$(request_json POST "/api/v1/meetings/$meeting_id/participants" "$token")"
  body="$(body_of <<<"$response")"
  require_success "$body" "join participant meetingId=$meeting_id"
}

tmp_fixture="$(mktemp)"
trap 'rm -f "$tmp_fixture"' EXIT

jq -n \
  --arg createdAt "$(date -Iseconds)" \
  --arg baseUrl "$BASE_URL" \
  --arg loginPrefix "$LOGIN_PREFIX" \
  --argjson teams "$TEAMS" \
  --argjson membersPerTeam "$MEMBERS_PER_TEAM" \
  '{
    createdAt: $createdAt,
    baseUrl: $baseUrl,
    loginPrefix: $loginPrefix,
    teamsRequested: $teams,
    membersPerTeam: $membersPerTeam,
    teams: [],
    connections: []
  }' > "$tmp_fixture"

for team_no in $(seq 1 "$TEAMS"); do
  team_code="$(printf '%03d' "$team_no")"
  leader_login_id="${LOGIN_PREFIX}-t${team_code}-u01"
  leader_auth="$(signup_or_login "$leader_login_id")"
  leader_user_id="$(jq -r '.userId' <<<"$leader_auth")"
  leader_token="$(jq -r '.accessToken' <<<"$leader_auth")"

  team_body="$(jq -n --arg name "LT$team_code" --arg displayName "u${team_code}01" \
    '{name: $name, displayName: $displayName}')"
  team_response="$(request_json POST "/api/v1/teams" "$leader_token" "$team_body")"
  team_response_body="$(body_of <<<"$team_response")"
  require_success "$team_response_body" "create team $team_code"

  team_id="$(jq -r '.data.teamId' <<<"$team_response_body")"
  invitation_code="$(jq -r '.data.invitationCode' <<<"$team_response_body")"

  meeting_body="$(jq -n --arg title "SSE$team_code" \
    '{title: $title, purpose: "issue238-sse-baseline", note: "fixture", targetDurationMinutes: 30}')"
  meeting_response="$(request_json POST "/api/v1/teams/$team_id/meetings" "$leader_token" "$meeting_body")"
  meeting_response_body="$(body_of <<<"$meeting_response")"
  require_success "$meeting_response_body" "create meeting teamId=$team_id"
  meeting_id="$(jq -r '.data.meetingId' <<<"$meeting_response_body")"

  join_participant "$leader_token" "$meeting_id"

  team_json="$(jq -n \
    --argjson teamIndex "$team_no" \
    --argjson teamId "$team_id" \
    --argjson meetingId "$meeting_id" \
    --arg invitationCode "$invitation_code" \
    '{teamIndex: $teamIndex, teamId: $teamId, meetingId: $meetingId, invitationCode: $invitationCode, participants: []}')"

  leader_connection="$(jq -n \
    --argjson teamIndex "$team_no" \
    --argjson memberIndex 1 \
    --argjson teamId "$team_id" \
    --argjson meetingId "$meeting_id" \
    --argjson userId "$leader_user_id" \
    --arg loginId "$leader_login_id" \
    --arg accessToken "$leader_token" \
    '{teamIndex: $teamIndex, memberIndex: $memberIndex, teamId: $teamId, meetingId: $meetingId, userId: $userId, loginId: $loginId, accessToken: $accessToken}')"

  team_json="$(jq -c --argjson participant "$leader_connection" '.participants += [$participant]' <<<"$team_json")"
  jq --argjson connection "$leader_connection" '.connections += [$connection]' "$tmp_fixture" > "$tmp_fixture.next"
  mv "$tmp_fixture.next" "$tmp_fixture"

  if (( MEMBERS_PER_TEAM > 1 )); then
    for member_no in $(seq 2 "$MEMBERS_PER_TEAM"); do
      member_code="$(printf '%02d' "$member_no")"
      login_id="${LOGIN_PREFIX}-t${team_code}-u${member_code}"
      auth="$(signup_or_login "$login_id")"
      user_id="$(jq -r '.userId' <<<"$auth")"
      token="$(jq -r '.accessToken' <<<"$auth")"

      join_body="$(jq -n --arg invitationCode "$invitation_code" --arg displayName "u${team_code}${member_code}" \
        '{invitationCode: $invitationCode, displayName: $displayName}')"
      join_response="$(request_json POST "/api/v1/team-memberships" "$token" "$join_body")"
      join_response_body="$(body_of <<<"$join_response")"
      require_success "$join_response_body" "join team teamId=$team_id loginId=$login_id"

      join_participant "$token" "$meeting_id"

      connection="$(jq -n \
        --argjson teamIndex "$team_no" \
        --argjson memberIndex "$member_no" \
        --argjson teamId "$team_id" \
        --argjson meetingId "$meeting_id" \
        --argjson userId "$user_id" \
        --arg loginId "$login_id" \
        --arg accessToken "$token" \
        '{teamIndex: $teamIndex, memberIndex: $memberIndex, teamId: $teamId, meetingId: $meetingId, userId: $userId, loginId: $loginId, accessToken: $accessToken}')"

      team_json="$(jq -c --argjson participant "$connection" '.participants += [$participant]' <<<"$team_json")"
      jq --argjson connection "$connection" '.connections += [$connection]' "$tmp_fixture" > "$tmp_fixture.next"
      mv "$tmp_fixture.next" "$tmp_fixture"
    done
  fi

  jq --argjson team "$team_json" '.teams += [$team]' "$tmp_fixture" > "$tmp_fixture.next"
  mv "$tmp_fixture.next" "$tmp_fixture"
  echo "prepared team=$team_no teamId=$team_id meetingId=$meeting_id participants=$MEMBERS_PER_TEAM"
done

jq . "$tmp_fixture" > "$FIXTURE_FILE"

echo "fixture written: $FIXTURE_FILE"
echo "connections: $(jq '.connections | length' "$FIXTURE_FILE")"

