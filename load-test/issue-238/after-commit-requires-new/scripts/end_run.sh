#!/bin/bash
# usage: end_run.sh <label> <image> <pool> <team-offset>
LABEL=$1; IMAGE=$2; POOL=$3; OFF=$4
cd "$(dirname "$0")"
docker rm -f meety-be-ab >/dev/null 2>&1
docker run -d --name meety-be-ab --cpus=1 --memory=2g -p 8080:8080 -p 8081:8081 -e SPRING_PROFILES_ACTIVE=local \
  -e DB_HOST=host.docker.internal -e REDIS_HOST=host.docker.internal \
  -e AI_HTTP_URL=http://host.docker.internal:8001 -e AI_WEBSOCKET_URL=ws://host.docker.internal:8000/v1/live-meeting \
  -e SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=$POOL $IMAGE >/dev/null
until [ "$(curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/v1/teams/1/meetings/in-progress)" = 401 ]; do sleep 2; done
IDX=$OFF
for C in 1 10 30 50; do
  sed -n "$((IDX+1)),$((IDX+C))p" free_teams.txt > batch.txt; IDX=$((IDX+C))
  echo -n "[" > end_batch.json; first=1; MIDS=""
  while read team user; do
    T=$(python3 make_token.py $user); H=(-s -b "accessToken=$T" -H "Content-Type: application/json")
    M=$(curl "${H[@]}" -X POST localhost:8080/api/v1/teams/$team/meetings -d '{"title":"END","purpose":"pool","targetDurationMinutes":10}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["meetingId"])')
    curl "${H[@]}" -o /dev/null -X POST localhost:8080/api/v1/meetings/$M/participants
    R=$(curl "${H[@]}" -X POST localhost:8080/api/v1/meetings/$M/recordings | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["recordingSessionId"])')
    [ $first = 1 ] || echo -n "," >> end_batch.json; first=0; MIDS="$MIDS${MIDS:+|}$M"
    echo -n "{\"team\":$team,\"meeting\":$M,\"rec\":$R,\"token\":\"$T\"}" >> end_batch.json
  done < batch.txt; echo "]" >> end_batch.json
  sleep 3
  BEFORE=$(docker logs meety-be-ab 2>&1 | grep -c "Connection is not available")
  OUT=$(TARGETS=./end_batch.json k6 run -q --summary-trend-stats "avg,p(95),max" end_n.js 2>&1)
  AFTER=$(docker logs meety-be-ab 2>&1 | grep -c "Connection is not available")
  OKN=$(echo "$OUT" | grep -oE "end_ok[^:]*: *[0-9]+" | grep -oE "[0-9]+$"); OKN=${OKN:-0}
  LAT=$(echo "$OUT" | grep -E "^ *end_ms" | sed -E 's/.*end_ms\.*: *//')
  sleep 8
  REG=$(docker exec meety-mysql mysql -uroot -pmeety meety -N -e "select concat(sum(request_type='SUMMARY'),'/',sum(request_type='DIARIZATION')) from ai_requests where idempotency_key regexp 'MEETING:($MIDS)\$'" 2>/dev/null)
  echo "$LABEL END c=$C ok=$OKN/$C poolTimeouts=$((AFTER-BEFORE)) summary/diarization=${REG:-0/0} $LAT"
done
docker rm -f meety-be-ab >/dev/null
