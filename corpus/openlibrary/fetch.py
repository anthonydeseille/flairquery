#!/usr/bin/env python3
"""Extracts a SMALL Open Library subset (CC0) by streaming the monthly dumps.

The full dumps (works ~3 GB, editions ~10 GB compressed) are NEVER written to disk: they are read over
HTTP, decompressed on the fly, filtered, and only the selected records are cached (gzip JSON lines)
under ~/.cache/flairquery/openlibrary/ (outside the repository). Each pass has a scan cap so the run
time and bandwidth stay bounded; the subset is therefore a sample, not a complete closure.

Selection (deterministic for a given dump):
  1. works pass:    keep works whose FIRST author key hashes into the sample (sha1 % --author-modulo == 0),
                    until --max-works works are kept or --works-scan-mb compressed MB have been read;
  2. authors pass:  keep the authors referenced by kept works (missing ones become placeholders);
  3. editions pass: keep editions of kept works (ISBN, publisher, year), up to --editions-scan-mb;
  4. ratings pass:  keep ratings of kept works (small dump, read fully unless --ratings-scan-mb).

    corpus/openlibrary/fetch.py                       # defaults: ~10k works, a few minutes
    corpus/openlibrary/fetch.py --max-works 500 --works-scan-mb 50 --editions-scan-mb 50   # quick try
"""
import argparse
import gzip
import hashlib
import io
import json
import os
import sys
import time
import urllib.request
from pathlib import Path

BASE_URL = "https://openlibrary.org/data"
DUMPS = {
    "works": "ol_dump_works_latest.txt.gz",
    "authors": "ol_dump_authors_latest.txt.gz",
    "editions": "ol_dump_editions_latest.txt.gz",
    "ratings": "ol_dump_ratings_latest.txt.gz",
}
USER_AGENT = "flairquery-corpus/0.1 (+demo dataset extraction)"
DEFAULT_CACHE = Path(os.environ.get("FQ_OL_CACHE", Path.home() / ".cache" / "flairquery" / "openlibrary"))


class CountingReader(io.RawIOBase):
    """Counts compressed bytes read from the network, to enforce scan caps."""

    def __init__(self, raw):
        self.raw = raw
        self.count = 0

    def readable(self):
        return True

    def readinto(self, b):
        data = self.raw.read(len(b))
        n = len(data)
        b[:n] = data
        self.count += n
        return n


def open_dump(args, name):
    if args.local_dir:
        path = Path(args.local_dir) / DUMPS[name]
        raw = open(path, "rb")
    else:
        req = urllib.request.Request(f"{args.base_url}/{DUMPS[name]}", headers={"User-Agent": USER_AGENT})
        raw = urllib.request.urlopen(req, timeout=60)
    counter = CountingReader(raw)
    text = io.TextIOWrapper(gzip.GzipFile(fileobj=io.BufferedReader(counter, 1 << 20)), encoding="utf-8",
                            errors="replace")
    return counter, text


def log(msg, **kv):
    print(json.dumps({"ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "level": "INFO",
                      "logger": "flairquery-corpus-ol", "msg": msg, **kv}), file=sys.stderr, flush=True)


def sampled(key, modulo):
    return int(hashlib.sha1(key.encode()).hexdigest(), 16) % modulo == 0


