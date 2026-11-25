# Intentional traps of the demo app (ground truth)

This file is FlairQuery's **ground truth**: at every phase we measure how many traps are detected
(phases 1-3), then fixed and proven (phase 4+). The phase 2 column is measured by
`scripts/scorecard.py` from the collector API, the phase 3 column from the agent's `/fq/findings`
(`--agent`); `make e2e` runs both and fails when a trap scores below its level or the witness is flagged
(`make scorecard`, `make scorecard-local` run it by hand). The phase 4 column is measured by the analyzer's `fix`
command (`target/fixes/scorecard.md`, `TrapScorecard`): a trap is fixed only when a fix is ACCEPTED by the
prover and resolves its finding; `FixIT` (in `make verify`) asserts 6/6, with the template fixes generated and
the LLM fixes replayed from the reference patches `analyzer/src/test/resources/fixes` (`make llm-it` regenerates
them with real API calls). The phase 5 column is measured by the analyzer's `mr` command
(`target/mr/scorecard.md`, `MrScorecard`): a trap is proposed when a merge request selected by `mr` resolves its
finding, with a regression test proven red on the current code and green with the fix; `FixIT` asserts 4/6 traps
in 3 merge requests, traps 3 and 5 deferred. Do not fix the traps in the demo app: the expected fixes
describe what FlairQuery must *propose*.

Data: deterministic corpus (`corpus/generate.py`), author 7 = prolific author (5,000 books), known
reviewer `Alice Martin 00000`, witness publisher 1.

