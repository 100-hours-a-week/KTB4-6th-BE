#!/usr/bin/env bash
set -euo pipefail

FIXTURE_FILE="${FIXTURE_FILE:?FIXTURE_FILE is required}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3307}"
DB_NAME="${DB_NAME:-meety}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-meety}"

ids_csv() {
  local path="$1"
  jq -r "$path" "$FIXTURE_FILE" | paste -sd, -
}

meeting_ids="$(ids_csv '.targets[].meetingId')"
team_ids="$(ids_csv '.targets[].teamId')"

if [[ -z "$meeting_ids" || -z "$team_ids" ]]; then
  echo "fixture has no targets: $FIXTURE_FILE"
  exit 1
fi

query="
select 'meetings' as section,
       count(*) as total,
       sum(status = 'IN_PROGRESS') as in_progress,
       sum(status = 'WAITING') as waiting,
       sum(status = 'COMPLETED') as completed
from meetings
where id in ($meeting_ids);

select 'recording_sessions' as section,
       count(*) as total,
       sum(status = 'RECORDING') as recording,
       sum(status = 'PAUSED') as paused,
       sum(status = 'COMPLETED') as completed
from recording_sessions
where deleted_at is null
  and meeting_id in ($meeting_ids);

select 'credit_ledgers' as section,
       count(*) as total,
       sum(source_type = 'RECORDING' and type = 'USE') as recording_use,
       coalesce(sum(amount), 0) as amount_sum
from credit_ledgers
where deleted_at is null
  and source_type = 'RECORDING'
  and type = 'USE'
  and source_id in (
      select id
      from recording_sessions
      where deleted_at is null
        and meeting_id in ($meeting_ids)
  );

select 'team_credits' as section,
       count(*) as total,
       min(balance) as min_balance,
       max(balance) as max_balance
from team_credits
where deleted_at is null
  and team_id in ($team_ids);
"

MYSQL_PWD="$DB_PASSWORD" mysql \
  -h "$DB_HOST" \
  -P "$DB_PORT" \
  -u "$DB_USERNAME" \
  -D "$DB_NAME" \
  --batch \
  --raw \
  -e "$query"
