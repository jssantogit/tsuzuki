#!/usr/bin/env python3
"""Build a NONSHIPPING, unsigned MangaALL code11 research ZIP.

Reuses the exact hash-verified original code10 package MODULE graph and the
pre-existing code11 native MangaDot chapter-snapshot wrapper. Only a two-source
router/permission view and bounded source page sizes are patched IN ZIP BYTES.
No file in providers-tsuzuki/dist, signing key, public index or release moves.
"""
from __future__ import annotations
import argparse
import hashlib
import io
import json
from pathlib import Path
import sys
import zipfile

SOURCE_REPO_SHA = "89b5979fff297c2a099359258c9f9249e0089db4"
EXPECT_VERSION = {"name": "0.6.0-unsigned-research", "code": 11}
TWO_ORIGINS = {
    "https://api.mangadex.org",
    "https://uploads.mangadex.org",
    "https://*.mangadex.network",
    "https://mangadot.net",
}


def change_one(blob: bytes, old: bytes, new: bytes, label: str) -> bytes:
    if blob.count(old) != 1:
        raise ValueError("SOURCE_PIN_DRIFT_" + label)
    return blob.replace(old, new, 1)


def candidate_bytes(source_root: Path, manhwa_only: bool = False,
                    mangadot_creators: bool = False,
                    original_language: str | None = None,
                    mangadex_internal_reading: bool = False) -> bytes:
    if original_language not in (None, "ko", "ja"):
        raise ValueError("INVALID_RESEARCH_ORIGINAL_LANGUAGE")
    if manhwa_only and original_language not in (None, "ko"):
        raise ValueError("CONFLICTING_RESEARCH_ORIGINAL_LANGUAGE")
    language = "ko" if manhwa_only else original_language
    sys.path.insert(0, str(source_root / "tooling"))
    from mangaall_future_code11_candidate import unsigned_candidate_zip
    origin = unsigned_candidate_zip(source_root)
    with zipfile.ZipFile(io.BytesIO(origin)) as zf:
        if zf.testzip() is not None:
            raise ValueError("CODE11_SOURCE_ZIP_INTEGRITY")
        files = {info.filename: zf.read(info) for info in zf.infolist()}
    expected = {"manifest.json", "main.js", "modules/router.js",
                "modules/v2-candidate.js",
                "modules/sources/mangadot/future-main.js",
                "modules/sources/mangadot/future-native-bridge.js",
                "modules/sources/mangadot/modules/mangadot.js",
                "modules/sources/mangadex/modules/mangadex.js"}
    if not expected.issubset(files) or len(files) > 200:
        raise ValueError("CODE11_SOURCE_GRAPH_DRIFT")

    files["main.js"] = change_one(
        files["main.js"],
        b'import mangaFire from "./modules/sources/mangafire/main.js";',
        b"/* No MangaFire import in this unsigned two-source candidate. */",
        "MAIN_IMPORT",
    )
    files["main.js"] = change_one(
        files["main.js"],
        b"    mangafire: mangaFire.reading,",
        b"    // MangaFire omitted from candidate adapters.",
        "MAIN_ADAPTER",
    )
    files["modules/router.js"] = change_one(
        files["modules/router.js"],
        b'const SOURCE_IDS = Object.freeze(["mangadex", "mangadot", "mangafire"]);',
        b'const SOURCE_IDS = Object.freeze(["mangadex", "mangadot"]);',
        "ROUTER_IDS",
    )
    files["modules/v2-candidate.js"] = change_one(
        files["modules/v2-candidate.js"],
        b'export const SOURCE_ORDER = Object.freeze(["mangadex", "mangadot", "mangafire"]);',
        b'export const SOURCE_ORDER = Object.freeze(["mangadex", "mangadot"]);',
        "V2_ORDER",
    )
    files["modules/sources/mangadot/modules/mangadot.js"] = change_one(
        files["modules/sources/mangadot/modules/mangadot.js"],
        b'if (n > 3) throw new Error("MangaAll bounded source search exhausted");',
        b'if (n > 32) throw new Error("MangaAll bounded source search exhausted");',
        "MANGADOT_LOOKUP",
    )
    # The real MangaDot API may report more than 32 pages for broad terms.
    # A valid large total must NOT be an invalid response. Still fail closed on
    # source cursor 33 and never convert that bounded scan into COMPLETE.
    dot_name = "modules/sources/mangadot/modules/mangadot.js"
    files[dot_name] = change_one(
        files[dot_name],
        b'(total != null && (!Number.isInteger(total) || total < 0 || total > 32))',
        b'(total != null && (!Number.isInteger(total) || total < 0 || total > 4096))',
        "MANGADOT_TOTAL_BUDGET",
    )
    files[dot_name] = change_one(
        files[dot_name],
        b'(hasNext && (rows.length === 0 || page >= 32)))',
        b'(hasNext && rows.length === 0))',
        "MANGADOT_PAGE32_PARTIAL",
    )
    files["modules/sources/mangadex/modules/mangadex.js"] = change_one(
        files["modules/sources/mangadex/modules/mangadex.js"],
        b"const CHAPTER_PAGE_SIZE = 100;",
        b"const CHAPTER_PAGE_SIZE = 50;",
        "MANGADEX_CHAPTERS",
    )
    if language is not None:
        # Only explicit research cohorts filter original language.
        lang = language.encode("ascii")
        dex_name = "modules/sources/mangadex/modules/mangadex.js"
        # Real CI wire observation #45396414321 confirmed the native API
        # filter preserves all four exact Korean controls while shrinking
        # unrelated results. Keep the existing response-language guard too.
        files[dex_name] = change_one(
            files[dex_name],
            b'["title", title], ["limit", SEARCH_PAGE_SIZE], ["offset", offset],',
            b'["title", title], ["originalLanguage[]", "' + lang + b'"], ["limit", SEARCH_PAGE_SIZE], ["offset", offset],',
            "MANHWA_MANGADEX_NATIVE_ORIGINAL_KO",
        )
        files[dex_name] = change_one(
            files[dex_name],
            b'if (item?.type !== "manga" || !ID_PATTERN.test(item.id || "") || seen.has(item.id)) continue;',
            b'if (item?.type !== "manga" || !ID_PATTERN.test(item.id || "") || item?.attributes?.originalLanguage !== "' + lang + b'" || seen.has(item.id)) continue;',
            "MANHWA_MANGADEX_ORIGINAL_KO",
        )
        # Retrieval-only normalization for short article-prefixed searches.
        # The original exact title remains the identity/enrichment predicate.
        # A uniform source cursor ceiling never changes the Host's per-call
        # page, time or result budgets; exhaustion is not COMPLETE.
        policy = Path(__file__).with_name("mangaall_mangadot_search_policy.mjs")
        files["modules/sources/mangadot/modules/search-policy.js"] = policy.read_bytes()
        files[dot_name] = (
            b'import { mangaDotRetrievalQuery, mangaDotResearchPageLimit } from "./search-policy.js";\n'
            + files[dot_name]
        )
        files[dot_name] = change_one(
            files[dot_name],
            b'["search", title], ["page", page], ["limit", 56],',
            b'["search", mangaDotRetrievalQuery(title)], ["page", page], ["limit", 56],',
            "MANHWA_MANGADOT_GENERIC_QUERY",
        )
        files[dot_name] = change_one(
            files[dot_name],
            b'if (n > 32) throw new Error("MangaAll bounded source search exhausted");',
            b'if (n > mangaDotResearchPageLimit()) throw new Error("MangaAll bounded source search exhausted");',
            "MANHWA_DOT_UNIFORM_CURSOR_BOUND",
        )
    if mangadex_internal_reading:
        from mangaall_mangadex_reading_contract import patch_mangadex_reading_contract
        dex_name = "modules/sources/mangadex/modules/mangadex.js"
        files[dex_name] = patch_mangadex_reading_contract(files[dex_name])
    if mangadot_creators and not mangadex_internal_reading:
        # The explicit catalog live requires both writer and illustrator.
        # Keep this metadata-only patch independent of Reader delivery policy.
        from mangaall_mangadex_reading_contract import patch_mangadex_creator_contract
        dex_name = "modules/sources/mangadex/modules/mangadex.js"
        files[dex_name] = patch_mangadex_creator_contract(files[dex_name])
    if mangadot_creators:
        # Separate opt-in; the five previous source-only live probes keep
        # exactly their original package shape. No authority or chapter
        # coverage is fabricated by the detail enrichment.
        creator_module = Path(__file__).with_name("mangaall_mangadot_creator_evidence.mjs")
        files["modules/sources/mangadot/modules/creator-evidence.js"] = creator_module.read_bytes()
        files[dot_name] = (
            b'import { enrichMangaDotLookupCandidates } from "./creator-evidence.js";\n'
            + files[dot_name]
        )
        files[dot_name] = change_one(
            files[dot_name],
            b'  return { items, nextCursor: hasNext ? String(page + 1) : null };',
            b'  if (input?.editorialEvidenceVersion === 1) {\n'
            b'    await enrichMangaDotLookupCandidates(items, title, globalThis.tsuzuki, input?.catalogEvidenceVersion === 1);\n'
            b'  }\n'
            b'  return { items, nextCursor: hasNext ? String(page + 1) : null };',
            "MANGADOT_CREATOR_ENRICHMENT",
        )
    for name in tuple(files):
        if name.startswith("modules/sources/mangafire/"):
            del files[name]

    manifest = json.loads(files["manifest.json"])
    if (
        manifest.get("id") != "app.tsuzuki.mangaall"
        or manifest.get("version") != EXPECT_VERSION
        or manifest.get("minHostApi") != 2
        or manifest.get("permissions", {})
          .get("mangaAllChapterSnapshot") != {"enabled": True}
        or "publication" in manifest
    ):
        raise ValueError("CODE11_UNSIGNED_MANIFEST_INVALID")
    perms = manifest["permissions"]
    if not isinstance(perms.get("network", {}).get("origins"), list):
        raise ValueError("NETWORK_GRANTS_MISSING")
    if not TWO_ORIGINS.issubset(set(perms["network"]["origins"])):
        raise ValueError("ORIGINAL_SOURCE_NETWORK_GRANTS_MISSING")
    perms["network"]["origins"] = sorted(TWO_ORIGINS)
    perms.pop("browser", None)
    manifest["name"] = ({
        "ko": "MangaAll — frozen 30 manhwa source research",
        "ja": "MangaAll — frozen 30 manga source research",
    }.get(language, "MangaAll — two-source unsigned research"))
    files["manifest.json"] = json.dumps(
        manifest, ensure_ascii=False, indent=2
    ).encode("utf-8")
    if "mangafire" in str(perms).lower() or "mfcdn" in str(perms).lower():
        raise ValueError("FORBIDDEN_THIRD_SOURCE_GRANT")
    if any("mangafire" in name.lower() for name in files):
        raise ValueError("FORBIDDEN_THIRD_SOURCE_ASSET")
    if len(files) > 200 or any(len(value) > 2 * 1024 * 1024 for value in files.values()):
        raise ValueError("UNSIGNED_PACKAGE_ENTRY_TOO_LARGE")
    with io.BytesIO() as out:
        with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zipf:
            for name, data in sorted(files.items()):
                part = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                part.compress_type = zipfile.ZIP_DEFLATED
                part.external_attr = 0o100644 << 16
                zipf.writestr(part, data)
        package = out.getvalue()
    with zipfile.ZipFile(io.BytesIO(package)) as probe:
        assert probe.testzip() is None
        assert len(probe.namelist()) == len(files)
    return package


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=Path("future-code11-source"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--manhwa-only", action="store_true",
                        help="CI-only original-ko and scoped search for frozen manhwa cohort")
    parser.add_argument("--original-language", choices=("ko", "ja"),
                        help="Explicit original-language constraint for unsigned research")
    parser.add_argument("--mangadot-creators", action="store_true",
                        help="Research-only exact MangaDot JSON-LD creator evidence")
    parser.add_argument("--mangadex-internal-reading", action="store_true",
                        help="Research-only staff expansion and internally deliverable chapters")
    args = parser.parse_args()
    source = args.source.resolve()
    raw = candidate_bytes(source, manhwa_only=args.manhwa_only,
                          mangadot_creators=args.mangadot_creators,
                          original_language=args.original_language,
                          mangadex_internal_reading=args.mangadex_internal_reading)
    if args.output is not None:
        destination = args.output.resolve()
        if "androidTest/assets" not in str(destination):
            raise SystemExit("Research ZIP may be staged only under androidTest/assets")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(raw)
    print("MANGAALL_DUAL_CODE11|twoSources=PINNED_CODE10_MODULES"
          "|nativeChapterBridge=UNSIGNED_CODE11|signature=UNTOUCHED"
          "|release=NOT_STARTED|mangafire=NOT_IN_PACKAGE"
          "|manhwaOnly=" + ("YES" if args.manhwa_only else "NO") +
                    "|originalLanguage=" + ("ko" if args.manhwa_only else args.original_language or "ANY") +
                    "|sha256=" + hashlib.sha256(raw).hexdigest())


if __name__ == "__main__":
    main()
