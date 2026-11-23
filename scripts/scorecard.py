#!/usr/bin/env python3
"""Trap scorecard of the demo app (demo-app/TRAPS.md).

    scripts/scorecard.py --url http://localhost:9465 [--agent http://localhost:9464] [--window 1h]
                         [--markdown out.md] [--json out.json]

Phase 2 rules read the collector API (database side). With --agent, the phase 3 rules also read the
agent's findings (/fq/findings, JPA side) and each trap keeps the best level of both; the expected
levels are then the phase 3 ones. Each trap gets DETECTED, PARTIAL or MISSED with its evidence; the
witness gets CLEAN or FLAGGED (by either side). Exit code 1 when a trap scores below its expected level
or the witness is flagged. Standard library only (runs in the tools image and on a laptop).
"""
import argparse
import json
import sys
import urllib.parse
import urllib.request

DETECTED, PARTIAL, MISSED, CLEAN, FLAGGED = "DETECTED", "PARTIAL", "MISSED", "CLEAN", "FLAGGED"
RANK = {MISSED: 0, PARTIAL: 1, DETECTED: 2}

# Level each phase must reach (ground truth: demo-app/TRAPS.md, columns "Phase 2" and "Phase 3").
EXPECTED_PHASE2 = {1: DETECTED, 2: DETECTED, 3: PARTIAL, 4: DETECTED, 5: PARTIAL, 6: PARTIAL, 7: DETECTED}
EXPECTED_PHASE3 = {1: DETECTED, 2: DETECTED, 3: DETECTED, 4: DETECTED, 5: DETECTED, 6: DETECTED, 7: DETECTED}
EXPECTED = EXPECTED_PHASE2

TITLES = {
    1: "N+1 authors -> books",
    2: "@ManyToOne EAGER publisher",
    3: "Over-fetching (book description)",
    4: "Missing index on review.reviewer_name",
    5: "JOIN FETCH + pagination",
    6: "open-in-view",
    7: "Sort spilling to disk",
    0: "Witness GET /publishers/{id}",
}


class Api:
    def __init__(self, url, window):
        self.url = url.rstrip("/")
        self.window = window

    def get(self, path, **params):
        q = urllib.parse.urlencode(params)
        with urllib.request.urlopen(f"{self.url}{path}?{q}", timeout=30) as r:
            return json.load(r)


class Data:
    def __init__(self, api):
        self.api = api
        self.queries = api.get("/fq/collector/queries", window=api.window, order="time", limit=300)["queries"]
        self._plans = {}
        self._columns = {}

    def statement(self, endpoint, *sql_parts):
        """The statement whose SQL contains every part and that ran for the endpoint (exact attribution)."""
        for q in self.queries:
            sql = q.get("sql") or ""
            if all(p in sql for p in sql_parts) and endpoint in endpoints_of(q):
                return q
        return None

    def plans(self, q):
        if q is None:
            return []
        qid = q["queryid"]
        if qid not in self._plans:
            self._plans[qid] = self.api.get("/fq/collector/plans", queryid=qid)["plans"]
        return self._plans[qid]

    def column(self, table, column):
        if table not in self._columns:
            self._columns[table] = self.api.get("/fq/collector/columns", table=table)["columns"]
        return next((c for c in self._columns[table] if c["column"] == column), None)


def endpoints_of(q):
    if q.get("attribution"):
        return {a["endpoint"] for a in q["attribution"]}
    return {q.get("endpoint")}


def executions(q, endpoint):
    """Executions of statement q for one endpoint (auto_explain attribution), else its calls."""
    if q is None:
        return 0
    for a in q.get("attribution") or []:
        if a["endpoint"] == endpoint:
            return a["executions"]
    return q["calls"] if q.get("endpoint") == endpoint else 0


def caller(q, endpoint):
    for a in (q or {}).get("attribution") or []:
        if a["endpoint"] == endpoint:
            return a["caller"]
    return (q or {}).get("caller")


def summaries(d, q):
    return [p["summary"] for p in d.plans(q)]


def short(caller_name):
    return (caller_name or "?").rsplit(".", 1)[-1]


# ---------------------------------------------------------------------------------------------- traps

def trap1(d):
    page = d.statement("GET /authors", "from author a1_0 order by")
    books = d.statement("GET /authors", "from book b1_0", "where b1_0.author_id=$1")
    if not page or not books:
        return MISSED, "authors page or per-author book statement not seen for GET /authors"
    ratio = executions(books, "GET /authors") / max(1, executions(page, "GET /authors"))
    ev = f"{ratio:.1f} book queries per authors page, caller {short(caller(books, 'GET /authors'))}"
    return (DETECTED if ratio >= 5 else MISSED), ev


