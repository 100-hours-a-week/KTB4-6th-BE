#!/bin/bash
# usage: ab_run.sh <label> <image> <pool> <team-offset>
LABEL=$1; IMAGE=$2; POOL=$3; OFF=$4
cd "$(dirname "$0")"
docker rm -f meety-be-ab >/dev/null 2>&1
docker run -d --name meety-be-ab --cpus=1 --memory=2g -p 8080:8080 -p 8081:8081 -e SPRING_PROFILES_ACTIVE=local \
  -e DB_HOST=host.docker.internal -e REDIS_HOST=host.docker.internal \
  -e AI_HTTP_URL=http://host.docker.internal:8001 -e AI_WEBSOCKET_URL=ws://host.docker.internal:8000/v1/live-meeting \
  -e SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=$POOL $IMAGE >/dev/null
until [ "$(curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/v1/teams/1/meetings/in-progress)" = 401 ]; do sleep 2; done
# 예열: 첫 요청 JIT 비용 제거
curl -s -o /dev/null localhost:8080/api/v1/teams/1/meetings/in-progress
IDX=$OFF
for C in 1 10 30 50; do
  sed -n "$((IDX+1)),$((IDX+C))p" free_teams.txt > batch.txt; IDX=$((IDX+C))
  echo -n "[" > batch.json; first=1
  while read team user; do
    T=$(python3 make_token.py $user); H=(-s -b "accessToken=$T" -H "Content-Type: application/json")
    M=$(curl "${H[@]}" -X POST localhost:8080/api/v1/teams/$team/meetings -d '{"title":"AB","purpose":"pool","targetDurationMinutes":10}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["meetingId"])')
    curl "${H[@]}" -o /dev/null -X POST localhost:8080/api/v1/meetings/$M/participants
    [ $first = 1 ] || echo -n "," >> batch.json; first=0
    echo -n "{\"team\":$team,\"meeting\":$M,\"token\":\"$T\"}" >> batch.json
  done < batch.txt; echo "]" >> batch.json
  BEFORE=$(docker logs meety-be-ab 2>&1 | grep -c "Connection is not available")
  OUT=$(TARGETS=./batch.json k6 run -q --summary-trend-stats "avg,p(95),max" start_n.js 2>&1)
  AFTER=$(docker logs meety-be-ab 2>&1 | grep -c "Connection is not available")
  OKN=$(echo "$OUT" | grep -oE "start_ok[^:]*: *[0-9]+" | grep -oE "[0-9]+$"); OKN=${OKN:-0}
  LAT=$(echo "$OUT" | grep -E "^ *start_ms" | sed -E 's/.*start_ms\.*: *//')
  echo "$LABEL pool=$POOL c=$C ok=$OKN/$C poolTimeouts=$((AFTER-BEFORE)) $LAT"
  sleep 5
done
if [ "$END" = 1 ]; then
  python3 - <<'PY'
import json, subprocess
b = json.load(open("batch.json"))
ids = ",".join(str(t["meeting"]) for t in b)
out = subprocess.run(["docker","exec","meety-mysql","mysql","-uroot","-pmeety","meety","-N","-e",
  f"select meeting_id, id from recording_sessions where meeting_id in ({ids}) and status='RECORDING'"],capture_output=True,text=True).stdout
rec = dict(l.split("\t") for l in out.strip().split("\n") if l)
json.dump([{**t, "rec": int(rec[str(t["meeting"])])} for t in b if str(t["meeting"]) in rec], open("end_batch.json","w"))
PY
  N=$(python3 -c "import json;print(len(json.load(open('end_batch.json'))))")
  BEFORE=$(docker logs meety-be-ab 2>&1 | grep -c "Connection is not available")
  OUT=$(TARGETS=./end_batch.json k6 run -q --summary-trend-stats "avg,p(95),max" end_n.js 2>&1)
  AFTER=$(docker logs meety-be-ab 2>&1 | grep -c "Connection is not available")
  OKN=$(echo "$OUT" | grep -oE "end_ok[^:]*: *[0-9]+" | grep -oE "[0-9]+$"); OKN=${OKN:-0}
  LAT=$(echo "$OUT" | grep -E "^ *end_ms" | sed -E 's/.*end_ms\.*: *//')
  echo "$LABEL END c=$N ok=$OKN/$N poolTimeouts=$((AFTER-BEFORE)) $LAT"
  sleep 8
  docker logs meety-be-ab 2>&1 | grep -E "ERROR" | sed -E 's/^.*ERROR 1 --- \[meety\] \[[^]]*\] //' | cut -c1-120 | sort | uniq -c | sort -rn | head -5
fi
docker rm -f meety-be-ab >/dev/null
