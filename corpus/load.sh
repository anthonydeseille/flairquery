#!/usr/bin/env bash
# Loads a corpus profile into PostgreSQL with COPY (never Liquibase). Streams generate.py -> psql:
# no CSV is ever written to disk.
#
#   PGHOST=localhost PGPORT=5432 PGUSER=flairquery PGPASSWORD=flairquery PGDATABASE=flairquery \
#     corpus/load.sh S [--force]
#
# Requirements: python3, psql. The schema must already exist (the demo app applies it with Liquibase
# at startup); this script waits for it. --force truncates the tables first.
#
# Profile M also loads the Open Library subset when it is cached (corpus/openlibrary/fetch.py):
#   FQ_OPENLIBRARY=auto (default: load if cached) | off | required
#   FQ_OL_CACHE=<dir containing subset.jsonl.gz>    (default ~/.cache/flairquery/openlibrary)
set -euo pipefail

PROFILE="${1:-S}"
FORCE="${2:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PY="${PYTHON:-python3}"
TABLES=(publisher author book review)
WAIT_SECONDS="${FQ_SEED_WAIT_SECONDS:-300}"
OL_MODE="${FQ_OPENLIBRARY:-auto}"
OL_SUBSET="${FQ_OL_CACHE:-$HOME/.cache/flairquery/openlibrary}/subset.jsonl.gz"

log() { printf '{"ts":"%s","level":"INFO","logger":"flairquery-corpus","msg":"%s"%s}\n' "$(date -u +%FT%TZ)" "$1" "${2:-}"; }
psqlq() { psql -v ON_ERROR_STOP=1 -X -q -t -A "$@"; }

"$PY" "$HERE/generate.py" --profile "$PROFILE" --expect >/dev/null   # validates the profile early

log "waiting for schema" ",\"profile\":\"$PROFILE\""
for ((i = 0; i < WAIT_SECONDS; i += 2)); do
  if [ "$(psqlq -c "select count(*) from information_schema.tables where table_name in ('publisher','author','book','review')" 2>/dev/null || echo 0)" = "4" ]; then
    break
  fi
  sleep 2
done
[ "$(psqlq -c "select count(*) from information_schema.tables where table_name in ('publisher','author','book','review')")" = "4" ] \
  || { echo "schema not found after ${WAIT_SECONDS}s (start the demo app first: Liquibase creates it)" >&2; exit 1; }

# auto_explain (log_min_duration=0 in dev) would log the plan of every FK check fired by COPY:
# millions of plans (a profile M load once produced a 14 GB server log). Disable it for this session
# when allowed (superuser-only parameter); otherwise warn.
if [ "$(psqlq -c 'select rolsuper from pg_roles where rolname = current_user')" = "t" ]; then
  export PGOPTIONS="${PGOPTIONS:-} -c auto_explain.log_min_duration=-1"
else
  log "not superuser: auto_explain stays active during the load (large server logs expected)"
fi

existing="$(psqlq -c 'select count(*) from author')"
if [ "$existing" != "0" ]; then
  if [ "$FORCE" = "--force" ]; then
    log "truncating existing data"
    psqlq -c 'TRUNCATE review, book, author, publisher RESTART IDENTITY'
  else
    log "data already present, nothing to do (use --force to reload)" ",\"authors\":$existing"
    exit 0
  fi
fi

start_all=$(date +%s)
for t in "${TABLES[@]}"; do
  start=$(date +%s)
  cols="$("$PY" "$HERE/generate.py" --profile "$PROFILE" --columns "$t")"
  "$PY" "$HERE/generate.py" --profile "$PROFILE" --table "$t" \
    | psqlq -c "\\copy $t ($cols) FROM STDIN WITH (FORMAT csv)"
  rows="$(psqlq -c "select count(*) from $t")"
  log "table loaded" ",\"table\":\"$t\",\"rows\":$rows,\"seconds\":$(( $(date +%s) - start ))"
done

# Open Library subset (profile M only; ids >= 50,000,000, never used by test assertions)
if [ "$PROFILE" = "M" ] && [ "$OL_MODE" != "off" ]; then
  if [ -f "$OL_SUBSET" ]; then
    for t in "${TABLES[@]}"; do
      start=$(date +%s)
      cols="$("$PY" "$HERE/generate.py" --profile "$PROFILE" --columns "$t")"
      "$PY" -I "$HERE/openlibrary/to_csv.py" --subset "$OL_SUBSET" --table "$t" \
        | psqlq -c "\\copy $t ($cols) FROM STDIN WITH (FORMAT csv)"
      log "open library rows loaded" ",\"table\":\"$t\",\"rows\":$(psqlq -c "select count(*) from $t where id >= 50000000"),\"seconds\":$(( $(date +%s) - start ))"
    done
  elif [ "$OL_MODE" = "required" ]; then
    echo "Open Library subset not found at $OL_SUBSET (run corpus/openlibrary/fetch.py)" >&2
    exit 1
  else
    log "no Open Library subset cached, generated data only" ",\"expected\":\"$OL_SUBSET\""
  fi
fi

# identity columns: continue after the loaded ids; then fresh planner statistics
for t in "${TABLES[@]}"; do
  psqlq -c "select setval(pg_get_serial_sequence('$t','id'), greatest((select max(id) from $t), 1))" >/dev/null
done
psqlq -c 'ANALYZE publisher, author, book, review'

total="$(psqlq -c "select pg_size_pretty(sum(pg_total_relation_size(c))) from unnest(array['publisher','author','book','review']::regclass[]) c")"
log "corpus loaded" ",\"profile\":\"$PROFILE\",\"seconds\":$(( $(date +%s) - start_all )),\"diskSize\":\"$total\""
psqlq -F ' | ' -c "select relname, n_live_tup, pg_size_pretty(pg_total_relation_size(relid)) from pg_stat_user_tables where relname in ('publisher','author','book','review') order by relname" \
  | sed 's/^/  /'
