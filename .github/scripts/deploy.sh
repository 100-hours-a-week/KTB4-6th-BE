#!/usr/bin/env bash
# 두 서비스는 독립적으로 실행하며 결과 파일도 서비스별로 분리한다.
set -euo pipefail
export AWS_PAGER=""
SERVICE=$1 TASK_DEFINITION=$2 DONE_STAGES=${3:-}
START_EPOCH=$(date +%s)
STARTED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
STAGE_STARTED=$START_EPOCH
LAST="" DEPLOYMENT="" RESULT=failed
RESULT_DIR="${RUNNER_TEMP}/be-deploy-results"
mkdir -p "$RESULT_DIR"
log() { printf '%s service=%s elapsed=%ss %s\n' "$(date -u +%FT%TZ)" "$SERVICE" "$(($(date +%s)-START_EPOCH))" "$*"; }

finish() {
  local code=$?
  trap - EXIT
  # 결과 요약 실패가 배포의 원래 종료 코드를 변경하지 않도록 한다.
  printf '| %s | %s | %ss | %s |\n' "$SERVICE" "$RESULT" "$(($(date +%s)-START_EPOCH))" "${TASK_DEFINITION##*/}" > "$RESULT_DIR/$SERVICE.md" || true
  log "result=$RESULT exit_code=$code final_stage_seconds=$(($(date +%s)-STAGE_STARTED))"
  exit "$code"
}
trap finish EXIT

log "event=start sha=$GITHUB_SHA image=$IMAGE run=$GITHUB_RUN_ID target=${TASK_DEFINITION##*/}"
bash .github/scripts/deploy-diagnostics.sh "$SERVICE" before || true
aws ecs update-service --cluster "$CLUSTER" --service "$SERVICE" --task-definition "$TASK_DEFINITION" > /dev/null
for i in $(seq 1 30); do
  DEPLOYMENT=$(aws ecs list-service-deployments --cluster "$CLUSTER" --service "$SERVICE" \
    --created-at "after=$STARTED" --query 'serviceDeployments[0].serviceDeploymentArn' --output text)
  [ "$DEPLOYMENT" != None ] && [ -n "$DEPLOYMENT" ] && break
  sleep 5
done
[ "$DEPLOYMENT" != None ] && [ -n "$DEPLOYMENT" ] || { log 'event=deployment_not_found'; exit 1; }
log "deployment=$DEPLOYMENT"
printf '%s\n' "$DEPLOYMENT" > "$RESULT_DIR/$SERVICE.deployment"
HEARTBEAT=0
while :; do
  read -r STATUS STAGE < <(aws ecs describe-service-deployments --service-deployment-arns "$DEPLOYMENT" \
    --query 'serviceDeployments[0].[status, lifecycleStage]' --output text)
  NOW=$(date +%s)
  if [ "$STATUS $STAGE" != "$LAST" ]; then
    log "status=$STATUS stage=$STAGE previous_stage_seconds=$((NOW-STAGE_STARTED))"
    LAST="$STATUS $STAGE" STAGE_STARTED=$NOW
  fi
  if (( NOW-HEARTBEAT >= 60 )); then
    # 진행 조회는 진단용이므로 실패해도 배포 대기를 계속한다.
    COUNTS=$(aws ecs describe-services --cluster "$CLUSTER" --services "$SERVICE" \
      --query 'services[0].[desiredCount,runningCount,pendingCount]' --output text) || COUNTS=unavailable
    log "heartbeat desired_running_pending=$COUNTS"
    HEARTBEAT=$NOW
  fi
  case "$STATUS" in
    SUCCESSFUL) RESULT=successful; exit 0 ;;
    FAILED|STOPPED|STOP_REQUESTED|ROLLBACK_*)
      # 기존 실패 판정 시점을 유지한다. 복구 완료는 확인된 상태에서만 표시한다.
      if [ "$STATUS" = ROLLBACK_SUCCESSFUL ]; then
        log 'rollback=completed'
      else
        log "rollback_completion=unconfirmed status=$STATUS"
      fi
      echo "::error::${SERVICE} 배포 실패 ($STATUS)"
      exit 1 ;;
  esac
  for done_stage in $DONE_STAGES; do
    if [ "$STAGE" = "$done_stage" ]; then RESULT=traffic_switched; exit 0; fi
  done
  sleep 10
done
