#!/usr/bin/env bash
# 환경변수와 태스크 정의 전체를 덤프하지 않고 진단 필드만 선택한다.
set -uo pipefail
export AWS_PAGER=""
SERVICE=$1 PHASE=$2
query() {
  local output
  if output=$("$@" 2>&1); then
    printf '%s service=%s phase=%s %s\n' "$(date -u +%FT%TZ)" "$SERVICE" "$PHASE" "$output"
  else
    printf '::warning::%s %s 진단 조회 실패: %s\n' "$SERVICE" "$PHASE" "$output"
  fi
}
query aws ecs describe-services --cluster "$CLUSTER" --services "$SERVICE" \
  --query 'services[].{service:serviceName,taskDefinition:taskDefinition,desired:desiredCount,running:runningCount,pending:pendingCount,deployments:deployments[].{status:status,taskDefinition:taskDefinition,rolloutState:rolloutState}}' --output json
[ "$PHASE" != before ] || exit 0
# 후속 진단 시점에 확인된 롤백 상태와 서비스의 현재 태스크 정의를 함께 남긴다.
if [ -f "$RUNNER_TEMP/be-deploy-results/$SERVICE.deployment" ]; then
  DEPLOYMENT=$(cat "$RUNNER_TEMP/be-deploy-results/$SERVICE.deployment")
  query aws ecs describe-service-deployments --service-deployment-arns "$DEPLOYMENT" \
    --query 'serviceDeployments[].{status:status,stage:lifecycleStage,reason:statusReason,rollback:rollback}' --output json
fi
if [ "$PHASE" = failure ]; then
  query aws ecs describe-services --cluster "$CLUSTER" --services "$SERVICE" --query 'services[].events[:10]' --output json
fi
# 최대 100개만 조회한다. 과거 중지 태스크는 현재 실패 원인이라고 단정하지 않는다.
STATES=(RUNNING)
[ "$PHASE" != failure ] || STATES+=(STOPPED)
for state in "${STATES[@]}"; do
  if TASKS=$(aws ecs list-tasks --cluster "$CLUSTER" --service-name "$SERVICE" --desired-status "$state" --no-paginate --max-results 100 --query taskArns --output text); then
    if [ -n "$TASKS" ] && [ "$TASKS" != None ]; then
      read -r -a TASK_IDS <<< "$TASKS"
      query aws ecs describe-tasks --cluster "$CLUSTER" --tasks "${TASK_IDS[@]}" \
        --query '{failures:failures,tasks:tasks[].{task:taskArn,definition:taskDefinitionArn,status:lastStatus,startedAt:startedAt,stoppedAt:stoppedAt,stopCode:stopCode,reason:stoppedReason,containers:containers[].{name:name,imageDigest:imageDigest,exitCode:exitCode,reason:reason}}}' --output json
    fi
  else
    echo "::warning::${SERVICE} ${state} 태스크 목록 조회 실패 (ecs:ListTasks 권한 확인 필요)"
  fi
done
# GROUPS는 bash 예약 변수(현재 사용자의 그룹 ID)라 대입이 무시되므로 다른 이름을 쓴다.
if TARGET_GROUPS=$(aws ecs describe-services --cluster "$CLUSTER" --services "$SERVICE" --query 'services[0].loadBalancers[].targetGroupArn' --output text); then
  for group in $TARGET_GROUPS; do
    [ "$group" != None ] || continue
    query aws elbv2 describe-target-health --target-group-arn "$group" \
      --query 'TargetHealthDescriptions[].{target:Target,health:TargetHealth}' --output json
  done
fi
exit 0
