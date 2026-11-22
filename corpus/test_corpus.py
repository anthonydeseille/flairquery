"""Corpus unit tests (stdlib unittest): python3 -m unittest discover -s corpus -p 'test_*.py'"""
import csv
import gzip
import hashlib
import io
import json
import subprocess
import sys
import tempfile
import unittest
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent


def run(*args, script="generate.py"):
    return subprocess.run([sys.executable, "-I", str(HERE / script), *args], check=True,
                          capture_output=True).stdout


def rows(table, profile="S"):
    return list(csv.reader(io.StringIO(run("--profile", profile, "--table", table).decode())))


class GenerateTest(unittest.TestCase):

    def test_output_is_deterministic(self):
        for table in ("author", "review"):
            a = hashlib.sha256(run("--profile", "S", "--table", table)).hexdigest()
            b = hashlib.sha256(run("--profile", "S", "--table", table)).hexdigest()
            self.assertEqual(a, b, table)

    def test_counts_match_expect_and_profile_s_is_about_100k_rows(self):
        expect = json.loads(run("--profile", "S", "--expect"))
        total = 0
        for table, n in expect["rows"].items():
            r = rows(table)
            self.assertEqual(len(r), n, table)
            self.assertEqual([int(x[0]) for x in r], list(range(1, n + 1)), f"{table} ids")
            total += n
        self.assertTrue(90_000 <= total <= 110_000, total)

    def test_skewed_distribution(self):
        expect = json.loads(run("--profile", "S", "--expect"))
        per_author = Counter(int(r[5]) for r in rows("book"))
        for author_id in expect["prolificAuthorIds"]:
            self.assertEqual(per_author[author_id], expect["prolificBooks"])
        others = [n for a, n in per_author.items() if a not in expect["prolificAuthorIds"]]
        self.assertTrue(all(1 <= n <= 3 for n in others))
        self.assertIn(7, expect["prolificAuthorIds"])  # inside GET /authors default page

    def test_foreign_keys_are_valid(self):
        expect = json.loads(run("--profile", "S", "--expect"))
        n_books = expect["rows"]["book"]
        for r in rows("review"):
            self.assertTrue(1 <= int(r[1]) <= n_books)
            self.assertTrue(1 <= int(r[2]) <= 5)
        self.assertTrue(any(r[3] == expect["knownReviewer"] for r in rows("review")))

    def test_profile_l_is_an_explicit_extension_point(self):
        p = subprocess.run([sys.executable, "-I", str(HERE / "generate.py"), "--profile", "L", "--table", "book"],
                           capture_output=True, text=True)
        self.assertNotEqual(p.returncode, 0)
        self.assertIn("not implemented in phase 1", p.stderr)


class OpenLibraryMappingTest(unittest.TestCase):

    def test_subset_maps_to_schema_with_disjoint_ids(self):
        records = [
            {"type": "meta"},
            {"type": "author", "key": "/authors/OL1A", "name": "Ada", "birth_date": "1815", "bio": {"type": "/type/text", "value": "x"}},
            {"type": "work", "key": "/works/OL1W", "title": "Notes", "author": "/authors/OL1A", "description": None,
             "first_publish_date": "March 1843", "edition": {"isbn": "9780000000001", "publisher": "Taylor", "country": "enk", "publish_date": "1843"}},
            {"type": "work", "key": "/works/OL2W", "title": "Orphan", "author": "/authors/OL9A", "description": "d",
             "first_publish_date": None, "edition": None},
            {"type": "rating", "work": "/works/OL1W", "rating": "5", "date": "2020-01-02"},
            {"type": "rating", "work": "/works/OL1W", "rating": "bad", "date": "2020-01-02"},
        ]
        with tempfile.TemporaryDirectory() as d:
            subset = Path(d) / "subset.jsonl.gz"
            with gzip.open(subset, "wt") as f:
                f.writelines(json.dumps(r) + "\n" for r in records)

            def table(name):
                out = run("--subset", str(subset), "--table", name, script="openlibrary/to_csv.py").decode()
                return list(csv.reader(io.StringIO(out)))

            authors, books, publishers, reviews = table("author"), table("book"), table("publisher"), table("review")
        self.assertEqual(len(authors), 2)  # Ada + placeholder for the missing OL9A
        self.assertTrue(all(int(r[0]) > 50_000_000 for r in authors + books + publishers + reviews))
        self.assertEqual(authors[1][1], "Unknown (OL9A)")
        self.assertEqual(books[0][3], "1843")
        self.assertEqual(books[0][6], publishers[0][0])
        self.assertEqual(len(reviews), 1)


if __name__ == "__main__":
    unittest.main()
