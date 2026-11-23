# FlairQuery

**SQL and endpoint analysis for Java / Spring / JPA applications, on Kubernetes.**

> **"Agent" means a Java agent here, not an AI agent.** The FlairQuery agent is a jar loaded with
> `-javaagent:` that instruments bytecode (ByteBuddy), like OpenTelemetry or JaCoCo agents. Its behaviour is
> fully deterministic, and **no LLM is involved in phase 1**. An LLM only appears in phase 4, in a separate
> component (the analyzer), and every proposal it makes must be proven by measurements.

The agent instruments the application **without any code change** and tags every SQL statement with the
HTTP endpoint and the calling line of code:

```sql
/* fq:app=demo-app,endpoint=GET /authors,caller=io.github.anthonydeseille.flairquery.demo.web.AuthorController#toDto:47 */
select b1_0.author_id, ... from book b1_0 left join publisher p1_0 on ... where b1_0.author_id=?
```

It also counts and times SQL statements per HTTP request, detects **N+1** patterns, and exposes Prometheus
metrics and JSON views. Later phases add correlation with `pg_stat_statements` / `auto_explain`,
per-endpoint profiles, proven fixes opened as merge requests, infrastructure right-sizing and a Kubernetes
operator: see [docs/ROADMAP.md](docs/ROADMAP.md).

> **Non-negotiable constraints**: read-only on the database, no SQL rewriting other than the tag comment,
> absolute fail-safe (the tool never impacts the observed application), can be fully disabled
> (`FQ_ENABLED=false`), no external service and no LLM in phase 1.

## Contents

