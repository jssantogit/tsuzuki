#!/usr/bin/env python3
"""Offline contract for the three original-language research packages."""
import hashlib
import io
import json
from pathlib import Path
import sys
import zipfile

from mangaall_code11_dual_source_unsigned import candidate_bytes, TWO_ORIGINS


def verify(raw, lang):
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        assert archive.testzip() is None
        manifest = json.loads(archive.read("manifest.json"))
        dex = archive.read("modules/sources/mangadex/modules/mangadex.js").decode()
        dot = archive.read("modules/sources/mangadot/modules/mangadot.js").decode()
        assert manifest["id"] == "app.tsuzuki.mangaall"
        assert manifest["version"]["code"] == 11
        assert "publication" not in manifest
        assert set(manifest["permissions"]["network"]["origins"]) == TWO_ORIGINS
        assert "browser" not in manifest["permissions"]
        assert not any("mangafire" in name.lower() for name in archive.namelist())
        assert f'["originalLanguage[]", "{lang}"]' in dex
        assert f'originalLanguage !== "{lang}"' in dex
        assert '["search", mangaDotRetrievalQuery(title)]' in dot
        assert "n > mangaDotResearchPageLimit()" in dot
        assert "enrichMangaDotLookupCandidates" in dot


def main():
    root = Path(sys.argv[1]).resolve()
    digests = {}
    for lang in ("ja", "ko", "zh"):
        raw = candidate_bytes(root, original_language=lang, mangadot_creators=True)
        verify(raw, lang)
        assert raw == candidate_bytes(root, original_language=lang, mangadot_creators=True)
        digests[lang] = hashlib.sha256(raw).hexdigest()
    assert len(set(digests.values())) == 3
    for bad in ("en", "", "ZH", "ja,ko", "zh-hant"):
        try:
            candidate_bytes(root, original_language=bad)
        except ValueError as error:
            assert str(error) == "INVALID_RESEARCH_ORIGINAL_LANGUAGE"
        else:
            raise AssertionError("unsupported profile accepted")
    for lang in ("ja", "zh"):
        try:
            candidate_bytes(root, manhwa_only=True, original_language=lang)
        except ValueError as error:
            assert str(error) == "CONFLICTING_RESEARCH_ORIGINAL_LANGUAGE"
        else:
            raise AssertionError("conflicting profile accepted")
    assert candidate_bytes(root, manhwa_only=True, mangadot_creators=True) == candidate_bytes(
        root, original_language="ko", mangadot_creators=True
    )
    for lang, digest in digests.items():
        print(f"MANGAALL_FV30_PACKAGE|language={lang}|sha256={digest}|offline=PASS|live=NOT_RUN")


if __name__ == "__main__":
    main()