| # | Trap | Endpoint | Where in the code | Expected signal | Detected in phase 1? | Phase 2 (collector) | Phase 3 (agent JPA profile) | Phase 4 (fixed and proven) | Phase 5 (proposed as a merge request) | Expected fix |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | **N+1** authors → books | `GET /authors?limit=20` | `AuthorController#toDto` (reads `author.getBooks()` for each author) | 1 query for authors + 1 query for books **per author** | ✅ `/fq/nplusone` (20 book queries for 20 authors) | ✅ DETECTED: 20 book statements per authors page, exact endpoint + caller from auto_explain | ✅ DETECTED `N_PLUS_ONE_LAZY`: `Author.books` loaded lazily 20 times per request, caller `AuthorController#toDto`, with the statement it triggers | ✅ FIXED by the LLM: `GET /authors` built in a read-only service from two projections (page of authors, then their titles in one `in (:ids)` query); 22 → 2 statements | ✅ PROPOSED: `flairquery/llm-trap1-authors-list-title-projection`; test red "GET /authors with 4 authors ran 5 SQL statements: expected at most 2", green with the fix | `@EntityGraph(attributePaths = "books")` or a dedicated `JOIN FETCH`, or `@BatchSize` / `hibernate.default_batch_fetch_size`, or a DTO projection |
| 2 | **`@ManyToOne` EAGER by default** `Book → Publisher`, never read | `GET /books?limit=50` | `Book.publisher` without `fetch = LAZY` | 1 `select ... from publisher where id=?` per distinct publisher | ✅ indirectly: shows up as an N+1 on `publisher` (also on `/books/titles` and `/authors/with-books`). The "useless EAGER" diagnosis is phase 3 | ✅ DETECTED as a repeated lookup: publisher by id split exactly across `GET /books`, `/books/titles`, `/authors/with-books`; the "useless EAGER" diagnosis stays in phase 3 | ✅ DETECTED `USELESS_EAGER`: `Book.publisher` EAGER by JPA default and never read (49 `Publisher` per `GET /books` on M) | ✅ FIXED by the `lazy-association` template: `fetch = FetchType.LAZY`; `GET /books` 45 → 1 statement | ✅ PROPOSED: `flairquery/lazy-book-publisher` (4 findings); test red "should run at most 2 SQL statements, but ran 6", green with the fix | `@ManyToOne(fetch = FetchType.LAZY)` |
| 3 | **Over-fetching**: full entities (including the `description` TEXT, ~600 chars) to return 2 fields | `GET /books/titles?authorId=7` | `BookService#titlesOfAuthor` | `select b1_0.id, ..., b1_0.description, ...` + EAGER publishers | ⚠️ partial: visible in `/fq/top` (time, SQL columns). Formal detection in phase 3 | ⚠️ PARTIAL: full entity with `description` selected for a titles endpoint; serialized fields unknown until phase 3 | ✅ DETECTED `OVER_FETCHING`: 5,000 `Book` per request, only `title` read, `description` (TEXT) never read | ✅ FIXED by the LLM: JPQL constructor projection `BookTitleDto(b.id, b.title)`; 501 → 1 statement | ⏸ DEFERRED: its fix changes the same lines of `BookRepository` as trap 1's; proven again once that merge request is merged | Projection `select new BookTitleDto(b.id, b.title)` or an interface projection |
| 4 | **Slow query without index** | `GET /reviews/search?reviewer=Alice%20Martin%2000000` | `ReviewRepository#searchByReviewer` | `Seq Scan on review` (largest table) | ⚠️ partial: visible in `/fq/top` and `pg_stat_statements`. Plans (auto_explain) used in phase 2 | ✅ DETECTED: plan scans `review` and discards rows through `Filter: reviewer_name = ?` (on M: an Index Scan on `review_pkey` used only for its order), and `pg_stats` shows a selective column (~61 rows per value on M) | — (database side: phase 2) | ✅ FIXED by the `index-migration` template: Liquibase changeset `CREATE INDEX CONCURRENTLY`; plan → index scan on `idx_review_reviewer_name`, SQL time ~-80 % | ✅ PROPOSED: `flairquery/index-review-reviewer-name`; test red on the plan (`Seq Scan on review`, `Filter: reviewer_name`), green with an `Index Cond` | Index `CREATE INDEX CONCURRENTLY idx_review_reviewer ON review (reviewer_name)` (Liquibase changeset), validated with HypoPG |
| 5 | **`JOIN FETCH` + pagination** | `GET /authors/with-books?prefix=Al&size=5` | `AuthorRepository#findWithBooksByNamePrefix` | `HHH90003004` warning (in-memory pagination); every `Al%` author and all their books are loaded | ⚠️ partial: volume visible in `/fq/endpoints`; the warning is in the application logs. Detection in phase 3 | ⚠️ PARTIAL: count query + collection fetch without `LIMIT` (21k rows per call on M for a page of 5) | ✅ DETECTED `IN_MEMORY_PAGINATION`: HHH90003004 caught on the request (26,162 rows read for a page of 5 on M) | ✅ FIXED by the LLM: authors paged in SQL (`LIMIT`), then the titles of the page in one query; 326 → 2 statements | ⏸ DEFERRED: its fix changes the same lines as trap 1's (`AuthorRepository`, `BookRepository`, `AuthorService`, `AuthorController`) | Page the ids first, then `JOIN FETCH ... where a.id in :ids`, or `@EntityGraph` + `@BatchSize` |
| 6 | **`spring.jpa.open-in-view=true`** left at its default | global (makes #1 worse) | `application.yml` (property absent) | Spring warning at startup; the N+1 of #1 happens in the web layer (`caller=...AuthorController#toDto`) | ⚠️ hint: caller in a `*Controller`, outside any transaction. Detection in phase 3 | ⚠️ PARTIAL: lazy loading run from a controller (`AuthorController#toDto`) | ✅ DETECTED `OPEN_IN_VIEW`: lazy loads outside any transaction while open-in-view is active | ✅ FIXED by the LLM: `spring.jpa.open-in-view: false` + the projections of #1 in the service; 22 → 2 statements | ✅ PROPOSED by trap 1's merge request, which resolves its finding too; its own fix is redundant (not opened, named in that merge request) | `spring.jpa.open-in-view: false` + explicit loading in the service |
| 7 | **Large sort spilling to disk** | `GET /reviews/ranking?page=400&size=50` | `ReviewRepository#ranking` (`order by created_at desc, comment` + OFFSET 20000) | `Sort Method: external merge Disk`, `temp_blks_written > 0` in `pg_stat_statements` | ⚠️ partial: `/fq/top` + `temp_blks_written`. Analysis in phase 2, `work_mem` sizing in phase 6 | ✅ DETECTED: `temp_blks_written` > 0 and a Sort on `created_at DESC, comment` in the plan | — (database side: phase 2) | — out of phase 4: keyset pagination changes the API, `work_mem` is phase 6 | — out of scope (no fix in phase 4) | Index on `(created_at desc)` + keyset pagination; or adjusted `work_mem` (phase 6, after the code fix) |
| ✓ | **Clean witness** | `GET /publishers/1` | `PublisherRepository#findDtoById` | 1 query, PK lookup, DTO projection | ✅ **never flagged** | ✅ CLEAN: Index Scan only, never flagged | ✅ CLEAN: DTO projection, no entity loaded, no finding | ✅ CLEAN: no fix targets it; JSON identical under every proven fix | ✅ CLEAN: no merge request targets it | — |

## Infrastructure traps (phase 6)

Planted in the Kubernetes manifests (`deploy/k8s`), not in the code. Phase 6 measures them from the
collector's `/fq/collector/infra` (pods and cAdvisor through the Kubernetes API, the JVM of every replica from
the agent's `/fq/jvm`, `pg_settings` / `pg_stat_database` / `pg_stat_activity`), always **after** the code fixes
of phases 4-5: a pod is never sized for the waste of an N+1. A sizing is accepted only when replayed on kind
(same load, throwaway namespace) with no OOMKill, less throttling, a p95 not worse, a startup not slower, no
refused connection and, for I4, `temp_blks_written` = 0 on trap 7.

| # | Trap | Where | Expected signal (`/fq/collector/infra`) | Expected fix |
|---|---|---|---|---|
| I1 | **Heap too large for the memory limit**: `-XX:MaxRAMPercentage=80` with a 768Mi limit | `demo-app.yaml` (`JAVA_TOOL_OPTIONS`) | `jvms[].maxRamPercentage` 80 and `heapMaxBytes` ~614 MiB against `containerMemoryBytes` 768 MiB: ~154 MiB left for metaspace, code cache, threads and direct buffers (`nonHeapUsedMaxBytes`, `rssMaxBytes` close to the limit); OOMKilled under load (`containers[].oomKilled`, `lastTerminations`) | `MaxRAMPercentage` from the measured live set (`liveSetMaxBytes`) and non-heap, consistent with the limit (typically 60-70 %), or a limit raised to fit |
| I2 | **Requests far below usage**: `cpu: 25m`, `memory: 64Mi` | `demo-app.yaml` (`resources.requests`) | `requests` against `cpu.usageP95Millis` and `memory.workingSetMaxBytes` (hundreds of MiB): the scheduler places the pod on a node that cannot hold it | requests from the measured p95 CPU and the memory peak |
| I3 | **CPU limit too tight**: `cpu: 500m` | `demo-app.yaml` (`resources.limits`) | `cpu.throttledRatio` high during load and at startup, `startupSecondsMax` ~45 s+ | a higher limit (or none, with a request at the p95), checked against startup and p95 latency |
| I4 | **`work_mem` 64kB**: trap 7's sort spills to disk | `postgresql-fq.conf` | `postgres.settings.work_mem` 64 KiB, `postgres.database.tempBytes` > 0 over the window, trap 7's `temp_blks_written` > 0 | `work_mem` sized from the spilled bytes (per role, reloadable), or trap 7's code fix first (keyset pagination) |
| I5 | **`shared_buffers` 32MB** for a corpus of several GB (profile M; on profile S the data fit) | `postgresql-fq.conf` | `postgres.settings.shared_buffers` 32 MiB, `postgres.database.cacheHitRatio` low under load, PostgreSQL's working set far below its 1Gi limit | `shared_buffers` ~25 % of the container memory limit, `effective_cache_size` consistent (restart: human review only) |
| I6 | **Pool × replicas > `max_connections`**: Hikari 20 × 3 replicas = 60 > 50 (47 for clients after the 3 reserved) | overlay `pool-overflow` | `jvms[].hikari[].maximumPoolSize` × the replicas of `containers[]` against `postgres.connections.maxConnections` - `reserved`; `peakWaiting` > 0, refused connections at startup or under load | a pool per replica such that pool × max replicas (HPA) + other clients ≤ `max_connections` - reserved, or a bouncer |
| ✓ | **Clean witness**: the collector (`fq-collector`, requests 50m/224Mi, limits 500m/256Mi, a plain JVM) | `collector.yaml` | steady-state usage within its requests and limits, no OOMKill, no restart (its JVM startup is briefly throttled, as any JVM under a 500m limit: measured over a window that includes it, not a finding) | **never flagged** |

### Phase 6 scorecard: detected by `size` (measured on kind, profile S)

`make size` (analyzer `size`, deterministic, no LLM) after 40 load rounds; two deployments, since I6 needs the
`pool-overflow` overlay:

| # | dev overlay (1 replica, pool 10) | pool-overflow overlay (3 replicas x pool 20) | Proposed (to be proven by the replay, step 3) |
|---|---|---|---|
| I1 | ✅ DETECTED: heap max 596 MiB + native ~243 MiB > 768 MiB | ✅ DETECTED | `MaxRAMPercentage` 60 (heap 461 MiB, live set peak ~76 MiB) |
| I2 | ✅ DETECTED: CPU 25m for a p95 of 426m, memory 64Mi for a 363 MiB peak | ✅ DETECTED | requests `450m` / `448Mi` |
| I3 | ✅ DETECTED: 11 % throttled under load at 85 % of the limit (marginal: the strong signal is the startup) | ✅ DETECTED: 100 % throttled during a 77 s startup | CPU limit `1` |
| I4 | ✅ DETECTED: 3.4 GB of temporary files, trap 7's sort spilling ~20 MB per call | ✅ DETECTED | `ALTER ROLE flairquery SET work_mem = '32MB'` (the memory budget caps it: trap 7's code fix remains the real remedy) |
| I5 | ⏸ NOT TRIGGERED: 49 MB of data fit in 32 MB of shared_buffers + the OS cache (hit ratio 97.7-100 %); visible on profile M (hit ratio 38 % measured on 2026-11-24) | ⏸ NOT TRIGGERED | (profile M: `shared_buffers` 256MB, `effective_cache_size` 768MB, restart) |
| I6 | — not deployed | ✅ DETECTED: the rollout stuck on `FATAL: sorry, too many clients already`; pools 10 + 20 + 20 + 20 (one crashing pod) = 70 > 43 left by PostgreSQL | `HIKARI_MAX_POOL` 10 for the 4 pods measured (14 for 3) |
| ✓ | ✅ CLEAN | ✅ CLEAN | — |

