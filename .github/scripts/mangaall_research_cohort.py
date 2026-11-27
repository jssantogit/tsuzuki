"""Immutable research profiles; never replace a previously validated cohort."""
import hashlib
import json
import unicodedata
from dataclasses import dataclass
from pathlib import Path

from run_mangaall_frozen30_independent_catalog import DIGEST, locked_works

@dataclass(frozen=True)
class Cohort:
    key: str
    format: str
    digest: str
    plan: Path
    output: Path
    works: tuple[str, ...]
    required_credits: dict[int, tuple[str, ...]]

def load_cohort(key="manhwa"):
    if key == "manhwa":
        return Cohort(key, "manhwa", DIGEST,
                      Path(".github/mangaall-frozen30-catalog-plan.json"),
                      Path(".github/results/mangaall-frozen30-catalog-plan"),
                      tuple(locked_works()), {})
    if key != "manga":
        raise ValueError("UNKNOWN_COHORT")
    doc = json.loads(Path(".github/mangaall-japanese-manga-30-cohort-lock.json").read_text())
    works = doc["works"]
    expected = "5fbf70722e1ed87e2d9847f6b138728115b77be14dae64ac9730cb72c9d1dcc9"
    assert doc["cohort"] == "MANGA_30_FIXED_20261126"
    assert doc["format"] == "manga" and doc["originalLanguage"] == "ja"
    assert doc["freezeUntil"] == "THIRTY_OF_THIRTY_VERIFIED_AND_EXPLICITLY_APPROVED"
    assert len(works) == len(set(works)) == 30 and not set(works).intersection(locked_works())
    assert hashlib.sha256(json.dumps(works, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest() == expected
    # Rejection-only controls for adaptations. The catalog still establishes
    # identity and actual credits; hints cannot create a binding or select a HID.
    return Cohort(key, "manga", expected,
                  Path(".github/mangaall-japanese-manga-30-catalog-plan.json"),
                  Path(".github/results/mangaall-japanese-manga-30-catalog-plan"),
                  tuple(works), {
                      1: ("Ichiei Ishibumi", "Hiroji Mishima"),
                      3: ("Tetsuto Uesu", "Miyakokasiwa"),
                      5: ("Koushi Tachibana", "Sekihiko Inui"),
                      25: ("Ichigo Takano",),
                  })

def creator_key(name):
    value = unicodedata.normalize("NFKD", name).casefold()
    # Exact tokens, with family/given name order tolerated; no fuzzy matching.
    normalized = "".join(c if c.isalnum() else " " if not unicodedata.combining(c) else "" for c in value)
    return tuple(sorted(normalized.split()))

def edition_credits_match(record, expected):
    if not expected:
        return True
    observed = {creator_key(name) for name in record["authors"] + record["artists"]}
    return all(creator_key(name) in observed for name in expected)
