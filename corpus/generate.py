#!/usr/bin/env python3
"""Deterministic FlairQuery demo corpus generator (Python stdlib only).

Writes ONE table as CSV on stdout, meant to be piped into `COPY ... FROM STDIN (FORMAT csv)`.
Nothing is written to disk. Same profile + seed => byte-identical output, whatever the table order.

    generate.py --profile S --table author | psql -c "\\copy author (...) FROM STDIN (FORMAT csv)"
    generate.py --profile S --expect      # JSON facts used by integration / e2e assertions
    generate.py --profile S --columns author

Data shape (see README "Corpus"):
  * very skewed books per author: a few "prolific" authors with exactly `prolific_books` books,
    every other author has 1 to 3 books;
  * the review table is the largest one, with a non-indexed reviewer_name (TRAP 4) and a sort
    column created_at + text comment (TRAP 7);
  * book.description is a long text (TRAP 3, over-fetch).
"""
import argparse
import csv
import datetime as dt
import json
import random
import signal
import sys

PROFILES = {
    # ~100k rows: Testcontainers and CI
    "S": dict(seed=42, publishers=500, authors=5_000, prolific=3, prolific_books=5_000,
              reviews=70_000, reviewers=2_000),
    # a few million rows (~1-1.5 GB on disk): fq-dev and e2e kind.
    # Generated part only; the Open Library extract is added by corpus/openlibrary (see README).
    "M": dict(seed=42, publishers=5_000, authors=200_000, prolific=20, prolific_books=5_000,
              reviews=3_000_000, reviewers=50_000),
    # Extension point only (phase 1 does not implement L): Open Library + generated reviews,
    # tens of millions of rows. Kept here so tooling can list it and fail explicitly.
    "L": None,
}

COLUMNS = {
    "publisher": ["id", "name", "country"],
    "author": ["id", "name", "birth_date", "bio", "ol_key"],
    "book": ["id", "title", "isbn", "publication_year", "description", "author_id", "publisher_id"],
    "review": ["id", "book_id", "rating", "reviewer_name", "comment", "created_at"],
}
TABLE_SALT = {"publisher": 1, "author": 2, "book": 3, "review": 4, "alloc": 5}

FIRST = ("Alice Antoine Albert Alma Bruno Camille Chloe Daniel Elise Emile Fanny Gabriel Hugo Ines Jules "
         "Karim Lea Louis Lucie Manon Marc Nina Olivier Paul Quentin Rose Sacha Theo Ursula Victor Yanis Zoe "
         "Akira Bjorn Carmen Dmitri Eva Farid Greta Hiro Ingrid Jamal Kofi Lena Mei Nadia Omar Priya").split()
LAST = ("Martin Bernard Dubois Thomas Robert Richard Petit Durand Leroy Moreau Simon Laurent Lefebvre Michel "
        "Garcia David Bertrand Roux Vincent Fournier Morel Girard Andre Mercier Dupont Lambert Bonnet Francois "
        "Martinez Legrand Garnier Faure Rousseau Blanc Guerin Muller Henry Roussel Nicolas Perrin Morin "
        "Mathieu Clement Gauthier Dumont Lopez Fontaine Chevalier Robin Tanaka Novak Silva Kowalski").split()
WORDS = ("lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut labore "
         "et dolore magna aliqua enim ad minim veniam quis nostrud exercitation ullamco laboris nisi aliquip "
         "ex ea commodo consequat duis aute irure in reprehenderit voluptate velit esse cillum fugiat nulla "
         "pariatur excepteur sint occaecat cupidatat non proident sunt culpa qui officia deserunt mollit anim "
         "id est laborum river night garden shadow empire winter silence stone mirror letters journey").split()
TITLE_WORDS = ("The A Of Last First Silent Hidden Red Blue Dark Long Little Great Secret Lost Broken Golden "
               "River Night Garden Shadow Empire Winter Silence Stone Mirror Letters Journey City Sea House "
               "Memory Fire Glass Paper Storm Island Road Kingdom Song Light").split()
COUNTRIES = "FR US GB DE ES IT JP CA BE CH NL SE BR IN".split()

REVIEW_EPOCH = dt.datetime(2015, 1, 1, tzinfo=dt.timezone.utc)
REVIEW_SPAN_SECONDS = 11 * 365 * 24 * 3600


def profile(name):
    p = PROFILES.get(name)
    if p is None:
        sys.exit(f"profile {name!r} is not implemented in phase 1 (extension point only)" if name in PROFILES
                 else f"unknown profile {name!r}, expected one of {sorted(PROFILES)}")
    return p


