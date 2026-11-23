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
| Overhead | p50/p99 latency with and without the agent | ✅ (local) |
| kind + Kustomize deployment | `dev` / `e2e` overlays, injection through an initContainer | ⏳ upcoming |
| K9s plugin | `/fq/top` and `/fq/nplusone` from K9s | ⏳ upcoming |
| kind E2E + E2E CI | `make e2e`, ephemeral `fq-e2e` namespace | ⏳ upcoming |

## Repository layout

```
flairquery/
├── agent/          flairquery-agent: Java 21 agent (relocated ByteBuddy, dependency-free shaded jar)
├── demo-app/       flairquery-demo-app: target Spring Boot app, traps documented in TRAPS.md
├── it/             flairquery-it: integration tests (Testcontainers + real agent)
├── corpus/         data generator (Python stdlib), COPY loading, Open Library extract
├── deploy/         instrumented PostgreSQL (config + init), local Docker Compose environment
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

On Kubernetes, an initContainer will copy the jar into an `emptyDir` mounted at `/fq` (upcoming step).
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

Formatting and parsing live in a single class (`SqlTag`), ready to be reused by the phase 2 collector.

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
| CI | GitHub Actions, Java 21: unit + corpus + integration tests on every push |

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

## Kubernetes (kind) and K9s plugin

⏳ **Upcoming** (next phase 1 steps):

- `make cluster build deploy seed load` on **kind**. PostgreSQL 16 with `pg_stat_statements` and
  `auto_explain`, demo app with the agent injected by an initContainer (`emptyDir` + `JAVA_TOOL_OPTIONS`),
  Prometheus annotations. CPU/memory requests and limits deliberately mis-sized, plus an overlay where
  Hikari pool × replicas > `max_connections`. Kustomize overlays `dev` (`fq-dev`, persistent) and `e2e`
  (`fq-e2e`, ephemeral).
- `make e2e`: deployment, corpus, traffic, assertions, guaranteed namespace deletion.
- `k9s/plugins.yaml`: `Shift-T` for `/fq/top`, `Shift-N` for `/fq/nplusone` on the selected pod.

Requirements: `brew install kind kubectl`.

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