def records(args, name, cap_mb):
    """Yields (key, json) of a dump, stopping at cap_mb compressed MB (0 = no cap)."""
    counter, text = open_dump(args, name)
    cap = cap_mb * 1024 * 1024
    n = 0
    try:
        for line in text:
            n += 1
            if cap and counter.count > cap:
                log("scan cap reached", dump=name, lines=n, scannedMb=counter.count // (1 << 20))
                return
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 5:
                continue
            try:
                yield parts[1], json.loads(parts[4])
            except json.JSONDecodeError:
                continue
    finally:
        log("dump pass done", dump=name, lines=n, scannedMb=counter.count // (1 << 20))
        text.close()


def first_author(work):
    for a in work.get("authors") or []:
        ref = a.get("author") if isinstance(a, dict) else None
        key = ref.get("key") if isinstance(ref, dict) else ref
        if isinstance(key, str) and key.startswith("/authors/"):
            return key
    return None


def text_value(v):
    if isinstance(v, dict):
        return v.get("value")
    return v if isinstance(v, str) else None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cache-dir", type=Path, default=DEFAULT_CACHE)
    ap.add_argument("--base-url", default=BASE_URL)
    ap.add_argument("--local-dir", help="read already downloaded dumps from this directory instead of HTTP")
    ap.add_argument("--author-modulo", type=int, default=200)
    ap.add_argument("--max-works", type=int, default=10_000)
    ap.add_argument("--works-scan-mb", type=int, default=1_500)
    ap.add_argument("--authors-scan-mb", type=int, default=0)
    ap.add_argument("--editions-scan-mb", type=int, default=1_000)
    ap.add_argument("--ratings-scan-mb", type=int, default=0)
    a = ap.parse_args()

    a.cache_dir.mkdir(parents=True, exist_ok=True)
    out_path = a.cache_dir / "subset.jsonl.gz"
    tmp_path = a.cache_dir / "subset.jsonl.gz.part"
    started = time.time()

    works = {}
    for key, w in records(a, "works", a.works_scan_mb):
        author = first_author(w)
        if author and sampled(author, a.author_modulo):
            works[key] = {"title": w.get("title"), "author": author,
                          "description": text_value(w.get("description")),
                          "first_publish_date": w.get("first_publish_date")}
            if len(works) >= a.max_works:
                break
    wanted_authors = {w["author"] for w in works.values()}
    log("works selected", works=len(works), authors=len(wanted_authors))

    authors = {}
    for key, au in records(a, "authors", a.authors_scan_mb):
        if key in wanted_authors:
            authors[key] = {"name": au.get("name") or au.get("personal_name"), "birth_date": au.get("birth_date"),
                            "bio": text_value(au.get("bio"))}
            if len(authors) == len(wanted_authors):
                break

    editions = {}
    for key, ed in records(a, "editions", a.editions_scan_mb):
        for wref in ed.get("works") or []:
            wk = wref.get("key") if isinstance(wref, dict) else None
            if wk in works and wk not in editions:
                isbn = (ed.get("isbn_13") or ed.get("isbn_10") or [None])[0]
                editions[wk] = {"isbn": isbn, "publisher": (ed.get("publishers") or [None])[0],
                                "country": ed.get("publish_country"), "publish_date": ed.get("publish_date")}

    ratings = []
    counter, text = open_dump(a, "ratings")
    try:
        cap = a.ratings_scan_mb * 1024 * 1024
        for line in text:
            if cap and counter.count > cap:
                break
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 4 and parts[0] in works:
                ratings.append({"work": parts[0], "rating": parts[2], "date": parts[3]})
    finally:
        text.close()

    with gzip.open(tmp_path, "wt", encoding="utf-8") as f:
        f.write(json.dumps({"type": "meta", "source": "Open Library dumps (CC0)", "baseUrl": a.base_url,
                            "fetchedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                            "authorModulo": a.author_modulo}) + "\n")
        for k, v in authors.items():
            f.write(json.dumps({"type": "author", "key": k, **v}) + "\n")
        for k, v in works.items():
            f.write(json.dumps({"type": "work", "key": k, **v, "edition": editions.get(k)}) + "\n")
        for r in ratings:
            f.write(json.dumps({"type": "rating", **r}) + "\n")
    tmp_path.replace(out_path)
    log("subset cached", path=str(out_path), works=len(works), authors=len(authors),
        worksWithEdition=len(editions), ratings=len(ratings), sizeKb=out_path.stat().st_size // 1024,
        seconds=int(time.time() - started))


if __name__ == "__main__":
    main()
