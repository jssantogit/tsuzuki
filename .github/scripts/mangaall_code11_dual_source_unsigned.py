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


def candidate_bytes(source_root: Path) -> bytes:
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
    manifest["name"] = "MangaAll — two-source unsigned research"
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
    args = parser.parse_args()
    source = args.source.resolve()
    raw = candidate_bytes(source)
    if args.output is not None:
        destination = args.output.resolve()
        if "androidTest/assets" not in str(destination):
            raise SystemExit("Research ZIP may be staged only under androidTest/assets")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(raw)
    print("MANGAALL_DUAL_CODE11|twoSources=PINNED_CODE10_MODULES"
          "|nativeChapterBridge=UNSIGNED_CODE11|signature=UNTOUCHED"
          "|release=NOT_STARTED|mangafire=NOT_IN_PACKAGE"
          "|sha256=" + hashlib.sha256(raw).hexdigest())


if __name__ == "__main__":
    main()
