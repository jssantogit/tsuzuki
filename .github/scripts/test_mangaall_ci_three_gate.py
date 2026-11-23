#!/usr/bin/env python3
"""Offline contract: no live traffic or Android emulator required."""
from mangaall_ci_three_gate import evaluate, WORKS, SOURCES, SUMMARY


def fixture():
    records = [SUMMARY]
    for work in WORKS:
        records.append(
            f"MANGAALL_CI_CHAPTER|work={work}|phase=LOOKUP|outcome=COMPLETE|pages=2|candidates=1"
        )
        for source in SOURCES:
            records.append(
                f"MANGAALL_CI_SOURCE|work={work}|source={source}|candidates=1|lookup=COMPLETE"
            )
        records.append(
            f"MANGAALL_CI_CHAPTER|work={work}|phase=API"
            "|signedUrl=OBSERVED|http=403|browser=VALID_JSON"
        )
        records.append(
            f"MANGAALL_CI_CHAPTER|work={work}|outcome=READY|pages=2"
            "|count=147|chapterOne=YES|reason=NONE"
        )
    return records


def reject_modified(line_number, replacement, expected):
    lines = fixture()
    lines[line_number] = replacement
    ok, reason = evaluate(lines)
    assert not ok, (replacement, reason)
    assert reason == expected, (reason, expected)


good = fixture()
assert evaluate(good) == (True, "SIGNED_CI_THREE_WORKS_ACCEPTED")
assert evaluate(list(reversed(good))) == (True, "SIGNED_CI_THREE_WORKS_ACCEPTED")
assert evaluate([line for line in good if not line.startswith(
    "MANGAALL_CI_SOURCE|work=koe|source=mangadot|"
)])[1] == "THREE_SOURCE_EVIDENCE_INCOMPLETE"
assert evaluate(good + ["MANGAALL_CI_SOURCE|work=punpun|source=mangafire|candidates=1|lookup=COMPLETE"])[1] == "DUPLICATE_SOURCE_VERDICT"
assert evaluate(good + [good[1]])[1] == "DUPLICATE_LOOKUP_VERDICT"
assert evaluate(good + [good[5]])[1] == "DUPLICATE_API_VERDICT"
assert evaluate(good + [good[6]])[1] == "DUPLICATE_CHAPTER_VERDICT"
assert evaluate([line for line in good if line != SUMMARY])[1] == "THREE_WORK_SUMMARY_MISSING"
assert evaluate([line for line in good if "work=akame|phase=LOOKUP" not in line])[1] == "LOOKUP_NOT_COMPLETE_AND_UNIQUE"

# An inconclusive summary must not hide the first actual broken source
# continuation from the operator behind a generic summary-missing label.
partial = fixture()
partial[0] = (
    "MANGAALL_CI_CHAPTER|outcome=INCONCLUSIVE|ready=0|works=3"
    "|canonical=NOT_TESTED|readerPixels=NOT_TESTED"
)
partial[1] = partial[1].replace("outcome=COMPLETE", "outcome=READ_ERROR")
assert evaluate(partial)[1] == "LOOKUP_NOT_COMPLETE_AND_UNIQUE"

reject_modified(1, good[1].replace("outcome=COMPLETE", "outcome=READ_ERROR"), "LOOKUP_NOT_COMPLETE_AND_UNIQUE")
reject_modified(1, good[1].replace("candidates=1", "candidates=2"), "LOOKUP_NOT_COMPLETE_AND_UNIQUE")
reject_modified(4, good[4].replace("candidates=1", "candidates=2"), "MANGAFIRE_CANDIDATE_NOT_UNIQUE")
reject_modified(4, good[4].replace("lookup=COMPLETE", "lookup=PARTIAL"), "THREE_SOURCE_EVIDENCE_INCOMPLETE")
reject_modified(5, good[5].replace("browser=VALID_JSON", "browser=INCONCLUSIVE"), "SIGNED_API_NOT_VALIDATED")
reject_modified(5, good[5].replace("signedUrl=OBSERVED", "signedUrl=NOT_OBSERVED"), "SIGNED_API_NOT_VALIDATED")
reject_modified(6, good[6].replace("outcome=READY", "outcome=INCONCLUSIVE"), "CHAPTER_PAGINATION_INCOMPLETE")
reject_modified(6, good[6].replace("chapterOne=YES", "chapterOne=NO"), "CHAPTER_PAGINATION_INCOMPLETE")

print("MANGAALL_CI_THREE_CONTRACT|result=PASS|checks=17|network=NOT_STARTED")