def rng(p, table):
    return random.Random(p["seed"] * 1000 + TABLE_SALT[table])


def prolific_ids(p):
    """Evenly spread, deterministic: always includes author 7 (inside GET /authors default page)."""
    step = p["authors"] // p["prolific"]
    return [7 + i * step for i in range(p["prolific"])]


def books_per_author(p):
    """Yields (author_id, n_books) in author id order. Same allocation for book and review tables."""
    r = rng(p, "alloc")
    prolific = set(prolific_ids(p))
    for author_id in range(1, p["authors"] + 1):
        if author_id in prolific:
            yield author_id, p["prolific_books"]
        else:
            x = r.random()
            yield author_id, 1 if x < 0.5 else (2 if x < 0.8 else 3)


def total_books(p):
    return sum(n for _, n in books_per_author(p))


def person(r):
    return f"{r.choice(FIRST)} {r.choice(LAST)}"


def text(r, n_words):
    return " ".join(r.choice(WORDS) for _ in range(n_words)).capitalize() + "."


def reviewer_pool(p):
    r = rng(p, "review")
    pool = [f"{person(r)} {i:05d}" for i in range(p["reviewers"])]
    pool[0] = "Alice Martin 00000"  # well-known value for /reviews/search (TRAP 4)
    return pool


def gen_publisher(p, w):
    r = rng(p, "publisher")
    for i in range(1, p["publishers"] + 1):
        w.writerow([i, f"{r.choice(TITLE_WORDS)} {r.choice(LAST)} Editions {i}", r.choice(COUNTRIES)])


def gen_author(p, w):
    r = rng(p, "author")
    for i in range(1, p["authors"] + 1):
        birth = dt.date(1900, 1, 1) + dt.timedelta(days=r.randrange(0, 100 * 365))
        w.writerow([i, person(r), birth.isoformat(), text(r, r.randint(10, 40)), None])


def gen_book(p, w):
    r = rng(p, "book")
    book_id = 0
    for author_id, n in books_per_author(p):
        for _ in range(n):
            book_id += 1
            title = " ".join(r.choice(TITLE_WORDS) for _ in range(r.randint(2, 6)))
            isbn = "978" + "".join(str(r.randrange(10)) for _ in range(10))
            year = r.randint(1900, 2026)
            description = text(r, r.randint(60, 140))  # ~400-900 chars: over-fetch is visible
            publisher = None if r.random() < 0.05 else r.randint(1, p["publishers"])
            w.writerow([book_id, f"{title} {book_id}", isbn, year, description, author_id, publisher])


def gen_review(p, w):
    pool = reviewer_pool(p)
    r = rng(p, "review")
    n_books = total_books(p)
    comments = [text(r, r.randint(12, 40)) for _ in range(2_000)]
    for i in range(1, p["reviews"] + 1):
        created = REVIEW_EPOCH + dt.timedelta(seconds=r.randrange(REVIEW_SPAN_SECONDS))
        w.writerow([i, r.randint(1, n_books), r.randint(1, 5), r.choice(pool), r.choice(comments),
                    created.isoformat()])


GENERATORS = {"publisher": gen_publisher, "author": gen_author, "book": gen_book, "review": gen_review}


def expect(p, name):
    """Facts derived from the generation parameters, used by tests (never by the application)."""
    n_books = total_books(p)
    return {
        "profile": name,
        "seed": p["seed"],
        "rows": {"publisher": p["publishers"], "author": p["authors"], "book": n_books, "review": p["reviews"]},
        "prolificAuthorIds": prolific_ids(p),
        "prolificBooks": p["prolific_books"],
        "knownReviewer": "Alice Martin 00000",
        "witnessPublisherId": 1,
    }


def main():
    if hasattr(signal, "SIGPIPE"):
        signal.signal(signal.SIGPIPE, signal.SIG_DFL)  # `| head` must not print a traceback
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--profile", required=True, choices=sorted(PROFILES))
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--table", choices=list(COLUMNS))
    g.add_argument("--columns", choices=list(COLUMNS), help="print the COPY column list of a table")
    g.add_argument("--expect", action="store_true", help="print expected facts as JSON")
    a = ap.parse_args()

    if a.columns:
        print(",".join(COLUMNS[a.columns]))
        return
    p = profile(a.profile)
    if a.expect:
        print(json.dumps(expect(p, a.profile), indent=2))
        return
    out = csv.writer(sys.stdout, lineterminator="\n")
    try:
        GENERATORS[a.table](p, out)
        sys.stdout.flush()
    except BrokenPipeError:
        sys.exit(1)


if __name__ == "__main__":
    main()
