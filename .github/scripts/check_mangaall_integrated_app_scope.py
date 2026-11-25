"""Exact App scope for the integrated, nonshipping MangaALL research PR."""
import argparse
import os
import subprocess
import sys

BRANCH = "probe/mangaall-dual-source-scale30-20261123"
APP_PATHS = frozenset((
    "app/src/androidTest/java/eu/kanade/presentation/reader/MangaAllReaderSourceCardInstrumentedTest.kt",
    "app/src/androidTest/java/eu/kanade/tachiyomi/data/tsuzuki/instrumentation/CanonicalReaderSourceSwitchInstrumentedTest.kt",
    "app/src/androidTest/java/eu/kanade/tachiyomi/data/tsuzuki/instrumentation/MangaAllFrozen30CanonicalPersistenceInstrumentedTest.kt",
    "app/src/androidTest/java/eu/kanade/tachiyomi/data/tsuzuki/instrumentation/MangaAllIndependentCatalogLiveInstrumentedTest.kt",
    "app/src/androidTest/java/eu/kanade/tachiyomi/data/tsuzuki/instrumentation/MangaAllMangaDotCorroborationInstrumentedTest.kt",
    "app/src/androidTest/java/eu/kanade/tachiyomi/data/tsuzuki/instrumentation/MangaAllReaderJourneyFixture.kt",
    "app/src/androidTest/java/eu/kanade/tachiyomi/provider/runtime/MangaAllFrozen30Code11AndroidLiveInstrumentedTest.kt",
    "app/src/androidTest/java/eu/kanade/tachiyomi/provider/runtime/MangaAllFrozen30MangaDexAuditInstrumentedTest.kt",
    "app/src/androidTest/java/eu/kanade/tachiyomi/provider/runtime/MangaAllFutureRealDualSourceCode11InstrumentedTest.kt",
    "app/src/androidTest/java/eu/kanade/tachiyomi/provider/runtime/MangaAllFutureRealDualSourceCode11LiveInstrumentedTest.kt",
    "app/src/androidTest/java/eu/kanade/tachiyomi/provider/runtime/MangaAllMangaDotCreatorLiveInstrumentedTest.kt",
    "app/src/main/java/eu/kanade/tachiyomi/data/track/mangaupdates/MangaUpdates.kt",
    "app/src/main/java/eu/kanade/tachiyomi/data/track/mangaupdates/MangaUpdatesApi.kt",
    "app/src/main/java/eu/kanade/tachiyomi/data/track/mangaupdates/dto/MUAuthor.kt",
    "app/src/main/java/eu/kanade/tachiyomi/data/track/shikimori/Shikimori.kt",
    "app/src/main/java/eu/kanade/tachiyomi/data/track/shikimori/ShikimoriApi.kt",
    "app/src/main/java/eu/kanade/tachiyomi/data/track/shikimori/dto/SMManga.kt",
    "app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/integration/MangaUpdatesIntegrationProvider.kt",
    "app/src/main/java/eu/kanade/tachiyomi/data/tsuzuki/integration/ShikimoriIntegrationProvider.kt",
    "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt",
    "app/src/test/java/eu/kanade/tachiyomi/data/track/shikimori/ShikimoriApiTest.kt",
    "app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/integration/MangaUpdatesIntegrationProviderTest.kt",
    "app/src/test/java/eu/kanade/tachiyomi/data/tsuzuki/integration/ShikimoriIntegrationProviderTest.kt",
))
REQUIRED_GATES = frozenset((
    ".github/workflows/mangaall-canonical-audit-offline.yml",
    ".github/workflows/mangaall-reader-selector-offline-android.yml",
))
PROTECTED_PREFIXES = (
    "providers/",
    "dist/",
    "core/common/src/main/kotlin/tachiyomi/core/provider/supplychain/",
)
PROTECTED_HTTP = frozenset((
    "core/common/src/main/kotlin/tachiyomi/core/provider/runtime/ProviderHttpProtocol.kt",
    "core/common/src/main/kotlin/tachiyomi/core/provider/runtime/ProviderHttpHostService.kt",
))


def check(paths, event, branch, head_repo, repo):
    if event != "pull_request" or branch != BRANCH or not repo or head_repo != repo:
        raise ValueError("Integrated App scope requires the exact same-repository research PR")
    protected = {path for path in paths if path.startswith(PROTECTED_PREFIXES) or path in PROTECTED_HTTP}
    if protected:
        raise ValueError("Signed release, supply chain or generic Host HTTP path touched: " + repr(sorted(protected)))
    app = {path for path in paths if path.startswith("app/")}
    unexpected = app - APP_PATHS
    if unexpected:
        raise ValueError("Unreviewed App path: " + repr(sorted(unexpected)))
    if app and not REQUIRED_GATES <= paths:
        raise ValueError("Integrated core and Reader regression gates must accompany App changes")


def selftest():
    args = ("pull_request", BRANCH, "owner/repo", "owner/repo")
    valid = set(APP_PATHS | REQUIRED_GATES)
    check(valid, *args)
    for paths, event_args in (
        (valid | {"app/src/main/aidl/Unexpected.aidl"}, args),
        (valid | {"app/src/main/java/Unreviewed.kt"}, args),
        (valid | {"providers/release.tsz"}, args),
        (valid | {"dist/release.zip"}, args),
        (valid | {"core/common/src/main/kotlin/tachiyomi/core/provider/supplychain/Unexpected.kt"}, args),
        (valid | PROTECTED_HTTP, args),
        (valid - REQUIRED_GATES, args),
        (valid, ("push", BRANCH, "owner/repo", "owner/repo")),
        (valid, ("pull_request", "other", "owner/repo", "owner/repo")),
        (valid, ("pull_request", BRANCH, "fork/repo", "owner/repo")),
    ):
        try:
            check(paths, *event_args)
        except ValueError:
            continue
        raise AssertionError("Scope unexpectedly accepted")
    print("MANGAALL_INTEGRATED_APP_SCOPE|selftest=PASS")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("base", nargs="?")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()
    if args.selftest:
        selftest()
        return
    if not args.base:
        parser.error("base is required")
    # NUL-separated names and no rename detection inspect both sides of moves.
    changed = subprocess.check_output(
        ["git", "diff", "--name-only", "--no-renames", "-z", args.base, "HEAD"]
    )
    paths = {os.fsdecode(path) for path in changed.split(b"\0") if path}
    try:
        check(paths, os.environ.get("GITHUB_EVENT_NAME"), os.environ.get("GITHUB_HEAD_REF"),
              os.environ.get("GITHUB_HEAD_REPO"), os.environ.get("GITHUB_REPOSITORY"))
    except ValueError as error:
        print("::error::" + str(error), file=sys.stderr)
        sys.exit(1)
    print("MANGAALL_INTEGRATED_APP_SCOPE|exact_paths=PASS|signed_and_http=UNCHANGED")


if __name__ == "__main__":
    main()