def trap2(d):
    lookup = next((q for q in d.queries if "from publisher p1_0 where p1_0.id=$1" in (q.get("sql") or "")
                   and "GET /publishers/{id}" not in endpoints_of(q)), None)
    if not lookup:
        return MISSED, "publisher lookup by id not seen"
    eps = sorted(e for e in endpoints_of(lookup) if e)
    page = d.statement("GET /books", "from book b1_0 order by")
    ratio = executions(lookup, "GET /books") / max(1, executions(page, "GET /books"))
    ev = f"publisher loaded one by one ({lookup['calls']} lookups) on {len(eps)} endpoints: {', '.join(eps)}; " \
         f"{ratio:.1f} per GET /books page"
    exact = lookup.get("attributionSource") == "auto_explain"
    return (DETECTED if ratio >= 5 and len(eps) >= 2 and exact else PARTIAL), ev


def trap3(d):
    q = d.statement("GET /books/titles", "from book b1_0", "b1_0.description")
    if not q:
        return MISSED, "book statement of GET /books/titles not seen"
    return PARTIAL, ("full entity selected (description TEXT included) for a titles endpoint; "
                     "serialized fields are only known to phase 3")


def trap4(d):
    q = d.statement("GET /reviews/search", "from review r1_0", "reviewer_name=$1")
    if not q:
        return MISSED, "review search statement not seen"
    scans = [s for sm in summaries(d, q) for s in sm.get("filteredScans", []) + sm.get("seqScans", [])
             if s.get("relation") == "review" and "reviewer_name" in (s.get("filter") or "")]
    col = d.column("review", "reviewer_name")
    if not scans:
        return MISSED, "no plan with a filter on review.reviewer_name (plans not collected?)"
    scan = scans[0]
    how = scan.get("node", "Seq Scan") + (f" on {scan['index']}" if scan.get("index") else "")
    ev = f"{how} discarding rows through Filter {scan.get('filter')}, mean {q['meanTimeMs']} ms"
    if col and col.get("rowsPerValue") is not None and col.get("estimatedRows"):
        ev += f"; column selective: ~{col['rowsPerValue']:.0f} rows per value out of {col['estimatedRows']}"
        selective = col["rowsPerValue"] < 0.01 * col["estimatedRows"]
        return (DETECTED if selective else PARTIAL), ev
    return PARTIAL, ev + "; no pg_stats yet for the column"


def trap5(d):
    fetch = d.statement("GET /authors/with-books", "join book b1_0")
    count = d.statement("GET /authors/with-books", "select count(a1_0.id) from author a1_0")
    if not fetch:
        return MISSED, "author + books fetch not seen"
    limit = any(s.get("hasLimit") for s in summaries(d, fetch))
    rows = fetch["rows"] / max(1, fetch["calls"])
    if count and not limit:
        return PARTIAL, (f"paginated endpoint (count query) but the collection fetch has no LIMIT: "
                         f"{rows:.0f} rows per call; the HHH90003004 warning is in the application logs")
    return MISSED, f"no LIMIT-less fetch next to a count query (hasLimit={limit})"


def trap6(d):
    books = d.statement("GET /authors", "from book b1_0", "where b1_0.author_id=$1")
    c = caller(books, "GET /authors")
    if c and "Controller#" in c:
        return PARTIAL, f"lazy loading run from the web layer ({short(c)}): hint of open-in-view"
    return MISSED, "no lazy loading seen in a controller"


def trap7(d):
    q = d.statement("GET /reviews/ranking", "from review r1_0 order by")
    if not q:
        return MISSED, "ranking statement not seen"
    sorts = [s for sm in summaries(d, q) for s in sm.get("sorts", [])]
    ev = f"temp_blks_written={q['tempBlksWritten']} for {q['calls']} calls, mean {q['meanTimeMs']} ms"
    if sorts:
        ev += f", Sort on {', '.join(sorts[0].get('keys') or [])}"
    return (DETECTED if q["tempBlksWritten"] > 0 and sorts else PARTIAL if q["tempBlksWritten"] > 0 else MISSED), ev


def witness(d):
    q = d.statement("GET /publishers/{id}", "from publisher p1_0 where p1_0.id=$1")
    if not q:
        return FLAGGED, "witness statement not seen (no traffic?)"
    problems = []
    for sm in summaries(d, q):
        if sm.get("seqScans") or sm.get("filteredScans"):
            problems.append("scan with filter")
        if sm.get("sorts"):
            problems.append("sort")
    if q["tempBlksWritten"]:
        problems.append("temp blocks")
    if len(endpoints_of(q)) != 1:
        problems.append("shared with other endpoints")
    if problems:
        return FLAGGED, ", ".join(sorted(set(problems)))
    nodes = sorted({n for sm in summaries(d, q) for n in sm.get("nodeTypes", [])})
    return CLEAN, f"{'+'.join(nodes) or 'no plan'}, mean {q['meanTimeMs']} ms, never flagged"