- [Phase 1 status](#phase-1-status)
- [Repository layout](#repository-layout)
- [Quick start (local, no Kubernetes)](#quick-start-local-no-kubernetes)
- [Installing the agent on an application](#installing-the-agent-on-an-application)
- [Configuration](#configuration)
- [The SQL tag](#the-sql-tag)
- [HTTP endpoints and sample output](#http-endpoints-and-sample-output)
- [Demo app and traps](#demo-app-and-traps)
- [Corpus](#corpus)
- [Tests and CI](#tests-and-ci)
- [Measured overhead](#measured-overhead)
- [Kubernetes (kind) and K9s plugin](#kubernetes-kind-and-k9s-plugin)
- [Fail-safe guarantees](#fail-safe-guarantees)
- [Known limitations](#known-limitations)

## Phase 1 status

| Step | Content | Status |
|---|---|---|
| Agent — tagging | `fq:` tag (endpoint + caller) on JDBC, servlet request context | ✅ |
| Agent — measurement + HTTP | Per-HTTP-request counts and timings, N+1, `/metrics`, `/fq/top`, `/fq/nplusone` | ✅ |
| Demo app + traps | Spring Boot 3.5, Hibernate 6.6, 7 traps + 1 witness ([TRAPS.md](demo-app/TRAPS.md)) | ✅ |
| Corpus S and M | Deterministic generator, streamed `COPY`, Open Library extract | ✅ |
| Integration tests | Testcontainers + the real agent jar, GitHub Actions CI | ✅ |
| Overhead | p50/p99 latency and startup time with and without the agent | ✅ |
| kind + Kustomize deployment | `dev` / `e2e` / `pool-overflow` overlays, injection through an initContainer | ✅ |
| K9s plugin | `/fq/top`, `/fq/nplusone`, `/fq/endpoints` from K9s | ✅ |
| kind E2E + E2E CI | `make e2e`, ephemeral `fq-e2e` namespace, GitHub Actions on pull requests | ✅ |

## Repository layout

```
flairquery/
├── core/           flairquery-core: shared fq: tag format/parser and SQL normalizer (no dependency)
├── collector/      flairquery-collector: phase 2 PostgreSQL collector (in progress)
├── agent/          flairquery-agent: Java 21 agent (relocated ByteBuddy, dependency-free shaded jar)
├── demo-app/       flairquery-demo-app: target Spring Boot app, traps documented in TRAPS.md
├── it/             flairquery-it: integration tests (Testcontainers + real agent)
├── corpus/         data generator (Python stdlib), COPY loading, Open Library extract
├── deploy/         PostgreSQL config, local Compose, kind config, images, Kustomize (k8s/)
├── k9s/            K9s plugin (plugins.yaml) and its view script (fq-view.sh)
├── scripts/        traffic (traffic.sh), overhead measurement (bench.sh)
├── docs/ROADMAP.md phases 2 to 7 (design guide)
└── Makefile        entry point (make help)
```

Maven multi-module build (`./mvnw`, nothing to install), `groupId io.github.anthonydeseille.flairquery`.
The demo app does **not** inherit from the FlairQuery parent and does not depend on the agent.

## Quick start (local, no Kubernetes)

Requirements: JDK 21+, Docker (Docker Desktop, colima…), Python 3, `psql`, `jq`.

```bash
# terminal 1: instrumented PostgreSQL 16 (Docker) + demo app with the agent, in the foreground
make demo-local

# terminal 2
make seed-local PROFILE=S          # ~99k rows, ~15 s (PROFILE=M: 3.65M rows, 1.25 GB, 5-8 min)
make traffic-local                 # calls every demo endpoint
curl -s localhost:9464/fq/nplusone | jq
curl -s 'localhost:9464/fq/top?limit=5' | jq
curl -s localhost:9464/metrics | grep '^fq_'
```

The tags are visible on the database side:

```bash
docker exec flairquery-local-postgres-1 psql -U flairquery -d flairquery \
  -c "select calls, left(query, 160) from pg_stat_statements where query like '/* fq:%' order by total_exec_time desc limit 5"
```

`make demo-local-clean` stops PostgreSQL **and deletes its volume**. `make help` lists every target.

## Installing the agent on an application

The agent is a single jar with no transitive dependency (ByteBuddy is relocated under
`io.github.anthonydeseille.flairquery.shaded`):

```bash
./mvnw -B -q -pl agent package -DskipTests      # → agent/target/flairquery-agent.jar

FQ_APP_NAME=my-app FQ_BASE_PACKAGES=com.mycompany \
  java -javaagent:/path/flairquery-agent.jar -jar my-app.jar
```

Without changing the command line (containers):

```bash
export JAVA_TOOL_OPTIONS="-javaagent:/fq/agent.jar"
```

On Kubernetes, an initContainer copies the jar into an `emptyDir` mounted at `/fq` (see [Kubernetes](#kubernetes-kind-and-k9s-plugin)).
In phase 7, an admission webhook will do the injection.

Instrumentation points:

| Point | Purpose |
|---|---|
| `jakarta.servlet.http.HttpServlet#service(HttpServletRequest, HttpServletResponse)` and overrides | opens / closes the request context (ThreadLocal + depth counter, cleaned even on exceptions) |
| `java.sql.Connection#prepareStatement(String, …)` (every implementation, driver and pool) | adds the tag, remembers the statement's SQL (weak map) |
| `java.sql.Statement#execute / executeQuery / executeUpdate / executeLargeUpdate(String, …)` | tag + measurement |
| no-argument `execute*()` / `executeBatch()` (prepared statements) | measurement |
| `Statement#addBatch(String)` | tag |

Verified compatibility: HikariCP 6 (both proxy **and** driver instrumented, tagged and counted once),
pgjdbc 42.7, H2 2.3, Hibernate 6.6, Spring Boot 3.5 (fat jar), Java 21 and 25.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `FQ_ENABLED` | `true` | `false`: the agent does not install anything (no transformation, no port) |
| `FQ_APP_NAME` | `unknown-app` | application name in the tag and the metrics |
| `FQ_BASE_PACKAGES` | *(empty)* | application packages used to find the `caller` (comma-separated). Empty: first frame outside well-known frameworks |
| `FQ_NPLUSONE_THRESHOLD` | `5` | N+1 when the same normalized SQL runs **more than** N times in one HTTP request |
| `FQ_SAMPLING_RATE` | `1.0` | fraction of HTTP requests tagged and measured (decided per request) |
| `FQ_HTTP_PORT` | `9464` | port of the agent's HTTP server (`0` = any free port) |
| `FQ_LOG_LEVEL` | `INFO` | `DEBUG` lists instrumented classes and every HTTP request |

An invalid value is logged and replaced by its default: configuration never prevents startup.

## The SQL tag

```
/* fq:app=<FQ_APP_NAME>,endpoint=<METHOD pattern>,caller=<class#method:line> */ <original SQL>
```

- **endpoint**: Spring's `HandlerMapping.bestMatchingPattern` attribute, read by reflection (no Spring
  dependency), e.g. `GET /authors/{id}`. Before handler mapping (filters) or outside Spring MVC: the raw
  URI. Outside any HTTP request (startup, Liquibase, schedulers): `none`.
- **caller**: first frame in `FQ_BASE_PACKAGES` found with `StackWalker`, skipping Spring CGLIB,
  Hibernate, ByteBuddy and JDK proxies and lambdas. The result is cached per frame.
- **Escaping**: every value goes through an ASCII allow-list. Excluded characters include `*`
  (PostgreSQL block comments nest, so a `/*` inside a value would swallow the query), quotes, `\`, `?`,
  `;`, `,`, `=` and control characters. Each value is truncated to 200 characters.
- **Idempotent**: SQL that already starts with `/* fq:` is not tagged again.
- **No other change**: the original SQL follows the comment, untouched (tested).
- `prepareCall` is **not** tagged: the JDBC escape syntax `{call …}` must stay first.

Formatting and parsing live in a single class (`SqlTag`, module `flairquery-core`), shared by the agent (embedded in its shaded jar) and the phase 2 collector.

## HTTP endpoints and sample output

JDK HTTP server, read-only (`GET` only), on a daemon thread.

| Path | Content |
|---|---|
| `/metrics` | Prometheus text format 0.0.4 |
| `/fq/top?limit=N` | top SQL statements by total time (endpoint, caller, calls, timings) |
| `/fq/nplusone` | detected N+1 patterns |
| `/fq/endpoints` | per-endpoint summary (HTTP requests, SQL statements, timings) |
| `/fq/health` | `{"status":"UP"}` |

JSON responses carry `schemaVersion` (currently `1`). Every in-memory structure is bounded (500 endpoints,
5,000 SQL statements, 1,000 N+1 entries); beyond that, new keys are ignored and counted in `fq_dropped`.

Real output (demo app, profile M, `make traffic-local` × 2):

**`/fq/nplusone`**

```json
{
  "schemaVersion": 1,
  "app": "demo-app",
  "threshold": 5,
  "nplusone": [
    {
      "endpoint": "GET /authors",
      "sql": "select b1_0.author_id,b1_0.id,b1_0.description,b1_0.isbn,b1_0.publication_year,p1_0.id,p1_0.country,p1_0.name,b1_0.title from book b1_0 left join publisher p1_0 on p1_0.id=b1_0.publisher_id where b1_0.author_id=?",
      "caller": "io.github.anthonydeseille.flairquery.demo.web.AuthorController#toDto:47",
      "occurrences": 2,
      "maxQueriesPerRequest": 20,
      "lastQueriesPerRequest": 20,
      "totalQueries": 40,
      "totalTimeMs": 219.779,
      "lastSeen": "2026-11-23T07:47:43.749Z"
    }
  ]
}
```

The caller points at the **controller**: the N+1 happens in the web layer, outside any transaction, which
only works because of `open-in-view` (traps 1 and 6).

**`/fq/top?limit=2`**

```json
{
  "schemaVersion": 1,
  "app": "demo-app",
  "generatedAt": "2026-11-23T07:48:22.999384Z",
  "top": [
    {
      "endpoint": "GET /reviews/ranking",
      "sql": "select r1_0.id,r1_0.book_id,r1_0.rating,r1_0.reviewer_name,r1_0.created_at from review r1_0 order by r1_0.created_at desc,r1_0.comment offset ? rows fetch first ? rows only",
      "caller": "io.github.anthonydeseille.flairquery.demo.web.ReviewController#ranking:39",
      "callers": ["io.github.anthonydeseille.flairquery.demo.web.ReviewController#ranking:39"],
      "calls": 2,
      "totalTimeMs": 49132.076,
      "avgTimeMs": 24566.038,
      "maxTimeMs": 25048.498
    },
    {
      "endpoint": "GET /authors/with-books",
      "sql": "select p1_0.id,p1_0.country,p1_0.name from publisher p1_0 where p1_0.id=?",
      "caller": "io.github.anthonydeseille.flairquery.demo.service.AuthorService#withBooks:28",
      "callers": ["io.github.anthonydeseille.flairquery.demo.service.AuthorService#withBooks:28"],
      "calls": 9808,
      "totalTimeMs": 14068.034,
      "avgTimeMs": 1.434,
      "maxTimeMs": 6.648
    }
  ]
}
```

On profile M, the on-disk sort of trap 7 costs 25 s per call (`work_mem=64kB`, 3M rows).

**`/metrics`** (excerpt)

```
fq_queries_count{app="demo-app",endpoint="GET /authors"} 44
fq_queries_time_seconds{app="demo-app",endpoint="GET /authors"} 0.310398289
fq_nplusone{app="demo-app",endpoint="GET /authors"} 2
fq_http_requests_count{app="demo-app",endpoint="GET /authors"} 2
fq_queries_count{app="demo-app",endpoint="GET /publishers/{id}"} 2
fq_nplusone{app="demo-app",endpoint="GET /publishers/{id}"} 0
fq_agent_errors{app="demo-app"} 0
fq_agent_active{app="demo-app"} 1
```

| Metric | Type | Meaning |
|---|---|---|
| `fq_queries_count` | counter | SQL statements executed, per endpoint |
| `fq_queries_time_seconds` | counter | cumulative SQL time (JDBC `execute*` calls) |
| `fq_nplusone` | counter | HTTP requests in which an N+1 was detected |
| `fq_http_requests_count` / `fq_http_time_seconds` | counter | observed (sampled) HTTP requests |
| `fq_agent_errors` | counter | internal agent errors (never propagated) |
| `fq_agent_active` | gauge | `0` once the circuit breaker has opened |
| `fq_dropped` | counter | entries ignored because an in-memory bound was reached |

**Logs**: one-line JSON on stdout; each N+1 is logged once per (endpoint, SQL):

```json
{"ts":"2026-11-23T07:47:02.207691Z","level":"WARN","logger":"flairquery-agent","msg":"N+1 detected","endpoint":"GET /authors","sql":"select b1_0.author_id,... where b1_0.author_id=?","caller":"io.github.anthonydeseille.flairquery.demo.web.AuthorController#toDto:47","queriesInRequest":20,"threshold":5}
```

**PostgreSQL side** (`pg_stat_statements.query`):

```
/* fq:app=demo-app,endpoint=GET /authors,caller=io.github.anthonydeseille.flairquery.demo.service.AuthorService#firstAuthors:23 */ select count(a1_0.id) from author a1_0
```

## Demo app and traps

Spring Boot 3.5.16, Hibernate 6.6, PostgreSQL 16, schema managed by Liquibase (data is never loaded by
Liquibase). Entities `Author`, `Book`, `Publisher`, `Review`.

| # | Trap | Endpoint |
|---|---|---|
| 1 | N+1 authors → books | `GET /authors?limit=20` |
| 2 | `@ManyToOne` EAGER by default (`Book → Publisher`), never read | `GET /books?limit=50` |
| 3 | Over-fetching: full entities loaded for 2 fields | `GET /books/titles?authorId=7` |
| 4 | Filter on a non-indexed column | `GET /reviews/search?reviewer=Alice%20Martin%2000000` |
| 5 | `JOIN FETCH` + pagination (HHH90003004) | `GET /authors/with-books?prefix=Al&size=5` |
| 6 | `spring.jpa.open-in-view=true` left at its default | global |
| 7 | Large sort spilling to disk | `GET /reviews/ranking?page=400&size=50` |
| ✓ | Clean witness | `GET /publishers/1` |

[demo-app/TRAPS.md](demo-app/TRAPS.md) is the **ground truth**: expected signal, phase 1 detection and
expected fix for each trap. Verified end to end: N+1 and EAGER detected, seq scan, `external merge Disk`
and `temp_blks_written`, `HHH90003004` and `open-in-view` warnings, witness never flagged.

## Corpus

Data is loaded **only through `COPY`**, generated as a stream (no CSV on disk), deterministic (fixed
seed). Details, volumes and the Open Library extract: [corpus/README.md](corpus/README.md).

| Profile | Usage | Rows | Disk | Local load time |
|---|---|---|---|---|
| S | Testcontainers, CI | ~99k | 41 MB | ~15 s |
| M | fq-dev, kind e2e | ~3.65M (+ Open Library extract) | 1.25 GB | 5 to 8 min |
| L | out of phase 1 | tens of millions | — | extension point |

## Tests and CI

```bash
make test      # unit tests: agent (52) + corpus (6)
make verify    # + demo app (Testcontainers) + it/ integration (11), ~2 min, Docker required
```

| Level | What is verified |
|---|---|
| Agent unit tests | tag format, escaping of hostile values, caller resolution (CGLIB/JDK proxies, lambdas), configuration, SQL normalization, N+1, Prometheus output, HTTP server (daemon threads, busy port), fail-safe, real instrumentation on H2 and HikariCP |
| Corpus unit tests | determinism, volumes, skewed distribution, foreign keys, Open Library mapping |
| Integration (`it/`) | PostgreSQL 16 + corpus S + demo app in a separate JVM with the **real** jar: tag in `pg_stat_statements`, N+1 detected and witness not, one query counted through Hikari, `FQ_ENABLED=false`, injected faults without any effect on responses (compared byte for byte with a run without the agent), JVM exits when the app fails to start, shaded jar contains no foreign class |
| E2E (kind) | see [End-to-end test](#end-to-end-test): 13 assertions in an ephemeral namespace |
| CI | GitHub Actions, Java 21: unit + corpus + integration tests on every push; kind e2e on every pull request |

**Non-default Docker (colima…)**: the Makefile picks the socket of the active Docker context. To run
`./mvnw verify` directly:

```bash
export DOCKER_HOST=$(docker context inspect --format '{{.Endpoints.docker.Host}}')
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock   # colima only
```

## Measured overhead

`scripts/bench.sh` runs the same demo app against the same database, without then with the agent. It
measures single-client sequential latency (`curl`) after 200 warm-up requests per endpoint.

Measured on 2026-11-23: Intel i7-9750H Mac, JDK 25, PostgreSQL 16 in colima, profile M, agent with
`FQ_SAMPLING_RATE=1.0`:

| Endpoint | SQL statements / call | Requests | p50 without | p50 with | Δ p50 | p99 without | p99 with | Δ p99 |
|---|---|---|---|---|---|---|---|---|
| `/publishers/1` | 1 | 2000 | 5.88 ms | 6.13 ms | **+0.25 ms** | 8.87 ms | 9.46 ms | +0.59 ms |
| `/books?limit=50` | ~45 | 500 | 79.61 ms | 82.97 ms | **+3.36 ms** | 108.41 ms | 114.01 ms | +5.60 ms |
| `/authors?limit=20` | 22 | 200 | 129.32 ms | 132.37 ms | **+3.04 ms** | 182.45 ms | 186.62 ms | +4.17 ms |

How to read it: about **70 µs per SQL statement** (caller resolution with `StackWalker`, tagging,
measurement) and **~0.25 ms** on a single-query endpoint, i.e. 3 to 4 %. The cost grows with the number of
statements, so it is highest precisely on N+1 endpoints. Use `FQ_SAMPLING_RATE` to reduce it in
production. These figures are indicative (single client, development machine); a measurement under load
on kind will come with the e2e step.

## Collector (phase 2, in progress)

`flairquery-collector` periodically reads the observed PostgreSQL with the read-only `fq_reader` role
(`pg_stat_statements`, `pg_stat_statements_info`, `pg_stat_user_tables`), computes deltas between
snapshots and stores them in **its own database** (`fq-store`), never in the observed one.

- The first cycle is a baseline (no delta). A statement seen for the first time, an evicted and re-created
  entry, or a global `pg_stat_statements_reset()` never produces negative or invented values.
- The collector's own statements are excluded, its read transactions are read-only and always closed.
- **Exact attribution through `auto_explain`.** `pg_stat_statements` keeps one text (one tag) per
  `queryid`. The JSON server log, however, has one `auto_explain` entry per execution, each with its
  `query_id` **and its own `fq:` tag**. The collector reads it incrementally through SQL
  (`pg_ls_logdir`, `pg_read_binary_file`, read-only), so a statement run by three endpoints is split into
  three attributions with their execution counts. `attributionSource` says where endpoint/caller come from
  (`auto_explain`, or `pg_stat_statements` when the statement was not logged).
- The `query_id` field of a log line is **not** trusted: with pgjdbc inside an explicit transaction, a
  statement's plan is logged when its portal closes (at the next statement or at `COMMIT`), with the
  `query_id` of *that* statement (observed on PostgreSQL 16). Each entry is matched to its
  `pg_stat_statements` entry by normalized text (tag removed, literals as `?`) instead; the logged id only
  breaks ties.
- **No data value is stored.** auto_explain prints parameter values into plan expressions
  (`Filter: (reviewer_name = 'Alice …')`); the collector replaces every literal of the plan with `?` as soon
  as it reads it, before fingerprinting, summarizing or storing. The PostgreSQL server log itself still
  contains them, as with any `log_min_duration_statement` setting: protect it accordingly.
- **Plans**: distinct plan shapes per statement (fingerprint of the node tree, costs ignored) with a summary
  of sequential scans, scans that discard rows through a `Filter` (including an Index Scan used only for its
  order, as trap 4 does on profile M), sorts and limits.
- The first cycle skips the existing log content (consistent with the statistics baseline); a bounded
  amount of log is read per cycle (`FQ_COLLECTOR_LOG_MAX_MB`), the backlog is exposed as
  `fq_collector_log_lag_bytes`.
- **Value-free column statistics** (`pg_stats`), every 10 minutes, only for the tables seen in plans or
  sequential scans: `n_distinct`, `null_frac`, `correlation`, `avg_width`, plus the derived estimated
  distinct count and rows per value (how selective an equality filter is). `most_common_vals`,
  `histogram_bounds` and every other column holding data values are never read.
- **Marks and before/after comparison.** `POST /fq/collector/marks` runs a collection cycle immediately
  and names it, so activity before and after the mark falls into different snapshots.
  `/fq/collector/compare` then compares two periods per statement (calls, time, blocks, temp, plan shape
  changes, statements gone or new), per endpoint and in total. Values are **totals per period**: compare
  periods that ran the same traffic (one `make traffic-local` each). The marks endpoint is the only write
  of the API, and it writes into fq-store only.

```bash
make demo-local              # terminal 1: PostgreSQL + fq-store (Compose) + demo app with the agent
make collector-local         # terminal 2: collector on the host (INTERVAL=10 for a 10 s cycle)
make traffic-local           # terminal 3, then:
curl -s 'localhost:9465/fq/collector/queries?window=15m&order=time&limit=10' | jq
curl -s 'localhost:9465/fq/collector/endpoints?window=15m' | jq      # exact, per endpoint
curl -s 'localhost:9465/fq/collector/plans?queryid=<queryid>' | jq    # plan shapes of one statement
curl -s 'localhost:9465/fq/collector/tables?window=15m' | jq
curl -s 'localhost:9465/fq/collector/snapshots' | jq
curl -s 'localhost:9465/fq/collector/columns?table=review' | jq       # value-free pg_stats
```

Measuring a fix (here trap 4, the missing index on `review.reviewer_name`):

```bash
make mark NAME=before            # runs a cycle now and names it
make traffic-local
make mark NAME=fix
docker compose -f deploy/local/compose.yaml exec postgres psql -U flairquery -d flairquery \
  -c 'CREATE INDEX CONCURRENTLY review_reviewer_name ON review (reviewer_name)'
make traffic-local
make mark NAME=after
make compare BEFORE=before AFTER=fix     # period "before" = before..fix, "after" = fix..after
```

A period is a mark name (from that mark to the next one, or now) or an explicit range
`<from>..<to>` whose bounds are mark names, ISO-8601 instants or `now`, e.g.
`compare?before=2026-11-23T14:00:00Z..before&after=fix..now`.

`window` accepts `90s`, `15m`, `2h`, `1d`; `order` accepts `time`, `calls`, `mean`, `temp`, `reads`.
If your local PostgreSQL volume predates phase 2, run `make db-restart-local` once (JSON log
configuration) — `make collector-local` re-applies the `fq_reader` grants itself.

| Variable | Default | Purpose |
|---|---|---|
| `FQ_COLLECTOR_TARGET_URL` | *(required)* | JDBC URL of the observed database |
| `FQ_COLLECTOR_TARGET_USER` / `_PASSWORD` | `fq_reader` | read-only role |
| `FQ_COLLECTOR_TARGET_NAME` | host:port | target label |
| `FQ_COLLECTOR_STORE_URL` | *(required)* | JDBC URL of fq-store |
| `FQ_COLLECTOR_STORE_USER` / `_PASSWORD` | `fqstore` | fq-store credentials |
| `FQ_COLLECTOR_INTERVAL` | `30` | seconds between cycles |
| `FQ_COLLECTOR_RETENTION_HOURS` | `72` | snapshots older than this are purged |
| `FQ_COLLECTOR_HTTP_PORT` | `9465` | HTTP API and `/metrics` |
| `FQ_COLLECTOR_PLANS` | `true` | read auto_explain plans from the JSON server log |
| `FQ_COLLECTOR_LOG_MAX_MB` | `64` | maximum log read per cycle (the rest is read next cycle) |
| `FQ_COLLECTOR_COLUMN_STATS_MINUTES` | `10` | minutes between `pg_stats` reads (`0`: disabled) |

## Kubernetes (kind) and K9s plugin

### Requirements

Docker with **at least 4 CPUs and 6 GB of RAM** for the VM (`colima start --cpu 4 --memory 6`), `jq`,
Python 3. `make tools` downloads pinned `kind` and `kubectl` release binaries into `.tools/bin/`
(checksums verified, no sudo, no Homebrew needed); the Makefile uses `.tools/bin` first and falls back to
the `PATH`. Every `kubectl` call of the Makefile
is pinned to the `kind-flairquery` context, never to your current context.

### Deploy

```bash
make cluster                 # kind cluster "flairquery" (CLUSTER_TOOL=k3d also supported)
make build                   # jars + images (agent, demo-app, tools) loaded into the cluster
make deploy                  # OVERLAY=dev (default) | e2e | pool-overflow, waits for readiness
make seed PROFILE=M          # corpus Job (COPY); FORCE=1 to reload
make load ROUNDS=5           # traffic Job on every demo endpoint
make status                  # pods, services, PVC, jobs
make undeploy                # deletes the namespace (and its data)
make clean                   # deletes the cluster and the FlairQuery images
```

| Overlay | Namespace | Storage | Purpose |
|---|---|---|---|
| `dev` | `fq-dev` | PVC (persistent) | manual testing with K9s |
| `e2e` | `fq-e2e` | `emptyDir` (ephemeral) | `make e2e` |
| `pool-overflow` | `fq-dev` | PVC | phase 6 target: 3 replicas × Hikari 20 = 60 > `max_connections` 50 (needs ~1.5 GB more RAM) |

What gets deployed (`deploy/k8s/base`), every object labelled `app.kubernetes.io/part-of: flairquery`:

- **PostgreSQL 16** with `pg_stat_statements` and `auto_explain` preloaded (`log_min_duration = 0`), same
  configuration as the local Compose environment (`deploy/postgres`). `shared_buffers=32MB`,
  `work_mem=64kB` and `max_connections=50` are deliberately small (phase 6 targets).
- **Demo app** whose image knows nothing about FlairQuery. The agent is injected at deploy time: the
  `fq-agent` initContainer copies the jar into an `emptyDir` mounted at `/fq`, and
  `JAVA_TOOL_OPTIONS=-javaagent:/fq/agent.jar` loads it (phase 7 replaces this with an admission webhook).
  Prometheus annotations: `prometheus.io/scrape: "true"`, `prometheus.io/port: "9464"`,
  `prometheus.io/path: /metrics`.
- **Deliberately mis-sized resources** (phase 6 targets): requests far below real usage (`25m` /
  `64Mi`), tight CPU limit (`500m`, slow startup through throttling), `MaxRAMPercentage=80` too high
  for a `768Mi` limit.
- **Jobs** (`deploy/k8s/jobs`): `fq-seed` (corpus through `COPY`, waits for the Liquibase schema) and
  `fq-load` (traffic), both on the `flairquery/tools` image.

### End-to-end test

```bash
make e2e                      # full run: images + profile M (~5 min of test after the build)
E2E_PROFILE=S make e2e        # profile S (~100 s)
E2E_SKIP_BUILD=1 make e2e     # reuse the images already loaded in the cluster
E2E_KEEP=1 make e2e           # keep the namespace for investigation
```

`scripts/e2e.sh` creates the ephemeral `fq-e2e` namespace, deploys the `e2e` overlay, loads the corpus,
waits for the pods to be Ready, resets `pg_stat_statements`, generates traffic, then checks:

| Assertion | Source |
|---|---|
| SQL statements listed with endpoint and caller; authors N+1 attributed to `AuthorController#toDto` | `/fq/top` |
| N+1 detected on `GET /authors` (20 queries per request) and on the EAGER publisher of `GET /books` | `/fq/nplusone` |
| witness `GET /publishers/{id}` never flagged, exactly 1 query per request | `/fq/nplusone`, `/fq/endpoints` |
| `fq_queries_count` exposed, agent active, zero internal error | `/metrics` |
| `fq:`-tagged statements present, witness tag with its caller | `pg_stat_statements` |
| trap 7 spills to disk (`temp_blks_written > 0`) | `pg_stat_statements` |
| generated corpus complete, prolific author has exactly 5,000 books | database vs `generate.py --expect` |

The namespace is **always** deleted at the end, even on failure or Ctrl-C (`trap`); on failure, pods,
application logs and seed logs are printed first. Assertions rely on the generated, deterministic part of
the corpus only, never on the Open Library extract.

CI: `.github/workflows/e2e.yml` runs `make tools` + `make e2e` (profile M) on every pull request, and on
demand (`workflow_dispatch`, profile M or S).

### K9s plugin

```bash
make k9s-plugin     # writes the K9s plugins.yaml (refuses to overwrite a non-FlairQuery file)
k9s --context kind-flairquery -n fq-dev
```

On the **pods** view, select the demo app pod:

| Shortcut | View |
|---|---|
| `Shift-Q` | top SQL statements by total time (`/fq/top`): endpoint, caller, calls, timings |
| `Shift-W` | N+1 warnings (`/fq/nplusone`): endpoint, caller, queries per request |
| `Shift-E` | per-endpoint summary (`/fq/endpoints`) |

(`Shift-T` and `Shift-N` are already bound by K9s in the pods view: sort by restarts and by name.)

The plugin runs `k9s/fq-view.sh`, which opens a temporary `kubectl port-forward` to the agent port (no
`curl` needed in the application image), renders the JSON with `jq`, pages it with `less` (`q` to go
back to K9s) and always closes the port-forward. The script also works on its own:

```bash
k9s/fq-view.sh nplusone <pod> fq-dev kind-flairquery
```

If you already have a `plugins.yaml`, copy the three entries of `k9s/plugins.yaml` into it and replace
`__FQ_VIEW__` with the absolute path of `k9s/fq-view.sh`.

Real output on fq-dev, profile M, after `make load ROUNDS=2`. `Shift-W`:

```
FlairQuery - N+1 detected (threshold: more than 5 identical statements per request)    app=demo-app

* GET /authors/with-books   up to 4904 queries/request   seen in 2 requests   11572 ms total
    caller: io.github.anthonydeseille.flairquery.demo.service.AuthorService#withBooks:28
    sql:    select p1_0.id,p1_0.country,p1_0.name from publisher p1_0 where p1_0.id=?

* GET /books/titles   up to 3084 queries/request   seen in 2 requests   8286 ms total
    caller: io.github.anthonydeseille.flairquery.demo.service.BookService#titlesOfAuthor:29
    sql:    select p1_0.id,p1_0.country,p1_0.name from publisher p1_0 where p1_0.id=?

* GET /books   up to 49 queries/request   seen in 2 requests   231 ms total
    caller: io.github.anthonydeseille.flairquery.demo.service.BookService#firstBooks:23
    sql:    select p1_0.id,p1_0.country,p1_0.name from publisher p1_0 where p1_0.id=?

* GET /authors   up to 20 queries/request   seen in 2 requests   245 ms total
    caller: io.github.anthonydeseille.flairquery.demo.web.AuthorController#toDto:47
    sql:    select b1_0.author_id,b1_0.id,b1_0.description,b1_0.isbn,b1_0.publication_year,p1_0.id,...
```

`Shift-E`:

```
REQUESTS  SQL/REQ   MAX  SQL ms     N+1  ENDPOINT
2         1         1    33407      0    GET /reviews/ranking
2         4906      4906 13023      2    GET /authors/with-books
2         3085      3085 8417       2    GET /books/titles
2         1         1    2437       0    GET /reviews/search
2         22        22   316        2    GET /authors
2         50        50   234        2    GET /books
2         1         1    1          0    GET /publishers/{id}
```

The witness (`GET /publishers/{id}`) stays at one query and is never flagged. The EAGER publisher (trap 2)
becomes a 4,900-query N+1 on `/authors/with-books` once the data is realistic.

### Agent startup overhead

ByteBuddy has to decide, for every class loaded by the application (~15,000 for the demo app), whether it
is a servlet or a JDBC implementation. Measured on kind with the demo app's `500m` CPU limit:

| Version | Without agent | With agent | Overhead |
|---|---|---|---|
| first implementation | 46.7 s | 69.9 s | +23 s (+50 %) |
| + type-pool cache | 47.3 s | 59.4 s | +12 s (+26 %) |
| + skip packages that cannot contain a target | 48.3 s | 54.5 s | **+6 s (+13 %)** |

Skipped packages: Spring (except `org.springframework.web` and `.jdbc`), Hibernate, Liquibase, Jackson,
logging, Tomcat internals, Kotlin… JDBC drivers, connection pools (HikariCP, commons-dbcp2, c3p0,
tomcat-jdbc) and `HttpServlet` are never skipped (guarded by a unit test). The type cache is cleared
every minute so that it never grows with the application's lifetime.

## Fail-safe guarantees

| Situation | Behaviour | Verified by |
|---|---|---|
| Exception (even an `Error`) inside the agent | never propagated; the original SQL is sent | unit + integration |
| More than 100 internal errors | circuit breaker: the agent becomes a no-op (`fq_agent_active 0`) | unit + integration |
| Initialization failure | the application starts normally, without instrumentation | integration |
| `FQ_ENABLED=false` | no transformation, no open port | unit + integration |
| HTTP port in use, JRE without `jdk.httpserver` | endpoints unavailable, application unchanged | unit |
| Application failing at startup | the JVM exits (every agent thread is a daemon) | integration |
| Memory | bounded structures; statements weakly referenced | unit |
| Dependency conflicts | shaded jar, only `io/github/anthonydeseille/flairquery/**` classes | integration |

## Known limitations

1. **`pg_stat_statements` keeps one text per `queryid`**, the first one seen: one tag, hence one endpoint
   and one caller per normalized statement on the database side. Measured example: the publisher lookup
   comes from 3 endpoints but is entirely attributed to the first tag seen. The exact breakdown comes from
   the agent (`/fq/top`, `/fq/nplusone`).
2. **ThreadLocal context**: asynchronous work (`@Async`, `CompletableFuture`, async servlets, WebFlux) is
   not attributed (`endpoint=none`). `javax.*` servlets (Spring Boot 2) are not instrumented.
3. **Statements executed before Spring handler mapping** (filters): the endpoint is the raw URI.
4. **`*` is not allowed in the tag**: `/actuator/health/**` shows up as `/actuator/health/__`.
5. **`prepareCall` is not tagged** (`{call …}` syntax).
6. **JDBC batches**: one `executeBatch` counts as one execution, whatever the number of rows.
7. **Server-side prepared statements (pgjdbc)**: each tag combination is a distinct SQL string, which
   fragments the driver's statement cache (`preparedStatementCacheQueries`).
8. **SQL normalization** is close to `pg_stat_statements` but not identical (text-based, not parse-tree
   based); phase 2 correlates through the tag, not through the text.
9. **JDK 24+**: ByteBuddy triggers a harmless `sun.misc.Unsafe` warning on stderr. ByteBuddy's
   multi-release entries are excluded from the jar: once relocated, they shadowed Hibernate's own ByteBuddy.
10. **`auto_explain` in dev** (`log_min_duration=0`) produces very large logs. Before the fix, one profile M
    load wrote 14 GB of server logs: seeding now disables `auto_explain` for its session, and the local
    container caps its logs.

## Data license

The Open Library extract is CC0. All other data is generated.
