# #176 — Performance smoke and main integration gate (2026-11-01)

## Proven physical baseline

Environment: Samsung Galaxy A54, Android 14; install-over Release `app.tsuzuki` 0.20.4/code29, commit `c149800f`; signed MangaFire `0.1.8/code9`. User confirmed chapters beyond 100. Sanitized opt-in capture `tsuzuki_logs (2).txt` (kept outside repository): 801 events, 41 workflows, **0 failures, 0 warnings, 0 invariant violations, 0 dropped recorder events**.

- Binder: 5 `BIND_STARTED`, 5 `BIND_CONNECTED`, 43 `BIND_REUSED`, 48 `INVOKE_STARTED/COMPLETED`, 5 `BIND_INVALIDATED`.
- MangaFire: 69 successful HTTP requests with HTTP 200, median 444 ms, arithmetic mean ~428 ms. Requests can overlap; avoid summing them as end-user latency.
- Pages: 44 chapter pages returned and completed, including pages 2–8. The earlier page-2 pre-HTTP stall did not reproduce.
- Chainsaw Man: three HIDs, 428 **provisional observations**, refresh PARTIAL 5.663 s.
- Vagabond `4mx`: 332 chapters across four pages, refresh SUCCEEDED 2.850 s.
- Naruto: two HIDs, 1,408 **provisional observations**, PARTIAL 7.221 s.
- Bleach: three HIDs, 2,231 **provisional observations**, PARTIAL 16.252 s.
- Dandadan: 265 chapters, refresh SUCCEEDED 4.321 s.

`completed=false` in multi-HID portfolios is intentional: author-corroborated work-source evidence does **not** authorize a single edition, Reader binding or completed editorial inventory. Counts from different HIDs can overlap. No Reader page-ready event occurred in this capture, so the physical smoke does **not** certify correctness of distant chapter content.

## Low-risk optimizations in PR #182

1. Shadow source scans no longer construct an authoritative `ChapterEvidence` snapshot that cannot be published; they still validate all pages, cursors, chapter IDs and resource limits.
2. The additive provisional evidence created for timeout salvage is returned and reused by the unresolved multi-HID collector, instead of converting/hashing it again.
3. `ProviderChapterEvidenceAdapter` encodes SHA-256 digests with fixed lower-case hexadecimal characters rather than `String.format`/Formatter per byte. Preserve existing persisted `provider:...` IDs exactly.

The old multi-HID path converted each observed chapter up to three times; the new path converts it once. With 4,067 observed multi-HID chapters in the above sample, approximately **8,134 redundant hashes** are avoided (code-path estimate, **not** a timing benchmark). Exact ID fixture and shadow pagination regressions are included in Domain tests. Affected CI [#41364512173](https://github.com/jssantogit/tsuzuki/actions/runs/41364512173) succeeded.

## Integrated smoke staging

Branch: `tsuzuki/provider-platform-issue-176-performance-smoke-20261101`, commit `32d0c2cb` (functional source identical to PR #182 `a21a7695`; only an explanatory comment differs).

- [Full CI #41364823477](https://github.com/jssantogit/tsuzuki/actions/runs/41364823477)
- [Provider Android instrumentation #41364823553](https://github.com/jssantogit/tsuzuki/actions/runs/41364823553)
- [Signed Release ARM64 APK build #41364823464](https://github.com/jssantogit/tsuzuki/actions/runs/41364823464)

These are **pending gates**, not accepted results until their jobs finish successfully. Verify APK Release lane, package ID, signature certificate match before presenting it for install-over benchmarking. Never clear data or reset existing providers.

## Merge preparation / stop conditions

- Verify full CI (Domain, App, Data, Core, format, SQLDelight, native package, release, Supabase) and actual Android instrumentation on the integrated staging SHA.
- Repeat **cold and warm** chapter-refresh baseline on an install-over physical Release: capture elapsed workflow, page counts, HTTP timing, Binder reuse and non-dropped history. Compare same works, Provider code, connectivity and prior persisted binding status. Avoid promising latency improvement from synthetic hash savings.
- Confirm Reader pages for chapter 1 and a distant chapter, including exact provider work/chapter ID; preserve progress, identity, backup and sync. Revisit other #176 physical regressions (Neverland ch1, Haikyuu gaps, Nanatsu wrong chapter, Berserk prologue identity) before closing issue.
- PR #182 is stacked on #181 and earlier branches, not a direct isolated main merge. At this analysis, the #182 head includes #173, #175, #179, #180 and #181 histories, but **PR #178 (secondary `⋮` edition-menu UI) diverges by three commits**, affecting Details UI/test. Reconcile it explicitly before an integration merge; do not silently omit the user's UI decision.
- Preserve unresolved multi-HID partial semantics and reader source guards. Never choose an edition by ranking, count or first result, claim unique chapter counts from overlapping observations, or turn partial into a complete snapshot.

Issue remains open and Host PR remains Draft pending integrated and physical acceptance.
