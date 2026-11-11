# MangaAll 0.5.0 smoke — signed Host build, 2026-11-11

The executable source is identical to PR #196 HEAD `0b0f29fd0342f9ffd680c820f207ca2e75ca6c84`.
Only this checklist document is added to the APK mirror branch.

* Provider must come only from the existing official signed repository `app.tsuzuki.providers`, signed sequence 31, package `app.tsuzuki.mangaall` 0.5.0/code5.
* Never silently re-pin or preserve an untrusted obsolete v4 smoke origin: explicitly uninstall the old MangaAll provider, remove the smoke-v4 repository enrollment, refresh the already enrolled official repository and install the unified v5 package.
* Do not uninstall the Android Host, delete app data, delete canonical titles, or remove other providers.
* Confirm Discover excludes already installed IDs and old MangaDex/MangaDot adapters, while Installed shows newly installed unified MangaAll.
* Capture Grand Blue Reader eligibility reason `provider_reader_mapped_source_ineligibility_reason` when `NO_ELIGIBLE_MAPPED_SOURCE`; inspect timing separately from reported chapter evidence.
* Probe Reader actual image rendering; never conclude from 200 HTTP or a successful URL/page count alone.
* Inspect MangaFire/MangaDot/MangaDex failures by internal source code and fail stage.
* Do not merge Host #196 until physical smoke and review.