Secondary findings (true, not numbered traps): PostgreSQL's CPU request (100m for a p95 of ~490m under load) is
reported `REQUESTS_BELOW_USAGE`. The witness itself was first flagged: its memory request (128Mi) was below its
measured peak (180 MiB under load); corrected to the `224Mi` that `size` proposed, it is clean.

Found while measuring, and fixed: the collector got OOMKilled reading a 45 MB log backlog in one go (now read in
4 MB chunks, 16 MB per cycle by default), and during I6 it could not connect either (`too many clients`): its
infrastructure readings are now stored even when the observed database refuses the cycle.

## Reference measurements (profile S, local, agent enabled)

| Endpoint | SQL statements / call | Note |
|---|---|---|
| `GET /authors?limit=20` | 22 | authors page + Spring Data count + 20 × books (#1) |
| `GET /books?limit=50` | ~45 | EAGER publisher (#2) |
| `GET /books/titles?authorId=7` | ~501 | 1 + 500 distinct publishers (#2 + #3) |
| `GET /authors/with-books?prefix=Al&size=5` | ~326 | #5 + #2 |
| `GET /reviews/search` | 1 | seq scan (#4) |
| `GET /reviews/ranking` | 1 | external merge (#7); 25 s per call on profile M |
| `GET /publishers/1` | 1 | witness |

## Observed limitation (phase 1), solved in phase 2

`pg_stat_statements` keeps the text (and therefore the tag) of the **first** occurrence of a `queryid`
only: `select ... from publisher where id=?`, executed by three endpoints (#2, #3, #5), was attributed
entirely to the first tag seen (`GET /books`). The phase 2 collector reads one `auto_explain` entry per
execution, each with its own tag, and splits it exactly (profile M, one traffic round:
24,520 `/authors/with-books`, 15,420 `/books/titles`, 245 `/books`).

## Secondary findings (phase 3, expected and true)

The phase 3 rules also report real problems that are not numbered traps. They are expected, not noise:

| Endpoint | Finding | Why it is true |
|---|---|---|
| `GET /books` | `OVER_FETCHING` on `Book`: `description` (TEXT) never read | `BookDto` has no description |
| `GET /authors` | `USELESS_EAGER` `Book.publisher`, `OVER_FETCHING` on `Book` and `Author` (`bio`) | `toDto` reads book titles only |
| `GET /authors/with-books` | `USELESS_EAGER` `Book.publisher`, `OVER_FETCHING` on `Book` and `Author` (`bio`) | `AuthorBooksDto` has no description, bio nor publisher |

Entities loaded only because of a useless EAGER (the `Publisher` rows) are not reported a second time as
over-fetching.

## Deterministic responses (phase 4)

The phase 4 prover only accepts a fix when every JSON response stays strictly identical. `Author.books` is
therefore `@OrderBy("id")`: `GET /authors` returns the first three titles of each author, and without an
order they followed the SQL plan. The fix of trap 2 (`Book.publisher` LAZY) removes a `left join publisher`
from the book query, which changed the row order and the response; the prover rejected it, rightly. This is
not a trap: an accidental non-determinism, fixed in the demo app.

## Measuring a fix (phase 2)

Trap 4 on profile M, with `make mark`, one `make traffic-local` per period and
`CREATE INDEX CONCURRENTLY ... ON review (reviewer_name)` in between (index dropped afterwards, the trap
stays in the demo):

| `GET /reviews/search` (5 calls) | Before | After |
|---|---|---|
| SQL time | 1,647 ms | 2.5 ms (**-99.8 %**) |
| Blocks read | 354,756 | 355 |
| Plan | Index Scan on `review_pkey` + Filter | Bitmap Index Scan |
