# Intentional traps of the demo app (ground truth)

This file is FlairQuery's **ground truth**: at every phase we measure how many traps are detected
(phases 1-3), then fixed and proven (phase 4+). Do not fix the traps in the demo app: the expected fixes
describe what FlairQuery must *propose*.

Data: deterministic corpus (`corpus/generate.py`), author 7 = prolific author (5,000 books), known
reviewer `Alice Martin 00000`, witness publisher 1.

| # | Trap | Endpoint | Where in the code | Expected signal | Detected in phase 1? | Expected fix |
|---|---|---|---|---|---|---|
| 1 | **N+1** authors → books | `GET /authors?limit=20` | `AuthorController#toDto` (reads `author.getBooks()` for each author) | 1 query for authors + 1 query for books **per author** | ✅ `/fq/nplusone` (20 book queries for 20 authors) | `@EntityGraph(attributePaths = "books")` or a dedicated `JOIN FETCH`, or `@BatchSize` / `hibernate.default_batch_fetch_size`, or a DTO projection |
| 2 | **`@ManyToOne` EAGER by default** `Book → Publisher`, never read | `GET /books?limit=50` | `Book.publisher` without `fetch = LAZY` | 1 `select ... from publisher where id=?` per distinct publisher | ✅ indirectly: shows up as an N+1 on `publisher` (also on `/books/titles` and `/authors/with-books`). The "useless EAGER" diagnosis is phase 3 | `@ManyToOne(fetch = FetchType.LAZY)` |
| 3 | **Over-fetching**: full entities (including the `description` TEXT, ~600 chars) to return 2 fields | `GET /books/titles?authorId=7` | `BookService#titlesOfAuthor` | `select b1_0.id, ..., b1_0.description, ...` + EAGER publishers | ⚠️ partial: visible in `/fq/top` (time, SQL columns). Formal detection in phase 3 | Projection `select new BookTitleDto(b.id, b.title)` or an interface projection |
| 4 | **Slow query without index** | `GET /reviews/search?reviewer=Alice%20Martin%2000000` | `ReviewRepository#searchByReviewer` | `Seq Scan on review` (largest table) | ⚠️ partial: visible in `/fq/top` and `pg_stat_statements`. Plans (auto_explain) used in phase 2 | Index `CREATE INDEX CONCURRENTLY idx_review_reviewer ON review (reviewer_name)` (Liquibase changeset), validated with HypoPG |
| 5 | **`JOIN FETCH` + pagination** | `GET /authors/with-books?prefix=Al&size=5` | `AuthorRepository#findWithBooksByNamePrefix` | `HHH90003004` warning (in-memory pagination); every `Al%` author and all their books are loaded | ⚠️ partial: volume visible in `/fq/endpoints`; the warning is in the application logs. Detection in phase 3 | Page the ids first, then `JOIN FETCH ... where a.id in :ids`, or `@EntityGraph` + `@BatchSize` |
| 6 | **`spring.jpa.open-in-view=true`** left at its default | global (makes #1 worse) | `application.yml` (property absent) | Spring warning at startup; the N+1 of #1 happens in the web layer (`caller=...AuthorController#toDto`) | ⚠️ hint: caller in a `*Controller`, outside any transaction. Detection in phase 3 | `spring.jpa.open-in-view: false` + explicit loading in the service |
| 7 | **Large sort spilling to disk** | `GET /reviews/ranking?page=400&size=50` | `ReviewRepository#ranking` (`order by created_at desc, comment` + OFFSET 20000) | `Sort Method: external merge Disk`, `temp_blks_written > 0` in `pg_stat_statements` | ⚠️ partial: `/fq/top` + `temp_blks_written`. Analysis in phase 2, `work_mem` sizing in phase 6 | Index on `(created_at desc)` + keyset pagination; or adjusted `work_mem` (phase 6, after the code fix) |
| ✓ | **Clean witness** | `GET /publishers/1` | `PublisherRepository#findDtoById` | 1 query, PK lookup, DTO projection | ✅ **never flagged** | — |

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

## Observed limitation (phase 1)

`pg_stat_statements` keeps the text (and therefore the tag) of the **first** occurrence of a `queryid`
only. Measured example: `select ... from publisher where id=?` is executed by three endpoints (#2, #3, #5),
but `pg_stat_statements` attributes it entirely to the first tag seen (`GET /books`). The exact breakdown
per endpoint and caller is provided by the agent (`/fq/top`, `/fq/nplusone`).
