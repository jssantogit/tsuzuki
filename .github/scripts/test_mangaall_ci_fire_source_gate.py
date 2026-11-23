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
            f"MANGAALL_CI_BROWSER_FALLBACK|work={work}|attempts=0|outcome=NOT_USED",
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
assert evaluate(good + [
    "MANGAALL_CI_BROWSER_FALLBACK|work=koe|attempts=0|outcome=NOT_USED"
])[1] == "DUPLICATE_BROWSER_FALLBACK"
assert evaluate([l for l in good if "work=koe|attempts=0|outcome=NOT_USED" not in l])[1] == "BROWSER_FALLBACK_EVIDENCE_MISSING"
rejected([("work=koe|attempts=0|outcome=NOT_USED",
           "work=koe|attempts=1|outcome=NOT_USED")], "BROWSER_FALLBACK_INCONSISTENT")
rejected([("work=koe|attempts=0|outcome=NOT_USED",
           "work=koe|attempts=0|outcome=SUCCEEDED")], "BROWSER_FALLBACK_INCONSISTENT")
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
assert evaluate(blocked)[1] == "MANGAFIRE_BROWSER_NOT_CALLED"
shape_required = blocked + [
    "MANGAALL_CI_FIRE_403|work=punpun|kind=SHAPE_REQUIRED",
]
assert evaluate(shape_required)[1] == "MANGAFIRE_SHAPE_REQUIRED_HTTP403"
assert evaluate(list(reversed(shape_required)))[1] == "MANGAFIRE_SHAPE_REQUIRED_HTTP403"
assert evaluate(shape_required + shape_required[-1:])[1] == "DUPLICATE_FIRE_403_CLASSIFICATION"
other_json = blocked + [
    "MANGAALL_CI_FIRE_403|work=punpun|kind=OTHER_JSON",
]
assert evaluate(other_json)[1] == "MANGAFIRE_BROWSER_NOT_CALLED"
unclassified = blocked + [
    "MANGAALL_CI_FIRE_403|work=punpun|kind=UNCLASSIFIED",
]
assert evaluate(unclassified)[1] == "MANGAFIRE_BROWSER_NOT_CALLED"
assert evaluate(good + shape_required[-1:])[1] == "FIRE_403_CLASSIFICATION_INCONSISTENT"
assert evaluate(blocked + [
    "MANGAALL_CI_FIRE_403|work=punpun|kind=NOT_OBSERVED",
])[1] == "FIRE_403_CLASSIFICATION_INCONSISTENT"

challenge = [
    line.replace("work=punpun|attempts=0|outcome=NOT_USED",
                 "work=punpun|attempts=1|outcome=UPSTREAM_CHALLENGE")
    for line in blocked
]
assert evaluate(challenge)[1] == "MANGAFIRE_BROWSER_UPSTREAM_CHALLENGE"
browser403 = [
    line.replace("work=punpun|attempts=1|outcome=UPSTREAM_CHALLENGE",
                 "work=punpun|attempts=1|outcome=HTTP_REJECTED")
    for line in challenge
]
assert evaluate(browser403)[1] == "MANGAFIRE_BROWSER_HTTP_REJECTED"
unsupported = [
    line.replace("work=punpun|attempts=1|outcome=UPSTREAM_CHALLENGE",
                 "work=punpun|attempts=1|outcome=WEBVIEW_UNSUPPORTED")
    for line in challenge
]
assert evaluate(unsupported)[1] == "MANGAFIRE_BROWSER_UNSUPPORTED"
browserSuccess = [
    line.replace("work=punpun|attempts=0|outcome=NOT_USED",
                 "work=punpun|attempts=1|outcome=SUCCEEDED").replace(
                 "work=punpun|phase=API|signedUrl=OBSERVED|http=200|transport=HTTP",
                 "work=punpun|phase=API|signedUrl=OBSERVED|http=403|transport=BROWSER")
    for line in good
]
assert evaluate(browserSuccess)[0] is True
browserFalsePositive = [
    line.replace("work=punpun|attempts=1|outcome=SUCCEEDED",
                 "work=punpun|attempts=1|outcome=HTTP_REJECTED")
    for line in browserSuccess
]
assert evaluate(browserFalsePositive)[1] == "BROWSER_SOURCE_NOT_PROVEN"

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
# A 403 edge family is a closed hint, never a WAF pass or a sourced title.
# The actual live parser REQUIRES this paired record; older pure unit
# fixtures keep require_edge=False to retain their intended failure reasons.
assert evaluate(good, require_edge=True)[0] is True
assert evaluate(blocked, require_edge=True)[1] == "FIRE_403_EDGE_EVIDENCE_MISSING"
paired_edge = blocked + [
    "MANGAALL_CI_FIRE_403|work=punpun|kind=UNCLASSIFIED",
    "MANGAALL_CI_FIRE_403_EDGE|work=punpun|family=CF_SERVER_HTML",
]
assert evaluate(paired_edge, require_edge=True)[1] == "MANGAFIRE_BROWSER_NOT_CALLED"
assert evaluate(paired_edge + paired_edge[-1:])[1] == "DUPLICATE_FIRE_403_EDGE"
assert evaluate(good + paired_edge[-1:])[1] == "FIRE_403_EDGE_INCONSISTENT"
contradictory = blocked + [
    "MANGAALL_CI_FIRE_403|work=punpun|kind=SHAPE_REQUIRED",
    "MANGAALL_CI_FIRE_403_EDGE|work=punpun|family=OTHER_HTML",
]
assert evaluate(contradictory, require_edge=True)[1] == "FIRE_403_KIND_EDGE_CONFLICT"
json_shape = blocked + [
    "MANGAALL_CI_FIRE_403|work=punpun|kind=SHAPE_REQUIRED",
    "MANGAALL_CI_FIRE_403_EDGE|work=punpun|family=JSON",
]
assert evaluate(json_shape, require_edge=True)[1] == "MANGAFIRE_SHAPE_REQUIRED_HTTP403"
non_shape = blocked + [
    "MANGAALL_CI_FIRE_403|work=punpun|kind=OTHER_JSON",
    "MANGAALL_CI_FIRE_403_EDGE|work=punpun|family=JSON",
]
assert evaluate(non_shape, require_edge=True)[1] == "MANGAFIRE_BROWSER_NOT_CALLED"
print("MANGAALL_CI_FIRE_SOURCE_CONTRACT|result=PASS|checks=44|live=NOT_STARTED|unified=NOT_PROVEN")