TRAPS = [(1, trap1), (2, trap2), (3, trap3), (4, trap4), (5, trap5), (6, trap6), (7, trap7), (0, witness)]


# ---------------------------------------------------------------------------------------------- phase 3 (agent)

class Agent:
    def __init__(self, url):
        self.url = url.rstrip("/")
        with urllib.request.urlopen(f"{self.url}/fq/findings", timeout=30) as r:
            self.findings = json.load(r)["findings"]

    def find(self, endpoint, kind, **evidence):
        for f in self.findings:
            if f["endpoint"] != endpoint or f["kind"] != kind:
                continue
            ev = f.get("evidence", {})
            if all(v in (ev.get(k) if isinstance(ev.get(k), (list, str)) else [ev.get(k)]) for k, v in evidence.items()):
                return f
        return None


def agent_rule(endpoint, kind, **evidence):
    def rule(agent):
        f = agent.find(endpoint, kind, **evidence)
        if f is None:
            return MISSED, f"no {kind} finding on {endpoint}"
        return DETECTED, f"{f['severity']} {kind}: {f['title']}"
    return rule


AGENT_TRAPS = {
    1: agent_rule("GET /authors", "N_PLUS_ONE_LAZY", association="Author.books"),
    2: agent_rule("GET /books", "USELESS_EAGER", association="Book.publisher"),
    3: agent_rule("GET /books/titles", "OVER_FETCHING", largeColumnsNeverRead="description"),
    5: agent_rule("GET /authors/with-books", "IN_MEMORY_PAGINATION"),
    6: agent_rule("GET /authors", "OPEN_IN_VIEW"),
}


def agent_witness(agent):
    flagged = [f["kind"] for f in agent.findings if f["endpoint"] == "GET /publishers/{id}"]
    return (FLAGGED, "agent findings: " + ", ".join(flagged)) if flagged else (CLEAN, "no agent finding")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", default="http://localhost:9465")
    ap.add_argument("--agent", help="agent base URL: adds the phase 3 rules (JPA findings)")
    ap.add_argument("--window", default="1h")
    ap.add_argument("--markdown")
    ap.add_argument("--json")
    a = ap.parse_args()

    d = Data(Api(a.url, a.window))
    agent = Agent(a.agent) if a.agent else None
    expected_levels = EXPECTED_PHASE3 if agent else EXPECTED_PHASE2
    phase = 3 if agent else 2
    rows, failures = [], 0
    for n, fn in TRAPS:
        try:
            status, evidence = fn(d)
        except Exception as e:  # one broken rule never hides the others
            status, evidence = (FLAGGED if n == 0 else MISSED), f"error: {e}"
        if agent is not None:
            rule = agent_witness if n == 0 else AGENT_TRAPS.get(n)
            if rule is not None:
                try:
                    a_status, a_evidence = rule(agent)
                except Exception as e:
                    a_status, a_evidence = (FLAGGED if n == 0 else MISSED), f"agent error: {e}"
                if n == 0:
                    if a_status == FLAGGED or status == FLAGGED:
                        status = FLAGGED
                    evidence = f"{evidence}; {a_evidence}"
                elif RANK[a_status] > RANK[status]:
                    status, evidence = a_status, f"{a_evidence} [database side: {evidence}]"
                else:
                    evidence = f"{evidence} [agent: {a_evidence}]"
        expected = CLEAN if n == 0 else expected_levels[n]
        ok = status == CLEAN if n == 0 else RANK[status] >= RANK[expected]
        failures += 0 if ok else 1
        rows.append(dict(trap=n or "witness", title=TITLES[n], status=status, expected=expected, ok=ok,
                         evidence=evidence))

    for r in rows:
        mark = "ok " if r["ok"] else "LOW"
        print(f"  {mark} {str(r['trap']):>7}  {r['status']:<9} (expected {r['expected']:<8})  {r['title']}")
        print(f"                {r['evidence']}")
    found = sum(1 for r in rows if r["status"] in (DETECTED, PARTIAL))
    full = sum(1 for r in rows if r["status"] == DETECTED)
    print(f"\n  phase {phase}: {full}/7 traps detected, {found - full} partial, witness "
          f"{rows[-1]['status'].lower()}; {failures} below expectation")

    if a.markdown:
        with open(a.markdown, "w") as f:
            f.write("| # | Trap | Phase 2 | Expected | Evidence |\n|---|---|---|---|---|\n")
            for r in rows:
                f.write(f"| {r['trap']} | {r['title']} | {r['status']} | {r['expected']} | {r['evidence']} |\n")
    if a.json:
        with open(a.json, "w") as f:
            json.dump(rows, f, indent=2)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
