#!/usr/bin/env bash
# Generates traffic on every demo endpoint (traps + witness).
#   scripts/traffic.sh [BASE_URL] [ROUNDS]
# FQ_TRAFFIC_TIMINGS=1 also prints one line per request: timing<TAB>round<TAB>path<TAB>status<TAB>seconds
# (read by `prove-infra`, phase 6).
set -euo pipefail
BASE="${1:-http://localhost:8080}"
ROUNDS="${2:-5}"
PATHS=(
  "/authors?limit=20"
  "/books?limit=50"
  "/books/titles?authorId=7"
  "/reviews/search?reviewer=Alice%20Martin%2000000"
  "/authors/with-books?prefix=Al&size=5"
  "/reviews/ranking?page=400&size=50"
  "/publishers/1"
)
fail=0
for ((r = 1; r <= ROUNDS; r++)); do
  for p in "${PATHS[@]}"; do
    out="$(curl -s -m 60 -o /dev/null -w '%{http_code} %{time_total}' "$BASE$p" || true)"
    code="${out%% *}"
    [ -n "$code" ] || code=000
    if [ "${FQ_TRAFFIC_TIMINGS:-}" = "1" ]; then printf 'timing\t%s\t%s\t%s\t%s\n' "$r" "$p" "$code" "${out#* }"; fi
    [ "$code" = "200" ] || { echo "round $r: $p -> HTTP $code" >&2; fail=1; }
  done
done
echo "traffic: $ROUNDS rounds x ${#PATHS[@]} endpoints on $BASE (errors: $fail)"
exit "$fail"
