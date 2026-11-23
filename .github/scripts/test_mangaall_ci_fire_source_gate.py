#!/usr/bin/env python3
"""No network: fail-closed contract for real Android source attestations."""
from mangaall_ci_fire_source_gate import evaluate, WORKS, SOURCES, SUMMARY

def fixture():
    rows = [SUMMARY]
    for work in WORKS:
        for source in SOURCES:
            rows.append(
                f"MANGAALL_CI_SOURCE|work={work}|source={source}"
                "|candidates=" + ("1" if source == "mangafire" else "0") + "|lookup=PARTIAL"
            )
        rows += [
            f"MANGAALL_CI_CHAPTER|work={work}|phase=LOOKUP"
            "|outcome=READ_ERROR|pages=3|candidates=1",
            f"MANGAALL_CI_CHAPTER|work={work}|phase=API"
            "|signedUrl=OBSERVED|http=200|transport=HTTP|json=VALIDATED",
            f"MANGAALL_CI_CHAPTER|work={work}|outcome=INCONCLUSIVE"
            "|pages=2|count=70|chapterOne=YES|reason=LOOKUP_INCOMPLETE",
            f"MANGAALL_CI_FIRE_SOURCE|work={work}|lookup=COMPLETE"
            "|api=VALIDATED|chapters=COMPLETE|chapterOne=YES|outcome=SOURCE_READY",
        ]
    return rows

def rejected(edits, expected):
    rows = fixture()
    for original, replacement in edits:
        i = next(i for i, row in enumerate(rows) if original in row)
        rows[i] = rows[i].replace(original, replacement)
    outcome = evaluate(rows)
    assert outcome[0] is False and outcome[1] == expected, outcome

good = fixture()
assert evaluate(good) == (True, "SIGNED_MANGAFIRE_3_SOURCE_CHAPTERS_ACCEPTED_UNIFIED_NOT_PROVEN")
assert evaluate(list(reversed(good)))[0] is True
rejected([("lookup=COMPLETE|api=VALIDATED", "lookup=INCOMPLETE|api=VALIDATED")], "FIRE_SOURCE_NOT_READY")
rejected([("chapters=COMPLETE|chapterOne=YES", "chapters=INCOMPLETE|chapterOne=YES")], "FIRE_SOURCE_NOT_READY")
rejected([("json=VALIDATED", "json=UNVERIFIED")], "SOURCE_JSON_NOT_PROVEN")
rejected([("signedUrl=OBSERVED", "signedUrl=NOT_OBSERVED")], "SOURCE_JSON_NOT_PROVEN")
rejected([("http=200|transport=HTTP", "http=403|transport=HTTP")], "SOURCE_JSON_NOT_PROVEN")
rejected([("chapterOne=YES|outcome=SOURCE_READY", "chapterOne=NO|outcome=INCONCLUSIVE")], "FIRE_SOURCE_NOT_READY")
rejected([("count=70", "count=0")], "SOURCE_CHAPTERS_NOT_ATTESTED")
rejected([("source=mangafire|candidates=1", "source=mangafire|candidates=2")], "FIRE_CANDIDATE_NOT_UNIQUE")
rejected([("phase=LOOKUP|outcome=READ_ERROR|pages=3|candidates=1",
           "phase=LOOKUP|outcome=READ_ERROR|pages=3|candidates=2")], "FIRE_LOOKUP_NOT_UNIQUE")
assert evaluate(good + [good[1]])[1] == "DUPLICATE_SOURCE"
assert evaluate(good + [good[-1]])[1] == "DUPLICATE_FIRE_PROOF"
assert evaluate([line for line in good if line != SUMMARY])[1] == "FIRE_SOURCE_SUMMARY_MISSING"
assert evaluate([line for line in good if "work=koe|source=mangadex" not in line])[1] == "THREE_SOURCE_MATRIX_MISSING"
assert evaluate([line for line in good if "work=punpun|phase=API" not in line])[1] == "SOURCE_EVIDENCE_MISSING"
# Source-only PASS is valid with an INCOMPLETE unified lookup, never a global PASS.
assert evaluate(good)[0] is True
# Reproduce the exact categorical failure from live run #45108738483,
# without fetching live network or copying private API payloads.
blocked = fixture()
blocked[0] = (
    "MANGAALL_CI_FIRE_SOURCE|outcome=INCONCLUSIVE|ready=0|works=3"
    "|canonical=NOT_TESTED|unified=NOT_PROVEN"
)
blocked = [
    line.replace("http=200|transport=HTTP|json=VALIDATED",
                 "http=403|transport=NONE|json=UNVERIFIED")
    if "work=punpun|phase=API" in line else line for line in blocked
]
blocked.append(
    "MANGAALL_CI_CHAPTER|work=punpun|phase=LOOKUP_FAILURE"
    "|cause=CHALLENGE_BLOCKED|http=403"
)
assert evaluate(blocked)[1] == "MANGAFIRE_UPSTREAM_HTTP403"

budget = fixture()
budget[0] = (
    "MANGAALL_CI_FIRE_SOURCE|outcome=INCONCLUSIVE|ready=2|works=3"
    "|canonical=NOT_TESTED|unified=NOT_PROVEN"
)
budget.extend([
    "MANGAALL_CI_CHAPTER|work=koe|phase=LOOKUP_FAILURE"
    "|cause=UNCLASSIFIED_ERROR|http=NONE",
    "MANGAALL_CI_LOOKUP_BLOCKER|work=koe|source=MANGADOT|reason=PAGE_FOUR_PENDING",
])
assert evaluate(budget)[1] == "MANGADOT_PAGE_FOUR_PENDING"
print("MANGAALL_CI_FIRE_SOURCE_CONTRACT|result=PASS|checks=19|live=NOT_STARTED|unified=NOT_PROVEN")
