#!/usr/bin/env bash
# End-to-end test on the local cluster (kind by default):
#   ephemeral namespace fq-e2e -> deploy (overlay e2e) -> corpus (profile M) -> pods Ready -> traffic
#   -> assertions on /fq/top, /fq/nplusone, /metrics and the fq: tag in pg_stat_statements.
# The namespace is ALWAYS deleted at the end, even on failure (trap), unless E2E_KEEP=1.
#
#   make e2e                      # full run (builds images)
#   E2E_PROFILE=S make e2e        # faster, smaller corpus
#   E2E_SKIP_BUILD=1 make e2e     # reuse the images already loaded in the cluster
set -euo pipefail
cd "$(dirname "$0")/.."

PROFILE="${E2E_PROFILE:-M}"
NS=fq-e2e
MAKE_ARGS=(OVERLAY=e2e NS=$NS)
KUBECTL="${KUBECTL:-$( [ -x .tools/bin/kubectl ] && echo .tools/bin/kubectl || echo kubectl)} --context ${KUBE_CONTEXT:-kind-flairquery}"
PF_PID=""
FAILURES=0
START=$(date +%s)

log() { printf '\n\033[1m[e2e %4ss] %s\033[0m\n' "$(( $(date +%s) - START ))" "$*"; }
pass() { printf '  \033[32mPASS\033[0m %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$*"; FAILURES=$((FAILURES + 1)); }
check() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then pass "$desc"; else fail "$desc"; fi; }

cleanup() {
  local code=$?
  [ -n "$PF_PID" ] && kill "$PF_PID" 2>/dev/null || true
  if [ $code -ne 0 ] || [ $FAILURES -ne 0 ]; then
    log "diagnostics"
    $KUBECTL -n $NS get pods -o wide 2>/dev/null || true
    $KUBECTL -n $NS logs deploy/demo-app -c demo-app --tail=40 2>/dev/null || true
    $KUBECTL -n $NS logs job/fq-seed --tail=20 2>/dev/null || true
  fi
  if [ "${E2E_KEEP:-}" = "1" ]; then
    log "E2E_KEEP=1: namespace $NS kept"
  else
    log "deleting namespace $NS"
    $KUBECTL delete namespace $NS --ignore-not-found --wait=true --timeout=5m >/dev/null 2>&1 || true
  fi
  exit $code
}
trap cleanup EXIT INT TERM

log "cluster"
make cluster
if [ -z "${E2E_SKIP_BUILD:-}" ]; then
  log "build images"
  make build
fi

log "fresh namespace $NS"
$KUBECTL delete namespace $NS --ignore-not-found --wait=true --timeout=5m
make deploy "${MAKE_ARGS[@]}"

log "corpus profile $PROFILE"
make seed "${MAKE_ARGS[@]}" PROFILE="$PROFILE"

log "pods Ready"
$KUBECTL -n $NS wait --for=condition=Ready pod -l 'app.kubernetes.io/part-of=flairquery,app.kubernetes.io/name in (postgres,demo-app)' --timeout=10m

log "reset pg_stat_statements, then traffic"
psql_q() { $KUBECTL -n $NS exec deploy/postgres -- psql -U flairquery -d flairquery -tAc "$1"; }
psql_q "select pg_stat_statements_reset()" >/dev/null
make load "${MAKE_ARGS[@]}" ROUNDS="${E2E_ROUNDS:-2}"

log "agent endpoints"
PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
$KUBECTL -n $NS port-forward svc/demo-app "$PORT:9464" >/dev/null 2>&1 &
PF_PID=$!
for _ in $(seq 1 50); do curl -sf -m 1 "localhost:$PORT/fq/health" >/dev/null && break; sleep 0.3; done
TOP=$(curl -sf "localhost:$PORT/fq/top?limit=100")
N1=$(curl -sf "localhost:$PORT/fq/nplusone")
EPS=$(curl -sf "localhost:$PORT/fq/endpoints")
METRICS=$(curl -sf "localhost:$PORT/metrics")
EXPECT=$(python3 corpus/generate.py --profile "$PROFILE" --expect)
DEMO=io.github.anthonydeseille.flairquery.demo

log "assertions"
# /fq/top
check "/fq/top lists SQL with endpoint and caller" \
  jq -e '.schemaVersion == 1 and (.top | length) > 0 and all(.top[]; .endpoint and .caller and .calls > 0)' <<<"$TOP"
check "/fq/top attributes the authors N+1 to AuthorController#toDto" \
  jq -e --arg c "$DEMO.web.AuthorController#toDto:" 'any(.top[]; .endpoint == "GET /authors" and (.caller | startswith($c)))' <<<"$TOP"
# /fq/nplusone: the trap is detected, the witness is not
check "/fq/nplusone detects GET /authors (20 queries per request)" \
  jq -e 'any(.nplusone[]; .endpoint == "GET /authors" and .maxQueriesPerRequest == 20)' <<<"$N1"
check "/fq/nplusone detects the EAGER publisher on GET /books" \
  jq -e 'any(.nplusone[]; .endpoint == "GET /books" and (.sql | contains("from publisher")))' <<<"$N1"
check "/fq/nplusone never flags the witness GET /publishers/{id}" \
  jq -e 'all(.nplusone[]; .endpoint != "GET /publishers/{id}")' <<<"$N1"
check "witness runs exactly 1 query per request" \
  jq -e 'any(.endpoints[]; .endpoint == "GET /publishers/{id}" and .queriesPerRequest == 1 and .nPlusOneRequests == 0)' <<<"$EPS"
# /metrics
check "/metrics exposes fq_queries_count for GET /authors" grep -q 'fq_queries_count{app="demo-app",endpoint="GET /authors"}' <<<"$METRICS"
check "/metrics: agent active, no internal error" bash -c 'grep -q "^fq_agent_active{app=\"demo-app\"} 1" <<<"$0" && grep -q "^fq_agent_errors{app=\"demo-app\"} 0" <<<"$0"' "$METRICS"
# PostgreSQL side
TAGGED=$(psql_q "select count(*) from pg_stat_statements where query like '/* fq:app=demo-app,endpoint=%'")
check "pg_stat_statements contains fq:-tagged statements ($TAGGED)" test "$TAGGED" -gt 0
check "pg_stat_statements has the witness tag with its caller" \
  test "$(psql_q "select count(*) from pg_stat_statements where query like '/* fq:app=demo-app,endpoint=GET /publishers/{id},caller=$DEMO.web.PublisherController#get:%'")" -gt 0
check "trap 7 spills to disk (temp_blks_written > 0)" \
  test "$(psql_q "select coalesce(sum(temp_blks_written),0) from pg_stat_statements where query like '%endpoint=GET /reviews/ranking%'")" -gt 0
# deterministic corpus (generated part only, never the Open Library extract)
AUTHORS=$(psql_q "select count(*) from author where id < 50000000")
check "generated corpus is complete ($AUTHORS authors)" test "$AUTHORS" = "$(jq -r .rows.author <<<"$EXPECT")"
PROLIFIC=$(jq -r '.prolificAuthorIds[0]' <<<"$EXPECT")
check "prolific author $PROLIFIC has $(jq -r .prolificBooks <<<"$EXPECT") books" \
  test "$(psql_q "select count(*) from book where author_id = $PROLIFIC")" = "$(jq -r .prolificBooks <<<"$EXPECT")"

log "result: $FAILURES failure(s), profile $PROFILE, $(( $(date +%s) - START )) s"
[ "$FAILURES" -eq 0 ]
