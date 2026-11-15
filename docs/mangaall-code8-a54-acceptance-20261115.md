# MangaAll 0.5.3/code8 — Galaxy A54 single combined acceptance (2026-11-15)

## Scope and provenance

The official signed MangaAll **0.5.3/code8** was published under the existing signed repository `app.tsuzuki.providers` sequence **34** by [Provider publisher #43746710468](https://github.com/jssantogit/providers-tsuzuki/actions/runs/43746710468), verified by its original root. Code8 SHA-256: `4584482d186f2ddb9d5aeb15f98685df56bfa3d556ced4aa5bd51854a7391bb1`; code7 archive is retained for rollback. The provider is ONE package with MangaDex, MangaDot and MangaFire **internal content sources**. It contains the exhausted-retry completeness fix (Provider #68) and source-language lookup routing fix (Provider #69). No fabricated source candidate was used as real ambiguity evidence.

The previous physical A54 capture used Host `efc725b`, before the merged diagnostic improvements in Host [#205](https://github.com/jssantogit/tsuzuki/pull/205). This Host APK is therefore a **single intentional diagnostic refresh** of the app's existing `main`, not a prerequisite for the source code8 API and not a new MangaAll package.

Host `main` baseline before this document: `edfa22460c147b95a3c06903571d6f2c5db00080`, CI [#43736960777](https://github.com/jssantogit/tsuzuki/actions/runs/43736960777) **SUCCESS**. The Host fix preserves bounded public MangaAll source work HIDs in detailed candidate/source events and emits per-HID MangaFire page count/number range; Reader events still exclude raw work IDs. No change to editorial auto-binding policy.

## Install: one update of each existing component, no reset

1. Install the official signed **arm64 release APK** built from this docs-triggered Host `main` commit **over the existing** `app.tsuzuki`; verify same package/signing root. Do not uninstall, clear app data, disable library, or use `app.tsuzuki.dev`.
2. In the installed Providers section, update the **already installed** `app.tsuzuki.mangaall` to **0.5.3/code8** using the same signed repository enrollment. Verify the displayed version. Do not install MangaFire/MangaDex/MangaDot as separate providers. Preserve user-confirmed editions and chapter progress.
3. Enable **detailed** diagnostic capture only for the following bounded journey. Save the export and the observed UI result after stopping capture. No private cookies, tokens, raw image payloads or VRF URLs are needed.

## High-yield journey from observed titles, not manufactured candidate lists

- **Enen no Shouboutai / Fire Force:** refresh under the eligible English and Portuguese facets. Previously an English MangaFire public HID `z1r3l` surfaced during a Portuguese lookup and was correctly rejected as `FACET_MISMATCH`; the English lookup showed only Fan Colored alternatives. **Expected code8 routing:** MangaFire is not invoked for `pt-br`, remains an eligible internal source for `en`. Determine whether the original HID appears in English, and why if not. Never auto-bind a colored alternate to the original or claim that this routing fix restored canonical chapters.
- **Dungeon Meshi:** inspect the real source candidates and independently verified authors/artists. An `AUTHOR_CONFLICT` or MangaDex complete-empty response must not become a guessed preferred MangaFire edition or a falsely complete all-source empty inventory. Record whether any provisional/canonical chapters actually appear.
- **Fairy Tail and Tower of God:** the earlier A54 code7 log counted **660** and **654** MangaFire observations respectively over seven pages, but failed to confirm a unique editorial binding. After refresh, record **canonical chapters actually displayed**, provisional source evidence count, candidate HIDs, page numeric min/max by HID, and partial/complete outcome **separately**. Observe whether cursor continuation after a second refresh preserves valid existing chapters, does not fabricate missing chapters, and does not replace a user-confirmed edition.
- **Reader if a safe source is offered:** open a known correct chapter, confirm real image pixels on the screen (not only `READY` or a page URL), verify source/language matches the chosen reading option and progress survives app restart. If no safe source exists, record the no-match reason instead of forcing the wrong candidate.

## Acceptance and rollback boundaries

A source response `HTTP 200`, a paginated source count, `chapter_refresh_completed.item_count` or Reader `READY` **do not** establish complete canonical inventory or visible pixels. Cursors and successful response pages are evidence, not proof of editorial identity. Capture explicit UI chapter count where possible. The MangaFire 403 in remote GitHub Actions is environmental and was not observed in the previous A54 HTTP200 run.

If the Host update fails its signature or the installed MangaAll remains code7, stop and report exact version/status; do not clear data or force an unofficial APK/provider. The signed repository sequence is monotonic; rollback uses existing Host Provider rollback and preserved code7 artifact, not a hand-edited signed index. The physical acceptance result is **PENDING** until a new A54 export and visible Reader check.
