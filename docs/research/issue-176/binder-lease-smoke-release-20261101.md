# Tsuzuki #176 — Binder lease pagination smoke candidate

Date: 2026-11-01. This document-only checkpoint triggers a signed Release ARM64 APK. It does not change runtime behavior compared with the tested green commit.

## Evidence

- Galaxy A54 Android 14, Host 0.20.4/code29 commit `7886985`, MangaFire signed 0.1.8/code9: multi-HID chapters appear, but after the first ~100, page 2 gets `provider_reading_chapter_attempt STARTED` without a Host HTTP STARTED until timeout.
- Host PR #182 has a bounded, reference-counted Binder connection, kept for 8s idle between the isolated process calls. Each invocation still has fresh VM, permissions, broker and resource ownership.
- RED [Android #41343995022](https://github.com/jssantogit/tsuzuki/actions/runs/41343995022) FAILED as expected: page-2 test expected 1 BIND_STARTED but got 2.
- GREEN [Android #41344272613](https://github.com/jssantogit/tsuzuki/actions/runs/41344272613) SUCCESS; includes consecutive-page, Binder-reuse, idle-expiration/rebind and existing Provider Platform runtime/reading/torrent tests.
- [Full CI #41344272801](https://github.com/jssantogit/tsuzuki/actions/runs/41344272801) SUCCESS including Domain, Data, Core, App, Format, migrations, Native Package, Release, Supabase. Host PR CI #41344251946 SUCCESS. The staging branch and PR #182 match byte-for-byte on production Binder runtime, DI and diagnostic contract; only a test comment differs.

## Manual acceptance (the issue is NOT yet resolved)

1. Install the **signed Release app.tsuzuki ARM64** over the existing Galaxy A54 app. Never uninstall or clear app storage; keep installed MangaFire code9 and repository configuration.
2. With detailed Tsuzuki capture enabled, refresh **Chainsaw Man** (HID `0w5k`), **Vagabond** normal (`4mx`), **Fullmetal Alchemist**, **Naruto** and **Bleach**. Confirm `page_index=2` invokes HTTP STARTED + SUCCEEDED, no fixed ceiling at chapter 100, and later chapters from exact correct HIDs. Further source pages may be capped by conservative per-source budgets; incomplete is not a complete inventory.
3. Verify Reader exact chapter ID and chapter number for the first and a later chapter. No silent source/edition remapping, colored/omnibus blending, wrong pages or lost progress after relaunch. Preserve backups/sync state.
4. Export the sanitized detailed Logs and report cases with a real gap, wrong chapter content, timeout or Provider runtime BIND/INVOKE stage. TDD any residual defect before another APK/merge.

Host PR #182 stays Draft and issue #176 stays open pending physical acceptance.
