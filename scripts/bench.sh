#!/usr/bin/env bash
# Agent overhead: same demo app, same database, without then with -javaagent.
# Sequential single-client latency (curl time_total), after a warm-up. Prints a markdown table.
#   scripts/bench.sh            (needs the local Postgres of `make demo-local`, seeded)
set -euo pipefail
cd "$(dirname "$0")/.."
PORT="${BENCH_PORT:-18080}"
AGENT_PORT="${BENCH_AGENT_PORT:-19464}"
WARMUP="${BENCH_WARMUP:-200}"
OUT="$(mktemp -d)"
# endpoint|iterations
CASES=("/publishers/1|2000" "/books?limit=50|500" "/authors?limit=20|200")

run_app() { # $1 = label, $2 = java agent option or empty
  local log="$OUT/$1.log"
  FQ_APP_NAME=bench FQ_BASE_PACKAGES=io.github.anthonydeseille.flairquery.demo FQ_HTTP_PORT=$AGENT_PORT \
    SERVER_PORT=$PORT java -Xmx1g ${2:+"$2"} -jar demo-app/target/flairquery-demo-app.jar >"$log" 2>&1 &
  local pid=$!
  for _ in $(seq 1 120); do curl -sf -m 2 "localhost:$PORT/actuator/health/readiness" >/dev/null && break; sleep 1; done
  for c in "${CASES[@]}"; do
    local path="${c%%|*}" n="${c##*|}"
    for _ in $(seq 1 "$WARMUP"); do curl -s -o /dev/null "localhost:$PORT$path"; done
    for _ in $(seq 1 "$n"); do curl -s -o /dev/null -w '%{time_total}\n' "localhost:$PORT$path"; done >"$OUT/$1.$(echo "$path" | tr -c 'a-z0-9' '_')"
  done
  kill "$pid"; wait "$pid" 2>/dev/null || true
}

run_app without ""
run_app with "-javaagent:agent/target/flairquery-agent.jar"

python3 - "$OUT" "${CASES[@]}" <<'PY'
import sys, statistics, re
out, cases = sys.argv[1], sys.argv[2:]
def load(label, path):
    f = f"{out}/{label}." + re.sub(r"[^a-z0-9]", "_", path + "\n")
    return sorted(float(x) * 1000 for x in open(f))
def pct(v, p): return v[min(len(v) - 1, int(round(p / 100 * (len(v) - 1))))]
print("| Endpoint | Requests | p50 without | p50 with | Δ p50 | p99 without | p99 with | Δ p99 |")
print("|---|---|---|---|---|---|---|---|")
for c in cases:
    path, n = c.split("|")
    a, b = load("without", path), load("with", path)
    print(f"| `{path}` | {n} | {pct(a,50):.2f} ms | {pct(b,50):.2f} ms | {pct(b,50)-pct(a,50):+.2f} ms "
          f"| {pct(a,99):.2f} ms | {pct(b,99):.2f} ms | {pct(b,99)-pct(a,99):+.2f} ms |")
PY
rm -rf "$OUT"
