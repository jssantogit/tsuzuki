#!/usr/bin/env python3
"""Check the exact packaged adapter without making network requests."""
import argparse
import io
from pathlib import Path
import subprocess
import tempfile
import zipfile
from mangaall_code11_dual_source_unsigned import candidate_bytes

parser = argparse.ArgumentParser()
parser.add_argument("--source", required=True, type=Path)
args = parser.parse_args()
source = args.source.resolve()
base = candidate_bytes(source, original_language="ja", mangadot_creators=True)
patched = candidate_bytes(source, original_language="ja", mangadot_creators=True,
                          mangadex_internal_reading=True)
name = "modules/sources/mangadex/modules/mangadex.js"
with zipfile.ZipFile(io.BytesIO(base)) as old, zipfile.ZipFile(io.BytesIO(patched)) as new:
    assert old.namelist() == new.namelist()
    assert [n for n in old.namelist() if old.read(n) != new.read(n)] == [name]
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        (root / "package.json").write_text('{"type":"module"}')
        (root / "mangadex.js").write_bytes(old.read(name))
        (root / "http.js").write_text(
            'export const MANGAALL_USER_AGENT = "fixture";\n'
            'export const getMangaDexJson = (...args) => globalThis.mangaDexFixture(...args);\n'
        )
        test = str(Path(__file__).with_name("test_mangaall_mangadex_reading_contract.mjs").resolve())
        subprocess.run(["node", test, str(root / "mangadex.js"), "--creators-only"], check=True)
        (root / "mangadex.js").write_bytes(new.read(name))
        subprocess.run(["node", test, str(root / "mangadex.js")], check=True)
