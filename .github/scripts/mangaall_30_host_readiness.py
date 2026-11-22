#!/usr/bin/env python3
"""Offline only: Tsuzuki MangaALL 30-work scale readiness, NOT a live smoke.

Keep operator order, all three source intents and catalog identity detached
from any claim that titles or chapter editions are canonically bound.
"""
from __future__ import annotations
import argparse
import json
import pathlib
import re
import sys
from mangaall_code10_canonical_smoke_plan import ACK, parse_plan


def source_host_limits(root: pathlib.Path) -> dict:
    def read(path):
        return (root / path).read_text(encoding="utf-8")

    protocol = read("core/common/src/main/kotlin/tachiyomi/core/provider/runtime/ProviderRuntimeProtocol.kt")
    http = read("core/common/src/main/kotlin/tachiyomi/core/provider/runtime/ProviderHttpProtocol.kt")
    storage = read("core/common/src/main/kotlin/tachiyomi/core/provider/runtime/ProviderStateHostServices.kt")
    refresh = read("domain/src/main/java/tachiyomi/domain/tsuzuki/provider/reading/RefreshProviderReadingChapters.kt")
    quickjs = read("core/common/src/main/kotlin/tachiyomi/core/provider/runtime/ProviderScriptPackageRuntime.kt")
    manifest = read("docs/mangaall-code10-signed-v2-host-readiness-20261121.md")
    first_three = read(".github/workflows/mangaall-code10-first-three-canonical-smoke.yml")

    def constant(text, pattern, expected, label):
        match = re.search(pattern, text)
        if not match or int(match.group(1)) != expected:
            raise ValueError("Host " + label + " changed; repeat capacity audit")
        return expected

    result_chars = constant(protocol, r"MAX_RESULT_JSON_CHARS\s*=\s*(\d+)\s*\*\s*1024", 64, "JS result cap")
    http_chars = constant(http, r"MAX_RESPONSE_BODY_CHARS\s*=\s*(\d+)\s*\*\s*1024", 192, "HTTP body cap")
    http_ipc = constant(http, r"MAX_RESPONSE_JSON_CHARS\s*=\s*(\d+)\s*\*\s*1024", 256, "HTTP envelope cap")
    value = constant(storage, r"maxValueChars:\s*Int\s*=\s*(\d+)\s*\*\s*1024", 64, "storage value cap")
    total = constant(storage, r"maxTotalChars:\s*Int\s*=\s*(\d+)\s*\*\s*1024", 512, "storage total cap")
    pages = constant(refresh, r"MAX_PAGES\s*=\s*(\d+)", 32, "canonical page cap")
    observations = constant(refresh, r"MAX_OBSERVATIONS\s*=\s*([\d_]+)".replace("([\\d_]+)", r"([\d_]+)"), 16000, "canonical row cap")
    if not ("QuickJs.create" in quickjs and "runtime.close()" in quickjs):
        raise ValueError("Host QuickJs invocation lifecycle changed; repeat capacity audit")
    if "manga :: Naruto" not in first_three or "manga :: Death Note" not in first_three or "manga :: Bleach" not in first_three:
        raise ValueError("Historical signed code10 first-three baseline changed")
    if "code10" not in manifest.lower():
        raise ValueError("Signed code10 readiness documentation missing")

    return {
        "scriptResultChars": result_chars * 1024,
        "httpBodyChars": http_chars * 1024,
        "httpEnvelopeChars": http_ipc * 1024,
        "storageValueChars": value * 1024,
        "storageTotalChars": total * 1024,
        "sourceMaxChapterPages": pages,
        "sourceMaxChapterObservations": observations,
        "quickJsIsolateLifetime": "ONE_INVOCATION",
        "signedCode10StoragePermission": "DISABLED",
    }


def plan_30(works: str, acknowledgement: str, root: pathlib.Path) -> dict:
    vector = parse_plan(works, "scale", acknowledgement)
    if len(vector) != 30:
        raise ValueError("30-work scale requires exactly 30 operator-supplied works")
    if any(entry["caseId"] != "user-" + entry["caseId"][5:] for entry in vector):
        raise ValueError("Invalid case identifiers")
    groups = [
        [entry for position, entry in enumerate(vector) if position % 3 == shard]
        for shard in range(3)
    ]
    if [len(group) for group in groups] != [10, 10, 10]:
        raise ValueError("30-work sharding differs from 10+10+10")
    flattened = [entry["caseId"] for group in groups for entry in group]
    if len(flattened) != 30 or len(set(flattened)) != 30 or set(flattened) != {
        entry["caseId"] for entry in vector
    }:
        raise ValueError("30-work plan lost or duplicated a case")
    limits = source_host_limits(root)
    return {
        "schema": 1,
        "tier": "SCALE_30_PREPARATION",
        "source": "ONE_SIGNED_MANGAALL_WITH_THREE_INTERNAL_SOURCES",
        "workCount": 30,
        "shardCount": 3,
        "perShard": [
            {"shard": idx, "size": len(group), "positions": [entry["position"] for entry in group],
             "caseIds": [entry["caseId"] for entry in group]}
            for idx, group in enumerate(groups)
        ],
        "hostLimits": limits,
        "gates": {
            "workVector": "READY_OFFLINE",
            "sourceHttp": "NOT_TESTED_ON_30",
            "independentAndroidCatalog": "NOT_TESTED_ON_30",
            "canonicalSqlite": "NOT_TESTED_ON_30",
            "readerPixels": "NOT_TESTED_ON_30",
            "mangaDotLargeList": "BLOCKED_UNTIL_BOUNDED_UPSTREAM_AND_HOST_OUTPUT",
            "editorialIdentity": "NO_AUTOMATIC_BINDING",
            "releaseSigning": "NOT_AUTHORIZED",
        },
    }


def main():
    import os
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan", action="store_true")
    args = parser.parse_args()
    if not args.plan:
        parser.error("--plan is required; no live execution exists")
    try:
        result = plan_30(
            os.environ.get("MANGAALL_WORKS", ""),
            os.environ.get("MANGAALL_SMOKE_ACK", ""),
            pathlib.Path(__file__).resolve().parents[2],
        )
    except (ValueError, OSError) as error:
        message = re.sub(r"[^A-Z0-9_]", "_", str(error).upper())[:100]
        print("MANGAALL_CAPACITY30|outcome=DENIED|reason=" + message, file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
