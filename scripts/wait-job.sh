#!/usr/bin/env bash
# Waits for a Kubernetes Job to succeed, and fails as soon as it fails (`kubectl wait --for=condition=complete`
# alone keeps waiting until its timeout when the Job fails). Prints the Job logs and recent events on failure.
#   scripts/wait-job.sh <namespace> <job> <timeout-seconds>      (KUBECTL: kubectl command with its context)
set -uo pipefail
NS="$1"; JOB="$2"; TIMEOUT="$3"
KUBECTL="${KUBECTL:-kubectl}"
deadline=$(( $(date +%s) + TIMEOUT ))
while :; do
  status=$($KUBECTL -n "$NS" get job "$JOB" -o jsonpath='{.status.succeeded}/{.status.failed}' 2>/dev/null || echo "/")
  case "$status" in
    1/*) exit 0 ;;
    */[1-9]*) reason="failed" ;;
    *) if [ "$(date +%s)" -ge "$deadline" ]; then reason="timed out after ${TIMEOUT}s"; else sleep 5; continue; fi ;;
  esac
  echo "job $JOB $reason" >&2
  $KUBECTL -n "$NS" logs "job/$JOB" --tail=40 >&2 2>&1 || true
  $KUBECTL -n "$NS" get events --sort-by=.lastTimestamp 2>/dev/null | tail -15 >&2 || true
  exit 1
done
