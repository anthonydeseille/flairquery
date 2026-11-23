#!/usr/bin/env bash
# Shows the FlairQuery view of a pod, readable in a terminal (used by the K9s plugin).
#   k9s/fq-view.sh top|nplusone|endpoints <pod> <namespace> [context]
# Application pod (agent, port 9464): top SQL / N+1 / endpoints of that pod.
# Collector pod (fq-collector-*, port 9465): top statements with their exact per-endpoint split / last
# before-after comparison between marks / endpoints, over FQ_WINDOW (default 1h).
# Opens a temporary `kubectl port-forward` - no curl needed in the image - renders the JSON with jq and
# pages it with less. The port-forward is always closed on exit.
set -euo pipefail

VIEW="${1:?view: top|nplusone|endpoints}"
POD="${2:?pod name}"
NS="${3:?namespace}"
CTX="${4:-}"
case "$POD" in
  fq-collector-*) MODE=collector; AGENT_PORT="${FQ_COLLECTOR_HTTP_PORT:-9465}"; HEALTH=/fq/collector/health ;;
  *) MODE=agent; AGENT_PORT="${FQ_HTTP_PORT:-9464}"; HEALTH=/fq/health ;;
esac
WINDOW="${FQ_WINDOW:-1h}"
HERE="$(cd "$(dirname "$0")" && pwd)"
KUBECTL="${FQ_KUBECTL:-$(command -v kubectl || echo "$HERE/../.tools/bin/kubectl")}"
[ -x "$KUBECTL" ] || { echo "kubectl not found (set FQ_KUBECTL)"; read -r -n1 -p "press a key"; exit 1; }
command -v jq >/dev/null || { echo "jq is required"; read -r -n1 -p "press a key"; exit 1; }

LOCAL_PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')"
"$KUBECTL" ${CTX:+--context "$CTX"} -n "$NS" port-forward "pod/$POD" "$LOCAL_PORT:$AGENT_PORT" >/dev/null 2>&1 &
PF=$!
trap 'kill $PF 2>/dev/null || true' EXIT

for _ in $(seq 1 30); do
  curl -sf -m 1 "http://127.0.0.1:$LOCAL_PORT$HEALTH" >/dev/null 2>&1 && break
  sleep 0.3
done

fetch() {
  curl -sf -m 10 "http://127.0.0.1:$LOCAL_PORT$1" || {
    echo "No FlairQuery $MODE answering on $POD:$AGENT_PORT (agent disabled, not injected, or wrong pod)."
    return 1
  }
}

