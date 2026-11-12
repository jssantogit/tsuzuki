# MangaAll 0.5.2 / Host #196 coordinated A54 smoke

Signed Release mirror of Host PR #196 executable HEAD `c3de8e964d650be406e41f3fc3cc27dde87384cf`. This document is not shipped; there are no functional changes from the PR HEAD.

## Packaging and safety
- Trigger the normal [apk] Release lane using the persistent Host signing secrets, `assembleRelease`, `apksigner verify`, and the arm64 APK artifact.
- This Host build does NOT include the unpublished MangaAll 0.5.2/code7 package. Provider PR #63 `e352da687fd861376a9ac70f2a611cd29de86e56` remains Draft until a separately reviewed signed incremental distribution through the existing `app.tsuzuki.providers` root. Do not create an ephemeral trust root, publish from this Host branch, or bypass origin pinning.
- Install Host over the existing Tsuzuki installation; preserve app data, library, history, source preferences, chapters, Reader sessions, and progress. Do not uninstall or reset Host.
- Do not merge Host #196 or Provider #63 based on CI alone.

## Physical A54 acceptance checklist (requires both Host PR #196 and provider code7)
1. Verify exact Host build and MangaAll `app.tsuzuki.mangaall` 0.5.2/code7 upgraded through the *same* official signed repository. The published code6 is not adequate to validate Koe's singleton coverage.
2. **Oyasumi Punpun:** existing preferred MangaDex/en edition may have a complete-empty chapter snapshot. Refresh chapters; inspect recovery of independently verified MangaFire `zk12` / Goodnight Punpun chapter evidence as provisional/canonical candidates **without changing saved preferred binding**. Verify actual canonical count and numbering, volume/edition as available; no fabricated chapter IDs.
3. **Koe no Katachi:** inspect MangaFire `7p56` singleton verified coverage preview, nonzero positive evidence, and eventual canonical reconciliation; absence or uncertainty should remain explicit rather than manufacturing a chapter total.
4. For both works, open exact chapter 1 and at least one later chapter where available. Check that displayed images, title, chapter number, source and language match the selected item; an HTTP 200/READY URL list is insufficient.
5. Reopen the original preferred edition and a previously read chapter; verify progress, mappings, Reader options, and pre-existing library records remain unchanged.
6. Regression: verify at least one already-working title (e.g. Monster, Kaguya-sama, Solo Leveling), Reader source switching, and no unrelated publication/enrollment change. Note any Grand Blue `PROVIDER_NOT_REGISTERED` or JJK `SCRIPT_ERROR` separately.
7. Export verbose diagnostic logs with provider coverage outcome/count/has-more and collection/reconciliation status, plus chapter/Reader identity reasons and source-stage errors. Do not log raw URLs, auth headers, cookies, or secret values.

## Exit gate
Record physical outcomes first. Only then review whether both Draft PRs should merge. If code7 cannot be distributed safely without publishing, keep it Draft and report that blocker explicitly rather than claiming a coordinated physical smoke.
