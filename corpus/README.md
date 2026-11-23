# Demo corpus

Data is loaded **only through `COPY`** (never through Liquibase, which only manages the schema).
Nothing is written to disk on the client side: `generate.py` writes CSV to stdout, piped straight into
`psql \copy` (or into the JDBC `CopyManager` in the integration tests).

```bash
make seed-local PROFILE=S            # local (PostgreSQL of make demo-local)
make seed-local PROFILE=M FORCE=1    # reload, truncating first
make openlibrary                     # (optional) Open Library extract for profile M
python3 corpus/generate.py --profile S --expect   # expected facts (tests)
```

## Profiles

| Profile | Usage | publisher | author | book | review | Total | Disk size | Load time (local, colima) |
|---|---|---|---|---|---|---|---|---|
| **S** | Testcontainers, CI | 500 | 5,000 | 23,485 | 70,000 | ~99k rows | **41 MB** | ~15 s |
| **M** | fq-dev, kind e2e | 5,000 | 200,000 | 440,167 | 3,000,000 | ~3.65M rows | **1.25 GB** | 5 to 8 min |
| M + Open Library | fq-dev | + extract | + extract | + extract | + extract | | + a few MB | + a few s |
| **L** | out of phase 1 | — | — | — | — | tens of millions | — | extension point only (`generate.py` fails explicitly) |

Measured on 2026-11-22 on a Mac (PostgreSQL 16 in colima, `shared_buffers=32MB`). Load time is dominated
by PostgreSQL writes (TOAST of descriptions, indexes, foreign keys), not by generation (27 s for the 440k
books of M).

Why these volumes: M is far larger than `shared_buffers` (32 MB) and `work_mem` (64 kB), which forces disk
reads and on-disk sorts (trap 7). S is enough for every trap in CI.

## Shape of the generated data (deterministic, seed 42)

- **Heavily skewed distribution**: a few "prolific" authors have exactly 5,000 books (S: ids 7, 1673,
  3339), every other author has 1 to 3 books. Author 7 is always on the first page of `GET /authors`.
- `book.description`: long text (~400-900 characters), so over-fetching (trap 3) is visible.
- `review`: the largest table, with a **non-indexed** `reviewer_name` (trap 4) and a sort on the
  non-indexed `created_at, comment` (trap 7). Known reviewer: `Alice Martin 00000`.
- Same profile ⇒ byte-identical output (tested). Integration and e2e assertions rely on this generated
  data, **never** on the Open Library extract.

## Open Library extract (CC0)

`corpus/openlibrary/fetch.py` reads the monthly dumps **as an HTTP stream** (works ~3 GB, editions ~10 GB
compressed), decompresses and filters them on the fly, and caches only the selected subset
(`~/.cache/flairquery/openlibrary/subset.jsonl.gz`, a few MB, outside the repository). Each pass has a cap
(`--works-scan-mb`, `--editions-scan-mb`…) to bound time and bandwidth: the extract is a sample.

| Open Library | → | Table |
|---|---|---|
| authors | → | `author` (`ol_key` kept) |
| works (+ one edition) | → | `book` (ISBN, year, publisher taken from the edition) |
| edition publishers | → | `publisher` |
| ratings | → | `review` (`reviewer_name = openlibrary-user`) |

Open Library ids start at 50,000,000: no collision with generated data. `load.sh` loads the extract for
profile M when it is cached (`FQ_OPENLIBRARY=auto|off|required`).

## Profile L extension point

`PROFILES["L"] = None` in `generate.py`: the command fails explicitly. Planned implementation: full Open
Library extract (no scan cap) + large-scale generated reviews, parallel loading per table.

## Lessons learned

- **auto_explain during seeding**: with `log_min_duration=0` and `log_nested_statements=on`, every foreign
  key check fired by `COPY` is logged. One profile M load produced a **14 GB** server log. `load.sh` therefore
  disables `auto_explain` for its own session (superuser parameter), and the local container caps its logs
  (2 × 50 MB).