render_collector() {
  case "$VIEW" in
    top)
      fetch "/fq/collector/queries?window=$WINDOW&order=time&limit=${FQ_TOP_LIMIT:-25}" | jq -r '
        "FlairQuery collector - top statements by total time    target=\(.target)    window=\(.window)\n",
        (.queries | to_entries[] | .value as $q |
          "\(.key + 1 | tostring | if length < 2 then " " + . else . end). \($q.totalTimeMs | floor) ms total   \($q.calls) calls   mean \($q.meanTimeMs) ms   read \($q.sharedBlksRead) blk   temp \($q.tempBlksWritten) blk",
          (if ($q.attribution | length) > 0 then
             ($q.attribution[] | "    \(.executions | tostring | . + "        " | .[0:8]) \(.endpoint)  <-  \(.caller | split(".") | last)")
           else "    (first tag only) \($q.endpoint // "untagged")  <-  \(($q.caller // "?") | split(".") | last)" end),
          "    sql: \($q.sql | if length > 180 then .[0:180] + "..." else . end)",
          "")'
      ;;
    nplusone)
      local marks before after
      marks=$(fetch "/fq/collector/marks") || return 1
      before=$(jq -r '.marks | if length >= 2 then .[-2].name else empty end' <<<"$marks")
      after=$(jq -r '.marks | if length >= 2 then .[-1].name else empty end' <<<"$marks")
      if [ -z "$before" ]; then
        echo "FlairQuery collector - before/after comparison"
        echo
        echo "  Needs two marks (POST /fq/collector/marks, or make mark NAME=... through a port-forward)."
        jq -r '.marks[] | "  mark \(.name)  \(.takenAt)  \(.note // "")"' <<<"$marks"
        return 0
      fi
      fetch "/fq/collector/compare?before=$before&after=$after&limit=${FQ_TOP_LIMIT:-15}" | jq -r '
        def pct: if . == null then "  n/a" else (if . > 0 then "+" else "" end) + (. | tostring) + "%" end;
        "FlairQuery collector - before \(.before.label) (\(.before.from[0:19]) .. \(.before.to[0:19])) vs after \(.after.label) (.. \(.after.to[0:19]))\n",
        "TOTAL  time \(.totals.before.totalTimeMs | floor) -> \(.totals.after.totalTimeMs | floor) ms (\(.totals.changePct.totalTimeMs | pct))   calls \(.totals.before.calls) -> \(.totals.after.calls)   read \(.totals.before.sharedBlksRead) -> \(.totals.after.sharedBlksRead) blk\n",
        (.notes[] | "note: \(.)"), "",
        (.statements[] |
          "\(.status | . + "     " | .[0:5]) \((.before.totalTimeMs // 0) | floor) -> \((.after.totalTimeMs // 0) | floor) ms (\(.changePct.totalTimeMs | pct))\(if .plans.changed then "   PLAN CHANGED" else "" end)   \(.endpoints | join(", "))",
          "      \(.sql | if length > 160 then .[0:160] + "..." else . end)")'
      ;;
    endpoints)
      fetch "/fq/collector/endpoints?window=$WINDOW&statements=3" | jq -r '
        "FlairQuery collector - endpoints (exact, from auto_explain)    target=\(.target)    window=\(.window)\n",
        "EXECUTIONS  SQL ms      STMTS  ENDPOINT",
        (.endpoints[] |
          "\(.executions | tostring | . + "          " | .[0:10])  \(.loggedTimeMs | floor | tostring | . + "          " | .[0:10])  \(.distinctStatements | tostring | . + "     " | .[0:5])  \(.endpoint // "untagged")",
          (.statements[] | "              \(.executions | tostring | . + "        " | .[0:8]) \(.caller // "?" | split(".") | last)  \(.sql // "" | .[0:90])"))'
      ;;
    *) echo "unknown view $VIEW"; return 1 ;;
  esac
}

render() {
  [ "$MODE" = collector ] && { render_collector; return; }
  case "$VIEW" in
    top)
      fetch "/fq/top?limit=${FQ_TOP_LIMIT:-25}" | jq -r '
        "FlairQuery - top SQL by total time    app=\(.app)    \(.generatedAt)\n",
        (.top | to_entries[] | .value as $t |
          "\(.key + 1 | tostring | if length < 2 then " " + . else . end). \($t.totalTimeMs | floor) ms total   \($t.calls) calls   avg \($t.avgTimeMs) ms   max \($t.maxTimeMs) ms",
          "    endpoint: \($t.endpoint)",
          "    caller:   \($t.caller)",
          "    sql:      \($t.sql | if length > 200 then .[0:200] + "..." else . end)",
          "")'
      ;;
    nplusone)
      fetch "/fq/nplusone" | jq -r '
        "FlairQuery - N+1 detected (threshold: more than \(.threshold) identical statements per request)    app=\(.app)\n",
        (if (.nplusone | length) == 0 then "  none detected" else
          (.nplusone[] |
            "* \(.endpoint)   up to \(.maxQueriesPerRequest) queries/request   seen in \(.occurrences) requests   \(.totalTimeMs | floor) ms total",
            "    caller: \(.caller)",
            "    sql:    \(.sql | if length > 200 then .[0:200] + "..." else . end)",
            "") end)'
      ;;
    endpoints)
      fetch "/fq/endpoints" | jq -r '
        "FlairQuery - endpoints    app=\(.app)\n",
        "REQUESTS  SQL/REQ   MAX  SQL ms     N+1  ENDPOINT",
        (.endpoints[] | "\(.requests | tostring | .[0:8] | . + "        " | .[0:8])  \((.queriesPerRequest // 0) * 10 | floor / 10 | tostring | . + "        " | .[0:8])  \(.maxQueriesPerRequest | tostring | . + "     " | .[0:4]) \(.sqlTimeMs | floor | tostring | . + "          " | .[0:10]) \(.nPlusOneRequests | tostring | . + "    " | .[0:4]) \(.endpoint)")'
      ;;
    *) echo "unknown view $VIEW"; return 1 ;;
  esac
}

if [ -t 1 ] && [ -z "${FQ_NO_PAGER:-}" ]; then
  render | less -R -S -X -K
else
  render
fi
