#!/usr/bin/env python3
"""Maps the cached Open Library subset to the demo schema, one table as CSV on stdout (for COPY).

  authors            -> author     (ol_key kept)
  works (+1 edition) -> book       (ISBN, year, publisher taken from one edition)
  edition publishers -> publisher  (distinct names)
  ratings            -> review     (reviewer "openlibrary-user", no comment)

Ids start at OL_ID_OFFSET so they never collide with generated rows: e2e assertions rely on the
generated (deterministic) part only.
"""
import argparse
import csv
import gzip
import json
import os
import re
import signal
import sys
from pathlib import Path

OL_ID_OFFSET = 50_000_000
DEFAULT_SUBSET = Path(os.environ.get("FQ_OL_CACHE", Path.home() / ".cache" / "flairquery" / "openlibrary")) / "subset.jsonl.gz"
YEAR = re.compile(r"(1[5-9]\d\d|20\d\d)")


def load(path):
    authors, works, ratings = {}, {}, []
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            t = r.get("type")
            if t == "author":
                authors[r["key"]] = r
            elif t == "work":
                works[r["key"]] = r
            elif t == "rating":
                ratings.append(r)
    return authors, works, ratings


def cut(v, n):
    return v[:n] if isinstance(v, str) else None


def year(*values):
    for v in values:
        m = YEAR.search(v) if isinstance(v, str) else None
        if m:
            return int(m.group(1))
    return None


def tables(path):
    authors, works, ratings = load(path)
    author_keys = sorted(set(authors) | {w["author"] for w in works.values()})
    author_id = {k: OL_ID_OFFSET + i for i, k in enumerate(author_keys, 1)}
    publishers = sorted({(w.get("edition") or {}).get("publisher") for w in works.values()} - {None})
    publisher_id = {p: OL_ID_OFFSET + i for i, p in enumerate(publishers, 1)}
    work_keys = sorted(works)
    book_id = {k: OL_ID_OFFSET + i for i, k in enumerate(work_keys, 1)}

    def publisher_rows():
        for name in publishers:
            yield [publisher_id[name], cut(name, 255), None]

    def author_rows():
        for k in author_keys:
            a = authors.get(k) or {}
            yield [author_id[k], cut(a.get("name") or f"Unknown ({k.rsplit('/', 1)[-1]})", 255), None,
                   cut(a.get("bio"), 4000), cut(k.rsplit("/", 1)[-1], 32)]

    def book_rows():
        for k in work_keys:
            w = works[k]
            ed = w.get("edition") or {}
            yield [book_id[k], cut(w.get("title") or "(untitled)", 512), cut(ed.get("isbn"), 32),
                   year(w.get("first_publish_date"), ed.get("publish_date")), cut(w.get("description"), 4000),
                   author_id[w["author"]], publisher_id.get(ed.get("publisher"))]

    def review_rows():
        for i, r in enumerate(ratings, 1):
            try:
                rating = int(r["rating"])
            except (TypeError, ValueError):
                continue
            if r["work"] in book_id and 1 <= rating <= 5:
                created = r.get("date") if YEAR.match(str(r.get("date") or "")) else None
                yield [OL_ID_OFFSET + i, book_id[r["work"]], rating, "openlibrary-user", None, created]

    return {"publisher": publisher_rows, "author": author_rows, "book": book_rows, "review": review_rows}


def main():
    if hasattr(signal, "SIGPIPE"):
        signal.signal(signal.SIGPIPE, signal.SIG_DFL)
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--subset", type=Path, default=DEFAULT_SUBSET)
    ap.add_argument("--table", required=True, choices=["publisher", "author", "book", "review"])
    a = ap.parse_args()
    if not a.subset.exists():
        sys.exit(f"no Open Library subset at {a.subset} (run corpus/openlibrary/fetch.py)")
    w = csv.writer(sys.stdout, lineterminator="\n")
    for row in tables(a.subset)[a.table]():
        w.writerow(row)


if __name__ == "__main__":
    main()
