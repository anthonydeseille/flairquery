#!/usr/bin/env bash
# Shows the FlairQuery agent view of a pod, readable in a terminal (used by the K9s plugin).
#   k9s/fq-view.sh top|nplusone|endpoints <pod> <namespace> [context]
# Opens a temporary `kubectl port-forward` to the agent port (9464) - no curl needed in the image -
# renders the JSON with jq and pages it with less. The port-forward is always closed on exit.
set -euo pipefail

VIEW="${1:?view: top|nplusone|endpoints}"
POD="${2:?pod name}"
NS="${3:?namespace}"
CTX="${4:-}"
AGENT_PORT="${FQ_HTTP_PORT:-9464}"
HERE="$(cd "$(dirname "$0")" && pwd)"
KUBECTL="${FQ_KUBECTL:-$(command -v kubectl || echo "$HERE/../.tools/bin/kubectl")}"
[ -x "$KUBECTL" ] || { echo "kubectl not found (set FQ_KUBECTL)"; read -r -n1 -p "press a key"; exit 1; }
command -v jq >/dev/null || { echo "jq is required"; read -r -n1 -p "press a key"; exit 1; }

LOCAL_PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')"
"$KUBECTL" ${CTX:+--context "$CTX"} -n "$NS" port-forward "pod/$POD" "$LOCAL_PORT:$AGENT_PORT" >/dev/null 2>&1 &
PF=$!
trap 'kill $PF 2>/dev/null || true' EXIT

for _ in $(seq 1 30); do
  curl -sf -m 1 "http://127.0.0.1:$LOCAL_PORT/fq/health" >/dev/null 2>&1 && break
  sleep 0.3
done

fetch() {
  curl -sf -m 10 "http://127.0.0.1:$LOCAL_PORT$1" || {
    echo "No FlairQuery agent answering on $POD:$AGENT_PORT (agent disabled, not injected, or wrong pod)."
    return 1
  }
}

render() {
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
